package io.github.pierre3119.cadesk.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.github.pierre3119.cadesk.Samples;
import io.github.pierre3119.cadesk.categorize.Classifier;
import io.github.pierre3119.cadesk.domain.Category;
import io.github.pierre3119.cadesk.domain.Category.IncomeKind;
import io.github.pierre3119.cadesk.domain.Category.Role;
import io.github.pierre3119.cadesk.domain.ClassifiedOperation;
import io.github.pierre3119.cadesk.domain.Nature;
import io.github.pierre3119.cadesk.domain.Operation;
import io.github.pierre3119.cadesk.domain.Rule;
import io.github.pierre3119.cadesk.importer.ImportService;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Schéma V2 : contenu de départ, mise à niveau d'une base existante, enregistrement des catégories et des règles. */
class CategoryStoreTest {

    @TempDir
    Path dir;

    private final CategoryRepository categories = new CategoryRepository();
    private final RuleRepository rules = new RuleRepository();
    private final OperationRepository operations = new OperationRepository();

    private Database open() {
        return new Database(dir.resolve("test.db"));
    }

    private static Category named(List<Category> all, String name) {
        return all.stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------ contenu de départ

    @Test
    void aNewBaseStartsWithCategoriesAndNoRule() {
        try (Database db = open()) {
            List<Category> all = db.inTransaction(categories::findAll);

            assertThat(all).hasSizeGreaterThan(30);
            assertThat(all).extracting(Category::position).isSorted();
            assertThat(all).filteredOn(c -> c.role() == Role.DEFAULT_OUT).singleElement()
                    .satisfies(c -> assertThat(c.inflow()).isFalse());
            assertThat(all).filteredOn(c -> c.role() == Role.DEFAULT_IN).singleElement()
                    .satisfies(c -> assertThat(c.inflow()).isTrue());
            assertThat(all).extracting(Category::nature).as("chaque nature a ses catégories").contains(Nature.values());
            assertThat(db.inTransaction(rules::findAll)).as("les règles sont celles de l'utilisateur").isEmpty();
        }
    }

    @Test
    void operationsAreToClassifyUntilRulesRecogniseThem() throws IOException {
        try (Database db = open()) {
            Path csv = Samples.write(dir, "releve.csv", Samples.demoStatement());
            long account = new ImportService(db).importFile(csv).account().id();
            List<Operation> ops = db.inTransaction(c -> operations.findByAccount(c, account));
            List<Category> all = db.inTransaction(categories::findAll);

            assertThat(new Classifier(all, db.inTransaction(rules::findAll)).classify(ops))
                    .as("sans règle, tout est à classer").isNotEmpty().allMatch(ClassifiedOperation::isUnclassified);

            db.inTransaction(c -> {
                for (Rule rule : Samples.demoRules(all)) {
                    rules.insertFirst(c, rule);
                }
                return null;
            });
            List<ClassifiedOperation> result = new Classifier(all, db.inTransaction(rules::findAll)).classify(ops);

            assertThat(result).filteredOn(o -> o.operation().label().contains("ACME SA"))
                    .extracting(o -> o.category().name(), ClassifiedOperation::poste).containsOnly(tuple("Salaire", "Salaire"));
            assertThat(result).filteredOn(ClassifiedOperation::isUnclassified)
                    .extracting(o -> o.category().name(), ClassifiedOperation::poste)
                    .containsOnly(tuple("Divers / à classer", "Garage Des Alpes"), tuple("Entrées à classer", "Mairie De Lyon"));
        }
    }

    // ------------------------------------------------------------------ mise à niveau

    @Test
    void anExistingBaseIsBackedUpThenUpgradedWithoutLosingData() throws Exception {
        Path file = dir.resolve("test.db");
        createVersion1(file);
        byte[] before = Files.readAllBytes(file);

        try (Database db = new Database(file)) {
            Path backup = dir.resolve("test.db.sauvegarde-v1");
            assertThat(backup).exists();
            assertThat(Files.readAllBytes(backup)).as("copie exacte de la base d'avant").isEqualTo(before);

            List<Operation> ops = db.inTransaction(c -> operations.findByAccount(c, 1));
            assertThat(ops).singleElement().satisfies(o -> {
                assertThat(o.label()).isEqualTo("X1234 BOULANGERIE DUPAIN 26/02");
                assertThat(o.amountCents()).isEqualTo(-450);
                assertThat(o.manualCategoryId()).isNull();
            });
            assertThat(db.inTransaction(categories::findAll)).isNotEmpty();
        }
    }

    @Test
    void noBackupIsMadeForANewBaseNorForAnUpToDateOne() throws IOException {
        open().close();
        open().close();

        try (var files = Files.list(dir)) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactly("test.db");
        }
    }

    private static void createVersion1(Path file) throws SQLException, IOException {
        String script;
        try (InputStream in = Database.class.getResourceAsStream("/db/migration/V1__schema.sql")) {
            script = new String(in.readAllBytes(), StandardCharsets.UTF_8).replaceAll("(?m)^\\s*--.*$", "");
        }
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file); Statement st = c.createStatement()) {
            for (String sql : script.split(";")) {
                if (!sql.isBlank()) {
                    st.execute(sql);
                }
            }
            st.execute("INSERT INTO account (id, number, label, holder) VALUES (1, '00012345678', 'Compte', 'M. MARTIN JEAN')");
            st.execute("INSERT INTO statement_import (id, account_id, file_name, file_sha256, imported_at, rows_read, rows_new, "
                    + "rows_known, rows_updated) VALUES (1, 1, 'a.csv', 'abc', '2026-03-05T10:00:00', 1, 1, 0, 0)");
            st.execute("INSERT INTO operation (account_id, op_date, value_date, type, label, amount_cents, import_id) "
                    + "VALUES (1, '2026-02-27', '2026-02-27', 'Paiement par carte', 'X1234 BOULANGERIE DUPAIN 26/02', -450, 1)");
            st.execute("PRAGMA user_version = 1");
        }
    }

    // ------------------------------------------------------------------ catégories

    @Test
    void aCategoryCanBeAddedChangedAndMoved() {
        try (Database db = open()) {
            Category created = db.inTransaction(c -> categories.insert(c,
                    Category.create("  Animaux  ", Nature.VARIABLES, false, IncomeKind.PONCTUEL)));
            List<Category> all = db.inTransaction(categories::findAll);

            assertThat(created.id()).isPositive();
            assertThat(created.name()).isEqualTo("Animaux");
            assertThat(all.getLast()).as("ajoutée à la fin du bilan").isEqualTo(created);

            Category previous = all.get(all.size() - 2);
            db.inTransaction(c -> {
                categories.update(c, new Category(created.id(), "Animaux de compagnie", Nature.FIXES, false, 0, Role.STANDARD,
                        IncomeKind.PONCTUEL, 15));
                categories.swapPositions(c, created.id(), previous.id());
                categories.setSavingPercent(c, previous.id(), 30);
                return null;
            });
            all = db.inTransaction(categories::findAll);

            Category changed = named(all, "Animaux de compagnie");
            assertThat(changed.nature()).isEqualTo(Nature.FIXES);
            assertThat(changed.savingPercent()).isEqualTo(15);
            assertThat(changed.position()).as("le rang ne change que par échange").isEqualTo(previous.position());
            assertThat(all.getLast().id()).isEqualTo(previous.id());
            assertThat(all.getLast().savingPercent()).isEqualTo(30);
        }
    }

    @Test
    void twoCategoriesCannotShareAName() {
        try (Database db = open()) {
            assertThatThrownBy(() -> db.inTransaction(c -> categories.insert(c,
                    Category.create("salaire", Nature.REVENUS, true, IncomeKind.REGULIER)))).isInstanceOf(DatabaseException.class);
        }
    }

    @Test
    void deletingACategoryRemovesItsRulesAndFreesItsOperations() throws IOException {
        try (Database db = open()) {
            long account = new ImportService(db).importFile(Samples.write(dir, "a.csv", Samples.statement(Samples.BAKERY)))
                    .account().id();
            long operationId = db.inTransaction(c -> operations.findByAccount(c, account)).getFirst().id();
            Category pets = db.inTransaction(c -> categories.insert(c,
                    Category.create("Animaux", Nature.VARIABLES, false, IncomeKind.PONCTUEL)));
            db.inTransaction(c -> {
                rules.insertFirst(c, Rule.create(pets.id(), "Vétérinaire", null, List.of("VETERINAIRE")));
                operations.setCategory(c, List.of(operationId), pets.id());
                return null;
            });
            assertThat(stored(db, account).getFirst().manualCategoryId()).isEqualTo(pets.id());

            db.inTransaction(c -> {
                categories.delete(c, pets.id());
                return null;
            });

            assertThat(db.inTransaction(rules::findAll)).noneMatch(r -> r.categoryId() == pets.id());
            assertThat(stored(db, account).getFirst().manualCategoryId()).as("retour au classement automatique").isNull();
        }
    }

    @Test
    void theDefaultCategoriesCannotBeDeletedNorReversed() {
        try (Database db = open()) {
            Category fallback = db.inTransaction(categories::findAll).stream().filter(c -> c.role() == Role.DEFAULT_OUT)
                    .findFirst().orElseThrow();

            assertThatThrownBy(() -> db.inTransaction(c -> {
                categories.delete(c, fallback.id());
                return null;
            })).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> db.inTransaction(c -> {
                categories.update(c, new Category(fallback.id(), "Divers", Nature.INTERNE, true, 0, Role.DEFAULT_OUT,
                        IncomeKind.PONCTUEL, 0));
                return null;
            })).isInstanceOf(IllegalArgumentException.class);
            assertThat(db.inTransaction(categories::findAll)).contains(fallback);
        }
    }

    // ------------------------------------------------------------------ règles et choix manuel

    @Test
    void aNewRuleComesFirstAndKeepsItsKeywordsExactly() {
        try (Database db = open()) {
            Category health = named(db.inTransaction(categories::findAll), "Santé");
            db.inTransaction(c -> {
                rules.insertFirst(c, Rule.create(health.id(), "Opticien", Classifier.CARD_PAYMENT, List.of("OPTIQUE")));
                rules.insertFirst(c, Rule.create(health.id(), "Pharmacies", Classifier.CARD_PAYMENT, List.of("PHARM")));
                return null;
            });
            Rule created = db.inTransaction(c -> rules.insertFirst(c,
                    Rule.create(health.id(), "Vétérinaire", Classifier.CARD_PAYMENT, List.of("DR ", "CLINIQUE VETERINAIRE"))));

            List<Rule> all = db.inTransaction(rules::findAll);
            assertThat(all.getFirst()).isEqualTo(created);
            assertThat(all.getFirst().keywords()).as("l'espace finale est conservée").containsExactly("DR ", "CLINIQUE VETERINAIRE");

            Rule second = all.get(1);
            db.inTransaction(c -> {
                rules.update(c, new Rule(created.id(), health.id(), null, null, List.of("VETO"), 0));
                rules.swapPositions(c, created.id(), second.id());
                return null;
            });
            all = db.inTransaction(rules::findAll);
            assertThat(all.get(0).id()).isEqualTo(second.id());
            assertThat(all.get(1)).satisfies(r -> {
                assertThat(r.id()).isEqualTo(created.id());
                assertThat(r.poste()).isNull();
                assertThat(r.operationType()).isNull();
                assertThat(r.keywords()).containsExactly("VETO");
            });

            Rule last = all.getLast();
            db.inTransaction(c -> {
                rules.moveFirst(c, last.id());
                rules.delete(c, created.id());
                return null;
            });
            all = db.inTransaction(rules::findAll);
            assertThat(all.getFirst().id()).isEqualTo(last.id());
            assertThat(all).noneMatch(r -> r.id() == created.id());
        }
    }

    @Test
    void aManualCategoryIsStoredAndCanBeCleared() throws IOException {
        try (Database db = open()) {
            long account = new ImportService(db).importFile(Samples.write(dir, "a.csv", Samples.statement(Samples.BAKERY, Samples.GIFT)))
                    .account().id();
            List<Long> ids = db.inTransaction(c -> operations.findByAccount(c, account)).stream().map(Operation::id).toList();
            long food = named(db.inTransaction(categories::findAll), "Alimentation et courses").id();

            db.inTransaction(c -> {
                operations.setCategory(c, ids, food);
                return null;
            });
            assertThat(stored(db, account)).extracting(Operation::manualCategoryId).containsOnly(food);

            db.inTransaction(c -> {
                operations.setCategory(c, List.of(ids.getFirst()), null);
                return null;
            });
            assertThat(stored(db, account)).extracting(Operation::manualCategoryId).containsExactly(null, food);
        }
    }

    private List<Operation> stored(Database db, long account) {
        return db.inTransaction(c -> operations.findByAccount(c, account));
    }
}
