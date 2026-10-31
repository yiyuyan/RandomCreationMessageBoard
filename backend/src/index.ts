/**
 * Worker entry point: routing + D1 access only.
 * Validation, ULID, day-window and compression logic lives in `./lib`.
 */

import {
  CAPSULE_READ_RETENTION_MS,
  compressContent,
  computeDayWindow,
  decompressContent,
  DEFAULT_LIST_LIMIT,
  generateUlid,
  isMessageType,
  LIST_LIMIT,
  parseIsoDateParts,
  parseTzOffset,
  RATE_LIMIT_MS,
  rateLimitRetryAfterMs,
  toBytes,
  validateMessageInput,
  type ApiErrorBody,
  type MessageType,
} from './lib';

export interface Env {
  DB: D1Database;
  /**
   * `"true"` relaxes the capsule rule so `openAt` may be in the past (development
   * convenience). Anything else keeps production semantics.
   */
  ALLOW_PAST_CAPSULE_OPEN?: string;
  /**
   * Optional cap on how long normal messages are kept, in days. `"0"` (or unset) keeps
   * them forever. Set to e.g. `"90"` to bound total storage for busy boards.
   */
  NORMAL_RETENTION_DAYS?: string;
}

/** Row shape as returned by D1; `content` arrives as a compressed BLOB. */
interface MessageRow {
  id: string;
  type: string;
  content: unknown;
  created_at: number;
  open_at: number | null;
  player_name: string | null;
}

/** Row shape for the list endpoint (no player UUID, no open_at). */
interface ListRow {
  id: string;
  type: string;
  content: unknown;
  created_at: number;
  player_name: string | null;
}

const CORS_HEADERS: Record<string, string> = {
  'access-control-allow-origin': '*',
  'access-control-allow-methods': 'GET, POST, OPTIONS',
  'access-control-allow-headers': 'content-type',
  'access-control-max-age': '86400',
};

const JSON_HEADERS: Record<string, string> = {
  'content-type': 'application/json; charset=utf-8',
  ...CORS_HEADERS,
};

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: JSON_HEADERS });
}

function errorJson(body: ApiErrorBody, status: number): Response {
  return json(body, status);
}

function notFound(): Response {
  return errorJson({ ok: false, error: 'not_found' }, 404);
}

function methodNotAllowed(): Response {
  return new Response(
    JSON.stringify({ ok: false, error: 'method_not_allowed' } satisfies ApiErrorBody),
    { status: 405, headers: { ...JSON_HEADERS, allow: 'GET, POST, OPTIONS' } },
  );
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    try {
      return await route(request, env);
    } catch (error) {
      // Never leak a stack trace to the client; log details for Workers Logs.
      console.error('unhandled request error', {
        method: request.method,
        url: request.url,
        message: error instanceof Error ? error.message : String(error),
        stack: error instanceof Error ? error.stack : undefined,
      });
      return errorJson({ ok: false, error: 'internal' }, 500);
    }
  },

  /**
   * Cron trigger: drop capsules that were opened more than 30 minutes ago. Capsules that
   * nobody has opened yet are kept - only the content that has already been read is
   * purged, which is what keeps storage from growing without bound.
   */
  async scheduled(_controller: ScheduledController, env: Env, _ctx: ExecutionContext): Promise<void> {
    try {
      const result = await purgeExpired(env, Date.now());
      console.info('purge finished', result);
    } catch (error) {
      console.error('purge failed', {
        message: error instanceof Error ? error.message : String(error),
      });
    }
  },
} satisfies ExportedHandler<Env>;

/**
 * Deletes capsules whose first read is older than the retention window, plus - only when
 * `NORMAL_RETENTION_DAYS` is set - normal messages older than that many days.
 */
async function purgeExpired(
  env: Env,
  now: number,
): Promise<{ capsules: number; normal: number }> {
  const capsules = await purgeOpenedCapsules(env, now);
  return { capsules, normal: await purgeOldNormalMessages(env, now) };
}

/** Optional: bound total storage by dropping old plain messages. Disabled by default. */
async function purgeOldNormalMessages(env: Env, now: number): Promise<number> {
  const days = Number(env.NORMAL_RETENTION_DAYS ?? '0');
  if (!Number.isFinite(days) || days <= 0) {
    return 0;
  }
  const cutoff = now - days * 86_400_000;
  const result = await env.DB.prepare(
    `DELETE FROM messages WHERE type = 'normal' AND created_at <= ?`,
  )
    .bind(cutoff)
    .run();
  return result.meta.changes ?? 0;
}

/** Deletes capsules whose first read is older than the retention window. */
async function purgeOpenedCapsules(env: Env, now: number): Promise<number> {
  const result = await env.DB.prepare(
    `DELETE FROM messages
      WHERE type = 'capsule'
        AND read_at IS NOT NULL
        AND read_at <= ?`,
  )
    .bind(now - CAPSULE_READ_RETENTION_MS)
    .run();
  return result.meta.changes ?? 0;
}

async function route(request: Request, env: Env): Promise<Response> {
  const url = new URL(request.url);
  const method = request.method.toUpperCase();

  // CORS preflight: answer for any path, no body.
  if (method === 'OPTIONS') {
    return new Response(null, { status: 204, headers: CORS_HEADERS });
  }

  // Trailing slashes are tolerated; empty path stays '/'.
  const path = url.pathname.replace(/\/+$/, '') || '/';

  if (path === '/v1/health') {
    if (method !== 'GET') return methodNotAllowed();
    return json({ ok: true, now: Date.now() });
  }

  if (path === '/v1/messages') {
    if (method === 'POST') return postMessage(request, env);
    if (method === 'GET') return listMessages(url, env);
    return methodNotAllowed();
  }

  const single = /^\/v1\/messages\/([^/]+)$/.exec(path);
  if (single !== null) {
    if (method !== 'GET') return methodNotAllowed();
    let id: string;
    try {
      id = decodeURIComponent(single[1] ?? '');
    } catch {
      // Malformed percent-encoding can never match a stored id.
      return notFound();
    }
    return getMessage(id, env);
  }

  return notFound();
}

/* -------------------------------------------------------------------------- */
/* POST /v1/messages                                                          */
/* -------------------------------------------------------------------------- */

async function postMessage(request: Request, env: Env): Promise<Response> {
  const now = Date.now();

  let raw: unknown;
  try {
    raw = await request.json();
  } catch {
    return errorJson({ ok: false, error: 'bad_json' }, 400);
  }

  const parsed = validateMessageInput(raw, now, {
    allowPastOpenAt: env.ALLOW_PAST_CAPSULE_OPEN === 'true',
  });
  if (!parsed.ok) return errorJson(parsed.body, parsed.status);
  const input = parsed.value;

  const id = generateUlid(now);
  const compressed = await compressContent(input.content);

  // Rate limit and insert in ONE statement: the row is only written when this player has
  // not posted within the window. This removes a read per POST (the old SELECT) and makes
  // the limit atomic, so two simultaneous requests can no longer both pass.
  const inserted = await env.DB.prepare(
    `INSERT INTO messages (id, type, content, created_at, open_at, player_uuid, player_name)
     SELECT ?, ?, ?, ?, ?, ?, ?
      WHERE NOT EXISTS (
        SELECT 1 FROM messages WHERE player_uuid = ? AND created_at > ?
      )`,
  )
    .bind(
      id,
      input.type,
      compressed,
      now,
      input.openAt,
      input.playerUuid,
      input.playerName,
      input.playerUuid,
      now - RATE_LIMIT_MS,
    )
    .run();

  if ((inserted.meta.changes ?? 0) === 0) {
    // Only the rejected path pays for the extra read that reports the remaining wait.
    const previous = await env.DB.prepare(
      'SELECT created_at FROM messages WHERE player_uuid = ? ORDER BY created_at DESC LIMIT 1',
    )
      .bind(input.playerUuid)
      .first<{ created_at: number }>();
    const retryAfterMs = rateLimitRetryAfterMs(previous?.created_at ?? null, now);
    return errorJson({ ok: false, error: 'rate_limited', retryAfterMs: Math.max(retryAfterMs, 1) }, 429);
  }

  // Opportunistic purge: a POST is already a write, so expired capsules are cleaned up
  // here instead of waiting for the (now hourly) cron backstop.
  await purgeExpired(env, now);

  return json({ ok: true, id, type: input.type, createdAt: now, openAt: input.openAt }, 201);
}

/* -------------------------------------------------------------------------- */
/* GET /v1/messages?date=&tzOffset=&type=                                     */
/* -------------------------------------------------------------------------- */

async function listMessages(url: URL, env: Env): Promise<Response> {
  const dateParam = url.searchParams.get('date');
  if (dateParam === null || parseIsoDateParts(dateParam) === null) {
    return errorJson({ ok: false, error: 'bad_date' }, 400);
  }

  const tzOffset = parseTzOffset(url.searchParams.get('tzOffset'));
  if (tzOffset === null) {
    return errorJson({ ok: false, error: 'bad_tz' }, 400);
  }

  const typeParam = url.searchParams.get('type');
  const type: MessageType = typeParam === null ? 'normal' : (typeParam as MessageType);
  if (!isMessageType(type)) {
    return errorJson({ ok: false, error: 'bad_type' }, 400);
  }

  const window = computeDayWindow(dateParam, tzOffset);
  if (window === null) return errorJson({ ok: false, error: 'bad_date' }, 400);

  // Incremental sync: `since` returns only rows newer than the given instant, so a board
  // refresh after the first one reads a handful of rows instead of the whole day.
  const sinceParam = url.searchParams.get('since');
  let since = 0;
  if (sinceParam !== null && sinceParam.length > 0) {
    const parsed = Number(sinceParam);
    if (!Number.isInteger(parsed) || parsed < 0) {
      return errorJson({ ok: false, error: 'bad_since' }, 400);
    }
    since = parsed;
  }

  const limitParam = url.searchParams.get('limit');
  let limit = DEFAULT_LIST_LIMIT;
  if (limitParam !== null && limitParam.length > 0) {
    const parsed = Number(limitParam);
    if (!Number.isInteger(parsed) || parsed < 1) {
      return errorJson({ ok: false, error: 'bad_limit' }, 400);
    }
    limit = Math.min(parsed, LIST_LIMIT);
  }

  const { results } = await env.DB.prepare(
    `SELECT id, type, content, created_at, player_name
       FROM messages
      WHERE created_at >= ? AND created_at < ? AND type = ? AND created_at > ?
      ORDER BY created_at ASC
      LIMIT ?`,
  )
    .bind(window.startMs, window.endMs, type, since, limit)
    .all<ListRow>();

  const rows = results ?? [];
  // Decompress rows concurrently: each content column is its own deflate-raw blob.
  const messages = await Promise.all(
    rows.map(async (row) => ({
      id: row.id,
      type: row.type,
      content: await decompressContent(toBytes(row.content)),
      createdAt: row.created_at,
      playerName: row.player_name,
    })),
  );

  // `truncated` lets the client ask again with a higher `since` instead of guessing.
  return json({ ok: true, messages, truncated: rows.length >= limit });
}

/* -------------------------------------------------------------------------- */
/* GET /v1/messages/{id}                                                      */
/* -------------------------------------------------------------------------- */

async function getMessage(id: string, env: Env): Promise<Response> {
  if (id.length === 0) return notFound();

  const row = await env.DB.prepare(
    `SELECT id, type, content, created_at, open_at, player_name
       FROM messages
      WHERE id = ?`,
  )
    .bind(id)
    .first<MessageRow>();

  if (row === null || row === undefined) return notFound();

  if (row.type === 'capsule' && row.open_at !== null && Date.now() < row.open_at) {
    // Sealed capsule: never include the content.
    return errorJson({ ok: false, error: 'not_yet_open', openAt: row.open_at }, 403);
  }

  const content = await decompressContent(toBytes(row.content));

  if (row.type === 'capsule') {
    // The first successful open starts the retention countdown for the purge job.
    await env.DB.prepare('UPDATE messages SET read_at = COALESCE(read_at, ?) WHERE id = ?')
      .bind(Date.now(), row.id)
      .run();
  }

  return json({
    ok: true,
    message: {
      id: row.id,
      type: row.type,
      content,
      createdAt: row.created_at,
      openAt: row.open_at,
      playerName: row.player_name,
    },
  });
}
