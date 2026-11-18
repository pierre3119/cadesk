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

-- ---------------------------------------------------------------------------------------------------
-- Contenu de départ, modifiable ensuite dans l'écran « Catégories » : des catégories générales, et aucune
-- règle. Les règles dépendent des commerçants et des tiers de chacun : elles se créent dans l'application,
-- à la main ou avec l'assistant.
-- ---------------------------------------------------------------------------------------------------

INSERT INTO category (id, name, nature, inflow, position, role, income_kind, saving_pct) VALUES
    (1, 'Salaire', 'REVENUS', 1, 1, 'STANDARD', 'REGULIER', 0),
    (2, 'Participation du conjoint aux factures', 'REVENUS', 1, 2, 'STANDARD', 'REGULIER', 0),
    (3, 'Remboursement de frais professionnels', 'REVENUS', 1, 3, 'STANDARD', 'REMBOURSEMENT', 0),
    (4, 'Remboursements d''impôts', 'REVENUS', 1, 4, 'STANDARD', 'PONCTUEL', 0),
    (5, 'Remboursements santé', 'REVENUS', 1, 5, 'STANDARD', 'REMBOURSEMENT', 0),
    (6, 'Cadeaux et aides de la famille', 'REVENUS', 1, 6, 'STANDARD', 'PONCTUEL', 0),
    (7, 'Remboursements d''amis', 'REVENUS', 1, 7, 'STANDARD', 'REMBOURSEMENT', 0),
    (8, 'Autres remboursements et avoirs', 'REVENUS', 1, 8, 'STANDARD', 'REMBOURSEMENT', 0),
    (9, 'Revenus financiers', 'REVENUS', 1, 9, 'STANDARD', 'PONCTUEL', 0),
    (10, 'Entrées exceptionnelles', 'REVENUS', 1, 10, 'STANDARD', 'PONCTUEL', 0),
    (11, 'Entrées à classer', 'REVENUS', 1, 11, 'DEFAULT_IN', 'PONCTUEL', 0),
    (12, 'Virements reçus de mes autres comptes', 'INTERNE', 1, 12, 'STANDARD', 'PONCTUEL', 0),
    (13, 'Prêt immobilier', 'FIXES', 0, 13, 'STANDARD', 'PONCTUEL', 0),
    (14, 'Impôts et taxes', 'FIXES', 0, 14, 'STANDARD', 'PONCTUEL', 0),
    (15, 'Énergie', 'FIXES', 0, 15, 'STANDARD', 'PONCTUEL', 0),
    (16, 'Eau', 'FIXES', 0, 16, 'STANDARD', 'PONCTUEL', 0),
    (17, 'Assurances', 'FIXES', 0, 17, 'STANDARD', 'PONCTUEL', 0),
    (18, 'Internet et mobile', 'FIXES', 0, 18, 'STANDARD', 'PONCTUEL', 0),
    (19, 'Abonnements et services', 'FIXES', 0, 19, 'STANDARD', 'PONCTUEL', 20),
    (20, 'Frais bancaires', 'FIXES', 0, 20, 'STANDARD', 'PONCTUEL', 0),
    (21, 'Alimentation et courses', 'VARIABLES', 0, 21, 'STANDARD', 'PONCTUEL', 10),
    (22, 'Restaurants, bars et livraison', 'VARIABLES', 0, 22, 'STANDARD', 'PONCTUEL', 20),
    (23, 'Maison, bricolage et jardin', 'VARIABLES', 0, 23, 'STANDARD', 'PONCTUEL', 10),
    (24, 'Achats et shopping', 'VARIABLES', 0, 24, 'STANDARD', 'PONCTUEL', 20),
    (25, 'Transports et voiture', 'VARIABLES', 0, 25, 'STANDARD', 'PONCTUEL', 0),
    (26, 'Vacances et week-ends', 'VARIABLES', 0, 26, 'STANDARD', 'PONCTUEL', 10),
    (27, 'Loisirs, culture et sport', 'VARIABLES', 0, 27, 'STANDARD', 'PONCTUEL', 20),
    (28, 'Santé', 'VARIABLES', 0, 28, 'STANDARD', 'PONCTUEL', 0),
    (29, 'Coiffeur', 'VARIABLES', 0, 29, 'STANDARD', 'PONCTUEL', 0),
    (30, 'Virements à des proches et cadeaux', 'VARIABLES', 0, 30, 'STANDARD', 'PONCTUEL', 0),
    (31, 'Virements vers le compte joint', 'VARIABLES', 0, 31, 'STANDARD', 'PONCTUEL', 0),
    (32, 'Retraits d''espèces', 'VARIABLES', 0, 32, 'STANDARD', 'PONCTUEL', 0),
    (33, 'Divers / à classer', 'VARIABLES', 0, 33, 'DEFAULT_OUT', 'PONCTUEL', 10),
    (34, 'Travaux et gros équipements', 'EXCEPTIONNELLES', 0, 34, 'STANDARD', 'PONCTUEL', 0),
    (35, 'Épargne et placements', 'INTERNE', 0, 35, 'STANDARD', 'PONCTUEL', 0),
    (36, 'Virements vers mes autres comptes', 'INTERNE', 0, 36, 'STANDARD', 'PONCTUEL', 0);
