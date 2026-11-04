-- VaultLocal schema v1 (DESIGN.md §3.3). Columns holding AES-GCM blobs are marked [enc].

CREATE TABLE vault_meta (
  id                 INTEGER PRIMARY KEY CHECK (id = 1),
  vault_id           TEXT    NOT NULL,
  format_version     INTEGER NOT NULL,
  master_key_length  INTEGER NOT NULL,
  dek_generation     INTEGER NOT NULL,
  created_at         INTEGER NOT NULL
);

CREATE TABLE key_wrap (
  wrap_type          TEXT    PRIMARY KEY CHECK (wrap_type IN ('MASTER','RECOVERY')),
  kdf_algorithm      TEXT    NOT NULL,
  kdf_version        INTEGER NOT NULL,
  kdf_memory_kib     INTEGER NOT NULL,
  kdf_iterations     INTEGER NOT NULL,
  kdf_parallelism    INTEGER NOT NULL,
  salt               BLOB    NOT NULL,
  wrapped_dek        BLOB    NOT NULL,   -- [enc]
  updated_at         INTEGER NOT NULL
);

CREATE TABLE security_question (
  slot               INTEGER PRIMARY KEY CHECK (slot IN (1,2,3)),
  question_key       TEXT    NOT NULL,
  custom_text        TEXT
);

CREATE TABLE entry (
  id                  TEXT    PRIMARY KEY,
  platform            BLOB    NOT NULL,  -- [enc]
  account_label       BLOB    NOT NULL,  -- [enc]
  username            BLOB    NOT NULL,  -- [enc]
  password            BLOB    NOT NULL,  -- [enc]
  website_url         BLOB,              -- [enc]
  notes               BLOB,              -- [enc]
  favorite            INTEGER NOT NULL DEFAULT 0,
  created_at          INTEGER NOT NULL,
  updated_at          INTEGER NOT NULL,
  password_changed_at INTEGER NOT NULL
);

CREATE TABLE password_history (
  entry_id           TEXT    NOT NULL REFERENCES entry(id) ON DELETE CASCADE,
  seq                INTEGER NOT NULL,
  password           BLOB    NOT NULL,   -- [enc]
  replaced_at        INTEGER NOT NULL,
  PRIMARY KEY (entry_id, seq)
);

CREATE TABLE lockout_state (
  channel              TEXT    PRIMARY KEY CHECK (channel IN ('MASTER','RECOVERY')),
  consecutive_failures INTEGER NOT NULL DEFAULT 0,
  last_failure_at      INTEGER,
  locked_until         INTEGER
);

CREATE TABLE activity_log (
  id                 INTEGER PRIMARY KEY AUTOINCREMENT,
  occurred_at        INTEGER NOT NULL,
  event_type         TEXT    NOT NULL,
  entry_id           TEXT
);

CREATE TABLE settings (
  key                TEXT PRIMARY KEY,
  value              TEXT NOT NULL
);
