package io.github.pierre3119.cadesk.analysis;

import io.github.pierre3119.cadesk.domain.Category;
import io.github.pierre3119.cadesk.domain.Category.IncomeKind;
import io.github.pierre3119.cadesk.domain.ClassifiedOperation;
import io.github.pierre3119.cadesk.domain.Nature;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Le bilan d'une période : montants par catégorie et par mois, totaux par nature, détail par poste.
 *
 * <p>Tous les montants sont en centimes et comptés dans le sens naturel de leur catégorie : une dépense
 * est positive dans une catégorie de sortie, un revenu positif dans une catégorie d'entrée. Les
 * moyennes mensuelles divisent par le nombre de mois de la période, du premier au dernier mois qui
 * contient une opération.
 */
public final class Analysis {

    /** Un poste d'une catégorie : un commerçant, un tiers, une charge. */
    public record PosteStat(Category category, String poste, long total, List<ClassifiedOperation> operations) {

        public PosteStat {
            operations = List.copyOf(operations);
        }

        public int count() {
            return operations.size();
        }

        public LocalDate first() {
            return operations.getFirst().operation().date();
        }

        public LocalDate last() {
            return operations.getLast().operation().date();
        }

        /** Montant de l'opération la plus récente. */
        public long lastAmount() {
            return operations.getLast().amount();
        }

        /** Montants successifs, sans répéter un montant identique au précédent : « 251,90 puis 223,95 ». */
        public List<Long> amountSteps() {
            List<Long> steps = new ArrayList<>();
            for (ClassifiedOperation o : operations) {
                if (steps.isEmpty() || steps.getLast() != o.amount()) {
                    steps.add(o.amount());
                }
            }
            return steps;
        }
    }

    private static final Comparator<PosteStat> BY_TOTAL =
            Comparator.comparingLong(PosteStat::total).reversed().thenComparing(PosteStat::poste, String.CASE_INSENSITIVE_ORDER);

    private final List<Category> categories;
    private final List<ClassifiedOperation> operations;
    private final List<YearMonth> months;
    private final Map<Long, Map<YearMonth, Long>> byMonth = new HashMap<>();
    private final Map<Long, Long> totals = new HashMap<>();
    private final Map<Long, Integer> counts = new HashMap<>();
    private final Map<Long, List<PosteStat>> postes = new HashMap<>();
    private final Map<YearMonth, Long> netByMonth = new HashMap<>();
    private final long net;

    /**
     * @param categories toutes les catégories
     * @param operations les opérations classées de la période, dans un ordre quelconque
     */
    public Analysis(List<Category> categories, List<ClassifiedOperation> operations) {
        this.categories = List.copyOf(categories.stream()
                .sorted(Comparator.comparingInt(Category::position).thenComparingLong(Category::id)).toList());
        this.operations = List.copyOf(operations.stream()
                .sorted(Comparator.comparing((ClassifiedOperation o) -> o.operation().date()).thenComparingLong(o -> o.operation().id()))
                .toList());
        List<YearMonth> span = new ArrayList<>();
        if (!this.operations.isEmpty()) {
            YearMonth last = this.operations.getLast().month();
            for (YearMonth m = this.operations.getFirst().month(); !m.isAfter(last); m = m.plusMonths(1)) {
                span.add(m);
            }
        }
        this.months = List.copyOf(span);
        Map<Long, Map<String, List<ClassifiedOperation>>> grouped = new HashMap<>();
        long sum = 0;
        for (ClassifiedOperation o : this.operations) {
            long id = o.category().id();
            byMonth.computeIfAbsent(id, _ -> new HashMap<>()).merge(o.month(), o.amount(), Long::sum);
            totals.merge(id, o.amount(), Long::sum);
            counts.merge(id, 1, Integer::sum);
            grouped.computeIfAbsent(id, _ -> new LinkedHashMap<>()).computeIfAbsent(o.poste(), _ -> new ArrayList<>()).add(o);
            netByMonth.merge(o.month(), o.operation().amountCents(), Long::sum);
            sum += o.operation().amountCents();
        }
        net = sum;
        grouped.forEach((id, byPoste) -> {
            List<PosteStat> stats = new ArrayList<>();
            byPoste.forEach((poste, ops) -> stats.add(new PosteStat(ops.getFirst().category(), poste,
                    ops.stream().mapToLong(ClassifiedOperation::amount).sum(), ops)));
            stats.sort(BY_TOTAL);
            postes.put(id, stats);
        });
    }

    // ------------------------------------------------------------------ période

    /** Opérations de la période, de la plus ancienne à la plus récente. */
    public List<ClassifiedOperation> operations() {
        return operations;
    }

    public boolean isEmpty() {
        return operations.isEmpty();
    }

    /** Mois de la période, du premier au dernier qui contient une opération, sans trou. */
    public List<YearMonth> months() {
        return months;
    }

    /** Nombre de mois servant aux moyennes mensuelles (au moins 1). */
    public int monthCount() {
        return Math.max(1, months.size());
    }

    /** Moyenne mensuelle, arrondie au centime le plus proche (un demi-centime s'éloigne de zéro, comme dans un tableur). */
    public long perMonth(long total) {
        long magnitude = Math.round(Math.abs((double) total) / monthCount());
        return total < 0 ? -magnitude : magnitude;
    }

    // ------------------------------------------------------------------ catégories

    /** Toutes les catégories, dans l'ordre du bilan. */
    public List<Category> categories() {
        return categories;
    }

    public List<Category> categories(Nature nature) {
        return categories.stream().filter(c -> c.nature() == nature).toList();
    }

    public List<Category> categories(Nature nature, boolean inflow) {
        return categories.stream().filter(c -> c.nature() == nature && c.inflow() == inflow).toList();
    }

    public long amount(Category category, YearMonth month) {
        return byMonth.getOrDefault(category.id(), Map.of()).getOrDefault(month, 0L);
    }

    public long total(Category category) {
        return totals.getOrDefault(category.id(), 0L);
    }

    public int count(Category category) {
        return counts.getOrDefault(category.id(), 0);
    }

    /** Catégories de dépense (fixes, variables, exceptionnelles) non nulles, de la plus lourde à la plus légère. */
    public List<Category> spendingCategories() {
        return categories.stream().filter(c -> c.nature().isSpending() && total(c) != 0)
                .sorted(Comparator.comparingLong((Category c) -> total(c)).reversed()).toList();
    }

    // ------------------------------------------------------------------ natures

    public long total(Nature nature) {
        return categories(nature).stream().mapToLong(this::total).sum();
    }

    public long total(Nature nature, boolean inflow) {
        return categories(nature, inflow).stream().mapToLong(this::total).sum();
    }

    public long amount(Nature nature, YearMonth month) {
        return categories(nature).stream().mapToLong(c -> amount(c, month)).sum();
    }

    public long amount(Nature nature, boolean inflow, YearMonth month) {
        return categories(nature, inflow).stream().mapToLong(c -> amount(c, month)).sum();
    }

    public int count(Nature nature) {
        return categories(nature).stream().mapToInt(this::count).sum();
    }

    public long revenus() {
        return total(Nature.REVENUS);
    }

    public long fixes() {
        return total(Nature.FIXES);
    }

    public long variables() {
        return total(Nature.VARIABLES);
    }

    public long exceptionnelles() {
        return total(Nature.EXCEPTIONNELLES);
    }

    /** Charges fixes + dépenses variables + dépenses exceptionnelles. */
    public long depenses() {
        return fixes() + variables() + exceptionnelles();
    }

    public long depenses(YearMonth month) {
        return amount(Nature.FIXES, month) + amount(Nature.VARIABLES, month) + amount(Nature.EXCEPTIONNELLES, month);
    }

    /** Reçu de mes autres comptes. */
    public long interneIn() {
        return total(Nature.INTERNE, true);
    }

    /** Versé sur mes autres comptes. */
    public long interneOut() {
        return total(Nature.INTERNE, false);
    }

    /** Revenus − dépenses, hors mouvements entre mes comptes. */
    public long resultat() {
        return revenus() - depenses();
    }

    public long resultat(YearMonth month) {
        return amount(Nature.REVENUS, month) - depenses(month);
    }

    /** Somme des revenus d'une régularité donnée. */
    public long income(IncomeKind kind) {
        return categories(Nature.REVENUS).stream().filter(c -> c.incomeKind() == kind).mapToLong(this::total).sum();
    }

    /** Variation du solde du compte d'après le bilan : résultat + mouvements nets entre mes comptes. */
    public long solde() {
        return resultat() + interneIn() - interneOut();
    }

    public long solde(YearMonth month) {
        return resultat(month) + amount(Nature.INTERNE, true, month) - amount(Nature.INTERNE, false, month);
    }

    /** Variation du solde d'après les opérations elles-mêmes : crédits − débits. */
    public long net() {
        return net;
    }

    public long net(YearMonth month) {
        return netByMonth.getOrDefault(month, 0L);
    }

    /** Écart entre le bilan par catégorie et la somme brute des opérations ; 0 quand tout est cohérent. */
    public long control() {
        return solde() - net;
    }

    // ------------------------------------------------------------------ postes

    /** Postes d'une catégorie, du plus lourd au plus léger. */
    public List<PosteStat> postes(Category category) {
        return postes.getOrDefault(category.id(), List.of());
    }

    /** Postes de toutes les catégories d'une nature, du plus lourd au plus léger. */
    public List<PosteStat> postes(Nature nature) {
        return categories(nature).stream().flatMap(c -> postes(c).stream()).sorted(BY_TOTAL).toList();
    }
}
