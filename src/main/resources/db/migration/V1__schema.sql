-- Schéma initial. Les montants sont en centimes entiers (signés : crédit > 0, débit < 0),
-- les dates au format ISO-8601 (AAAA-MM-JJ), ce qui les rend triables telles quelles.

CREATE TABLE account (
    id     INTEGER PRIMARY KEY,
    number TEXT NOT NULL UNIQUE,
    label  TEXT NOT NULL,
    holder TEXT
);

CREATE TABLE statement_import (
    id            INTEGER PRIMARY KEY,
    account_id    INTEGER NOT NULL REFERENCES account (id),
    file_name     TEXT    NOT NULL,
    file_sha256   TEXT    NOT NULL,
    imported_at   TEXT    NOT NULL,
    period_start  TEXT,
    period_end    TEXT,
    balance_cents INTEGER,
    balance_date  TEXT,
    rows_read     INTEGER NOT NULL,
    rows_new      INTEGER NOT NULL,
    rows_known    INTEGER NOT NULL,
    rows_updated  INTEGER NOT NULL
);

CREATE TABLE operation (
    id                INTEGER PRIMARY KEY,
    account_id        INTEGER NOT NULL REFERENCES account (id),
    op_date           TEXT    NOT NULL,
    value_date        TEXT    NOT NULL,
    type              TEXT    NOT NULL,
    label             TEXT    NOT NULL,
    amount_cents      INTEGER NOT NULL CHECK (amount_cents <> 0),
    import_id         INTEGER NOT NULL REFERENCES statement_import (id),
    updated_import_id INTEGER REFERENCES statement_import (id)
);

CREATE INDEX idx_operation_account_date ON operation (account_id, op_date);

CREATE TABLE setting (
    name  TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
