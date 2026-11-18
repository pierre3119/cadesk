package io.github.pierre3119.cadesk.analysis;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * Période d'analyse ; une borne {@code null} signifie « sans limite ».
 *
 * @param custom vrai pour la période choisie librement entre deux dates
 */
public record Period(String label, LocalDate from, LocalDate to, boolean custom) {

    public static final Period ALL = new Period("Tout l'historique", null, null);

    private static final String CUSTOM_LABEL = "Entre deux dates";

    public Period(String label, LocalDate from, LocalDate to) {
        this(label, from, to, false);
    }

    /** Période choisie librement, bornes comprises ; des bornes inversées sont remises dans l'ordre. */
    public static Period between(LocalDate from, LocalDate to) {
        boolean ordered = from == null || to == null || !from.isAfter(to);
        return new Period(CUSTOM_LABEL, ordered ? from : to, ordered ? to : from, true);
    }

    public boolean contains(LocalDate date) {
        return (from == null || !date.isBefore(from)) && (to == null || !date.isAfter(to));
    }

    /**
     * Périodes proposées d'après les dates des opérations : les 12 mois qui se terminent par le dernier
     * mois connu, chaque année civile de la plus récente à la plus ancienne, puis tout l'historique.
     */
    public static List<Period> choices(Collection<LocalDate> dates) {
        List<Period> choices = new ArrayList<>();
        if (!dates.isEmpty()) {
            YearMonth last = YearMonth.from(dates.stream().max(Comparator.naturalOrder()).orElseThrow());
            choices.add(new Period("12 derniers mois", last.minusMonths(11).atDay(1), last.atEndOfMonth()));
            dates.stream().map(LocalDate::getYear).distinct().sorted(Comparator.reverseOrder())
                    .forEach(y -> choices.add(new Period("Année " + y, LocalDate.of(y, 1, 1), LocalDate.of(y, 12, 31))));
        }
        choices.add(ALL);
        return choices;
    }

    @Override
    public String toString() {
        return label;
    }
}
