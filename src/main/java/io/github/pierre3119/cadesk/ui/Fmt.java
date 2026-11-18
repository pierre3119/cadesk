package io.github.pierre3119.cadesk.ui;

import io.github.pierre3119.cadesk.analysis.Analysis;
import io.github.pierre3119.cadesk.analysis.Period;
import io.github.pierre3119.cadesk.domain.Money;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/** Mise en forme des dates, mois et montants, pour les écrans comme pour le rapport PDF. */
public final class Fmt {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATE_SHORT = DateTimeFormatter.ofPattern("dd/MM/yy");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy 'à' HH:mm");
    private static final DateTimeFormatter DATE_TIME_SHORT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final DateTimeFormatter MONTH_LONG = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.FRANCE);
    private static final DateTimeFormatter MONTH_SHORT = DateTimeFormatter.ofPattern("MMM yy", Locale.FRANCE);

    private Fmt() {
    }

    public static String date(LocalDate d) {
        return d == null ? "—" : DATE.format(d);
    }

    /** « 01/11/25 », pour les colonnes étroites. */
    public static String dateShort(LocalDate d) {
        return d == null ? "—" : DATE_SHORT.format(d);
    }

    public static String dateTime(LocalDateTime d) {
        return d == null ? "—" : DATE_TIME.format(d);
    }

    /** « 17/11/2026 14:26 », pour les colonnes de tableau. */
    public static String dateTimeShort(LocalDateTime d) {
        return d == null ? "—" : DATE_TIME_SHORT.format(d);
    }

    /** « octobre 2026 » */
    public static String month(YearMonth m) {
        return MONTH_LONG.format(m);
    }

    /** « oct. 26 » */
    public static String monthShort(YearMonth m) {
        return MONTH_SHORT.format(m);
    }

    /** « Année 2026 (du 01/01/2026 au 31/12/2026) », « Du 05/01/2026 au 20/03/2026 », « Tout l'historique ». */
    public static String period(Period p) {
        if (p.from() == null && p.to() == null) {
            return Period.ALL.label();
        }
        String range = p.from() == null ? "jusqu'au " + date(p.to())
                : p.to() == null ? "à partir du " + date(p.from()) : "du " + date(p.from()) + " au " + date(p.to());
        return p.custom() ? range.substring(0, 1).toUpperCase(Locale.FRANCE) + range.substring(1) : p.label() + " (" + range + ")";
    }

    /** « 12 mois, de nov. 25 à oct. 26  ·  789 opérations » : ce que couvre un bilan. */
    public static String span(Analysis a) {
        if (a.isEmpty()) {
            return "Aucune opération sur cette période";
        }
        return plural(a.monthCount(), "mois", "mois") + ", de " + monthShort(a.months().getFirst()) + " à "
                + monthShort(a.months().getLast()) + "  ·  " + plural(a.operations().size(), "opération", "opérations");
    }

    /** « Sur 12 mois », « Sur 1 mois » : titre des colonnes de total. */
    public static String overPeriod(Analysis a) {
        return "Sur " + a.monthCount() + " mois";
    }

    /** « novembre 2025 → octobre 2026 », ou un seul mois si les deux bornes sont dans le même. */
    public static String months(LocalDate first, LocalDate last) {
        YearMonth a = YearMonth.from(first);
        YearMonth b = YearMonth.from(last);
        return a.equals(b) ? monthShort(a) : monthShort(a) + " → " + monthShort(b);
    }

    public static String money(Long cents) {
        return cents == null ? "—" : Money.format(cents);
    }

    /** « 1 234,56 » sans symbole monétaire, et un tiret pour zéro : pour les grilles denses. */
    public static String amount(long cents) {
        if (cents == 0) {
            return "–";
        }
        String text = Money.format(Math.abs(cents));
        return (cents < 0 ? "−" : "") + text.substring(0, text.length() - 2);
    }

    /** Comme {@link #amount} mais avec le signe « + » pour un montant positif. */
    public static String amountSigned(long cents) {
        return cents > 0 ? "+" + amount(cents) : amount(cents);
    }

    /** Montant arrondi à l'euro : « 53 739 € ». */
    public static String euros(double cents) {
        return String.format(Locale.FRANCE, "%,.0f\u00a0€", cents / 100);
    }

    /** Montant arrondi à l'euro avec son signe : « −4 513 € ». */
    public static String eurosSigned(long cents) {
        String text = euros(Math.abs(cents));
        return cents > 0 ? "+" + text : cents < 0 ? "−" + text : text;
    }

    /** Part exprimée en pourcentage à une décimale : « 48,7 % » ; un tiret si le total est nul. */
    public static String percent(long part, long total) {
        return total == 0 ? "–" : String.format(Locale.FRANCE, "%.1f\u00a0%%", 100.0 * part / total).replace('-', '−');
    }

    /** Montants successifs d'un poste : « 251,90 → 223,95 € », ou « de 31,19 à 43,98 € » au-delà de quatre. */
    public static String steps(List<Long> amounts) {
        if (amounts.size() <= 4) {
            return amounts.stream().map(Fmt::amount).collect(Collectors.joining(" → ")) + "\u00a0€";
        }
        long min = amounts.stream().mapToLong(Long::longValue).min().orElse(0);
        long max = amounts.stream().mapToLong(Long::longValue).max().orElse(0);
        return "de " + amount(min) + " à " + amount(max) + "\u00a0€";
    }

    /** « 1 234 » */
    public static String count(int n) {
        return String.format(Locale.FRANCE, "%,d", n);
    }

    public static String plural(int n, String singular, String plural) {
        return count(n) + " " + (n > 1 ? plural : singular);
    }
}
