package io.github.pierre3119.cadesk.db;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pierre3119.cadesk.Samples;
import io.github.pierre3119.cadesk.Samples.Row;
import io.github.pierre3119.cadesk.domain.Category;
import io.github.pierre3119.cadesk.domain.Category.IncomeKind;
import io.github.pierre3119.cadesk.domain.ImportRecord;
import io.github.pierre3119.cadesk.domain.Nature;
import io.github.pierre3119.cadesk.domain.Operation;
import io.github.pierre3119.cadesk.importer.ImportService;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Base conservée hors d'un fichier SQLite (profil chiffré), vidage de la base, et schéma V3. */
class DatabaseStorageTest {

    /** Stockage en mémoire qui garde la trace de chaque enregistrement. */
    private static final class MemoryStorage implements Database.Storage {

        private byte[] image = new byte[0];
        private int stores;

        @Override
        public byte[] load() {
            return image;
        }

        @Override
        public void store(byte[] newImage) {
            image = newImage;
            stores++;
        }
    }

    @TempDir
    Path dir;

    private final CategoryRepository categories = new CategoryRepository();
    private final RuleRepository rules = new RuleRepository();
    private final OperationRepository operations = new OperationRepository();
    private final SettingsRepository settings = new SettingsRepository();

    private long importing(Database db, Row... rows) throws IOException {
        return new ImportService(db).importFile(Samples.write(dir, "releve.csv", Samples.statement(rows))).account().id();
    }

    private List<Operation> stored(Database db, long account) {
        return db.inTransaction(c -> operations.findByAccount(c, account));
    }

    private Optional<String> setting(Database db, String name) {
        return db.inTransaction(c -> settings.get(c, name));
    }

    // ------------------------------------------------------------------ base en mémoire

    @Test
    void aStoredBaseIsSavedAfterEachChangeAndOnlyThen() throws IOException {
        MemoryStorage storage = new MemoryStorage();
        Path file = dir.resolve("profil.db");
        long account;
        try (Database db = new Database(file, storage)) {
            assertThat(storage.stores).as("base neuve : enregistrée une fois son schéma créé").isEqualTo(1);
            assertThat(new String(storage.image, 0, 15, StandardCharsets.US_ASCII)).isEqualTo("SQLite format 3");

            account = importing(db, Samples.BAKERY, Samples.SALARY);
            assertThat(storage.stores).isEqualTo(2);

            db.inTransaction(categories::findAll);
            assertThat(storage.stores).as("une lecture n'enregistre rien").isEqualTo(2);
        }
        assertThat(file).as("rien n'est écrit dans un fichier SQLite").doesNotExist();

        try (Database reopened = new Database(file, storage)) {
            assertThat(stored(reopened, account)).hasSize(2);
            assertThat(storage.stores).as("base à jour : rien à enregistrer à l'ouverture").isEqualTo(2);
        }
    }

    @Test
    void aStoredBaseGrowsWellBeyondItsFirstImage() throws IOException {
        MemoryStorage storage = new MemoryStorage();
        List<Row> rows = new ArrayList<>();
        for (int day = 1; day <= 28; day++) {
            for (int n = 0; n < 40; n++) {
                rows.add(Row.debit("%02d/02/2026".formatted(day), "Paiement par carte\nX1234 COMMERCE NUMERO " + n + " DE LA RUE", "1,%02d".formatted(n)));
            }
        }
        try (Database db = new Database(dir.resolve("profil.db"), storage)) {
            int before = storage.image.length;
            long account = importing(db, rows.toArray(Row[]::new));

            assertThat(stored(db, account)).hasSize(rows.size());
            assertThat(storage.image.length).isGreaterThan(before + 50_000);
        }
    }

    @Test
    void aFailedTransactionSavesNothing() {
        MemoryStorage storage = new MemoryStorage();
        try (Database db = new Database(dir.resolve("profil.db"), storage)) {
            int before = storage.stores;

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> db.inTransaction(c -> {
                categories.insert(c, Category.create("Animaux", Nature.VARIABLES, false, IncomeKind.PONCTUEL));
                throw new IllegalArgumentException("annulée");
            })).isInstanceOf(IllegalArgumentException.class);

            assertThat(storage.stores).isEqualTo(before);
            assertThat(db.inTransaction(categories::findAll)).noneMatch(c -> c.name().equals("Animaux"));
        }
    }

    // ------------------------------------------------------------------ vidage

    @Test
    void clearingOperationsKeepsCategoriesRulesAndSettingsAndLeavesNoTraceInTheFile() throws IOException {
        Path file = dir.resolve("test.db");
        try (Database db = new Database(file)) {
            long account = importing(db, Samples.BAKERY, Samples.SALARY, Samples.ENERGY);
            Category pets = db.inTransaction(c -> categories.insert(c, Category.create("Animaux", Nature.VARIABLES, false, IncomeKind.PONCTUEL)));
            db.inTransaction(c -> {
                settings.put(c, "theme", "dark");
                return null;
            });
            int ruleCount = db.inTransaction(rules::findAll).size();
            assertThat(new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1)).contains("BOULANGERIE DUPAIN");

            db.clearOperations();

            assertThat(stored(db, account)).isEmpty();
            assertThat(db.inTransaction(new AccountRepository()::findAll)).isEmpty();
            List<ImportRecord> imports = db.inTransaction(c -> new ImportRepository().findByAccount(c, account));
            assertThat(imports).isEmpty();
            assertThat(db.inTransaction(categories::findAll)).contains(pets);
            assertThat(db.inTransaction(rules::findAll)).hasSize(ruleCount);
            assertThat(setting(db, "theme")).contains("dark");
        }
        assertThat(new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1))
                .as("les opérations supprimées ne subsistent pas dans les pages libres du fichier").doesNotContain("BOULANGERIE DUPAIN");
    }

    @Test
    void resettingRestoresTheStartingCategoriesAndKeepsOnlyTheSettings() throws IOException {
        try (Database db = new Database(dir.resolve("test.db"))) {
            List<Category> starting = db.inTransaction(categories::findAll);
            int startingRules = db.inTransaction(rules::findAll).size();
            long account = importing(db, Samples.BAKERY);
            db.inTransaction(c -> {
                categories.insert(c, Category.create("Animaux", Nature.VARIABLES, false, IncomeKind.PONCTUEL));
                categories.delete(c, starting.getFirst().id());
                settings.put(c, "theme", "dark");
                return null;
            });

            db.reset();

            assertThat(db.inTransaction(categories::findAll)).isEqualTo(starting);
            assertThat(db.inTransaction(rules::findAll)).hasSize(startingRules);
            assertThat(stored(db, account)).isEmpty();
            assertThat(setting(db, "theme")).contains("dark");
            // la base reste pleinement utilisable
            assertThat(importing(db, Samples.BAKERY, Samples.SALARY)).isPositive();
        }
    }

    @Test
    void aStoredBaseIsSavedOnceClearedOrReset() throws IOException {
        MemoryStorage storage = new MemoryStorage();
        try (Database db = new Database(dir.resolve("profil.db"), storage)) {
            importing(db, Samples.BAKERY);
            db.clearOperations();
            assertThat(new String(storage.image, StandardCharsets.ISO_8859_1)).doesNotContain("BOULANGERIE DUPAIN");

            importing(db, Samples.BAKERY);
            db.reset();
            assertThat(new String(storage.image, StandardCharsets.ISO_8859_1)).doesNotContain("BOULANGERIE DUPAIN");
        }
        try (Database reopened = new Database(dir.resolve("profil.db"), storage)) {
            assertThat(reopened.inTransaction(categories::findAll)).isNotEmpty();
        }
    }

    // ------------------------------------------------------------------ image et schéma V3

    @Test
    void theImageOfAFileBaseIsACompleteSqliteFile() throws Exception {
        long account;
        byte[] image;
        try (Database db = new Database(dir.resolve("test.db"))) {
            account = importing(db, Samples.BAKERY, Samples.SALARY);
            image = db.image();
        }
        Path copy = Files.write(dir.resolve("copie.db"), image);

        try (Database db = new Database(copy)) {
            assertThat(stored(db, account)).hasSize(2);
        }
    }

    @Test
    void aCategoryIsStoredAsManualOrAssistedAndAVersion2BaseIsUpgraded() throws Exception {
        Path file = dir.resolve("test.db");
        createVersion2(file);

        try (Database db = new Database(file)) {
            assertThat(dir.resolve("test.db.sauvegarde-v2")).exists();
            List<Operation> before = stored(db, 1);
            assertThat(before).singleElement().satisfies(o -> {
                assertThat(o.manualCategoryId()).as("le choix manuel d'avant est conservé").isEqualTo(23L);
                assertThat(o.assisted()).isFalse();
            });
            long id = before.getFirst().id();

            db.inTransaction(c -> {
                operations.setCategory(c, List.of(id), 24L, true);
                return null;
            });
            assertThat(stored(db, 1).getFirst())
                    .satisfies(o -> assertThat(o.manualCategoryId()).isEqualTo(24L), o -> assertThat(o.assisted()).isTrue());

            db.inTransaction(c -> {
                operations.setCategory(c, List.of(id), 23L);
                return null;
            });
            assertThat(stored(db, 1).getFirst().assisted()).as("un choix manuel reprend la main").isFalse();

            db.inTransaction(c -> {
                operations.setCategory(c, List.of(id), null, true);
                return null;
            });
            assertThat(stored(db, 1).getFirst())
                    .satisfies(o -> assertThat(o.manualCategoryId()).isNull(), o -> assertThat(o.assisted()).isFalse());
            List<Long> added = db.inTransaction(c -> operations.findIdsByImport(c, 1));
            assertThat(added).containsExactly(id);
        }
    }

    private static void createVersion2(Path file) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file); Statement st = c.createStatement()) {
            for (String name : List.of("V1__schema.sql", "V2__categories.sql")) {
                String script;
                try (InputStream in = Database.class.getResourceAsStream("/db/migration/" + name)) {
                    script = new String(in.readAllBytes(), StandardCharsets.UTF_8).replaceAll("(?m)^\\s*--.*$", "");
                }
                for (String sql : script.split(";")) {
                    if (!sql.isBlank()) {
                        st.execute(sql);
                    }
                }
            }
            st.execute("INSERT INTO account (id, number, label, holder) VALUES (1, '00012345678', 'Compte', 'M. MARTIN JEAN')");
            st.execute("INSERT INTO statement_import (id, account_id, file_name, file_sha256, imported_at, rows_read, rows_new, "
                    + "rows_known, rows_updated) VALUES (1, 1, 'a.csv', 'abc', '2026-03-05T10:00:00', 1, 1, 0, 0)");
            st.execute("INSERT INTO operation (account_id, op_date, value_date, type, label, amount_cents, import_id, category_id) "
                    + "VALUES (1, '2026-02-27', '2026-02-27', 'Paiement par carte', 'X1234 BOULANGERIE DUPAIN 26/02', -450, 1, 23)");
            st.execute("PRAGMA user_version = 2");
        }
    }
}
