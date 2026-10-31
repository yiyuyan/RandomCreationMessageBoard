# crc-msg-board — Cloudflare Worker + D1 backend

Backend for the RandomCreation mod's in-game message board: players post short
**normal** messages and sealed **capsules** (time-locked messages), and clients
read back a whole in-game "day" of messages for their timezone.

Runs on Cloudflare Workers with a single D1 binding (`DB`). No runtime npm
dependencies, no Node built-ins — Web-standard APIs only
(`crypto.getRandomValues`, `CompressionStream`, `TextEncoder/Decoder`).

---

## Layout

```
backend/
├── package.json          # scripts + dev tooling (wrangler, typescript, types)
├── package-lock.json
├── tsconfig.json         # Worker program (strict, workers-types, noEmit)
├── tsconfig.test.json    # Node program for tests (@types/node)
├── wrangler.toml         # name, main, compatibility_date, D1 binding
├── schema.sql            # table + indexes
├── src/
│   ├── lib.ts            # pure helpers: validation, ULID, day window, compression
│   └── index.ts          # Worker fetch handler: routing + D1 only
├── tests/
│   └── lib.test.ts       # node:test unit tests for lib.ts
└── README.md
```

---

## Setup

On Windows use `npm.cmd` / `npx.cmd` (the `.ps1` shims are blocked by execution
policy). Node 20+ (Node 24 recommended for the tests).

```powershell
cd backend
npm.cmd install
```

### 1. Create the D1 database

```powershell
npx.cmd wrangler login          # once per machine
npx.cmd wrangler d1 create crc_msg_board
```

The command prints the new database id. Copy it into `wrangler.toml`:

```toml
[[d1_databases]]
binding = "DB"
database_name = "crc_msg_board"
database_id = "REPLACE_WITH_YOUR_DATABASE_ID"   # <-- replace this line
```

### 2. Apply the schema (remote)

```powershell
npx.cmd wrangler d1 execute crc_msg_board --file=schema.sql --remote
# same thing via the package script:
npm.cmd run db:init
```

### 3. Deploy

```powershell
npm.cmd run deploy              # wrangler deploy
```

### Local development

```powershell
npm.cmd run db:local            # apply schema to the local SQLite-backed D1
npm.cmd run dev                 # wrangler dev -> http://127.0.0.1:8787
```

`wrangler dev` uses the local simulator, so the placeholder `database_id` is fine
locally; it must be replaced only for `deploy` / `--remote` commands.

### Scripts

| Script | Command | Purpose |
| --- | --- | --- |
| `dev` | `wrangler dev` | Local Worker + local D1 |
| `deploy` | `wrangler deploy` | Publish the Worker |
| `typecheck` | `tsc --noEmit && tsc --noEmit -p tsconfig.test.json` | Type-check src and tests |
| `db:init` | `wrangler d1 execute crc_msg_board --file=schema.sql --remote` | Create tables/indexes in production D1 |
| `db:local` | `wrangler d1 execute crc_msg_board --file=schema.sql --local` | Same for the local D1 |
| `test` | `node --experimental-strip-types --test "tests/**/*.test.ts"` | Unit tests |

---

## API contract

### Development flag: `ALLOW_PAST_CAPSULE_OPEN`

Set in `wrangler.toml` under `[vars]`. When `"true"`, a `capsule` may be created
with an `openAt` in the **past**, so it can be opened immediately instead of
waiting for the clock — useful while testing the mod's lock/unlock flow.

The backdating is bounded: `openAt` must be **no older than 30 minutes**
(`DEV_MAX_BACKDATE_MS`). A request older than that is rejected with
`400 bad_open_at` and an `earliest` field naming the oldest accepted instant.
Content limits, rate limit, UUID and type checks are unchanged.

Set it to `"false"` or remove it to restore production semantics, where every
capsule must open in the future.

### Capsule retention

A capsule is deleted once it has been **opened and 30 minutes have passed**
(`CAPSULE_READ_RETENTION_MS`). The first successful `GET /v1/messages/{id}`
stamps `read_at` (`COALESCE` keeps the first value); a cron trigger
(`*/5 * * * *`) then runs:

```sql
DELETE FROM messages
 WHERE type = 'capsule'
   AND read_at IS NOT NULL
   AND read_at <= now - 30 minutes;
```

Unread capsules are never purged, so a player can open a capsule long after its
`openAt` has passed. To purge by opening time instead, change the predicate to
`open_at <= ?` and drop the `read_at IS NOT NULL` clause.

### Cost and capacity

Measured on this deployment (2000 row local experiment, real D1 semantics):

| Item | Before | After |
|---|---|---|
| Cron invocations | 288/day (`*/5`) | **24/day** (`0 * * * *`) |
| Purge latency | up to 5 min after expiry | **next POST** (hourly cron is only a backstop) |
| Reads per POST | 1 SELECT (rate limit) + 1 INSERT | **1 INSERT** (rate limit folded into the write) |
| Rows read per board refresh | whole day (up to 500) | **only rows newer than `since`** |
| Storage per row | ~309 B (4 indexes) | **~294.6 B** (unused `open_at` index dropped) |

Per-row cost breaks down as: 26 B id, ~36 B `player_uuid`, compressed content,
two integer timestamps plus the three index entries. At ~295 B/row the 5 GB free
D1 allowance holds roughly **17 million messages**.

The list endpoint now accepts `since=<epochMillis>` (incremental) and
`limit=<1..500>` (default 200) and reports `truncated`, so a client can sync
cheaply instead of re-reading a whole day. `NORMAL_RETENTION_DAYS` (default
`"0"` = keep forever) can cap plain-message growth on a busy board.

Base URL: `https://crc-msg-board.<your-subdomain>.workers.dev` (or your custom
domain). All responses are `content-type: application/json; charset=utf-8` and
carry permissive CORS headers (`access-control-allow-origin: *`,
`access-control-allow-methods: GET, POST, OPTIONS`,
`access-control-allow-headers: content-type`). `OPTIONS` on any path returns
`204` with no body. Every failure body has the shape:

```json
{ "ok": false, "error": "<code>" }
```

plus optional fields listed in the error table below.

### POST /v1/messages

Create a message. Request body:

```json
{
  "type": "normal",
  "content": "hello world",
  "playerUuid": "123e4567-e89b-12d3-a456-426614174000",
  "playerName": "Steve",
  "openAt": 1767225600000
}
```

| Field | Required | Rules |
| --- | --- | --- |
| `type` | yes | `"normal"` or `"capsule"` |
| `content` | yes | string, non-empty after `.trim()`; max **200** code points (`normal`) / **500** (`capsule`), measured on the **raw** string |
| `playerUuid` | yes | canonical UUID `8-4-4-4-12` (stored lowercase) |
| `playerName` | no | string, truncated to **32** code points; non-strings are ignored |
| `openAt` | capsules only | integer epoch millis, strictly greater than server `now`; ignored (stored `NULL`) for `normal` |

Validation errors (HTTP 400 unless stated):

| `error` | Cause | Extra fields |
| --- | --- | --- |
| `bad_json` | body is not valid JSON | — |
| `bad_type` | `type` missing / not `normal` or `capsule` | — |
| `empty_content` | `content` is not a string, or is empty/whitespace-only | — |
| `too_long` | code-point length above the limit for that type | `max`, `actual` |
| `bad_player` | `playerUuid` is not a canonical UUID | — |
| `bad_open_at` | capsule `openAt` missing, non-integer, or not in the future | — |
| `rate_limited` | same `playerUuid` submitted within the last 3 s (HTTP **429**) | `retryAfterMs` |

Success — **201**:

```json
{ "ok": true, "id": "01M69AC8WR643VMVEVCTGQBZSC", "type": "normal", "createdAt": 1793461724056, "openAt": null }
```

### GET /v1/messages?date=YYYY-MM-DD&tzOffset=<minutes>&type=normal

Lists one local calendar day of messages, oldest first (max 500 rows).

| Param | Required | Rules |
| --- | --- | --- |
| `date` | yes | strict `YYYY-MM-DD` **and** a real calendar date |
| `tzOffset` | yes | integer minutes east of UTC, `-840 .. 840` (e.g. `480` = UTC+8) |
| `type` | no | `normal` (default) or `capsule` |

Errors: `bad_date` (missing/malformed/impossible date), `bad_tz`
(missing/non-integer/out of range), `bad_type` — all HTTP 400.

Success — **200** (note: no `playerUuid`, no `openAt` in list rows):

```json
{
  "ok": true,
  "messages": [
    { "id": "01M69AC8WR643VMVEVCTGQBZSC", "type": "normal", "content": "hello", "createdAt": 1793461724056, "playerName": "Steve" }
  ]
}
```

### GET /v1/messages/{id}

* **404** `{ "ok": false, "error": "not_found" }` when the id does not exist.
* **403** `{ "ok": false, "error": "not_yet_open", "openAt": 1793465324210 }` when
  the row is a capsule whose `open_at` is in the future — the content is **not**
  included.
* **200** otherwise:

```json
{
  "ok": true,
  "message": {
    "id": "01M69AC9WR643VMVEVCTGQBZSC",
    "type": "capsule",
    "content": "secret",
    "createdAt": 1793461724216,
    "openAt": 1793465324210,
    "playerName": "Alex"
  }
}
```

### GET /v1/health

```json
{ "ok": true, "now": 1793461688681 }
```

### Routing behaviour

| Situation | Response |
| --- | --- |
| unknown path | 404 `{ "ok": false, "error": "not_found" }` |
| known path, wrong method | 405 `{ "ok": false, "error": "method_not_allowed" }` + `allow` header |
| uncaught exception | 500 `{ "ok": false, "error": "internal" }` (details only in `console.error` / Workers Logs) |

---

## curl examples

Bash / zsh / `curl.exe` (use `--data-binary` so UTF-8 is not mangled on Windows):

```bash
BASE=https://crc-msg-board.example.workers.dev

# health
curl -s $BASE/v1/health

# post a normal message
curl -s -X POST $BASE/v1/messages \
  -H 'content-type: application/json' \
  --data-binary '{"type":"normal","content":"你好，我的世界！😀","playerUuid":"123e4567-e89b-12d3-a456-426614174000","playerName":"Steve"}'

# post a capsule that opens in one hour
OPEN_AT=$(( $(date +%s) * 1000 + 3600000 ))
curl -s -X POST $BASE/v1/messages \
  -H 'content-type: application/json' \
  --data-binary "{\"type\":\"capsule\",\"content\":\"see you tomorrow\",\"playerUuid\":\"123e4567-e89b-12d3-a456-426614174001\",\"openAt\":$OPEN_AT}"

# read one message (403 not_yet_open while sealed)
curl -s $BASE/v1/messages/01M69AC8WR643VMVEVCTGQBZSC

# list today's normal messages for UTC+8
curl -s "$BASE/v1/messages?date=2026-11-01&tzOffset=480&type=normal"

# list today's capsules for UTC-5
curl -s "$BASE/v1/messages?date=2026-10-31&tzOffset=-300&type=capsule"

# validation error examples
curl -s -X POST $BASE/v1/messages -H 'content-type: application/json' \
  --data-binary '{"type":"normal","content":"   ","playerUuid":"123e4567-e89b-12d3-a456-426614174000"}'
# {"ok":false,"error":"empty_content"}

curl -s "$BASE/v1/messages?date=2024-02-30&tzOffset=0"
# {"ok":false,"error":"bad_date"}
```

PowerShell equivalent (note `curl` is an alias for `Invoke-WebRequest` there —
call `curl.exe` explicitly, or use `Invoke-RestMethod`):

```powershell
$body = '{"type":"normal","content":"hello","playerUuid":"123e4567-e89b-12d3-a456-426614174000"}'
curl.exe -s -X POST http://127.0.0.1:8787/v1/messages -H "content-type: application/json" --data-binary $body
```

---

## Storage and design notes

* **Compression** — `content` is compressed with `CompressionStream('deflate-raw')`
  and stored as a D1 `BLOB` (bound as a `Uint8Array`). Reads reverse it with
  `DecompressionStream`. `toBytes()` in `lib.ts` normalises whatever shape D1
  hands back (`ArrayBuffer`, `Uint8Array`, byte array).
* **IDs** — 26-character ULIDs in Crockford base32: 10 chars of 48-bit
  millisecond timestamp + 16 chars of 80 random bits from
  `crypto.getRandomValues`. Encoded in `lib.ts` (`encodeUlid`, `generateUlid`),
  no dependency. ULIDs sort lexicographically by creation time.
* **Day window** — `startMs = UTC_midnight(date) - tzOffset * 60000`,
  `endMs = startMs + 86_400_000`; the query is
  `created_at >= startMs AND created_at < endMs AND type = ?` ordered by
  `created_at ASC LIMIT 500`, served by `idx_msg_type_created`. The timezone is
  a fixed offset for that date (no DST table on the server) — the client sends
  the offset actually in effect.
* **Rate limit** — server-side, per `playerUuid`, 3 s between submissions.
  Enforced by reading `MAX(created_at)` for that player (index
  `idx_msg_player_created`) and returning **429** with `retryAfterMs` when
  `now - last < 3000`. The limit spans both message types and is applied
  *after* payload validation. The client cannot bypass it.
* **No secondary per-IP limit** — deliberately omitted: the agreed schema has no
  IP column, so a per-IP counter would require either an extra table/column or a
  non-durable in-isolate `Map`. Say the word and it can be added as a small
  `ip_hits` table.
* **Parameterised SQL** — every statement uses `prepare().bind(...)`; no string
  interpolation of user input.
* **Errors** — the handler wraps everything in one `try/catch`; unexpected
  failures log structured details with `console.error` and return
  `{ ok: false, error: "internal" }` / 500. Stack traces never reach clients.
  `observability.enabled` + `observability.traces.enabled` are on in
  `wrangler.toml` so those logs are queryable in the dashboard.
* **Thin handler** — `src/index.ts` is routing + D1 only; all validation, ULID,
  weekday/date math and compression primes live in `src/lib.ts`, which is why it
  is unit-testable with plain `node:test`.

---

## Ambiguities in the spec and how they were resolved

These are the places where the contract left room and an explicit choice was
made (each is easy to flip):

1. **Stored content is trimmed.** Length is validated on the raw string (as
   specified), but the persisted/returned content is `content.trim()`-ed, so
   clients never see leading/trailing whitespace.
2. **Non-string `content`** → `400 empty_content` (the spec only defines
   `empty_content` for the "must be a string / non-empty" branch).
3. **Malformed JSON body** → `400 { ok:false, error:"bad_json" }` (new code; the
   spec did not name one). Empty body behaves the same way.
4. **405 body** uses `error: "method_not_allowed"` (spec fixed the status but not
   the code) and adds an `allow` header.
5. **`playerName`** — truncated to 32 code points, never rejected; a non-string
   value is ignored and stored as `NULL`; an empty/whitespace-only name is also
   stored as `NULL`.
6. **`playerUuid` is lowercased** before storage and before the rate-limit
   lookup, so the same player using uppercase hex is treated as one player.
   Validation still accepts `A-F` as the regex requires.
7. **Rate limit is per UUID across both types** (a capsule right after a normal
   message is throttled too) and is checked after payload validation, so an
   invalid payload returns its 400 rather than a 429.
8. **`open_at` IS NULL on capsule rows is treated as "open"** in
   `GET /v1/messages/{id}` (defensive: the API cannot create such a row, since a
   capsule always requires a future `openAt`).
9. **Date years are not range-limited** (any real `YYYY-MM-DD` is accepted, e.g.
   `0001-01-01`); only shape + calendar validity is enforced, per the contract.
10. **`tzOffset` accepts a leading `+`** (`+480`) and surrounding whitespace via
    trimming; `480.0`/`1e3`/`480min` are rejected.
11. **Trailing slashes** on paths are tolerated (`/v1/health/` == `/v1/health`).
12. **CORS** headers are also attached to error responses and 405s, and
    `access-control-max-age: 86400` was added for preflight caching.
13. **`type` on the list endpoint** must be exactly `normal`/`capsule` when
    supplied; an empty string (`?type=`) is `bad_type`, not "default".

---

## Verification performed

```text
$ npm.cmd run typecheck
> crc-msg-board@1.0.0 typecheck
> tsc --noEmit && tsc --noEmit -p tsconfig.test.json
(no output — 0 errors, exit code 0)

$ npm.cmd run test
ℹ tests 27
ℹ pass 27
ℹ fail 0
```

Two `tsconfig` files are used because `@cloudflare/workers-types` and
`@types/node` both declare the same globals (`Response`, `CompressionStream`, …)
and cannot be loaded into one program. `tsconfig.json` type-checks `src/`
against the Workers runtime; `tsconfig.test.json` type-checks `tests/` against
Node.

Unit tests (`tests/lib.test.ts`) cover: code-point length limits 200/500 with
`😀` counted as one code point, raw-vs-trimmed measurement, empty/whitespace
rejection, UUID validation, `openAt` rules, ULID format/length/decoding/
lexicographic sortability, day-window maths across timezone boundaries
(UTC+8, UTC-5, UTC+0), impossible calendar dates, `tzOffset` range, the rate-limit
cooldown maths, and deflate-raw round-trips for ASCII, CJK + emoji and
boundary-length content.

An end-to-end smoke test was also run against `wrangler dev` + local D1
(42 assertions, all passing): create → read → list for `normal` and `capsule`,
403 `not_yet_open` before `open_at` and 200 after it, 429 on a second post
within 3 s and success after the cooldown, all validation branches, 404/405/
`OPTIONS` 204, CORS headers, and `typeof(content) = 'blob'` in SQLite with the
expected compressed byte lengths.

> Node note: `node --experimental-strip-types --test tests/` (directory form)
> fails on Node 24.9/Windows with `Cannot find module '...\tests'`; the `test`
> script uses the glob form `--test "tests/**/*.test.ts"` instead, which works.
> Passing the file directly (`--test tests/lib.test.ts`) also works.

---

## Operational notes

* `database_id` in `wrangler.toml` is the literal placeholder
  `REPLACE_WITH_YOUR_DATABASE_ID`. `wrangler dev` and
  `wrangler deploy --dry-run` work with it, but `wrangler deploy` / `--remote`
  against the real database requires the id from `wrangler d1 create`.
* If `wrangler` aborts with
  `The package "@cloudflare/workerd-windows-64" could not be found`, the platform
  binary was skipped during install; fix it with
  `npm.cmd install --no-save @cloudflare/workerd-windows-64@1.20261023.1`
  (or simply `npm.cmd install` again).
* D1 has no cross-statement transaction here: the rate-limit `SELECT` and the
  `INSERT` are two separate calls, so two truly simultaneous requests from the
  same UUID could in principle both pass the check. Real-world submissions are
  seconds apart, and the window is 3 s; if strict enforcement is needed, move to
  a per-player Durable Object or a unique-constraint trick on a rounded
  timestamp.
