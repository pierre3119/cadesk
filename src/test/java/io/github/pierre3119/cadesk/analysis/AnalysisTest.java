package io.github.pierre3119.cadesk.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pierre3119.cadesk.analysis.Analysis.PosteStat;
import io.github.pierre3119.cadesk.domain.Category;
import io.github.pierre3119.cadesk.domain.Category.IncomeKind;
import io.github.pierre3119.cadesk.domain.Category.Role;
import io.github.pierre3119.cadesk.domain.ClassifiedOperation;
import io.github.pierre3119.cadesk.domain.ClassifiedOperation.Source;
import io.github.pierre3119.cadesk.domain.Nature;
import io.github.pierre3119.cadesk.domain.Operation;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class AnalysisTest {

    private static final Category SALARY = category(1, "Salaire", Nature.REVENUS, true, IncomeKind.REGULIER);
    private static final Category REFUNDS = category(2, "Remboursements", Nature.REVENUS, true, IncomeKind.REMBOURSEMENT);
    private static final Category GIFTS = category(3, "Cadeaux", Nature.REVENUS, true, IncomeKind.PONCTUEL);
    private static final Category FROM_SAVINGS = category(4, "Reçu de mon épargne", Nature.INTERNE, true, IncomeKind.PONCTUEL);
    private static final Category RENT = category(5, "Loyer", Nature.FIXES, false, IncomeKind.PONCTUEL);
    private static final Category ENERGY = category(6, "Énergie", Nature.FIXES, false, IncomeKind.PONCTUEL);
    private static final Category FOOD = category(7, "Alimentation", Nature.VARIABLES, false, IncomeKind.PONCTUEL);
    private static final Category WORKS = category(8, "Travaux", Nature.EXCEPTIONNELLES, false, IncomeKind.PONCTUEL);
    private static final Category TO_SAVINGS = category(9, "Versé sur mon épargne", Nature.INTERNE, false, IncomeKind.PONCTUEL);
    private static final List<Category> CATEGORIES =
            List.of(TO_SAVINGS, SALARY, REFUNDS, GIFTS, FROM_SAVINGS, RENT, ENERGY, FOOD, WORKS);

    private final List<ClassifiedOperation> operations = new ArrayList<>();

    private static Category category(long id, String name, Nature nature, boolean inflow, IncomeKind kind) {
        return new Category(id, name, nature, inflow, (int) id, Role.STANDARD, kind, 0);
    }

    private void op(String date, Category category, String poste, long cents) {
        LocalDate d = LocalDate.parse(date);
        Operation o = new Operation(operations.size() + 1, d, d, "Test", poste, cents);
        operations.add(new ClassifiedOperation(o, category, poste, Source.RULE, null));
    }

    private Analysis sample() {
        op("2026-01-05", RENT, "Loyer", -80_000);
        op("2026-01-20", SALARY, "Employeur", 250_000);
        op("2026-01-15", ENERGY, "Fournisseur", -12_000);
        op("2026-01-12", FOOD, "Supermarché", -9_000);
        op("2026-01-28", FOOD, "Boulangerie", -450);
        op("2026-03-05", RENT, "Loyer", -80_000);
        op("2026-03-20", SALARY, "Employeur", 250_000);
        op("2026-03-15", ENERGY, "Fournisseur", -15_000);
        op("2026-03-12", FOOD, "Supermarché", -11_000);
        op("2026-03-14", FOOD, "Supermarché", 2_000); // avoir : vient en déduction
        op("2026-03-02", GIFTS, "Famille", 30_000);
        op("2026-03-03", REFUNDS, "Mutuelle", 4_000);
        op("2026-03-08", WORKS, "Chaudière", -300_000);
        op("2026-03-09", FROM_SAVINGS, "Livret", 200_000);
        op("2026-03-25", TO_SAVINGS, "Livret", -50_000);
        return new Analysis(CATEGORIES, operations);
    }

    @Test
    void monthsRunFromTheFirstToTheLastWithoutGap() {
        Analysis a = sample();

        assertThat(a.months()).containsExactly(YearMonth.of(2026, 1), YearMonth.of(2026, 2), YearMonth.of(2026, 3));
        assertThat(a.monthCount()).isEqualTo(3);
        assertThat(a.perMonth(a.fixes())).as("187 000 / 3, arrondi au centime").isEqualTo(62_333);
        assertThat(a.operations()).as("ordre chronologique").first().extracting(ClassifiedOperation::poste).isEqualTo("Loyer");
    }

    @Test
    void amountsAreCountedPerCategoryAndPerMonthInTheNaturalDirection() {
        Analysis a = sample();

        assertThat(a.amount(FOOD, YearMonth.of(2026, 1))).isEqualTo(9_450);
        assertThat(a.amount(FOOD, YearMonth.of(2026, 2))).isZero();
        assertThat(a.amount(FOOD, YearMonth.of(2026, 3))).as("l'avoir est déduit").isEqualTo(9_000);
        assertThat(a.total(FOOD)).isEqualTo(18_450);
        assertThat(a.count(FOOD)).isEqualTo(4);
        assertThat(a.total(SALARY)).isEqualTo(500_000);
        assertThat(a.categories()).as("ordre du bilan").startsWith(SALARY, REFUNDS).endsWith(TO_SAVINGS);
    }

    @Test
    void totalsByNatureGiveTheResultAndTheBalance() {
        Analysis a = sample();

        assertThat(a.revenus()).isEqualTo(534_000);
        assertThat(a.fixes()).isEqualTo(187_000);
        assertThat(a.variables()).isEqualTo(18_450);
        assertThat(a.exceptionnelles()).isEqualTo(300_000);
        assertThat(a.depenses()).isEqualTo(505_450);
        assertThat(a.resultat()).isEqualTo(28_550);
        assertThat(a.interneIn()).isEqualTo(200_000);
        assertThat(a.interneOut()).isEqualTo(50_000);
        assertThat(a.solde()).isEqualTo(178_550);
        assertThat(a.net()).as("crédits − débits des opérations").isEqualTo(178_550);
        assertThat(a.control()).isZero();
        assertThat(a.amount(Nature.FIXES, YearMonth.of(2026, 3))).isEqualTo(95_000);
        assertThat(a.resultat(YearMonth.of(2026, 1))).isEqualTo(250_000 - 80_000 - 12_000 - 9_450);
        assertThat(a.solde(YearMonth.of(2026, 3))).isEqualTo(a.net(YearMonth.of(2026, 3)));
        assertThat(a.count(Nature.FIXES)).isEqualTo(4);
    }

    @Test
    void incomeIsSplitByRegularity() {
        Analysis a = sample();

        assertThat(a.income(IncomeKind.REGULIER)).isEqualTo(500_000);
        assertThat(a.income(IncomeKind.REMBOURSEMENT)).isEqualTo(4_000);
        assertThat(a.income(IncomeKind.PONCTUEL)).isEqualTo(30_000);
    }

    @Test
    void postesAreSortedFromTheHeaviestAndKeepTheirHistory() {
        Analysis a = sample();

        assertThat(a.postes(FOOD)).extracting(PosteStat::poste).containsExactly("Supermarché", "Boulangerie");
        PosteStat energy = a.postes(ENERGY).getFirst();
        assertThat(energy.count()).isEqualTo(2);
        assertThat(energy.total()).isEqualTo(27_000);
        assertThat(energy.first()).isEqualTo(LocalDate.of(2026, 1, 15));
        assertThat(energy.last()).isEqualTo(LocalDate.of(2026, 3, 15));
        assertThat(energy.lastAmount()).isEqualTo(15_000);
        assertThat(energy.amountSteps()).containsExactly(12_000L, 15_000L);
        assertThat(a.postes(RENT).getFirst().amountSteps()).as("montant inchangé : une seule étape").containsExactly(80_000L);
        assertThat(a.postes(Nature.FIXES)).extracting(PosteStat::poste).containsExactly("Loyer", "Fournisseur");
        assertThat(a.spendingCategories()).containsExactly(WORKS, RENT, ENERGY, FOOD);
    }

    @Test
    void aMonthlyAverageRoundsHalfACentAwayFromZero() {
        op("2026-01-05", RENT, "Loyer", -80_000);
        op("2026-02-05", RENT, "Loyer", -80_000);
        Analysis a = new Analysis(CATEGORIES, operations);

        assertThat(a.monthCount()).isEqualTo(2);
        assertThat(a.perMonth(5)).isEqualTo(3);
        assertThat(a.perMonth(-5)).as("−2,5 centimes : −3, comme un tableur").isEqualTo(-3);
        assertThat(a.perMonth(-4)).isEqualTo(-2);
    }

    @Test
    void anEmptyPeriodGivesZerosWithoutFailing() {
        Analysis a = new Analysis(CATEGORIES, List.of());

        assertThat(a.isEmpty()).isTrue();
        assertThat(a.months()).isEmpty();
        assertThat(a.monthCount()).isEqualTo(1);
        assertThat(a.resultat()).isZero();
        assertThat(a.postes(FOOD)).isEmpty();
        assertThat(a.spendingCategories()).isEmpty();
    }

    @Test
    void periodsAreProposedFromTheDatesOfTheOperations() {
        List<Period> choices = Period.choices(List.of(LocalDate.of(2025, 11, 3), LocalDate.of(2026, 10, 30)));

        assertThat(choices).extracting(Period::label)
                .containsExactly("12 derniers mois", "Année 2026", "Année 2025", "Tout l'historique");
        Period lastTwelve = choices.getFirst();
        assertThat(lastTwelve.from()).isEqualTo(LocalDate.of(2025, 11, 1));
        assertThat(lastTwelve.to()).isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(lastTwelve.contains(LocalDate.of(2025, 10, 31))).isFalse();
        assertThat(lastTwelve.contains(LocalDate.of(2025, 11, 1))).isTrue();
        assertThat(Period.ALL.contains(LocalDate.MIN)).isTrue();
        assertThat(Period.choices(List.of())).containsExactly(Period.ALL);
    }
}
