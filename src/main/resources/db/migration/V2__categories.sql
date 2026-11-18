-- Catégories, règles de classement, et choix manuel d'une catégorie pour une opération.
--
-- Une opération est classée ainsi : la catégorie choisie à la main (operation.category_id) si elle existe ;
-- sinon la première règle, dans l'ordre de « position », dont le type d'opération et les mots-clés
-- correspondent ; sinon la catégorie par défaut de son sens (role DEFAULT_OUT ou DEFAULT_IN).
-- Rien de ce classement automatique n'est enregistré : il est recalculé à l'affichage.

CREATE TABLE category (
    id          INTEGER PRIMARY KEY,
    name        TEXT    NOT NULL UNIQUE COLLATE NOCASE,
    nature      TEXT    NOT NULL CHECK (nature IN ('REVENUS', 'FIXES', 'VARIABLES', 'EXCEPTIONNELLES', 'INTERNE')),
    inflow      INTEGER NOT NULL CHECK (inflow IN (0, 1)),
    position    INTEGER NOT NULL,
    role        TEXT    NOT NULL DEFAULT 'STANDARD' CHECK (role IN ('STANDARD', 'DEFAULT_OUT', 'DEFAULT_IN')),
    income_kind TEXT    NOT NULL DEFAULT 'PONCTUEL' CHECK (income_kind IN ('REGULIER', 'REMBOURSEMENT', 'PONCTUEL')),
    saving_pct  INTEGER NOT NULL DEFAULT 0 CHECK (saving_pct BETWEEN 0 AND 100),
    CHECK (nature = 'INTERNE' OR inflow = (nature = 'REVENUS'))
);

CREATE UNIQUE INDEX idx_category_role ON category (role) WHERE role <> 'STANDARD';

-- keywords : un mot-clé par ligne, en majuscules sans accents ; un seul suffit ; « A + B » exige A et B ;
-- vide = toutes les opérations du type. poste NULL = nom du commerçant ou du tiers, tiré du libellé.
CREATE TABLE category_rule (
    id          INTEGER PRIMARY KEY,
    category_id INTEGER NOT NULL REFERENCES category (id) ON DELETE CASCADE,
    poste       TEXT,
    op_type     TEXT,
    keywords    TEXT    NOT NULL DEFAULT '',
    position    INTEGER NOT NULL
);

CREATE INDEX idx_category_rule_position ON category_rule (position);

ALTER TABLE operation ADD COLUMN category_id INTEGER REFERENCES category (id) ON DELETE SET NULL;
