package io.github.pierre3119.cadesk.report;

/** Couleur d'un tracé du rapport : rouge, vert et bleu de 0 à 1. */
record Rgb(float r, float g, float b) {

    static final Rgb WHITE = hex(0xffffff);
    static final Rgb TEXT = hex(0x1f2328);
    static final Rgb MUTED = hex(0x656d76);
    static final Rgb BORDER = hex(0xd0d7de);
    static final Rgb SUBTLE = hex(0xf6f8fa);
    static final Rgb INK = hex(0x1f2a44);
    static final Rgb IN = hex(0x1a7f37);
    static final Rgb OUT = hex(0xcf222e);
    static final Rgb ACCENT = hex(0x0969da);

    static Rgb hex(int rgb) {
        return new Rgb(((rgb >> 16) & 0xff) / 255f, ((rgb >> 8) & 0xff) / 255f, (rgb & 0xff) / 255f);
    }
}
