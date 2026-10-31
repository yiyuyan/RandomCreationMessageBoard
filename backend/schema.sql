-- D1 schema for the CRC message board.
-- Apply with:
--   npx wrangler d1 execute crc_msg_board --file=schema.sql --remote
CREATE TABLE IF NOT EXISTS messages (
  id          TEXT PRIMARY KEY,
  type        TEXT NOT NULL CHECK (type IN ('normal','capsule')),
  content     BLOB NOT NULL,          -- deflate-raw compressed UTF-8 bytes
  created_at  INTEGER NOT NULL,       -- epoch millis (server clock)
  open_at     INTEGER,                -- epoch millis, capsule only, else NULL
  read_at     INTEGER,                -- epoch millis of the first successful open
  player_uuid TEXT NOT NULL,
  player_name TEXT
);
CREATE INDEX IF NOT EXISTS idx_msg_type_created ON messages (type, created_at);
CREATE INDEX IF NOT EXISTS idx_msg_player_created ON messages (player_uuid, created_at);
-- `idx_msg_open_at` was dropped: no query filters on open_at (the purge uses read_at and
-- the list uses type+created_at), so keeping it only cost index pages and one extra
-- index update per insert.
-- Drives the scheduled purge of already-opened capsules.
CREATE INDEX IF NOT EXISTS idx_msg_read_at ON messages (read_at);
