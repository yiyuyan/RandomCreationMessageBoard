/**
 * Pure, dependency-free helpers for the CRC message board Worker.
 *
 * Everything in this file is Web-standard only (no `node:` imports, no npm
 * packages) so it runs unchanged inside Cloudflare Workers and inside
 * `node --experimental-strip-types` for unit tests.
 *
 * Responsibilities:
 *  - request validation (type / content / player / openAt)
 *  - ULID generation (Crockford base32, time-ordered)
 *  - day-window computation for the list endpoint
 *  - deflate-raw content compression helpers
 */

/* -------------------------------------------------------------------------- */
/* Constants                                                                  */
/* -------------------------------------------------------------------------- */

export type MessageType = 'normal' | 'capsule';

/** The only accepted `type` values, in contract order. */
export const MESSAGE_TYPES: readonly MessageType[] = ['normal', 'capsule'];

/** Maximum content length in Unicode code points, per message type. */
export const MAX_CONTENT_LENGTH: Readonly<Record<MessageType, number>> = {
  normal: 200,
  capsule: 500,
};

/** Maximum `playerName` length in Unicode code points (truncated, never rejected). */
export const MAX_PLAYER_NAME_LENGTH = 32;

/** Minimum spacing between two submissions from the same `playerUuid`. */
export const RATE_LIMIT_MS = 3000;

/** Hard cap on rows returned by the list endpoint. */
export const LIST_LIMIT = 500;

/** Rows returned when the client does not ask for a specific page size. */
export const DEFAULT_LIST_LIMIT = 200;

/** Timezone offsets are minutes east of UTC (`+480` == UTC+8). */
export const MIN_TZ_OFFSET = -840;
export const MAX_TZ_OFFSET = 840;

/** Canonical 8-4-4-4-12 UUID. */
export const UUID_RE = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

/** Strict `YYYY-MM-DD` shape (calendar validity is checked separately). */
export const DATE_RE = /^(\d{4})-(\d{2})-(\d{2})$/;

export const MS_PER_DAY = 86_400_000;

/* -------------------------------------------------------------------------- */
/* Error bodies                                                               */
/* -------------------------------------------------------------------------- */

export type ErrorCode =
  | 'bad_json'
  | 'bad_type'
  | 'empty_content'
  | 'too_long'
  | 'bad_player'
  | 'bad_open_at'
  | 'rate_limited'
  | 'bad_date'
  | 'bad_tz'
  | 'bad_since'
  | 'bad_limit'
  | 'not_found'
  | 'not_yet_open'
  | 'method_not_allowed'
  | 'internal';

/** JSON body returned for every failed request. */
export interface ApiErrorBody {
  ok: false;
  error: ErrorCode;
  /** `too_long` only. */
  max?: number;
  /** `too_long` only. */
  actual?: number;
  /** `rate_limited` only. */
  retryAfterMs?: number;
  /** `not_yet_open` only. */
  openAt?: number;
  /** `bad_open_at` in development mode: the oldest instant that would be accepted. */
  earliest?: number;
}

/** Validation outcome: either a value or a ready-to-send error. */
export type ValidationResult<T> =
  | { ok: true; value: T }
  | { ok: false; status: number; body: ApiErrorBody };

function fail(status: number, error: ErrorCode, extra?: Omit<ApiErrorBody, 'ok' | 'error'>): {
  ok: false;
  status: number;
  body: ApiErrorBody;
} {
  return { ok: false, status, body: { ok: false, error, ...extra } };
}

/* -------------------------------------------------------------------------- */
/* Small string / number helpers                                              */
/* -------------------------------------------------------------------------- */

/** Length in Unicode code points (not UTF-16 units): `'😀'` is 1. */
export function codePointLength(text: string): number {
  return Array.from(text).length;
}

/** Truncate to at most `max` Unicode code points without splitting surrogates. */
export function truncateCodePoints(text: string, max: number): string {
  if (max <= 0) return '';
  const points = Array.from(text);
  return points.length <= max ? text : points.slice(0, max).join('');
}

/** Whether `value` is an integer inside the accepted timezone range. */
export function isValidTzOffset(value: number): boolean {
  return Number.isInteger(value) && value >= MIN_TZ_OFFSET && value <= MAX_TZ_OFFSET;
}

/** Parse the `tzOffset` query parameter; `null` when missing or malformed. */
export function parseTzOffset(raw: string | null): number | null {
  if (raw === null) return null;
  const trimmed = raw.trim();
  if (!/^[+-]?\d+$/.test(trimmed)) return null;
  const value = Number(trimmed);
  return isValidTzOffset(value) ? value : null;
}

/** Whether `value` is one of the contract message types. */
export function isMessageType(value: unknown): value is MessageType {
  return value === 'normal' || value === 'capsule';
}

/** Whether `value` is a canonical 8-4-4-4-12 UUID. */
export function isCanonicalUuid(value: unknown): value is string {
  return typeof value === 'string' && UUID_RE.test(value);
}

/* -------------------------------------------------------------------------- */
/* ULID                                                                       */
/* -------------------------------------------------------------------------- */

/** Crockford base32 alphabet (no I, L, O, U). */
const CROCKFORD = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';

/** ULID layout: 10 chars of 48-bit millisecond time + 16 chars of 80 random bits. */
export const ULID_TIME_LENGTH = 10;
export const ULID_RANDOM_LENGTH = 16;
export const ULID_LENGTH = ULID_TIME_LENGTH + ULID_RANDOM_LENGTH;
/** Largest representable ULID timestamp (2^48 - 1 ms). */
export const ULID_MAX_TIME = 2 ** 48 - 1;

const ULID_RE = /^[0-9A-HJKMNP-TV-Z]{26}$/;

function encodeTime(timeMs: number): string {
  if (!Number.isFinite(timeMs) || !Number.isInteger(timeMs)) {
    throw new RangeError('ULID timestamp must be an integer number of milliseconds');
  }
  if (timeMs < 0 || timeMs > ULID_MAX_TIME) {
    throw new RangeError(`ULID timestamp out of range: ${timeMs}`);
  }
  let rest = timeMs;
  let out = '';
  for (let i = 0; i < ULID_TIME_LENGTH; i += 1) {
    out = CROCKFORD.charAt(rest % 32) + out;
    rest = Math.floor(rest / 32);
  }
  return out;
}

/**
 * Encode the 80-bit random component from a 10-byte buffer.
 * 16 base32 chars x 5 bits == 80 bits, consumed MSB-first.
 */
function encodeRandomness(randomness: Uint8Array): string {
  if (randomness.length < 10) {
    throw new RangeError('ULID randomness needs at least 10 bytes (80 bits)');
  }
  let out = '';
  let buffer = 0;
  let bits = 0;
  let cursor = 0;
  for (let i = 0; i < ULID_RANDOM_LENGTH; i += 1) {
    while (bits < 5) {
      buffer = (buffer << 8) | (randomness[cursor] ?? 0);
      cursor += 1;
      bits += 8;
    }
    const shift = bits - 5;
    const value = (buffer >> shift) & 0x1f;
    bits -= 5;
    buffer &= bits === 0 ? 0 : (1 << bits) - 1;
    out += CROCKFORD.charAt(value);
  }
  return out;
}

/**
 * Build a ULID from an explicit timestamp and 10-byte randomness buffer.
 * Throws `RangeError` on invalid input; usable in deterministic tests.
 */
export function encodeUlid(timeMs: number, randomness: Uint8Array): string {
  return encodeTime(timeMs) + encodeRandomness(randomness);
}

/**
 * Generate a 26-character ULID for `timeMs`.
 * Time-ordered: IDs minted at increasing timestamps sort lexicographically.
 */
export function generateUlid(timeMs: number): string {
  const randomness = new Uint8Array(10);
  crypto.getRandomValues(randomness);
  return encodeUlid(timeMs, randomness);
}

/** Structural check for a syntactically valid ULID. */
export function isValidUlid(value: unknown): value is string {
  return typeof value === 'string' && ULID_RE.test(value);
}

/* -------------------------------------------------------------------------- */
/* Calendar day window                                                        */
/* -------------------------------------------------------------------------- */

export interface DateParts {
  year: number;
  month: number;
  day: number;
}

export interface DayWindow {
  /** Inclusive UTC epoch millis of local 00:00. */
  startMs: number;
  /** Exclusive UTC epoch millis of the next local 00:00. */
  endMs: number;
}

/**
 * UTC millis for a calendar date at 00:00:00Z.
 * Uses `setUTCFullYear` so years 0000-0099 are not remapped to 1900-1999.
 */
function utcMidnightMs(year: number, month: number, day: number): number {
  const date = new Date(0);
  date.setUTCHours(0, 0, 0, 0);
  date.setUTCFullYear(year, month - 1, day);
  return date.getTime();
}

/**
 * Parse `YYYY-MM-DD` into parts, rejecting impossible calendar dates
 * (e.g. `2024-02-30`, `2023-13-01`). Returns `null` when invalid.
 */
export function parseIsoDateParts(date: string): DateParts | null {
  const match = DATE_RE.exec(date);
  if (match === null) return null;
  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  if (month < 1 || month > 12 || day < 1 || day > 31) return null;
  const midnight = utcMidnightMs(year, month, day);
  if (!Number.isFinite(midnight)) return null;
  const check = new Date(midnight);
  if (
    check.getUTCFullYear() !== year ||
    check.getUTCMonth() !== month - 1 ||
    check.getUTCDate() !== day
  ) {
    return null;
  }
  return { year, month, day };
}

/**
 * Convert a local calendar date plus a fixed timezone offset (minutes east of
 * UTC, e.g. `+480` for UTC+8) into the `[startMs, endMs)` UTC window.
 *
 * DST is intentionally not modelled: the client supplies the effective offset
 * for that date, and the window is always exactly 24h long.
 * Returns `null` when the date or the offset is invalid.
 */
export function computeDayWindow(date: string, tzOffsetMinutes: number): DayWindow | null {
  if (!isValidTzOffset(tzOffsetMinutes)) return null;
  const parts = parseIsoDateParts(date);
  if (parts === null) return null;
  const startMs = utcMidnightMs(parts.year, parts.month, parts.day) - tzOffsetMinutes * 60_000;
  return { startMs, endMs: startMs + MS_PER_DAY };
}

/* -------------------------------------------------------------------------- */
/* Rate limiting                                                              */
/* -------------------------------------------------------------------------- */

/**
 * Remaining cooldown for a player, in milliseconds.
 * `0` means the submission may proceed.
 * Pure: the caller supplies the most recent stored `created_at` (or `null`).
 */
export function rateLimitRetryAfterMs(lastCreatedAt: number | null, now: number): number {
  if (lastCreatedAt === null || !Number.isFinite(lastCreatedAt)) return 0;
  const elapsed = now - lastCreatedAt;
  if (!Number.isFinite(elapsed)) return 0;
  if (elapsed < 0) return RATE_LIMIT_MS; // clock moved backwards: be conservative
  return elapsed < RATE_LIMIT_MS ? RATE_LIMIT_MS - elapsed : 0;
}

/* -------------------------------------------------------------------------- */
/* Message input validation                                                   */
/* -------------------------------------------------------------------------- */

/** Normalised, validated submission payload. */
export interface ValidatedMessageInput {
  type: MessageType;
  /** Trimmed storage form; length limits were checked against the raw input. */
  content: string;
  playerUuid: string;
  playerName: string | null;
  /** `null` for `normal` messages and for capsules without a valid future date. */
  openAt: number | null;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function normalisePlayerName(value: unknown): string | null {
  if (typeof value !== 'string') return null;
  const trimmed = value.trim();
  if (trimmed.length === 0) return null;
  return truncateCodePoints(trimmed, MAX_PLAYER_NAME_LENGTH);
}

/** How long an opened capsule survives before the scheduled purge removes it. */
export const CAPSULE_READ_RETENTION_MS = 30 * 60 * 1000;

/**
 * Development builds may backdate `openAt`, but never by more than this. Even in dev a
 * capsule must be a *time capsule*, not an arbitrary backdated record.
 */
export const DEV_MAX_BACKDATE_MS = 30 * 60 * 1000;

/** Options that relax validation for development builds. */
export interface ValidationOptions {
  /**
   * Allow a capsule whose `openAt` is in the past, so it can be created and read
   * immediately while developing. Enabled only when the Worker var
   * `ALLOW_PAST_CAPSULE_OPEN` is `"true"`.
   */
  allowPastOpenAt?: boolean;
}

/**
 * Validate a decoded JSON body for `POST /v1/messages`.
 *
 * Order of checks matches the API contract: type, content, length, player,
 * openAt. `playerName` never rejects the request; it is truncated to 32 code
 * points and non-string values are ignored (`null`).
 */
export function validateMessageInput(
  raw: unknown,
  now: number,
  options: ValidationOptions = {},
): ValidationResult<ValidatedMessageInput> {
  if (!isRecord(raw)) return fail(400, 'bad_type');

  const type = raw.type;
  if (!isMessageType(type)) return fail(400, 'bad_type');

  const content = raw.content;
  if (typeof content !== 'string') return fail(400, 'empty_content');
  const trimmed = content.trim();
  if (trimmed.length === 0) return fail(400, 'empty_content');

  const max = MAX_CONTENT_LENGTH[type];
  const actual = codePointLength(content);
  if (actual > max) return fail(400, 'too_long', { max, actual });

  const playerUuid = raw.playerUuid;
  if (!isCanonicalUuid(playerUuid)) return fail(400, 'bad_player');
  const canonicalUuid = playerUuid.toLowerCase();

  let openAt: number | null = null;
  if (type === 'capsule') {
    const rawOpenAt = raw.openAt;
    if (typeof rawOpenAt !== 'number' || !Number.isInteger(rawOpenAt)) {
      return fail(400, 'bad_open_at');
    }
    // Production semantics: a capsule must always open in the future. Development
    // builds may backdate it, but only by DEV_MAX_BACKDATE_MS - even while debugging a
    // capsule may not claim to have been waiting for an arbitrarily long time.
    if (!options.allowPastOpenAt && rawOpenAt <= now) {
      return fail(400, 'bad_open_at');
    }
    if (options.allowPastOpenAt && rawOpenAt < now - DEV_MAX_BACKDATE_MS) {
      return fail(400, 'bad_open_at', { earliest: now - DEV_MAX_BACKDATE_MS });
    }
    openAt = rawOpenAt;
  }
  // `normal` messages ignore any supplied `openAt` and store NULL.

  return {
    ok: true,
    value: {
      type,
      content: trimmed,
      playerUuid: canonicalUuid,
      playerName: normalisePlayerName(raw.playerName),
      openAt,
    },
  };
}

/* -------------------------------------------------------------------------- */
/* Compression (deflate-raw)                                                  */
/* -------------------------------------------------------------------------- */

const textEncoder = new TextEncoder();
const textDecoder = new TextDecoder();

function streamOf(bytes: Uint8Array): ReadableStream<Uint8Array> {
  return new ReadableStream<Uint8Array>({
    start(controller): void {
      controller.enqueue(bytes);
      controller.close();
    },
  });
}

/** Compress UTF-8 text to deflate-raw bytes (stored as a D1 BLOB). */
export async function compressContent(text: string): Promise<Uint8Array> {
  const source = streamOf(textEncoder.encode(text));
  const compressed = source.pipeThrough(new CompressionStream('deflate-raw'));
  const buffer = await new Response(compressed).arrayBuffer();
  return new Uint8Array(buffer);
}

/** Accept any of the shapes D1 may hand back for a BLOB column. */
export function toBytes(value: unknown): Uint8Array {
  if (value instanceof Uint8Array) return value;
  if (value instanceof ArrayBuffer) return new Uint8Array(value);
  if (ArrayBuffer.isView(value)) {
    const view = value as ArrayBufferView;
    return new Uint8Array(view.buffer, view.byteOffset, view.byteLength);
  }
  if (Array.isArray(value)) {
    return Uint8Array.from(value as number[]);
  }
  throw new TypeError(`unsupported BLOB representation: ${typeof value}`);
}

/** Decompress deflate-raw bytes back into UTF-8 text. */
export async function decompressContent(bytes: Uint8Array | ArrayBuffer): Promise<string> {
  const input = bytes instanceof ArrayBuffer ? new Uint8Array(bytes) : bytes;
  const source = streamOf(input);
  const inflated = source.pipeThrough(new DecompressionStream('deflate-raw'));
  const buffer = await new Response(inflated).arrayBuffer();
  return textDecoder.decode(new Uint8Array(buffer));
}
