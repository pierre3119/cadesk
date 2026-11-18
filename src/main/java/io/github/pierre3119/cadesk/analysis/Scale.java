package io.github.pierre3119.cadesk.analysis;

/** Échelle des graphiques. */
public final class Scale {

    private Scale() {
    }

    /** Pas « rond » (1, 2 ou 5 × 10ⁿ) au moins égal à {@code rough}, pour graduer un axe. */
    public static double niceStep(double rough) {
        double magnitude = Math.pow(10, Math.floor(Math.log10(rough)));
        double ratio = rough / magnitude;
        return (ratio <= 1 ? 1 : ratio <= 2 ? 2 : ratio <= 5 ? 5 : 10) * magnitude;
    }
}
