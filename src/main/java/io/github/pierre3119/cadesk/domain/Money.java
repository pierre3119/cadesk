package io.github.pierre3119.cadesk.domain;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/** Montants en euros, manipulés en centimes entiers pour éviter toute erreur d'arrondi. */
public final class Money {

    private static final ThreadLocal<DecimalFormat> FORMAT = ThreadLocal.withInitial(
            () -> new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.FRANCE)));

    private Money() {
    }

    /** « 1 234,56 € » */
    public static String format(long cents) {
        return FORMAT.get().format(BigDecimal.valueOf(cents, 2)) + "\u00a0€";
    }

    /** « +1 234,56 € » ou « −1 234,56 € » */
    public static String formatSigned(long cents) {
        String sign = cents > 0 ? "+" : cents < 0 ? "−" : "";
        return sign + format(Math.abs(cents));
    }

    /**
     * Lit un montant au format français (« 1 234,56 », « 55,10 ») et le renvoie en centimes.
     *
     * @throws NumberFormatException si le texte n'est pas un montant à deux décimales au plus
     */
    public static long parseCents(String text) {
        String cleaned = text.strip()
                .replace("\u00a0", "").replace("\u202f", "").replace(" ", "")
                .replace("€", "").replace(',', '.');
        try {
            return new BigDecimal(cleaned).movePointRight(2).longValueExact();
        } catch (ArithmeticException e) {
            throw new NumberFormatException("Montant avec plus de deux décimales : " + text);
        }
    }
}
