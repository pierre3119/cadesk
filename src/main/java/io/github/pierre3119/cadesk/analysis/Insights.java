package io.github.pierre3119.cadesk.analysis;

import io.github.pierre3119.cadesk.analysis.Analysis.PosteStat;
import io.github.pierre3119.cadesk.categorize.Texts;
import io.github.pierre3119.cadesk.domain.Category;
import io.github.pierre3119.cadesk.domain.Category.IncomeKind;
import io.github.pierre3119.cadesk.domain.ClassifiedOperation;
import io.github.pierre3119.cadesk.domain.Nature;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Lectures d'un bilan communes aux écrans et au rapport PDF : capacité d'épargne, postes du simulateur
 * et pistes d'économies.
 */
public final class Insights {

    private static final int BIGGEST = 5;
    private static final int FREQUENT = 5;
    private static final int FREQUENT_FROM = 6;
    private static final int RECURRING = 8;

    /**
     * Capacité d'épargne constatée sur la période.
     *
     * @param regular           revenus réguliers
     * @param refunds           remboursements reçus
     * @param oneOff            autres entrées ponctuelles
     * @param current           reste sur le train de vie courant : revenus réguliers et remboursements, moins
     *                          les charges fixes et les dépenses variables
     * @param beforeExceptional capacité d'épargne hors dépenses exceptionnelles
     */
    public record Capacity(long regular, long refunds, long oneOff, long current, long beforeExceptional) {
    }

    private Insights() {
    }

    public static Capacity capacity(Analysis a) {
        long regular = a.income(IncomeKind.REGULIER);
        long refunds = a.income(IncomeKind.REMBOURSEMENT);
        long oneOff = a.income(IncomeKind.PONCTUEL);
        long current = regular + refunds - a.fixes() - a.variables();
        return new Capacity(regular, refunds, oneOff, current, current + oneOff);
    }

    /** Catégories du simulateur d'économies : les dépenses variables puis les charges fixes, de la plus lourde à la plus légère. */
    public static List<Category> simulated(Analysis a) {
        List<Category> categories = new ArrayList<>();
        for (Nature nature : List.of(Nature.VARIABLES, Nature.FIXES)) {
            categories.addAll(a.categories(nature).stream().filter(c -> a.total(c) > 0)
                    .sorted(Comparator.comparingLong((Category c) -> a.total(c)).reversed()).toList());
        }
        return categories;
    }

    /** Les plus gros postes de dépenses variables. */
    public static List<PosteStat> biggestVariable(Analysis a) {
        return a.postes(Nature.VARIABLES).stream().limit(BIGGEST).toList();
    }

    /** Les petites dépenses variables qui reviennent souvent, hors plus gros postes. */
    public static List<PosteStat> frequentVariable(Analysis a) {
        List<PosteStat> biggest = biggestVariable(a);
        return a.postes(Nature.VARIABLES).stream().filter(p -> p.count() >= FREQUENT_FROM && !biggest.contains(p))
                .sorted(Comparator.comparingInt(PosteStat::count).reversed()).limit(FREQUENT).toList();
    }

    /** Les factures et abonnements à comparer : les premiers postes de charges fixes qui ne sont pas payés par virement. */
    public static List<PosteStat> recurringFixed(Analysis a) {
        return a.postes(Nature.FIXES).stream().filter(p -> p.total() > 0 && !isTransfer(p)).limit(RECURRING).toList();
    }

    /** Les dépenses qui attendent encore une catégorie. */
    public static List<ClassifiedOperation> pendingSpending(Analysis a) {
        return a.operations().stream().filter(o -> o.isUnclassified() && !o.category().inflow()).toList();
    }

    /** Part des charges fixes payée par virement (prêt, budgets, virements permanents). */
    public static long fixedTransfers(Analysis a) {
        return a.operations().stream().filter(o -> o.nature() == Nature.FIXES && isTransfer(o))
                .mapToLong(ClassifiedOperation::amount).sum();
    }

    public static boolean isTransfer(ClassifiedOperation o) {
        return Texts.fold(o.operation().type()).contains("VIREMENT");
    }

    /** Vrai si le poste est payé surtout par virement. */
    public static boolean isTransfer(PosteStat poste) {
        long transfers = poste.operations().stream().filter(Insights::isTransfer).count();
        return 2 * transfers > poste.count();
    }
}
