package io.github.pierre3119.cadesk;

import io.github.pierre3119.cadesk.categorize.Classifier;
import io.github.pierre3119.cadesk.domain.Category;
import io.github.pierre3119.cadesk.domain.Rule;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Fabrique de relevés fictifs, au format exact des exports du Crédit Agricole : CSV (Windows-1252) et OFX. */
public final class Samples {

    public static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

    /**
     * Une ligne de relevé : dates JJ/MM/AAAA, libellé multi-lignes, débit ou crédit au format français.
     *
     * @param valueDate date de valeur ; un relevé OFX ne la donne pas
     */
    public record Row(String date, String valueDate, String label, String debit, String credit) {

        public Row(String date, String label, String debit, String credit) {
            this(date, date, label, debit, credit);
        }

        public static Row debit(String date, String label, String amount) {
            return new Row(date, label, amount, "");
        }

        public static Row credit(String date, String label, String amount) {
            return new Row(date, label, "", amount);
        }

        /** La même opération, avec une date de valeur différente de sa date d'opération. */
        public Row valued(String otherValueDate) {
            return new Row(date, otherValueDate, label, debit, credit);
        }

        String csv() {
            return "%s;%s;\"%s\n\n\";%s;%s;\n".formatted(date, valueDate, label.replace("\"", "\"\""), debit, credit);
        }

        /**
         * Cette opération telle que l'export OFX la résume : ni date de valeur, ni détail du libellé.
         *
         * @param name le tiers (NAME), que le format coupe à 32 caractères
         * @param memo le type de l'opération, le tiers et le motif à la suite (MEMO)
         */
        String ofx(int index, String name, String memo) {
            String amount = (debit.isEmpty() ? "+" + credit : "-" + debit).replace(" ", "").replace(',', '.');
            return """
                    <STMTTRN>
                    <TRNTYPE>OTHER
                    <DTPOSTED>%s%s%s
                    <TRNAMT>%s
                    <FITID>%013d
                    <NAME>%s
                    <MEMO>%s
                    </STMTTRN>
                    """.formatted(date.substring(6), date.substring(3, 5), date.substring(0, 2), amount, 4_700_000_000_000L + index,
                    name, memo);
        }
    }

    /** Une opération d'un relevé OFX : la ligne dont elle reprend la date et le montant, et ses deux textes. */
    public record Summary(Row row, String name, String memo) {
    }

    public static final Row BAKERY = Row.debit("27/02/2026",
            "Paiement par carte\nX1234 BOULANGERIE DUPAIN  26/02 - Boulangerie Dupain\n\n", "4,50");
    public static final Row SALARY = Row.credit("20/02/2026",
            "Virement en votre faveur\nACME SA R000123 - Paie du mois de Fevrier 2026\n\nR000123", "2 500,00");
    public static final Row ENERGY = Row.debit("05/02/2026",
            "Prélèvement\nENERGIE SA - Mandat \"ABC\"\n\nFR00ZZZ123456", "120,00");
    public static final Row GIFT = Row.credit("01/02/2026",
            "Virement en votre faveur\nBon anniversaire M.OU MME MARTIN - Bon anniversaire", "500,00");

    /** Les mêmes opérations, comme les résume un relevé OFX. Un paiement par carte y occupe les 32 caractères de NAME. */
    public static final Summary BAKERY_OFX = new Summary(BAKERY,
            "X1234 BOULANGERIE DUPAIN   26/02", "Paiement par carte X1234 BOULANGERIE DUPAIN   26/02");
    public static final Summary SALARY_OFX = new Summary(SALARY,
            "ACME SA R000123", "Virement en votre faveur ACME SA R000123 Paie du mois de Fevrier 2026");
    public static final Summary ENERGY_OFX = new Summary(ENERGY,
            "ENERGIE SA", "Prélèvement ENERGIE SA Mandat \"ABC\"");
    public static final Summary GIFT_OFX = new Summary(GIFT,
            "Bon anniversaire M.OU MME MARTIN", "Virement en votre faveur Bon anniversaire M.OU MME MARTIN Bon anniversaire");

    private Samples() {
    }

    /** Relevé complet, lignes de la plus récente à la plus ancienne comme dans les vrais exports. */
    public static String statement(String accountNumber, List<Row> rows) {
        StringBuilder sb = new StringBuilder("""

                Téléchargement du 05/03/2026;


                M.       MARTIN JEAN
                Compte de Dépôt carte n° %s;
                Solde au 05/03/2026 1 234,56 €

                Liste des opérations du compte entre le 01/01/2026 et le 01/03/2026;

                Date d'opération;Date valeur;Libellé;Débit euros;Crédit euros;
                """.formatted(accountNumber));
        rows.forEach(r -> sb.append(r.csv()));
        return sb.append("\n\n").toString();
    }

    public static String statement(Row... rows) {
        return statement("00012345678", List.of(rows));
    }

    /**
     * Relevé OFX complet, tel que la banque l'écrit : OFX 1, une balise par ligne, valeurs non refermées,
     * opérations de la plus récente à la plus ancienne. Le solde disponible diffère du solde comptable.
     */
    public static String ofxStatement(String accountNumber, List<Summary> rows) {
        StringBuilder sb = new StringBuilder("""
                OFXHEADER:100
                DATA:OFXSGML
                VERSION:102
                SECURITY:NONE
                ENCODING:USASCII
                CHARSET:1252
                COMPRESSION:NONE
                OLDFILEUID:NONE
                NEWFILEUID:NONE
                <OFX>
                <SIGNONMSGSRSV1>
                <SONRS>
                <STATUS>
                <CODE>0
                <SEVERITY>INFO
                </STATUS>
                <DTSERVER>20260305101500
                <LANGUAGE>FRA
                </SONRS>
                </SIGNONMSGSRSV1>
                <BANKMSGSRSV1>
                <STMTTRNRS>
                <TRNUID>00012345001
                <STATUS>
                <CODE>0
                <SEVERITY>INFO
                </STATUS>
                <STMTRS>
                <CURDEF>EUR
                <BANKACCTFROM>
                <BANKID>10001
                <BRANCHID>00002
                <ACCTID>%s
                <ACCTTYPE>CHECKING
                </BANKACCTFROM>
                <BANKTRANLIST>
                <DTSTART>20260101000000
                <DTEND>20260305235959
                """.formatted(accountNumber));
        for (int i = 0; i < rows.size(); i++) {
            sb.append(rows.get(i).row().ofx(i, rows.get(i).name(), rows.get(i).memo()));
        }
        return sb.append("""
                </BANKTRANLIST>
                <LEDGERBAL>
                <BALAMT>+1234.56
                <DTASOF>20260305
                </LEDGERBAL>
                <AVAILBAL>
                <BALAMT>+1200.00
                <DTASOF>20260304
                </AVAILBAL>
                </STMTRS>
                </STMTTRNRS>
                </BANKMSGSRSV1>
                </OFX>
                """).toString();
    }

    public static String ofxStatement(Summary... rows) {
        return ofxStatement("00012345678", List.of(rows));
    }

    /** Relevé de démonstration : quatorze mois de données fictives, écrites pour être reconnues par {@link #demoRules}. */
    public static String demoStatement() {
        List<Row> rows = new ArrayList<>();
        String[] months = {"10/2026", "09/2026", "08/2026", "07/2026", "06/2026", "05/2026", "04/2026", "03/2026",
                "02/2026", "01/2026", "12/2025", "11/2025", "10/2025", "09/2025"};
        for (int i = 0; i < months.length; i++) {
            String m = months[i];
            String mm = m.substring(0, 2);
            rows.add(Row.debit("28/" + m, "Paiement par carte\nX1234 PIZZERIA DU PORT 27/" + mm, (38 + 7 * i % 40) + ",50"));
            rows.add(Row.debit("24/" + m, "Paiement par carte\nX1234 AMAZON PAYMENTS 23/" + mm + " - Amazon", (22 + 17 * i % 90) + ",99"));
            rows.add(Row.credit("20/" + m, "Virement en votre faveur\nACME SA R000" + i + " - Paie du mois\n\nR000" + i,
                    i % 5 == 0 ? "4 120,00" : "2 980,45"));
            rows.add(Row.debit("15/" + m, "Prélèvement\nENGIE - Mandat 00S01\n\nFR03SYM0", i < 6 ? "148,20" : "131,70"));
            rows.add(Row.debit("12/" + m, "Paiement par carte\nX1234 INTERMARCHE 11/" + mm + " - Intermarché", (84 + 13 * i % 60) + ",37"));
            rows.add(Row.debit("08/" + m, "Paiement par carte\nX1234 SPOTIFY P2E4 07/" + mm, "11,99"));
            rows.add(Row.debit("05/" + m, "Virement émis\nWEB MARTIN JEAN PRET MAISON - MARTIN JEAN\nPRET MAISON", "890,00"));
            rows.add(Row.debit("03/" + m, "Prélèvement\nORANGE SA - Votre abonnement fibre (facture: 123)", "31,99"));
            if (i % 4 == 1) {
                rows.add(Row.debit("09/" + m, "Paiement par carte\nX1234 GARAGE DES ALPES 08/" + mm, "340,00"));
            }
            if (i % 3 == 0) {
                rows.add(Row.credit("18/" + m, "Virement en votre faveur\nC.P.A.M. LYON 2611 - 000123", "25,00"));
                rows.add(Row.debit("17/" + m, "Retrait au distributeur\nRETRAIT DAB 16/" + mm, "60,00"));
            }
        }
        rows.add(Row.debit("22/06/2026", "Virement émis\nWEB CLIM SERVICES CLIMATISATION FACTURE\nFACTURE N 77", "3 200,00"));
        rows.add(Row.credit("02/04/2026", "Virement en votre faveur\nMAIRIE DE LYON - Remboursement caution", "150,00"));
        return statement("00012345678", rows);
    }

    /**
     * Les règles d'un profil déjà réglé — une base neuve n'en a aucune : elles reconnaissent le relevé de
     * démonstration, sauf le garage et le virement de la mairie, qui restent à classer.
     *
     * @param categories les catégories de la base où ces règles seront enregistrées
     */
    public static List<Rule> demoRules(List<Category> categories) {
        String transferIn = "Virement en votre faveur";
        String debit = "Prélèvement";
        String transferOut = "Virement émis";
        return List.of(
                rule(categories, "Abonnements et services", "Spotify", Classifier.CARD_PAYMENT, "SPOTIFY"),
                rule(categories, "Restaurants, bars et livraison", null, Classifier.CARD_PAYMENT, "PIZZ"),
                rule(categories, "Alimentation et courses", "Intermarché", Classifier.CARD_PAYMENT, "INTERMARCHE"),
                rule(categories, "Achats et shopping", "Amazon", Classifier.CARD_PAYMENT, "AMAZON"),
                rule(categories, "Salaire", "Salaire", transferIn, "PAIE DU MOIS"),
                rule(categories, "Remboursements santé", "CPAM", transferIn, "C.P.A.M"),
                rule(categories, "Énergie", "ENGIE", debit, "ENGIE"),
                rule(categories, "Internet et mobile", "Internet (fibre)", debit, "ABONNEMENT FIBRE"),
                rule(categories, "Prêt immobilier", "Prêt immobilier (virement permanent)", transferOut, "PRET MAISON"),
                rule(categories, "Travaux et gros équipements", null, transferOut, "CLIMATISATION"),
                rule(categories, "Retraits d'espèces", "Retraits au distributeur", "Retrait au distributeur"));
    }

    private static Rule rule(List<Category> categories, String category, String poste, String type, String... keywords) {
        long id = categories.stream().filter(c -> c.name().equals(category)).findFirst().orElseThrow().id();
        return Rule.create(id, poste, type, List.of(keywords));
    }

    public static Path write(Path dir, String fileName, String content) throws IOException {
        return Files.write(dir.resolve(fileName), content.getBytes(WINDOWS_1252));
    }
}
