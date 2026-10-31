/**
 * Unit tests for the pure helpers in `src/lib.ts`.
 * Run with: node --experimental-strip-types --test tests/
 */
import assert from 'node:assert/strict';
import { test } from 'node:test';

import {
  CAPSULE_READ_RETENTION_MS,
  codePointLength,
  compressContent,
  computeDayWindow,
  decompressContent,
  DEV_MAX_BACKDATE_MS,
  encodeUlid,
  generateUlid,
  isValidUlid,
  MAX_CONTENT_LENGTH,
  parseIsoDateParts,
  parseTzOffset,
  RATE_LIMIT_MS,
  rateLimitRetryAfterMs,
  truncateCodePoints,
  ULID_LENGTH,
  validateMessageInput,
} from '../src/lib.ts';

const UUID = '123e4567-e89b-12d3-a456-426614174000';
const NOW = 1_700_000_000_000; // 2023-11-14T22:13:20Z

function body(error: string): { ok: false; error: string } {
  return { ok: false, error };
}

/* -------------------------------------------------------------------------- */
/* Code point length / limits                                                 */
/* -------------------------------------------------------------------------- */

test('codePointLength counts code points, not UTF-16 units', () => {
  assert.equal(codePointLength(''), 0);
  assert.equal(codePointLength('abc'), 3);
  assert.equal(codePointLength('😀'), 1);
  assert.equal('😀'.length, 2); // sanity: astral plane in UTF-16
  assert.equal(codePointLength('a😀b'), 3);
  assert.equal(codePointLength('你好'), 2);
  assert.equal(truncateCodePoints('😀😀😀', 2), '😀😀');
  assert.equal(truncateCodePoints('abc', 5), 'abc');
});

test('normal content: 200 code points ok, 201 too_long', () => {
  const ok = validateMessageInput(
    { type: 'normal', content: 'a'.repeat(MAX_CONTENT_LENGTH.normal), playerUuid: UUID },
    NOW,
  );
  assert.equal(ok.ok, true);

  const tooLong = validateMessageInput(
    { type: 'normal', content: 'a'.repeat(MAX_CONTENT_LENGTH.normal + 1), playerUuid: UUID },
    NOW,
  );
  assert.equal(tooLong.ok, false);
  if (tooLong.ok) return;
  assert.equal(tooLong.status, 400);
  assert.deepEqual(tooLong.body, { ok: false, error: 'too_long', max: 200, actual: 201 });
});

test('casual: 500 ok / 501 too_long for capsules', () => {
  const ok = validateMessageInput(
    {
      type: 'capsule',
      content: 'x'.repeat(MAX_CONTENT_LENGTH.capsule),
      playerUuid: UUID,
      openAt: NOW + 1000,
    },
    NOW,
  );
  assert.equal(ok.ok, true);

  const tooLong = validateMessageInput(
    {
      type: 'capsule',
      content: 'x'.repeat(MAX_CONTENT_LENGTH.capsule + 1),
      playerUuid: UUID,
      openAt: NOW + 1000,
    },
    NOW,
  );
  assert.equal(tooLong.ok, false);
  if (tooLong.ok) return;
  assert.deepEqual(tooLong.body, { ok: false, error: 'too_long', max: 500, actual: 501 });
});

test('emoji count as one code point against the limit', () => {
  const exactly200 = '😀'.repeat(200);
  assert.equal(codePointLength(exactly200), 200);
  assert.equal(
    validateMessageInput({ type: 'normal', content: exactly200, playerUuid: UUID }, NOW).ok,
    true,
  );

  const over = validateMessageInput(
    { type: 'normal', content: '😀'.repeat(201), playerUuid: UUID },
    NOW,
  );
  assert.equal(over.ok, false);
  if (over.ok) return;
  assert.deepEqual(over.body, { ok: false, error: 'too_long', max: 200, actual: 201 });
});

test('length is measured on raw content, before trimming', () => {
  const padded = `  ${'a'.repeat(200)}  `; // 204 code points, trims to 200
  const result = validateMessageInput({ type: 'normal', content: padded, playerUuid: UUID }, NOW);
  assert.equal(result.ok, false);
  if (result.ok) return;
  assert.deepEqual(result.body, { ok: false, error: 'too_long', max: 200, actual: 204 });
});

/* -------------------------------------------------------------------------- */
/* Emptiness / type / player / openAt                                         */
/* -------------------------------------------------------------------------- */

test('empty and whitespace-only content is rejected', () => {
  for (const content of ['', '   ', '\n\t ', '\u3000']) {
    const result = validateMessageInput({ type: 'normal', content, playerUuid: UUID }, NOW);
    assert.equal(result.ok, false);
    if (result.ok) continue;
    assert.equal(result.status, 400);
    assert.deepEqual(result.body, body('empty_content'));
  }
});

test('non-string content is rejected as empty_content', () => {
  const result = validateMessageInput({ type: 'normal', content: 42, playerUuid: UUID }, NOW);
  assert.equal(result.ok, false);
  if (result.ok) return;
  assert.deepEqual(result.body, body('empty_content'));
});

test('bad type is rejected', () => {
  for (const type of ['weird', '', null, 3, undefined]) {
    const result = validateMessageInput({ type, content: 'hi', playerUuid: UUID }, NOW);
    assert.equal(result.ok, false);
    if (result.ok) continue;
    assert.deepEqual(result.body, body('bad_type'));
  }
  assert.equal(validateMessageInput(null, NOW).ok, false);
  assert.equal(validateMessageInput([], NOW).ok, false);
});

test('playerUuid must be a canonical UUID', () => {
  for (const playerUuid of ['', 'not-a-uuid', UUID.replace('-', ''), '123e4567-e89b-12d3-a456-42661417400g']) {
    const result = validateMessageInput({ type: 'normal', content: 'hi', playerUuid }, NOW);
    assert.equal(result.ok, false);
    if (result.ok) continue;
    assert.deepEqual(result.body, body('bad_player'));
  }

  const upper = validateMessageInput(
    { type: 'normal', content: 'hi', playerUuid: UUID.toUpperCase() },
    NOW,
  );
  assert.equal(upper.ok, true);
  if (!upper.ok) return;
  assert.equal(upper.value.playerUuid, UUID); // canonicalised to lowercase
});

test('capsule requires an integer openAt strictly in the future', () => {
  const cases: unknown[] = [undefined, null, NOW, NOW - 1, NOW + 0.5, String(NOW + 1000), 1.5];
  for (const openAt of cases) {
    const result = validateMessageInput(
      { type: 'capsule', content: 'sealed', playerUuid: UUID, openAt },
      NOW,
    );
    assert.equal(result.ok, false, `openAt=${String(openAt)} should be rejected`);
    if (result.ok) continue;
    assert.deepEqual(result.body, body('bad_open_at'));
  }

  const ok = validateMessageInput(
    { type: 'capsule', content: 'sealed', playerUuid: UUID, openAt: NOW + 1 },
    NOW,
  );
  assert.equal(ok.ok, true);
  if (!ok.ok) return;
  assert.equal(ok.value.openAt, NOW + 1);
});

test('normal messages always store a NULL openAt', () => {
  const result = validateMessageInput(
    { type: 'normal', content: 'hi', playerUuid: UUID, openAt: NOW + 10_000 },
    NOW,
  );
  assert.equal(result.ok, true);
  if (!result.ok) return;
  assert.equal(result.value.openAt, null);
});

test('playerName is truncated to 32 code points and never rejects', () => {
  const long = '😀'.repeat(40);
  const result = validateMessageInput(
    { type: 'normal', content: 'hi', playerUuid: UUID, playerName: long },
    NOW,
  );
  assert.equal(result.ok, true);
  if (!result.ok) return;
  assert.equal(codePointLength(result.value.playerName ?? ''), 32);
  assert.equal(result.value.playerName, '😀'.repeat(32));

  const weird = validateMessageInput(
    { type: 'normal', content: 'hi', playerUuid: UUID, playerName: 7 },
    NOW,
  );
  assert.equal(weird.ok, true);
  if (!weird.ok) return;
  assert.equal(weird.value.playerName, null);
});

test('content is stored trimmed', () => {
  const result = validateMessageInput(
    { type: 'normal', content: '  hello  ', playerUuid: UUID },
    NOW,
  );
  assert.equal(result.ok, true);
  if (!result.ok) return;
  assert.equal(result.value.content, 'hello');
});

/* -------------------------------------------------------------------------- */
/* Rate limiting                                                              */
/* -------------------------------------------------------------------------- */

test('rate limit allows a submission after the 3s cooldown', () => {
  assert.equal(RATE_LIMIT_MS, 3000);
  assert.equal(rateLimitRetryAfterMs(null, NOW), 0);
  assert.equal(rateLimitRetryAfterMs(NOW - 3000, NOW), 0);
  assert.equal(rateLimitRetryAfterMs(NOW - 5000, NOW), 0);
  assert.equal(rateLimitRetryAfterMs(NOW - 2999, NOW), 1);
  assert.equal(rateLimitRetryAfterMs(NOW - 1000, NOW), 2000);
  assert.equal(rateLimitRetryAfterMs(NOW, NOW), 3000);
  assert.equal(rateLimitRetryAfterMs(NOW + 500, NOW), RATE_LIMIT_MS); // clock skew
});

/* -------------------------------------------------------------------------- */
/* ULID                                                                       */
/* -------------------------------------------------------------------------- */

const CROCKFORD = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';

function decodeTime(ulid: string): number {
  let time = 0;
  for (const char of ulid.slice(0, 10)) {
    time = time * 32 + CROCKFORD.indexOf(char);
  }
  return time;
}

test('ULID is 26 chars, Crockford base32, with a time-ordered prefix', () => {
  const id = generateUlid(NOW);
  assert.equal(id.length, ULID_LENGTH);
  assert.equal(id.length, 26);
  assert.match(id, /^[0-9A-HJKMNP-TV-Z]{26}$/);
  assert.equal(isValidUlid(id), true);

  assert.equal(decodeTime(id), NOW);
  assert.equal(decodeTime(generateUlid(0)), 0);
  assert.equal(decodeTime(generateUlid(2 ** 48 - 1)), 2 ** 48 - 1);
});

test('ULIDs are lexicographically sortable by timestamp', () => {
  const ids = [NOW, NOW + 1, NOW + 2, NOW + 1000, NOW + 86_400_000].map((t) => generateUlid(t));
  const sorted = [...ids].sort();
  assert.deepEqual(ids, sorted);
  assert.deepEqual([...ids].sort((a, b) => (a < b ? -1 : 1)), ids);
});

test('ULID random component uses 80 bits and is deterministic for fixed randomness', () => {
  const zeros = new Uint8Array(10);
  const id = encodeUlid(1, zeros);
  assert.equal(id.length, 26);
  assert.equal(id.slice(0, 10), '0000000001');
  assert.equal(id.slice(10), '0'.repeat(16));

  const ones = new Uint8Array(10).fill(0xff);
  const maxId = encodeUlid(0, ones);
  assert.equal(maxId.slice(10), 'Z'.repeat(16));
});

test('isValidUlid rejects malformed ids', () => {
  for (const bad of [
    '',
    'not-a-ulid',
    '0000000000000000000000000', // 25 chars
    '00000000000000000000000000', // 26 chars, valid shape (control)
    '01ARZ3NDEKTSV4RRFFQ69G5FAI', // contains I
    '01ARZ3NDEKTSV4RRFFQ69G5FAL', // contains L
    '01ARZ3NDEKTSV4RRFFQ69G5FAO', // contains O
    '01ARZ3NDEKTSV4RRFFQ69G5FAU', // contains U
    '01arz3ndektsv4rrffq69g5fav', // lowercase
  ]) {
    const expected = bad === '00000000000000000000000000';
    assert.equal(isValidUlid(bad), expected, `unexpected result for ${bad}`);
  }
});

/* -------------------------------------------------------------------------- */
/* Day window                                                                 */
/* -------------------------------------------------------------------------- */

test('day window for UTC+8 starts 16:00Z the previous day', () => {
  const window = computeDayWindow('2024-03-10', 480);
  assert.notEqual(window, null);
  if (window === null) return;
  assert.equal(window.startMs, Date.UTC(2024, 2, 9, 16, 0, 0, 0));
  assert.equal(window.endMs, Date.UTC(2024, 2, 10, 16, 0, 0, 0));
  assert.equal(window.endMs - window.startMs, 86_400_000);
  // Local midnight + offset maps back to the calendar midnight in UTC.
  assert.equal(new Date(window.startMs + 480 * 60_000).toISOString(), '2024-03-10T00:00:00.000Z');
});

test('day window for UTC-5 starts 05:00Z the same day', () => {
  const window = computeDayWindow('2024-03-10', -300);
  assert.notEqual(window, null);
  if (window === null) return;
  assert.equal(window.startMs, Date.UTC(2024, 2, 10, 5, 0, 0, 0));
  assert.equal(window.endMs, Date.UTC(2024, 2, 11, 5, 0, 0, 0));
});

test('day window boundaries are honoured across a timezone boundary', () => {
  const window = computeDayWindow('2024-03-10', 480);
  if (window === null) return assert.fail('window should be valid');
  const inside = { early: window.startMs, late: window.endMs - 1 };
  const outside = { before: window.startMs - 1, after: window.endMs };
  assert.ok(inside.early >= window.startMs && inside.early < window.endMs);
  assert.ok(inside.late >= window.startMs && inside.late < window.endMs);
  assert.ok(!(outside.before >= window.startMs && outside.before < window.endMs));
  assert.ok(!(outside.after >= window.startMs && outside.after < window.endMs));
});

test('UTC offset 0 is midnight UTC', () => {
  const window = computeDayWindow('2024-02-29', 0);
  if (window === null) return assert.fail('window should be valid');
  assert.equal(window.startMs, Date.UTC(2024, 1, 29, 0, 0, 0, 0));
  assert.equal(new Date(window.startMs).toISOString(), '2024-02-29T00:00:00.000Z');
});

test('impossible calendar dates are rejected', () => {
  for (const bad of [
    '2024-02-30',
    '2023-02-29',
    '2023-13-01',
    '2023-00-10',
    '2023-12-00',
    '2024-1-01',
    '2024-01-1',
    '2024/01/01',
    '20240101',
    '',
    '2024-01-01T00:00:00Z',
  ]) {
    assert.equal(parseIsoDateParts(bad), null, `${bad} should be invalid`);
    assert.equal(computeDayWindow(bad, 0), null);
  }
  assert.deepEqual(parseIsoDateParts('2024-02-29'), { year: 2024, month: 2, day: 29 });
  assert.deepEqual(parseIsoDateParts('2024-12-31'), { year: 2024, month: 12, day: 31 });
});

test('tzOffset must be an integer within ±840 minutes', () => {
  assert.equal(parseTzOffset('480'), 480);
  assert.equal(parseTzOffset('+480'), 480);
  assert.equal(parseTzOffset('-300'), -300);
  assert.equal(parseTzOffset('0'), 0);
  assert.equal(parseTzOffset('840'), 840);
  assert.equal(parseTzOffset('-840'), -840);
  for (const bad of [null, '', 'abc', '480.5', '841', '-841', '1e3', '480min', ' 480 ']) {
    const expected = bad === ' 480 ' ? 480 : null;
    assert.equal(parseTzOffset(bad), expected, `unexpected result for ${String(bad)}`);
  }
  assert.equal(computeDayWindow('2024-03-10', 841), null);
  assert.equal(computeDayWindow('2024-03-10', 480.5), null);
});

/* -------------------------------------------------------------------------- */
/* Compression                                                                */
/* -------------------------------------------------------------------------- */

test('compress → decompress round-trips ASCII', async () => {
  const text = 'Hello, Minecraft! '.repeat(20);
  const compressed = await compressContent(text);
  assert.ok(compressed instanceof Uint8Array);
  assert.ok(compressed.length > 0);
  assert.ok(compressed.length < text.length, 'repetitive ASCII should compress');
  assert.equal(await decompressContent(compressed), text);
});

test('compress → decompress round-trips CJK and emoji', async () => {
  const text = '你好，我的世界！这是一个胶囊留言。😀🎮';
  const compressed = await compressContent(text);
  assert.ok(compressed.length > 0);
  assert.equal(await decompressContent(compressed), text);
});

test('compress → decompress round-trips empty and boundary content', async () => {
  for (const text of ['', 'a', '😀', 'a'.repeat(500)]) {
    assert.equal(await decompressContent(await compressContent(text)), text);
  }
});

/* -------------------------------------------------------------------------- */
/* Development backdating window                                              */
/* -------------------------------------------------------------------------- */

function capsuleAt(openAt: number) {
  return { type: 'capsule', content: 'hi', playerUuid: UUID, openAt };
}

test('dev mode allows a capsule backdated within 30 minutes', () => {
  const result = validateMessageInput(
    capsuleAt(NOW - DEV_MAX_BACKDATE_MS + 1_000),
    NOW,
    { allowPastOpenAt: true },
  );
  assert.equal(result.ok, true);
});

test('dev mode rejects a capsule backdated by more than 30 minutes', () => {
  const result = validateMessageInput(
    capsuleAt(NOW - DEV_MAX_BACKDATE_MS - 1_000),
    NOW,
    { allowPastOpenAt: true },
  );
  assert.equal(result.ok, false);
  if (!result.ok) assert.equal(result.body.error, 'bad_open_at');
});

test('dev mode still rejects a non-integer openAt', () => {
  const result = validateMessageInput(
    capsuleAt((NOW - 1_000) + 0.5),
    NOW,
    { allowPastOpenAt: true },
  );
  assert.equal(result.ok, false);
});

test('production mode still rejects any past openAt', () => {
  const result = validateMessageInput(capsuleAt(NOW - 1), NOW);
  assert.equal(result.ok, false);
  if (!result.ok) assert.equal(result.body.error, 'bad_open_at');
});

test('production mode still accepts a future openAt', () => {
  const result = validateMessageInput(capsuleAt(NOW + 60_000), NOW);
  assert.equal(result.ok, true);
});

test('capsule retention window is 30 minutes', () => {
  assert.equal(CAPSULE_READ_RETENTION_MS, 30 * 60 * 1000);
  assert.equal(DEV_MAX_BACKDATE_MS, 30 * 60 * 1000);
});
