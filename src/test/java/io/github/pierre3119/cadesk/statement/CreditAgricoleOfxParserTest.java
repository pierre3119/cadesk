package io.github.pierre3119.cadesk.statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.github.pierre3119.cadesk.Samples;
import io.github.pierre3119.cadesk.domain.Statement;
import io.github.pierre3119.cadesk.domain.StatementLine;
import io.github.pierre3119.cadesk.statement.CreditAgricoleOfxParser.Text;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Locale;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Le relevé OFX est connu par ce qu'on doit y retrouver : les opérations que la banque affiche, et qu'un
 * relevé CSV de la même période décrit en entier.
 */
class CreditAgricoleOfxParserTest {

    private final CreditAgricoleOfxParser parser = new CreditAgricoleOfxParser();

    private Statement parse(String text) {
        return parser.parse(text.getBytes(Samples.WINDOWS_1252));
    }

    private static String sample() {
        return Samples.ofxStatement(Samples.BAKERY_OFX, Samples.SALARY_OFX, Samples.ENERGY_OFX);
    }

    // ------------------------------------------------------------------ ce que le fichier dit

    @Test
    void readsTheAccountThePeriodAndTheBalance() {
        Statement s = parse(sample());

        assertThat(s.accountNumber()).isEqualTo("00012345678");
        assertThat(s.accountLabel()).isEqualTo("Compte de dépôt");
        assertThat(s.holder()).as("un relevé OFX ne nomme pas le titulaire").isNull();
        assertThat(s.downloadDate()).isEqualTo(LocalDate.of(2026, 3, 5));
        assertThat(s.periodStart()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(s.periodEnd()).isEqualTo(LocalDate.of(2026, 3, 5));
        assertThat(s.balanceCents()).as("solde comptable, pas le solde disponible").isEqualTo(123_456L);
        assertThat(s.balanceDate()).isEqualTo(LocalDate.of(2026, 3, 5));
    }

    @Test
    void readsEveryOperationWithItsDateItsSignedAmountItsTypeAndItsLabel() {
        Statement s = parse(sample());

        assertThat(s.lines()).as("dans l'ordre du fichier, sans date de valeur").containsExactly(
                new StatementLine(LocalDate.of(2026, 2, 27), null, "Paiement par carte", "X1234 BOULANGERIE DUPAIN 26/02", -450),
                new StatementLine(LocalDate.of(2026, 2, 20), null, "Virement en votre faveur",
                        "ACME SA R000123 - Paie du mois de Fevrier 2026", 250_000),
                new StatementLine(LocalDate.of(2026, 2, 5), null, "Prélèvement", "ENERGIE SA - Mandat \"ABC\"", -12_000));
        assertThat(s.lines()).allMatch(StatementLine::summary);
    }

    @Test
    void theLabelIsTheFirstLineOfTheOneACsvStatementGives() {
        Statement csv = new CreditAgricoleCsvParser().parse(
                Samples.statement(Samples.SALARY, Samples.ENERGY).getBytes(Samples.WINDOWS_1252));
        Statement ofx = parse(Samples.ofxStatement(Samples.SALARY_OFX, Samples.ENERGY_OFX));

        assertThat(ofx.lines()).extracting(StatementLine::label)
                .containsExactlyElementsOf(csv.lines().stream().map(l -> l.label().lines().findFirst().orElseThrow()).toList());
        assertThat(ofx.lines()).extracting(StatementLine::type, StatementLine::date, StatementLine::amountCents)
                .containsExactlyElementsOf(csv.lines().stream().map(l -> tuple(l.type(), l.date(), l.amountCents())).toList());
    }

    // ------------------------------------------------------------------ type et libellé

    @Test
    void theTypeIsWhatPrecedesThePartyInTheMemo() {
        assertThat(CreditAgricoleOfxParser.typeAndLabel("ACME SA R000123", "Virement en votre faveur ACME SA R000123 Paie du mois"))
                .isEqualTo(new Text("Virement en votre faveur", "ACME SA R000123 - Paie du mois"));
        assertThat(CreditAgricoleOfxParser.typeAndLabel("VIR INST vers Jean MARTIN", "Virement émis VIR INST vers Jean MARTIN"))
                .as("sans motif").isEqualTo(new Text("Virement émis", "VIR INST vers Jean MARTIN"));
        assertThat(CreditAgricoleOfxParser.typeAndLabel("0001234", "Chèque emis 0001234"))
                .isEqualTo(new Text("Chèque emis", "0001234"));
    }

    @Test
    void aShortNameAfterThePartyIsKeptInTheLabelThoughTheMemoOmitsIt() {
        assertThat(CreditAgricoleOfxParser.typeAndLabel("FOURNISSEUR SA-FIBRE - Fibre",
                "Prélèvement FOURNISSEUR SA-FIBRE Votre abonnement (facture: 123)"))
                .isEqualTo(new Text("Prélèvement", "FOURNISSEUR SA-FIBRE - Fibre - Votre abonnement (facture: 123)"));
        assertThat(CreditAgricoleOfxParser.typeAndLabel("ENERGIE - ENERGIE", "Prélèvement ENERGIE Mandat 00S01"))
                .isEqualTo(new Text("Prélèvement", "ENERGIE - ENERGIE - Mandat 00S01"));
        assertThat(CreditAgricoleOfxParser.typeAndLabel("ALPHA - BETA SARL", "Virement émis ALPHA - BETA SARL Facture 12"))
                .as("un tiret dans le nom du tiers").isEqualTo(new Text("Virement émis", "ALPHA - BETA SARL - Facture 12"));
    }

    @Test
    void aPartyThatFillsTheNameMayHaveBeenCutSoTheMemoIsTakenAsItIs() {
        assertThat(CreditAgricoleOfxParser.typeAndLabel("X1234 PARIS          27/02 10H02", "Retrait au distributeur X1234 PARIS          27/02 10H02"))
                .isEqualTo(new Text("Retrait au distributeur", "X1234 PARIS 27/02 10H02"));
        assertThat(CreditAgricoleOfxParser.typeAndLabel("DIRECTION GENERALE DES FINANCES ",
                "Prélèvement DIRECTION GENERALE DES FINANCES PUBLIQUES PRELEVEMENT A LA SOURCE"))
                .isEqualTo(new Text("Prélèvement", "DIRECTION GENERALE DES FINANCES PUBLIQUES PRELEVEMENT A LA SOURCE"));
    }

    @Test
    void textThatDoesNotHaveTheExpectedShapeBecomesTheLabelOfAnOperationOfUnknownType() {
        String unknown = CreditAgricoleOfxParser.UNKNOWN_TYPE;

        assertThat(CreditAgricoleOfxParser.typeAndLabel("SUPERMARCHE", "")).isEqualTo(new Text(unknown, "SUPERMARCHE"));
        assertThat(CreditAgricoleOfxParser.typeAndLabel("", "Achat du 12/03")).isEqualTo(new Text(unknown, "Achat du 12/03"));
        assertThat(CreditAgricoleOfxParser.typeAndLabel("SUPERMARCHE", "SUPERMARCHE DU CENTRE"))
                .as("le texte commence par le tiers : rien ne le précède").isEqualTo(new Text(unknown, "SUPERMARCHE DU CENTRE"));
        assertThat(CreditAgricoleOfxParser.typeAndLabel("SUPERMARCHE", "Achat du 12/03"))
                .isEqualTo(new Text(unknown, "SUPERMARCHE - Achat du 12/03"));
    }

    // ------------------------------------------------------------------ formes du fichier

    @Test
    void readsValuesClosedByATagAsInOfx2() {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<?OFX OFXHEADER=\"200\" VERSION=\"220\"?>\n"
                + sample().substring(sample().indexOf("<OFX>")).replaceAll("(?m)^<([A-Z]+)>(.+)$", "<$1>$2</$1>");

        assertThat(xml).contains("<ACCTID>00012345678</ACCTID>", "<TRNAMT>-4.50</TRNAMT>");
        assertThat(parse(xml)).isEqualTo(parse(sample()));
    }

    @Test
    void toleratesWindowsLineEndingsLowerCaseTagsAndUnclosedOperations() {
        String text = sample();

        assertThat(parse(text.replace("\n", "\r\n"))).isEqualTo(parse(text));
        assertThat(parse(Pattern.compile("</?[A-Z0-9]+>").matcher(text).replaceAll(tag -> tag.group().toLowerCase(Locale.ROOT))))
                .isEqualTo(parse(text));
        assertThat(parse(text.replace("</STMTTRN>\n", ""))).as("opérations non refermées").isEqualTo(parse(text));
    }

    @Test
    void acceptsUtf8AsWellAsWindows1252AndDecodesEntities() {
        String text = Samples.ofxStatement(new Samples.Summary(Samples.ENERGY, "H&amp;M PARIS", "Prélèvement H&amp;M PARIS Mandat &lt;A&gt;"));

        Statement utf8 = parser.parse(("\uFEFF" + text).getBytes(StandardCharsets.UTF_8));

        assertThat(utf8).isEqualTo(parse(text));
        assertThat(utf8.lines()).singleElement().satisfies(line -> {
            assertThat(line.type()).isEqualTo("Prélèvement");
            assertThat(line.label()).isEqualTo("H&M PARIS - Mandat <A>");
        });
    }

    @Test
    void readsTheValueDateWhenTheFileGivesOneAndSkipsAnOperationWithoutAmount() {
        String text = sample().replace("<TRNAMT>-4.50", "<TRNAMT>-4.50\n<DTAVAIL>20260228120000.000[+1:CET]")
                .replace("<TRNAMT>-120.00", "<TRNAMT>0.00");

        Statement s = parse(text);

        assertThat(s.lines()).hasSize(2);
        assertThat(s.lines().getFirst().valueDate()).isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(s.lines().getFirst().summary()).isFalse();
    }

    // ------------------------------------------------------------------ fichiers refusés

    @Test
    void rejectsAFileWithoutABankStatementOrWithSeveral() {
        assertThatThrownBy(() -> parse("OFXHEADER:100\n<OFX>\n<SIGNONMSGSRSV1>\n</SIGNONMSGSRSV1>\n</OFX>\n"))
                .isInstanceOf(StatementFormatException.class).hasMessageContaining("ne contient pas le relevé");

        String body = sample().substring(sample().indexOf("<STMTTRNRS>"), sample().indexOf("</BANKMSGSRSV1>"));
        String twice = sample().replace("</BANKMSGSRSV1>", body + "</BANKMSGSRSV1>");
        assertThatThrownBy(() -> parse(twice)).isInstanceOf(StatementFormatException.class)
                .hasMessageContaining("2 comptes").hasMessageContaining("un fichier par compte");
    }

    @Test
    void reportsWhatCannotBeRead() {
        assertThatThrownBy(() -> parse(sample().replace("<ACCTID>00012345678\n", ""))).isInstanceOf(StatementFormatException.class)
                .hasMessageContaining("Numéro de compte introuvable");
        assertThatThrownBy(() -> parse(sample().replace("<TRNAMT>+2500.00", "<TRNAMT>beaucoup")))
                .isInstanceOf(StatementFormatException.class).hasMessageContaining("Opération n° 2").hasMessageContaining("montant illisible");
        assertThatThrownBy(() -> parse(sample().replace("<DTPOSTED>20260205", "<DTPOSTED>20260231")))
                .isInstanceOf(StatementFormatException.class).hasMessageContaining("Opération n° 3").hasMessageContaining("date illisible");
        assertThatThrownBy(() -> parse(sample().replace("<DTPOSTED>20260227\n", "")))
                .isInstanceOf(StatementFormatException.class).hasMessageContaining("Opération n° 1").hasMessageContaining("date illisible");
    }
}
