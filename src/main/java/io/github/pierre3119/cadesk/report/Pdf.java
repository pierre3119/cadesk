package io.github.pierre3119.cadesk.report;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName;

/**
 * Feuille de dessin du rapport : pages A4 en paysage, remplies de haut en bas. Les coordonnées sont
 * celles du PDF (origine en bas à gauche, en points) ; {@link #y()} est la hauteur où s'écrit la suite.
 *
 * <p>Les polices sont les polices standard du format PDF (Helvetica), que tout lecteur possède : rien
 * n'est à embarquer. Elles ne connaissent que le jeu de caractères d'Europe de l'Ouest ; les quelques
 * signes qui en sortent (flèches, signe moins typographique, espaces fines) sont remplacés.
 */
final class Pdf {

    static final float MARGIN = 34;
    private static final float FOOTER = 20;
    private static final float FOOTER_SIZE = 7.5f;
    private static final Map<Character, Boolean> ENCODABLE = new ConcurrentHashMap<>();

    final PDFont regular = new PDType1Font(FontName.HELVETICA);
    final PDFont bold = new PDType1Font(FontName.HELVETICA_BOLD);
    final PDFont italic = new PDType1Font(FontName.HELVETICA_OBLIQUE);

    private final PDDocument document = new PDDocument();
    private final PDRectangle size = new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth());
    private PDPageContentStream stream;
    private float y;

    Pdf() throws IOException {
        newPage();
    }

    // ------------------------------------------------------------------ pages

    float left() {
        return MARGIN;
    }

    float right() {
        return size.getWidth() - MARGIN;
    }

    float width() {
        return right() - left();
    }

    float top() {
        return size.getHeight() - MARGIN;
    }

    float bottom() {
        return MARGIN + FOOTER;
    }

    float y() {
        return y;
    }

    void y(float value) {
        y = value;
    }

    void skip(float height) {
        y -= height;
    }

    void newPage() throws IOException {
        if (stream != null) {
            stream.close();
        }
        PDPage page = new PDPage(size);
        document.addPage(page);
        stream = new PDPageContentStream(document, page);
        y = top();
    }

    /** Passe à la page suivante s'il ne reste pas {@code height} points sur celle-ci ; renvoie vrai si la page a changé. */
    boolean ensure(float height) throws IOException {
        if (y - height < bottom() && y < top()) {
            newPage();
            return true;
        }
        return false;
    }

    /** Le flux de la page courante, pour les tracés libres des graphiques. */
    PDPageContentStream stream() {
        return stream;
    }

    // ------------------------------------------------------------------ tracés

    /** Rectangle plein dont le coin supérieur gauche est ({@code x}, {@code top}). */
    void fill(float x, float top, float width, float height, Rgb color) throws IOException {
        if (width <= 0 || height <= 0) {
            return;
        }
        stream.setNonStrokingColor(color.r(), color.g(), color.b());
        stream.addRect(x, top - height, width, height);
        stream.fill();
    }

    void line(float x1, float y1, float x2, float y2, Rgb color, float thickness) throws IOException {
        stream.setStrokingColor(color.r(), color.g(), color.b());
        stream.setLineWidth(thickness);
        stream.moveTo(x1, y1);
        stream.lineTo(x2, y2);
        stream.stroke();
    }

    // ------------------------------------------------------------------ texte

    void text(String text, float x, float baseline, PDFont font, float fontSize, Rgb color) throws IOException {
        String shown = clean(text);
        if (shown.isEmpty()) {
            return;
        }
        stream.beginText();
        stream.setNonStrokingColor(color.r(), color.g(), color.b());
        stream.setFont(font, fontSize);
        stream.newLineAtOffset(x, baseline);
        stream.showText(shown);
        stream.endText();
    }

    void textRight(String text, float right, float baseline, PDFont font, float fontSize, Rgb color) throws IOException {
        text(text, right - width(text, font, fontSize), baseline, font, fontSize, color);
    }

    void textCenter(String text, float center, float baseline, PDFont font, float fontSize, Rgb color) throws IOException {
        text(text, center - width(text, font, fontSize) / 2, baseline, font, fontSize, color);
    }

    float width(String text, PDFont font, float fontSize) throws IOException {
        return font.getStringWidth(clean(text)) / 1000 * fontSize;
    }

    /** Le texte, raccourci et terminé par « … » s'il dépasse {@code max} points. */
    String fit(String text, PDFont font, float fontSize, float max) throws IOException {
        String shown = clean(text);
        if (width(shown, font, fontSize) <= max) {
            return shown;
        }
        int length = shown.length();
        while (length > 0 && width(shown.substring(0, length).stripTrailing() + "…", font, fontSize) > max) {
            length--;
        }
        return length == 0 ? "" : shown.substring(0, length).stripTrailing() + "…";
    }

    /** Le texte découpé en lignes d'au plus {@code max} points ; un mot plus long que la ligne est raccourci. */
    List<String> wrap(String text, PDFont font, float fontSize, float max) throws IOException {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : clean(text).split(" ")) {
            if (word.isEmpty()) {
                continue;
            }
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (width(candidate, font, fontSize) <= max) {
                line.setLength(0);
                line.append(candidate);
            } else {
                if (!line.isEmpty()) {
                    lines.add(line.toString());
                }
                line.setLength(0);
                line.append(fit(word, font, fontSize, max));
            }
        }
        if (!line.isEmpty() || lines.isEmpty()) {
            lines.add(line.toString());
        }
        return lines;
    }

    /** Ne garde que ce que les polices standard savent écrire. */
    String clean(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder shown = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            switch (ch) {
                case '\u00a0', '\u202f', '\u2009', '\t', '\n', '\r' -> shown.append(' ');
                case '\u2212' -> shown.append('\u2013');
                case '\u2192' -> shown.append('\u203a');
                case '\u00ad' -> shown.append('-');
                default -> shown.append(Character.isISOControl(ch) ? ' ' : encodable(ch) ? ch : '?');
            }
        }
        return shown.toString();
    }

    private boolean encodable(char ch) {
        return ENCODABLE.computeIfAbsent(ch, c -> {
            try {
                regular.getStringWidth(String.valueOf(c));
                return true;
            } catch (IOException | IllegalArgumentException e) {
                return false;
            }
        });
    }

    // ------------------------------------------------------------------ enregistrement

    /** Numérote les pages, écrit {@code footer} au bas de chacune, puis enregistre et ferme le document. */
    void save(Path file, String footer) throws IOException {
        try (document) {
            stream.close();
            int count = document.getNumberOfPages();
            for (int i = 0; i < count; i++) {
                try (PDPageContentStream page = new PDPageContentStream(document, document.getPage(i), AppendMode.APPEND, true, true)) {
                    stream = page;
                    float baseline = MARGIN + 2;
                    line(left(), baseline + FOOTER_SIZE + 4, right(), baseline + FOOTER_SIZE + 4, Rgb.BORDER, 0.5f);
                    text(footer, left(), baseline, regular, FOOTER_SIZE, Rgb.MUTED);
                    textRight("Page " + (i + 1) + " / " + count, right(), baseline, regular, FOOTER_SIZE, Rgb.MUTED);
                }
            }
            document.save(file.toFile());
        }
    }

    /** Abandonne le document sans l'enregistrer. */
    void discard() {
        try {
            if (stream != null) {
                stream.close();
            }
            document.close();
        } catch (IOException ignored) {
            // rien n'a été écrit sur le disque
        }
    }
}
