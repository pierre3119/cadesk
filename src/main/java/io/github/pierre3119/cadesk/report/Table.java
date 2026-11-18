package io.github.pierre3119.cadesk.report;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.pdmodel.font.PDFont;

/**
 * Tableau du rapport : les lignes sont dessinées au fur et à mesure, et la ligne de titres est répétée
 * en haut de chaque nouvelle page.
 */
final class Table {

    enum Align { LEFT, CENTER, RIGHT }

    /** Une colonne : sa largeur relative, son alignement, et si un texte long passe à la ligne au lieu d'être coupé. */
    record Col(float weight, Align align, boolean wrap) {

        static Col text(float weight) {
            return new Col(weight, Align.LEFT, false);
        }

        static Col number(float weight) {
            return new Col(weight, Align.RIGHT, false);
        }

        static Col center(float weight) {
            return new Col(weight, Align.CENTER, false);
        }

        static Col wrap(float weight) {
            return new Col(weight, Align.LEFT, true);
        }
    }

    /**
     * Aspect d'une ligne.
     *
     * @param fill   couleur de fond, ou {@code null}
     * @param indent retrait de la première cellule, en points
     */
    record Look(Rgb fill, Rgb ink, boolean bold, boolean italic, float indent) {

        static final Look PLAIN = new Look(null, Rgb.TEXT, false, false, 0);
        static final Look ITEM = new Look(null, Rgb.TEXT, false, false, 10);
        static final Look STRONG = new Look(Rgb.SUBTLE, Rgb.TEXT, true, false, 0);
        static final Look CALC = new Look(Rgb.SUBTLE, Rgb.MUTED, false, false, 0);
        static final Look EMPTY = new Look(null, Rgb.MUTED, false, true, 0);

        static Look filled(Rgb fill, Rgb ink, boolean bold) {
            return new Look(fill, ink, bold, false, 0);
        }
    }

    /**
     * Cellule qui s'écarte de l'aspect de sa ligne.
     *
     * @param ink  couleur du texte, ou {@code null} pour celle de la ligne
     * @param bold {@code null} pour la graisse de la ligne
     * @param bar  part (0 à 1) de la cellule occupée par une barre de fond, ou une valeur négative pour aucune
     */
    record Cell(String text, Rgb ink, Boolean bold, double bar, Rgb barColor) {

        static Cell ink(String text, Rgb ink) {
            return new Cell(text, ink, null, -1, null);
        }

        static Cell bold(String text) {
            return new Cell(text, null, true, -1, null);
        }

        static Cell muted(String text) {
            return new Cell(text, Rgb.MUTED, false, -1, null);
        }

        static Cell bar(String text, double fraction, Rgb color) {
            return new Cell(text, null, null, Math.clamp(fraction, 0, 1), color);
        }

        /** Montant coloré selon son signe. */
        static Cell signed(String text, long cents) {
            return new Cell(text, cents > 0 ? Rgb.IN : cents < 0 ? Rgb.OUT : null, null, -1, null);
        }
    }

    private static final float PAD_X = 4;
    private static final float PAD_Y = 3.2f;
    /** Jusqu'où un nombre trop large peut être réduit avant d'être coupé. */
    private static final float MIN_SHRINK = 0.7f;

    private final Pdf pdf;
    private final float x;
    private final float fontSize;
    private final Col[] cols;
    private final float[] widths;
    private final float[] offsets;
    private final float lineHeight;
    private String[] titles;

    Table(Pdf pdf, float x, float width, float fontSize, Col... cols) {
        this.pdf = pdf;
        this.x = x;
        this.fontSize = fontSize;
        this.cols = cols;
        this.lineHeight = fontSize * 1.25f;
        widths = new float[cols.length];
        offsets = new float[cols.length];
        float total = 0;
        for (Col col : cols) {
            total += col.weight();
        }
        float offset = 0;
        for (int i = 0; i < cols.length; i++) {
            widths[i] = width * cols[i].weight() / total;
            offsets[i] = offset;
            offset += widths[i];
        }
    }

    private float width() {
        return offsets[cols.length - 1] + widths[cols.length - 1];
    }

    /** Dessine la ligne de titres ; elle sera répétée en haut de chaque page suivante. */
    Table header(String... columnTitles) throws IOException {
        titles = columnTitles;
        pdf.ensure(titleHeight() + 3 * lineHeight);
        drawTitles();
        return this;
    }

    private float titleHeight() throws IOException {
        int lines = 1;
        for (int i = 0; i < cols.length; i++) {
            lines = Math.max(lines, pdf.wrap(title(i), pdf.bold, fontSize - 0.5f, widths[i] - 2 * PAD_X).size());
        }
        return lines * lineHeight + 2 * PAD_Y;
    }

    private String title(int column) {
        return column < titles.length && titles[column] != null ? titles[column] : "";
    }

    private void drawTitles() throws IOException {
        float height = titleHeight();
        float top = pdf.y();
        pdf.fill(x, top, width(), height, Rgb.SUBTLE);
        for (int i = 0; i < cols.length; i++) {
            List<String> lines = pdf.wrap(title(i), pdf.bold, fontSize - 0.5f, widths[i] - 2 * PAD_X);
            // les titres sont calés en bas de la ligne, comme dans un tableur
            float baseline = top - height + PAD_Y + (lines.size() - 1) * lineHeight + fontSize * 0.28f;
            for (String line : lines) {
                place(line, i, baseline, pdf.bold, fontSize - 0.5f, Rgb.MUTED, 0);
                baseline -= lineHeight;
            }
        }
        pdf.line(x, top - height, x + width(), top - height, Rgb.BORDER, 0.6f);
        pdf.skip(height);
    }

    /** Ajoute une ligne ; chaque cellule est un texte, une {@link Cell}, ou {@code null} pour une cellule vide. */
    void row(Look look, Object... cells) throws IOException {
        List<List<String>> texts = new ArrayList<>();
        float[] sizes = new float[cols.length];
        int lines = 1;
        for (int i = 0; i < cols.length; i++) {
            Object content = i < cells.length ? cells[i] : null;
            String text = content instanceof Cell cell ? cell.text() : content == null ? "" : content.toString();
            PDFont font = font(look, content);
            float room = widths[i] - 2 * PAD_X - (i == 0 ? look.indent() : 0);
            sizes[i] = fontSize;
            if (cols[i].align() == Align.RIGHT) {
                // un nombre un peu trop large est écrit plus petit plutôt que coupé
                float needed = pdf.width(text, font, fontSize);
                if (needed > room) {
                    sizes[i] = Math.max(fontSize * MIN_SHRINK, fontSize * room / needed);
                }
            }
            List<String> shown = cols[i].wrap() ? pdf.wrap(text, font, fontSize, room) : List.of(pdf.fit(text, font, sizes[i], room));
            texts.add(shown);
            lines = Math.max(lines, shown.size());
        }
        float height = lines * lineHeight + 2 * PAD_Y;
        newPageIfNeeded(height);
        float top = pdf.y();
        if (look.fill() != null) {
            pdf.fill(x, top, width(), height, look.fill());
        }
        for (int i = 0; i < cols.length; i++) {
            Object content = i < cells.length ? cells[i] : null;
            Rgb ink = content instanceof Cell cell && cell.ink() != null ? cell.ink() : look.ink();
            if (content instanceof Cell cell && cell.bar() >= 0) {
                pdf.fill(x + offsets[i] + 2, top - 2, (float) ((widths[i] - 4) * cell.bar()), height - 4, cell.barColor());
            }
            float baseline = top - PAD_Y - fontSize * 0.86f;
            for (String line : texts.get(i)) {
                place(line, i, baseline, font(look, content), sizes[i], ink, i == 0 ? look.indent() : 0);
                baseline -= lineHeight;
            }
        }
        pdf.line(x, top - height, x + width(), top - height, Rgb.BORDER, 0.3f);
        pdf.skip(height);
    }

    /** Ligne d'un seul texte, sur toute la largeur. */
    void band(Look look, String text) throws IOException {
        float height = lineHeight + 2 * PAD_Y;
        newPageIfNeeded(height + lineHeight + 2 * PAD_Y); // un bandeau ne reste pas seul en bas de page
        float top = pdf.y();
        if (look.fill() != null) {
            pdf.fill(x, top, width(), height, look.fill());
        }
        PDFont font = look.bold() ? pdf.bold : look.italic() ? pdf.italic : pdf.regular;
        pdf.text(pdf.fit(text, font, fontSize, width() - 2 * PAD_X - look.indent()), x + PAD_X + look.indent(),
                top - PAD_Y - fontSize * 0.86f, font, fontSize, look.ink());
        pdf.skip(height);
    }

    void gap(float height) {
        pdf.skip(height);
    }

    private void newPageIfNeeded(float height) throws IOException {
        if (pdf.ensure(height) && titles != null) {
            drawTitles();
        }
    }

    private PDFont font(Look look, Object content) {
        boolean bold = content instanceof Cell cell && cell.bold() != null ? cell.bold() : look.bold();
        return bold ? pdf.bold : look.italic() ? pdf.italic : pdf.regular;
    }

    private void place(String text, int column, float baseline, PDFont font, float size, Rgb ink, float indent) throws IOException {
        float left = x + offsets[column] + PAD_X + indent;
        float right = x + offsets[column] + widths[column] - PAD_X;
        switch (cols[column].align()) {
            case LEFT -> pdf.text(text, left, baseline, font, size, ink);
            case RIGHT -> pdf.textRight(text, right, baseline, font, size, ink);
            case CENTER -> pdf.textCenter(text, (left + right) / 2, baseline, font, size, ink);
        }
    }
}
