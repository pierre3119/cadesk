package io.github.pierre3119.cadesk.statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pierre3119.cadesk.Samples;
import io.github.pierre3119.cadesk.Samples.Row;
import io.github.pierre3119.cadesk.domain.Statement;
import io.github.pierre3119.cadesk.domain.StatementLine;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class CreditAgricoleCsvParserTest {

    private final CreditAgricoleCsvParser parser = new CreditAgricoleCsvParser();

    private Statement parse(String text) {
        return parser.parse(text.getBytes(Samples.WINDOWS_1252));
    }

    @Test
    void readsTheHeader() {
        Statement s = parse(Samples.statement(Samples.BAKERY));

        assertThat(s.accountNumber()).isEqualTo("00012345678");
        assertThat(s.accountLabel()).isEqualTo("Compte de Dépôt carte");
        assertThat(s.holder()).isEqualTo("M. MARTIN JEAN");
        assertThat(s.downloadDate()).isEqualTo(LocalDate.of(2026, 3, 5));
        assertThat(s.periodStart()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(s.periodEnd()).isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(s.balanceCents()).isEqualTo(123_456L);
        assertThat(s.balanceDate()).isEqualTo(LocalDate.of(2026, 3, 5));
    }

    @Test
    void readsOperationsWithMultiLineLabelsAndSignedAmounts() {
        Statement s = parse(Samples.statement(Samples.BAKERY, Samples.SALARY, Samples.ENERGY));

        assertThat(s.lines()).containsExactly(
                new StatementLine(LocalDate.of(2026, 2, 27), LocalDate.of(2026, 2, 27), "Paiement par carte",
                        "X1234 BOULANGERIE DUPAIN 26/02 - Boulangerie Dupain", -450),
                new StatementLine(LocalDate.of(2026, 2, 20), LocalDate.of(2026, 2, 20), "Virement en votre faveur",
                        "ACME SA R000123 - Paie du mois de Fevrier 2026\nR000123", 250_000),
                new StatementLine(LocalDate.of(2026, 2, 5), LocalDate.of(2026, 2, 5), "Prélèvement",
                        "ENERGIE SA - Mandat \"ABC\"\nFR00ZZZ123456", -12_000));
    }

    @Test
    void acceptsUtf8AsWellAsWindows1252() {
        String text = Samples.statement(Samples.ENERGY);

        Statement utf8 = parser.parse(("\uFEFF" + text).getBytes(StandardCharsets.UTF_8));

        assertThat(utf8).isEqualTo(parse(text));
        assertThat(utf8.lines().getFirst().type()).isEqualTo("Prélèvement");
    }

    @Test
    void toleratesWindowsLineEndings() {
        String text = Samples.statement(Samples.BAKERY, Samples.SALARY);

        assertThat(parse(text.replace("\n", "\r\n"))).isEqualTo(parse(text));
    }

    @Test
    void rejectsAFileThatIsNotAStatement() {
        assertThatThrownBy(() -> parse("nom;prenom\nMartin;Jean\n"))
                .isInstanceOf(StatementFormatException.class)
                .hasMessageContaining("n'est pas un relevé CSV du Crédit Agricole");
    }

    @Test
    void reportsTheLineOfAnInvalidOperation() {
        String both = Samples.statement(Samples.BAKERY, new Row("20/02/2026", "Virement\nX", "10,00", "10,00"));
        String badAmount = Samples.statement(Row.debit("20/02/2026", "Virement\nX", "dix euros"));
        String badDate = Samples.statement(Row.debit("31/02/2026", "Virement\nX", "10,00"));

        // l'en-tête occupe 11 lignes ; la boulangerie les lignes 12 à 17 (libellé multi-lignes) : l'erreur est ligne 18
        assertThatThrownBy(() -> parse(both)).isInstanceOf(StatementFormatException.class)
                .hasMessageContaining("Ligne 18").hasMessageContaining("soit un débit, soit un crédit");
        assertThatThrownBy(() -> parse(badAmount)).isInstanceOf(StatementFormatException.class)
                .hasMessageContaining("Ligne 12").hasMessageContaining("montant illisible");
        assertThatThrownBy(() -> parse(badDate)).isInstanceOf(StatementFormatException.class)
                .hasMessageContaining("Ligne 12").hasMessageContaining("date illisible");
    }

    @Test
    void rejectsAnUnterminatedQuote() {
        String text = Samples.statement(Samples.BAKERY) + "20/02/2026;20/02/2026;\"Virement\nsans fin;1,00;;\n";

        assertThatThrownBy(() -> parse(text)).isInstanceOf(StatementFormatException.class)
                .hasMessageContaining("Guillemet non refermé");
    }
}
