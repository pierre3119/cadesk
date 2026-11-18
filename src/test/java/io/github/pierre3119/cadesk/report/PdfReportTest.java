package io.github.pierre3119.cadesk.report;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pierre3119.cadesk.analysis.Analysis;
import io.github.pierre3119.cadesk.analysis.Period;
import io.github.pierre3119.cadesk.domain.Account;
import io.github.pierre3119.cadesk.domain.Category;
import io.github.pierre3119.cadesk.domain.Category.IncomeKind;
import io.github.pierre3119.cadesk.domain.Category.Role;
import io.github.pierre3119.cadesk.domain.ClassifiedOperation;
import io.github.pierre3119.cadesk.domain.ClassifiedOperation.Source;
import io.github.pierre3119.cadesk.domain.Nature;
import io.github.pierre3119.cadesk.domain.Operation;
import io.github.pierre3119.cadesk.report.PdfReport.Content;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Rapport PDF : il se relit, reprend chaque partie de l'application et respecte la période demandée. */
class PdfReportTest {

    private static final Category SALARY = category(1, "Salaire", Nature.REVENUS, true, IncomeKind.REGULIER, 0);
    private static final Category REFUNDS = category(2, "Remboursements santé", Nature.REVENUS, true, IncomeKind.REMBOURSEMENT, 0);
    private static final Category FROM_SAVINGS = category(3, "Reçu de mon épargne", Nature.INTERNE, true, IncomeKind.PONCTUEL, 0);
    private static final Category LOAN = category(4, "Prêt maison", Nature.FIXES, false, IncomeKind.PONCTUEL, 0);
    private static final Category ENERGY = category(5, "Énergie", Nature.FIXES, false, IncomeKind.PONCTUEL, 0);
    private static final Category FOOD = category(6, "Alimentation et courses", Nature.VARIABLES, false, IncomeKind.PONCTUEL, 10);
    private static final Category RESTAURANTS = category(7, "Restaurants", Nature.VARIABLES, false, IncomeKind.PONCTUEL, 20);
    private static final Category PENDING = new Category(8, "Divers / à classer", Nature.VARIABLES, false, 8, Role.DEFAULT_OUT,
            IncomeKind.PONCTUEL, 0);
    private static final Category WORKS = category(9, "Climatisation", Nature.EXCEPTIONNELLES, false, IncomeKind.PONCTUEL, 0);
    private static final Category TO_SAVINGS = category(10, "Versé sur mon épargne", Nature.INTERNE, false, IncomeKind.PONCTUEL, 0);
    private static final List<Category> CATEGORIES =
            List.of(SALARY, REFUNDS, FROM_SAVINGS, LOAN, ENERGY, FOOD, RESTAURANTS, PENDING, WORKS, TO_SAVINGS);
    private static final Account ACCOUNT = new Account(1, "00012345678", "Compte de Dépôt", "M. MARTIN JEAN");

    @TempDir
    Path dir;

    private final List<ClassifiedOperation> operations = new ArrayList<>();

    private static Category category(long id, String name, Nature nature, boolean inflow, IncomeKind kind, int saving) {
        return new Category(id, name, nature, inflow, (int) id, Role.STANDARD, kind, saving);
    }

    private void op(LocalDate date, Category category, String poste, String type, long cents) {
        Operation o = new Operation(operations.size() + 1, date, date, type, "X1234 " + poste.toUpperCase() + " – détail → fin", cents);
        operations.add(new ClassifiedOperation(o, category, poste, category.isFallback() ? Source.DEFAULT : Source.RULE, null));
    }

    /** {@code count} mois d'opérations, jusqu'à octobre 2026 compris. */
    private void months(int count) {
        for (int i = 0; i < count; i++) {
            YearMonth month = YearMonth.of(2026, 10).minusMonths(i);
            op(month.atDay(20), SALARY, "Acme – salaire", "Virement en votre faveur", 298_045);
            op(month.atDay(5), LOAN, "Prêt maison", "Virement émis", -89_000);
            op(month.atDay(15), ENERGY, "Engie", "Prélèvement", i < 6 ? -14_820 : -13_170);
            op(month.atDay(12), FOOD, "Intermarché", "Paiement par carte", -8_437 - 100L * i);
            op(month.atDay(13), FOOD, "Boulangerie Dupain", "Paiement par carte", -450);
            op(month.atDay(28), RESTAURANTS, "Pizzeria du Port", "Paiement par carte", -3_850);
            if (i % 3 == 0) {
                op(month.atDay(18), REFUNDS, "C.P.A.M.", "Virement en votre faveur", 2_500);
                op(month.atDay(9), PENDING, "Garage des Alpes", "Paiement par carte", -34_000);
                op(month.atDay(25), TO_SAVINGS, "Livret A", "Virement émis", -20_000);
            }
        }
        op(LocalDate.of(2026, 6, 22), WORKS, "Clim Services", "Virement émis", -320_000);
        op(LocalDate.of(2026, 6, 23), FROM_SAVINGS, "Livret A", "Virement en votre faveur", 300_000);
    }

    private Path write(Period period, boolean withOperations) throws IOException {
        Analysis analysis = new Analysis(CATEGORIES, operations.stream().filter(o -> period.contains(o.operation().date())).toList());
        Path file = dir.resolve("rapport.pdf");
        PdfReport.write(file, new Content("Pierre", ACCOUNT, period, analysis, withOperations, LocalDateTime.of(2026, 11, 18, 9, 30)));
        return file;
    }

    private static String text(Path file) throws IOException {
        try (PDDocument document = Loader.loadPDF(file.toFile())) {
            return new PDFTextStripper().getText(document).replace('\u00a0', ' ');
        }
    }

    private static int pages(Path file) throws IOException {
        try (PDDocument document = Loader.loadPDF(file.toFile())) {
            return document.getNumberOfPages();
        }
    }

    @Test
    void theReportCoversEveryScreenOfThePeriod() throws IOException {
        months(14);
        Period lastTwelve = Period.choices(operations.stream().map(o -> o.operation().date()).toList()).getFirst();

        Path file = write(lastTwelve, false);

        assertThat(Files.readAllBytes(file)).startsWith("%PDF".getBytes());
        String text = text(file);
        assertThat(text).contains("Rapport des comptes", "12 derniers mois (du 01/11/2025 au 31/10/2026)", "Profil : Pierre",
                "Compte de Dépôt", "5678", "Édité le 18/11/2026");
        assertThat(text).as("synthèse").contains("Synthèse", "Vue d'ensemble", "Répartition des dépenses", "Mois par mois",
                "Les 12 premiers postes de dépenses", "Contrôle : le bilan retombe au centime près");
        assertThat(text).as("bilan mensuel").contains("Bilan mensuel", "TOTAL DES ENTRÉES", "TOTAL DES SORTIES", "SOLDE DU MOIS",
                "nov. 25", "oct. 26", "Prêt maison");
        assertThat(text).as("charges fixes").contains("Charges fixes", "TOTAL DES CHARGES FIXES", "Engie", "dont virements");
        assertThat(text).as("dépenses variables").contains("Dépenses variables", "Par catégorie", "Intermarché",
                "Les 30 plus gros postes de dépenses variables", "encore une catégorie");
        assertThat(text).as("économies").contains("Économies", "Capacité d'épargne", "Simulateur", "TOTAL SIMULÉ", "Pistes repérées",
                "Opérations à classer");
        assertThat(text).as("hors période").doesNotContain("sept. 25", "oct. 25");
        assertThat(text).as("la liste des opérations n'a pas été demandée").doesNotContain("Libellé");
        assertThat(text).contains("Page 1 / " + pages(file));
        assertThat(dir).isDirectoryNotContaining("glob:**.tmp");
    }

    @Test
    void theAmountsOfTheReportAreThoseOfThePeriod() throws IOException {
        months(3);
        Period custom = Period.between(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 31));

        String text = text(write(custom, false));

        assertThat(text).contains("Du 01/09/2026 au 31/10/2026", "2 mois, de sept. 26 à oct. 26");
        // deux salaires de 2 980,45 € et un remboursement de 25,00 € sur les deux mois retenus
        assertThat(text).contains("5 985,90 €");
        assertThat(text).doesNotContain("août 26");
    }

    @Test
    void theOperationsAreListedOnRequestAndUnusualCharactersDoNotBreakTheReport() throws IOException {
        months(2);
        op(LocalDate.of(2026, 10, 2), FOOD, "Café ☕ « Chez Zoé » → œufs", "Paiement par carte", -1_250);

        Path file = write(Period.ALL, true);

        String text = text(file);
        assertThat(text).contains("Tout l'historique", "Opérations", "Libellé", "octobre 2026", "X1234 PIZZERIA DU PORT", "Chez Zoé");
        assertThat(text).contains("28/10/2026");
        assertThat(pages(file)).isGreaterThanOrEqualTo(6);
    }

    @Test
    void aLongHistorySplitsTheMonthlyBalanceIntoSeveralGrids() throws IOException {
        months(27);

        String text = text(write(Period.ALL, false));

        assertThat(text).contains("août 24", "août 25", "oct. 26", "suite page suivante", "et totaux de la période");
        assertThat(text).contains("Sur 27 mois");
    }

    @Test
    void anEmptyPeriodStillGivesAReadableReport() throws IOException {
        months(1);

        Path file = write(Period.between(LocalDate.of(2020, 1, 1), LocalDate.of(2020, 12, 31)), true);

        assertThat(text(file)).contains("Rapport des comptes", "Aucune opération sur cette période");
        assertThat(pages(file)).isEqualTo(1);
    }
}
