package io.github.pierre3119.cadesk.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.pierre3119.cadesk.Samples;
import io.github.pierre3119.cadesk.Samples.Row;
import io.github.pierre3119.cadesk.Samples.Summary;
import io.github.pierre3119.cadesk.db.Database;
import io.github.pierre3119.cadesk.db.ImportRepository;
import io.github.pierre3119.cadesk.db.OperationRepository;
import io.github.pierre3119.cadesk.domain.ImportRecord;
import io.github.pierre3119.cadesk.domain.Money;
import io.github.pierre3119.cadesk.domain.Operation;
import io.github.pierre3119.cadesk.importer.ImportService.Outcome;
import io.github.pierre3119.cadesk.statement.StatementFormatException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Imports successifs sur une vraie base SQLite (fichier temporaire). */
class ImportServiceTest {

    @TempDir
    Path dir;
    private Database database;
    private ImportService service;

    @BeforeEach
    void openDatabase() {
        database = new Database(dir.resolve("data").resolve("test.db"));
        service = new ImportService(database);
    }

    @AfterEach
    void closeDatabase() {
        database.close();
    }

    private Outcome importing(String fileName, String content) throws IOException {
        return service.importFile(Samples.write(dir, fileName, content));
    }

    private List<Operation> operations(long accountId) {
        return database.inTransaction(c -> new OperationRepository().findByAccount(c, accountId));
    }

    @Test
    void firstImportStoresEveryOperationAndTheStatementHeader() throws IOException {
        Outcome outcome = importing("janvier.csv", Samples.statement(Samples.BAKERY, Samples.SALARY, Samples.ENERGY));

        ImportRecord r = outcome.record();
        assertThat(List.of(r.rowsRead(), r.rowsNew(), r.rowsKnown(), r.rowsUpdated())).containsExactly(3, 3, 0, 0);
        assertThat(r.fileName()).isEqualTo("janvier.csv");
        assertThat(r.balanceCents()).isEqualTo(123_456L);
        assertThat(outcome.account().number()).isEqualTo("00012345678");
        assertThat(outcome.account().holder()).isEqualTo("M. MARTIN JEAN");
        assertThat(outcome.previousImportOfSameFile()).isEmpty();
        assertThat(operations(outcome.account().id())).extracting(Operation::amountCents)
                .as("de la plus récente à la plus ancienne").containsExactly(-450L, 250_000L, -12_000L);
    }

    @Test
    void importingTheSameFileAgainAddsNothingAndSaysSo() throws IOException {
        String content = Samples.statement(Samples.BAKERY, Samples.SALARY, Samples.GIFT, Samples.GIFT);
        Outcome first = importing("a.csv", content);

        Outcome second = importing("a-copie.csv", content);

        assertThat(first.record().rowsNew()).isEqualTo(4);
        assertThat(List.of(second.record().rowsNew(), second.record().rowsKnown(), second.record().rowsUpdated()))
                .containsExactly(0, 4, 0);
        assertThat(second.previousImportOfSameFile()).contains(first.record().importedAt());
        assertThat(operations(first.account().id())).hasSize(4);
    }

    @Test
    void aLaterStatementOverlappingTheFirstAddsOnlyNewOperations() throws IOException {
        Outcome first = importing("fevrier.csv", Samples.statement(Samples.SALARY, Samples.ENERGY, Samples.GIFT));
        Row march = Row.debit("03/03/2026", "Paiement par carte\nX1234 LIBRAIRIE 02/03", "19,90");

        Outcome second = importing("mars.csv", Samples.statement(march, Samples.BAKERY, Samples.SALARY, Samples.ENERGY));

        assertThat(List.of(second.record().rowsRead(), second.record().rowsNew(), second.record().rowsKnown()))
                .containsExactly(4, 2, 2);
        List<Operation> all = operations(first.account().id());
        assertThat(all).hasSize(5);
        assertThat(all.stream().mapToLong(Operation::amountCents).sum())
                .isEqualTo(Money.parseCents("2500,00") + Money.parseCents("500,00") - 12_000 - 450 - 1_990);
        List<ImportRecord> history = database.inTransaction(c -> new ImportRepository().findByAccount(c, first.account().id()));
        assertThat(history).extracting(ImportRecord::fileName).containsExactly("mars.csv", "fevrier.csv");
    }

    @Test
    void aLabelRewordedByTheBankIsUpdatedNotDuplicated() throws IOException {
        Row plain = Row.debit("27/02/2026", "Paiement par carte\nX1234 BOULANGERIE DUPAIN 26/02", "4,50");
        Outcome first = importing("avant.csv", Samples.statement(plain, Samples.SALARY));

        Outcome second = importing("apres.csv", Samples.statement(Samples.BAKERY, Samples.SALARY));

        assertThat(List.of(second.record().rowsNew(), second.record().rowsKnown(), second.record().rowsUpdated()))
                .containsExactly(0, 1, 1);
        assertThat(operations(first.account().id())).hasSize(2).extracting(Operation::label)
                .contains("X1234 BOULANGERIE DUPAIN 26/02 - Boulangerie Dupain");
    }

    // ------------------------------------------------------------------ relevés OFX

    /** Des frais dont la date de valeur précède la date d'opération : un relevé OFX ne dit que la seconde. */
    private static final Row FEE = Row.debit("02/02/2026", "Cotisation\nOffre Compte", "5,00").valued("01/02/2026");
    private static final Summary FEE_OFX = new Summary(FEE, "Offre Compte", "Cotisation Offre Compte");

    @Test
    void anOfxStatementStoresEveryOperationWithItsDateOfOperationForValueDate() throws IOException {
        Outcome outcome = importing("CA20260305_101500.ofx",
                Samples.ofxStatement(Samples.BAKERY_OFX, Samples.SALARY_OFX, Samples.ENERGY_OFX));

        ImportRecord r = outcome.record();
        assertThat(List.of(r.rowsRead(), r.rowsNew(), r.rowsKnown(), r.rowsUpdated())).containsExactly(3, 3, 0, 0);
        assertThat(r.balanceCents()).isEqualTo(123_456L);
        assertThat(outcome.account().number()).isEqualTo("00012345678");
        assertThat(outcome.account().label()).isEqualTo("Compte de dépôt");
        assertThat(outcome.account().holder()).isNull();
        assertThat(operations(outcome.account().id()))
                .extracting(Operation::type, Operation::label, Operation::amountCents, Operation::date, Operation::valueDate)
                .containsExactly(
                        tuple("Paiement par carte", "X1234 BOULANGERIE DUPAIN 26/02", -450L, LocalDate.of(2026, 2, 27), LocalDate.of(2026, 2, 27)),
                        tuple("Virement en votre faveur", "ACME SA R000123 - Paie du mois de Fevrier 2026", 250_000L,
                                LocalDate.of(2026, 2, 20), LocalDate.of(2026, 2, 20)),
                        tuple("Prélèvement", "ENERGIE SA - Mandat \"ABC\"", -12_000L, LocalDate.of(2026, 2, 5), LocalDate.of(2026, 2, 5)));
    }

    @Test
    void importingTheSameOfxStatementAgainAddsNothing() throws IOException {
        String content = Samples.ofxStatement(Samples.BAKERY_OFX, Samples.SALARY_OFX, Samples.GIFT_OFX, Samples.GIFT_OFX);
        Outcome first = importing("a.ofx", content);

        Outcome second = importing("a-copie.ofx", content);

        assertThat(first.record().rowsNew()).isEqualTo(4);
        assertThat(List.of(second.record().rowsNew(), second.record().rowsKnown(), second.record().rowsUpdated()))
                .containsExactly(0, 4, 0);
        assertThat(second.previousImportOfSameFile()).contains(first.record().importedAt());
        assertThat(operations(first.account().id())).hasSize(4);
    }

    @Test
    void anOfxStatementOverACsvOneAddsOnlyWhatIsNewAndLeavesTheRestUntouched() throws IOException {
        Outcome csv = importing("fevrier.csv", Samples.statement(Samples.BAKERY, Samples.SALARY, Samples.ENERGY, FEE, Samples.GIFT));
        List<Operation> before = operations(csv.account().id());
        Summary march = new Summary(Row.debit("03/03/2026", "", "19,90"),
                "X1234 LIBRAIRIE DU PORT    02/03", "Paiement par carte X1234 LIBRAIRIE DU PORT    02/03");

        Outcome ofx = importing("mars.ofx", Samples.ofxStatement(march, Samples.BAKERY_OFX, Samples.SALARY_OFX, Samples.ENERGY_OFX,
                FEE_OFX, Samples.GIFT_OFX));

        ImportRecord r = ofx.record();
        assertThat(List.of(r.rowsRead(), r.rowsNew(), r.rowsKnown(), r.rowsUpdated())).containsExactly(6, 1, 5, 0);
        assertThat(ofx.account()).as("le même compte, avec son titulaire").isEqualTo(csv.account());
        List<Operation> after = operations(csv.account().id());
        assertThat(after).hasSize(6);
        assertThat(after.getFirst().label()).isEqualTo("X1234 LIBRAIRIE DU PORT 02/03");
        assertThat(after.subList(1, 6)).as("libellés complets et dates de valeur du relevé CSV").isEqualTo(before);
    }

    @Test
    void aCsvStatementCompletesTheOperationsAnOfxOneBrought() throws IOException {
        Outcome ofx = importing("fevrier.ofx", Samples.ofxStatement(Samples.BAKERY_OFX, Samples.SALARY_OFX, FEE_OFX));

        Outcome csv = importing("fevrier.csv", Samples.statement(Samples.BAKERY, Samples.SALARY, FEE));

        ImportRecord r = csv.record();
        assertThat(List.of(r.rowsRead(), r.rowsNew(), r.rowsKnown(), r.rowsUpdated())).containsExactly(3, 0, 0, 3);
        assertThat(csv.account().id()).isEqualTo(ofx.account().id());
        assertThat(csv.account().holder()).as("le relevé CSV nomme le titulaire").isEqualTo("M. MARTIN JEAN");
        assertThat(csv.account().label()).isEqualTo("Compte de Dépôt carte");
        assertThat(operations(csv.account().id())).extracting(Operation::label, Operation::valueDate).containsExactly(
                tuple("X1234 BOULANGERIE DUPAIN 26/02 - Boulangerie Dupain", LocalDate.of(2026, 2, 27)),
                tuple("ACME SA R000123 - Paie du mois de Fevrier 2026\nR000123", LocalDate.of(2026, 2, 20)),
                tuple("Offre Compte", LocalDate.of(2026, 2, 1)));
    }

    @Test
    void statementsOfDifferentAccountsDoNotMix() throws IOException {
        Outcome a = importing("a.csv", Samples.statement("00012345678", List.of(Samples.SALARY)));

        Outcome b = importing("b.csv", Samples.statement("99999999999", List.of(Samples.SALARY)));

        assertThat(b.record().rowsNew()).as("même opération, autre compte : nouvelle").isEqualTo(1);
        assertThat(b.account().id()).isNotEqualTo(a.account().id());
        assertThat(operations(a.account().id())).hasSize(1);
    }

    @Test
    void anInvalidFileLeavesTheBaseUntouched() throws IOException {
        Outcome ok = importing("ok.csv", Samples.statement(Samples.SALARY));
        String broken = Samples.statement(Samples.BAKERY, new Row("20/02/2026", "Virement\nX", "", ""));

        assertThatThrownBy(() -> importing("casse.csv", broken)).isInstanceOf(StatementFormatException.class);
        assertThatThrownBy(() -> importing("vide.csv", Samples.statement())).isInstanceOf(StatementFormatException.class)
                .hasMessageContaining("aucune opération");

        assertThat(operations(ok.account().id())).hasSize(1);
    }

    @Test
    void theBaseSurvivesBeingClosedAndReopened() throws IOException {
        Outcome first = importing("a.csv", Samples.statement(Samples.SALARY, Samples.ENERGY));
        database.close();
        database = new Database(dir.resolve("data").resolve("test.db"));
        service = new ImportService(database);

        Outcome again = importing("a.csv", Samples.statement(Samples.SALARY, Samples.ENERGY));

        assertThat(again.record().rowsNew()).isZero();
        assertThat(operations(first.account().id())).hasSize(2);
    }

    /**
     * Contrôle facultatif sur un vrai relevé : {@code -Dcadesk.sample.csv=/chemin/vers/releve.csv}.
     * Ignoré si la propriété est absente ; aucune donnée réelle n'est conservée dans le projet.
     */
    @Test
    void aRealStatementImportsCompletelyAndIsIdempotent() {
        String sample = System.getProperty("cadesk.sample.csv", "");
        assumeTrue(!sample.isBlank() && Files.isRegularFile(Path.of(sample)), "pas de relevé réel fourni");

        Outcome first = service.importFile(Path.of(sample));
        Outcome second = service.importFile(Path.of(sample));

        List<Operation> all = operations(first.account().id());
        long credits = all.stream().mapToLong(Operation::amountCents).filter(a -> a > 0).sum();
        long debits = -all.stream().mapToLong(Operation::amountCents).filter(a -> a < 0).sum();
        System.out.printf("RELEVE REEL : %d lignes lues, %d nouvelles ; crédits %s ; débits %s ; 2e import : %d nouvelles, "
                        + "%d connues, %d mises à jour%n", first.record().rowsRead(), first.record().rowsNew(),
                Money.format(credits), Money.format(debits), second.record().rowsNew(), second.record().rowsKnown(),
                second.record().rowsUpdated());
        assertThat(first.record().rowsNew()).isEqualTo(first.record().rowsRead()).isEqualTo(all.size());
        assertThat(List.of(second.record().rowsNew(), second.record().rowsUpdated())).containsExactly(0, 0);
        assertThat(second.record().rowsKnown()).isEqualTo(first.record().rowsRead());
    }
}
