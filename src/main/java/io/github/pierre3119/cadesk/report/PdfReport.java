package io.github.pierre3119.cadesk.report;

import io.github.pierre3119.cadesk.analysis.Analysis;
import io.github.pierre3119.cadesk.analysis.Analysis.PosteStat;
import io.github.pierre3119.cadesk.analysis.Insights;
import io.github.pierre3119.cadesk.analysis.Insights.Capacity;
import io.github.pierre3119.cadesk.analysis.Period;
import io.github.pierre3119.cadesk.analysis.Scale;
import io.github.pierre3119.cadesk.domain.Account;
import io.github.pierre3119.cadesk.domain.Category;
import io.github.pierre3119.cadesk.domain.Category.IncomeKind;
import io.github.pierre3119.cadesk.domain.ClassifiedOperation;
import io.github.pierre3119.cadesk.domain.Money;
import io.github.pierre3119.cadesk.domain.Nature;
import io.github.pierre3119.cadesk.report.Table.Cell;
import io.github.pierre3119.cadesk.report.Table.Col;
import io.github.pierre3119.cadesk.report.Table.Look;
import io.github.pierre3119.cadesk.ui.Fmt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;

import org.apache.pdfbox.pdmodel.PDPageContentStream;

/**
 * Rapport PDF d'une période : il reprend, écran par écran, les chiffres et les graphiques de
 * l'application — synthèse, bilan mensuel, charges fixes, dépenses variables, économies — et, si on le
 * demande, la liste des opérations. Tout est tiré du {@link Analysis bilan} de la période choisie.
 */
public final class PdfReport {

    /**
     * Ce que le rapport présente.
     *
     * @param profile        nom du profil
     * @param account        le compte analysé, ou {@code null}
     * @param period         la période choisie à l'écran
     * @param analysis       le bilan de cette période
     * @param withOperations vrai pour terminer par la liste des opérations
     */
    public record Content(String profile, Account account, Period period, Analysis analysis, boolean withOperations,
            LocalDateTime generatedAt) {
    }

    private record Tile(String caption, String value, String note, Rgb fill, Rgb ink) {
    }

    private record Slice(String label, long value, Rgb color) {
    }

    /** Une ligne du bilan mensuel, avant sa mise en colonnes. */
    private record GridRow(Look look, String label, ToLongFunction<YearMonth> monthly, long total, boolean average,
            String part, boolean signed) {
    }

    private static final float GAP = 12;
    private static final int TOP_CATEGORIES = 12;
    private static final int TOP_POSTES = 30;
    private static final int MONTHS_PER_GRID = 12;
    private static final float DONUT_RADIUS = 56;
    private static final float DONUT_HOLE = 33;
    private static final float CHART_HEIGHT = 150;
    private static final float CHART_MIN_HEIGHT = 100;
    /** Ce qui entoure le tracé du graphique mensuel : son titre au-dessus, les mois et la légende en dessous. */
    private static final float CHART_FRAME = 60;
    private static final Look WHITE_ON_INK = Look.filled(Rgb.INK, Rgb.WHITE, true);

    private final Pdf pdf;
    private final Content content;
    private final Analysis a;

    private PdfReport(Pdf pdf, Content content) {
        this.pdf = pdf;
        this.content = content;
        this.a = content.analysis();
    }

    /** Écrit le rapport ; le fichier n'apparaît à son emplacement qu'une fois complet. */
    public static void write(Path file, Content content) throws IOException {
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Pdf pdf = new Pdf();
        try {
            new PdfReport(pdf, content).build();
            pdf.save(temporary, "CaDesk  ·  " + content.profile() + "  ·  " + Fmt.period(content.period()));
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            pdf.discard();
            throw e;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void build() throws IOException {
        cover();
        if (a.isEmpty()) {
            pdf.text("Aucune opération sur cette période.", pdf.left(), pdf.y() - 12, pdf.italic, 10, Rgb.MUTED);
            return;
        }
        synthesis();
        monthly();
        fixed();
        variable();
        savings();
        if (content.withOperations()) {
            operations();
        }
    }

    // ------------------------------------------------------------------ couleurs des natures

    private static Rgb solid(Nature nature) {
        return Rgb.hex(switch (nature) {
            case REVENUS -> 0x1f8a3f;
            case FIXES -> 0x1f5fa8;
            case VARIABLES -> 0xdd7714;
            case EXCEPTIONNELLES -> 0x7b3fa0;
            case INTERNE -> 0x6e7781;
        });
    }

    private static Rgb ink(Nature nature) {
        return Rgb.hex(switch (nature) {
            case REVENUS -> 0x1a7f37;
            case FIXES -> 0x1f5fa8;
            case VARIABLES -> 0xc05f08;
            case EXCEPTIONNELLES -> 0x7b3fa0;
            case INTERNE -> 0x57606a;
        });
    }

    private static Rgb pale(Nature nature) {
        return Rgb.hex(switch (nature) {
            case REVENUS -> 0xeaf6ec;
            case FIXES -> 0xecf4fd;
            case VARIABLES -> 0xfff4e5;
            case EXCEPTIONNELLES -> 0xf6eefb;
            case INTERNE -> 0xf1f3f6;
        });
    }

    private static Rgb mid(Nature nature) {
        return Rgb.hex(switch (nature) {
            case REVENUS -> 0xcdeacf;
            case FIXES -> 0xc8dff8;
            case VARIABLES -> 0xffdfb6;
            case EXCEPTIONNELLES -> 0xe5d2f1;
            case INTERNE -> 0xdee3e9;
        });
    }

    private static Look tint(Nature nature) {
        return Look.filled(pale(nature), Rgb.TEXT, false);
    }

    private static Look group(Nature nature) {
        return Look.filled(mid(nature), ink(nature), true);
    }

    private static Look total(Nature nature) {
        return Look.filled(pale(nature), ink(nature), true);
    }

    private static Look grand(Nature nature) {
        return Look.filled(solid(nature), Rgb.WHITE, true);
    }

    // ------------------------------------------------------------------ en-tête, titres, tuiles

    private void cover() throws IOException {
        float top = pdf.y();
        pdf.text("Rapport des comptes", pdf.left(), top - 18, pdf.bold, 20, Rgb.INK);
        pdf.textRight("CaDesk", pdf.right(), top - 10, pdf.bold, 9, Rgb.MUTED);
        pdf.textRight("Édité le " + Fmt.dateTime(content.generatedAt()), pdf.right(), top - 21, pdf.regular, 8, Rgb.MUTED);
        pdf.text(Fmt.period(content.period()), pdf.left(), top - 36, pdf.bold, 11.5f, Rgb.TEXT);
        Account account = content.account();
        String who = "Profil : " + content.profile()
                + (account == null ? "" : "  ·  " + account.label() + " " + account.maskedNumber()
                        + (account.holder() == null || account.holder().isBlank() ? "" : " (" + account.holder() + ")"))
                + "  ·  " + Fmt.span(a);
        pdf.text(pdf.fit(who, pdf.regular, 8.5f, pdf.width()), pdf.left(), top - 49, pdf.regular, 8.5f, Rgb.MUTED);
        pdf.line(pdf.left(), top - 56, pdf.right(), top - 56, Rgb.BORDER, 0.8f);
        pdf.y(top - 66);
    }

    private void section(String title, boolean onNewPage) throws IOException {
        if (onNewPage) {
            pdf.newPage();
        }
        float top = pdf.y();
        pdf.text(title, pdf.left(), top - 13, pdf.bold, 14, Rgb.INK);
        pdf.line(pdf.left(), top - 19, pdf.right(), top - 19, Rgb.ACCENT, 1.2f);
        pdf.y(top - 30);
    }

    private void subtitle(String title) throws IOException {
        pdf.ensure(70);
        pdf.text(title, pdf.left(), pdf.y() - 12, pdf.bold, 10.5f, Rgb.TEXT);
        pdf.skip(19);
    }

    /** Remarque en italique, sur toute la largeur. */
    private void note(String text, Rgb color) throws IOException {
        List<String> lines = pdf.wrap(text, pdf.italic, 8, pdf.width());
        pdf.ensure(lines.size() * 10.5f + 6);
        pdf.skip(4);
        for (String line : lines) {
            pdf.text(line, pdf.left(), pdf.y() - 8, pdf.italic, 8, color);
            pdf.skip(10.5f);
        }
    }

    private void tiles(Tile... tiles) throws IOException {
        float gap = 8;
        float width = (pdf.width() - gap * (tiles.length - 1)) / tiles.length;
        float height = 42;
        pdf.ensure(height + GAP);
        float top = pdf.y();
        for (int i = 0; i < tiles.length; i++) {
            Tile tile = tiles[i];
            float x = pdf.left() + i * (width + gap);
            pdf.fill(x, top, width, height, tile.fill());
            pdf.text(pdf.fit(tile.caption(), pdf.bold, 7.2f, width - 14), x + 7, top - 11, pdf.bold, 7.2f, Rgb.MUTED);
            pdf.text(pdf.fit(tile.value(), pdf.bold, 12.5f, width - 14), x + 7, top - 26, pdf.bold, 12.5f, tile.ink());
            pdf.text(pdf.fit(tile.note(), pdf.regular, 7.2f, width - 14), x + 7, top - 36.5f, pdf.regular, 7.2f, Rgb.MUTED);
        }
        pdf.y(top - height - GAP);
    }

    private static Tile tile(String caption, String value, String note, Nature nature) {
        return new Tile(caption, value, note, pale(nature), ink(nature));
    }

    private static Tile soft(String caption, String value, String note) {
        return new Tile(caption, value, note, Rgb.SUBTLE, Rgb.TEXT);
    }

    private Table table(float fontSize, Col... cols) {
        return new Table(pdf, pdf.left(), pdf.width(), fontSize, cols);
    }

    private String over() {
        return Fmt.overPeriod(a);
    }

    // ------------------------------------------------------------------ synthèse

    private void synthesis() throws IOException {
        section("Synthèse", false);
        long result = a.resultat();
        tiles(kpi(Nature.REVENUS, a.revenus()), kpi(Nature.FIXES, a.fixes()), kpi(Nature.VARIABLES, a.variables()),
                kpi(Nature.EXCEPTIONNELLES, a.exceptionnelles()),
                new Tile("Résultat de la période", Fmt.eurosSigned(result), "soit " + Fmt.eurosSigned(a.perMonth(result)) + " par mois",
                        result < 0 ? Rgb.hex(0xffebe9) : pale(Nature.REVENUS), result < 0 ? Rgb.OUT : Rgb.IN));

        pdf.ensure(200);
        float top = pdf.y();
        float leftWidth = pdf.width() * 0.6f;
        pdf.text("Vue d'ensemble", pdf.left(), top - 12, pdf.bold, 10.5f, Rgb.TEXT);
        pdf.text("Répartition des dépenses", pdf.left() + leftWidth + 24, top - 12, pdf.bold, 10.5f, Rgb.TEXT);
        pdf.y(top - 19);
        Table overview = new Table(pdf, pdf.left(), leftWidth, 8.5f, Col.text(200), Col.number(86), Col.number(78), Col.number(66));
        overview.header("", over(), "Par mois", "% des revenus");
        overviewLine(overview, tint(Nature.REVENUS), ink(Nature.REVENUS), Nature.REVENUS.label(), a.revenus(), false);
        overviewLine(overview, tint(Nature.FIXES), ink(Nature.FIXES), Nature.FIXES.label(), a.fixes(), false);
        overviewLine(overview, tint(Nature.VARIABLES), ink(Nature.VARIABLES), Nature.VARIABLES.label(), a.variables(), false);
        overviewLine(overview, tint(Nature.EXCEPTIONNELLES), ink(Nature.EXCEPTIONNELLES), Nature.EXCEPTIONNELLES.label(),
                a.exceptionnelles(), false);
        overviewLine(overview, Look.STRONG, Rgb.TEXT, "Résultat (revenus – dépenses)", a.resultat(), true);
        overviewLine(overview, tint(Nature.INTERNE), ink(Nature.INTERNE), "Reçu de mes autres comptes", a.interneIn(), false);
        overviewLine(overview, tint(Nature.INTERNE), ink(Nature.INTERNE), "Versé sur mes autres comptes", a.interneOut(), false);
        overviewLine(overview, Look.STRONG, Rgb.TEXT, "Variation du solde du compte", a.solde(), true);
        boolean consistent = a.control() == 0;
        pdf.text(consistent ? "Contrôle : le bilan retombe au centime près sur le total des opérations (crédits – débits)."
                : "Contrôle : écart de " + Money.formatSigned(a.control()) + " entre le bilan et le total des opérations.",
                pdf.left(), pdf.y() - 11, pdf.italic, 7.8f, consistent ? Rgb.IN : Rgb.OUT);
        float afterTable = pdf.y() - 14;

        donut(pdf.left() + leftWidth + 24, top - 26, List.of(
                new Slice(Nature.FIXES.label(), a.fixes(), solid(Nature.FIXES)),
                new Slice(Nature.VARIABLES.label(), a.variables(), solid(Nature.VARIABLES)),
                new Slice(Nature.EXCEPTIONNELLES.label(), a.exceptionnelles(), solid(Nature.EXCEPTIONNELLES))));
        pdf.y(Math.min(afterTable, top - 26 - 2 * DONUT_RADIUS) - 6);

        monthlyChart();
        topCategories();
    }

    private Tile kpi(Nature nature, long total) {
        return tile(nature.label(), Fmt.euros(total), "soit " + Fmt.euros(a.perMonth(total)) + " par mois", nature);
    }

    private void overviewLine(Table table, Look look, Rgb labelInk, String label, long total, boolean signed) throws IOException {
        Cell name = new Cell(label, labelInk, true, -1, null);
        if (signed) {
            table.row(look, name, Cell.signed(Money.formatSigned(total), total), Cell.signed(Money.formatSigned(a.perMonth(total)), total),
                    Fmt.percent(total, a.revenus()));
        } else {
            table.row(look, name, Money.format(total), Money.format(a.perMonth(total)), Fmt.percent(total, a.revenus()));
        }
    }

    private void topCategories() throws IOException {
        subtitle("Les " + TOP_CATEGORIES + " premiers postes de dépenses");
        Table table = table(8.5f, Col.text(230), Col.text(170), Col.number(90), Col.number(80), Col.text(150)).header("Poste", "Nature", over(),
                "Par mois", "Part des dépenses");
        List<Category> categories = a.spendingCategories().stream().limit(TOP_CATEGORIES).toList();
        long highest = categories.isEmpty() ? 0 : a.total(categories.getFirst());
        int rank = 1;
        for (Category category : categories) {
            long total = a.total(category);
            Nature nature = category.nature();
            table.row(tint(nature), new Cell(rank++ + ".  " + category.name(), ink(nature), true, -1, null),
                    Cell.ink(nature.label(), ink(nature)), Cell.bold(Money.format(total)), Money.format(a.perMonth(total)),
                    Cell.bar(Fmt.percent(total, a.depenses()), highest == 0 ? 0 : (double) total / highest, mid(nature)));
        }
        if (categories.isEmpty()) {
            table.band(Look.EMPTY, "Aucune dépense sur cette période.");
        }
    }

    // ------------------------------------------------------------------ graphiques

    /** Anneau des dépenses et sa légende ; ({@code x}, {@code top}) est le coin supérieur gauche de l'ensemble. */
    private void donut(float x, float top, List<Slice> slices) throws IOException {
        List<Slice> shown = slices.stream().filter(s -> s.value() > 0).toList();
        long total = shown.stream().mapToLong(Slice::value).sum();
        float cx = x + DONUT_RADIUS;
        float cy = top - DONUT_RADIUS;
        float legendX = x + 2 * DONUT_RADIUS + 18;
        if (shown.isEmpty()) {
            pdf.text("Aucune dépense sur la période.", x, cy, pdf.italic, 8.5f, Rgb.MUTED);
            return;
        }
        double gap = shown.size() > 1 ? 1.6 : 0;
        double start = 90; // midi, puis dans le sens des aiguilles d'une montre
        float legendY = cy + shown.size() * 13 - 4;
        for (Slice slice : shown) {
            double sweep = 360.0 * slice.value() / total;
            sector(cx, cy, start - gap / 2, -(sweep - gap), slice.color());
            if (sweep >= 18) { // en dessous, le pourcentage ne tient pas dans la tranche
                double middle = Math.toRadians(start - sweep / 2);
                float radius = (DONUT_RADIUS + DONUT_HOLE) / 2;
                pdf.textCenter(Math.round(100.0 * slice.value() / total) + " %", cx + (float) (radius * Math.cos(middle)),
                        cy + (float) (radius * Math.sin(middle)) - 2.8f, pdf.bold, 8, Rgb.WHITE);
            }
            start -= sweep;

            pdf.fill(legendX, legendY + 7, 7, 7, slice.color());
            pdf.text(slice.label(), legendX + 12, legendY + 0.8f, pdf.regular, 8.5f, Rgb.TEXT);
            pdf.text(Money.format(slice.value()) + "  ·  " + Fmt.percent(slice.value(), total), legendX + 12, legendY - 9.5f, pdf.regular,
                    7.5f, Rgb.MUTED);
            legendY -= 26;
        }
        pdf.textCenter("Dépenses", cx, cy + 4, pdf.regular, 7.5f, Rgb.MUTED);
        pdf.textCenter(Fmt.euros(total), cx, cy - 8, pdf.bold, 10.5f, Rgb.TEXT);
    }

    /** Secteur d'anneau plein, de l'angle {@code startDegrees} sur {@code sweepDegrees} (négatif : sens horaire). */
    private void sector(float cx, float cy, double startDegrees, double sweepDegrees, Rgb color) throws IOException {
        PDPageContentStream stream = pdf.stream();
        double from = Math.toRadians(startDegrees);
        double to = Math.toRadians(startDegrees + sweepDegrees);
        stream.setNonStrokingColor(color.r(), color.g(), color.b());
        stream.moveTo(cx + (float) (DONUT_RADIUS * Math.cos(from)), cy + (float) (DONUT_RADIUS * Math.sin(from)));
        arc(stream, cx, cy, DONUT_RADIUS, from, to);
        stream.lineTo(cx + (float) (DONUT_HOLE * Math.cos(to)), cy + (float) (DONUT_HOLE * Math.sin(to)));
        arc(stream, cx, cy, DONUT_HOLE, to, from);
        stream.closePath();
        stream.fill();
    }

    /** Prolonge le tracé par un arc de cercle, approché par des courbes de Bézier d'un quart de tour au plus. */
    private static void arc(PDPageContentStream stream, float cx, float cy, float radius, double from, double to) throws IOException {
        int pieces = Math.max(1, (int) Math.ceil(Math.abs(to - from) / (Math.PI / 2)));
        double step = (to - from) / pieces;
        double k = 4.0 / 3.0 * Math.tan(step / 4);
        for (int i = 0; i < pieces; i++) {
            double a0 = from + i * step;
            double a1 = a0 + step;
            stream.curveTo(
                    cx + (float) (radius * (Math.cos(a0) - k * Math.sin(a0))), cy + (float) (radius * (Math.sin(a0) + k * Math.cos(a0))),
                    cx + (float) (radius * (Math.cos(a1) + k * Math.sin(a1))), cy + (float) (radius * (Math.sin(a1) - k * Math.cos(a1))),
                    cx + (float) (radius * Math.cos(a1)), cy + (float) (radius * Math.sin(a1)));
        }
    }

    /**
     * Dépenses de chaque mois en barres empilées, revenus en ligne, sur la même échelle. Le graphique
     * prend la place qui reste sur la page si elle suffit, sinon il passe à la page suivante.
     */
    private void monthlyChart() throws IOException {
        float room = pdf.y() - pdf.bottom() - CHART_FRAME;
        if (room < CHART_MIN_HEIGHT) {
            pdf.newPage();
            room = CHART_HEIGHT;
        }
        float height = Math.min(CHART_HEIGHT, room);
        pdf.text("Mois par mois : dépenses (barres) et revenus (ligne)", pdf.left(), pdf.y() - 12, pdf.bold, 10.5f, Rgb.TEXT);
        pdf.skip(22);
        List<YearMonth> months = a.months();
        float axis = 52;
        float plotLeft = pdf.left() + axis;
        float plotWidth = pdf.width() - axis - 6;
        float plotTop = pdf.y();
        float base = plotTop - height;
        long highest = 100;
        for (YearMonth m : months) {
            highest = Math.max(highest, Math.max(spending(m), a.amount(Nature.REVENUS, m)));
        }
        double step = Scale.niceStep(highest / (height < 130 ? 4.0 : 5.0));
        double max = Math.ceil(highest / step) * step;
        double scale = height / max;
        for (double value = 0; value <= max + step / 2; value += step) {
            float y = base + (float) (value * scale);
            pdf.line(plotLeft, y, plotLeft + plotWidth, y, value == 0 ? Rgb.MUTED : Rgb.BORDER, value == 0 ? 0.6f : 0.3f);
            pdf.textRight(Fmt.euros(value), plotLeft - 6, y - 2.5f, pdf.regular, 7, Rgb.MUTED);
        }
        float slot = plotWidth / months.size();
        float barWidth = Math.min(40, slot * 0.62f);
        int labelEvery = (int) Math.ceil(34 / slot);
        List<float[]> points = new ArrayList<>();
        for (int i = 0; i < months.size(); i++) {
            YearMonth m = months.get(i);
            float middle = plotLeft + slot * (i + 0.5f);
            float y = base;
            for (Nature nature : List.of(Nature.FIXES, Nature.VARIABLES, Nature.EXCEPTIONNELLES)) {
                float segment = (float) (Math.max(0, a.amount(nature, m)) * scale);
                pdf.fill(middle - barWidth / 2, y + segment, barWidth, segment, solid(nature));
                y += segment;
            }
            points.add(new float[] {middle, base + (float) (Math.max(0, a.amount(Nature.REVENUS, m)) * scale)});
            if (i % labelEvery == 0) {
                pdf.textCenter(Fmt.monthShort(m), middle, base - 10, pdf.regular, 7, Rgb.MUTED);
            }
        }
        PDPageContentStream stream = pdf.stream();
        Rgb line = solid(Nature.REVENUS);
        stream.setStrokingColor(line.r(), line.g(), line.b());
        stream.setLineWidth(1.6f);
        stream.setLineJoinStyle(1);
        stream.moveTo(points.getFirst()[0], points.getFirst()[1]);
        for (float[] point : points) {
            stream.lineTo(point[0], point[1]);
        }
        stream.stroke();
        for (float[] point : points) {
            stream.setNonStrokingColor(1f, 1f, 1f);
            stream.setLineWidth(1.3f);
            stream.moveTo(point[0] + 2.4f, point[1]);
            arc(stream, point[0], point[1], 2.4f, 0, 2 * Math.PI);
            stream.closePath();
            stream.fillAndStroke();
        }
        pdf.y(base - 18);

        float x = pdf.left() + axis;
        for (Nature nature : List.of(Nature.FIXES, Nature.VARIABLES, Nature.EXCEPTIONNELLES)) {
            pdf.fill(x, pdf.y() - 1.5f, 7, 7, solid(nature));
            pdf.text(nature.label(), x + 11, pdf.y() - 8, pdf.regular, 8, Rgb.TEXT);
            x += 11 + pdf.width(nature.label(), pdf.regular, 8) + 20;
        }
        pdf.line(x, pdf.y() - 5, x + 16, pdf.y() - 5, line, 1.6f);
        pdf.text(Nature.REVENUS.label(), x + 21, pdf.y() - 8, pdf.regular, 8, Rgb.TEXT);
        pdf.skip(10 + GAP / 2);
    }

    private long spending(YearMonth month) {
        return Math.max(0, a.amount(Nature.FIXES, month)) + Math.max(0, a.amount(Nature.VARIABLES, month))
                + Math.max(0, a.amount(Nature.EXCEPTIONNELLES, month));
    }

    // ------------------------------------------------------------------ bilan mensuel

    private void monthly() throws IOException {
        section("Bilan mensuel", true);
        List<YearMonth> months = a.months();
        long in = a.revenus() + a.interneIn();
        long out = a.depenses() + a.interneOut();
        List<GridRow> rows = new ArrayList<>();

        rows.add(band("ENTRÉES  –  ce qui rentre", grand(Nature.REVENUS)));
        group(rows, Nature.REVENUS, Nature.REVENUS.label(), a.categories(Nature.REVENUS), "Total revenus et remboursements", in, true);
        group(rows, Nature.INTERNE, "Virements depuis mes autres comptes", a.categories(Nature.INTERNE, true),
                "Total reçu de mes autres comptes", in, false);
        rows.add(new GridRow(grand(Nature.REVENUS), "TOTAL DES ENTRÉES",
                m -> a.amount(Nature.REVENUS, m) + a.amount(Nature.INTERNE, true, m), in, true, Fmt.percent(in, in), false));
        rows.add(null);

        Look outLook = Look.filled(Rgb.OUT, Rgb.WHITE, true);
        rows.add(band("SORTIES  –  ce qui sort", outLook));
        group(rows, Nature.FIXES, Nature.FIXES.label(), a.categories(Nature.FIXES), "Total charges fixes", out, true);
        group(rows, Nature.VARIABLES, Nature.VARIABLES.label(), a.categories(Nature.VARIABLES), "Total dépenses variables", out, true);
        group(rows, Nature.EXCEPTIONNELLES, Nature.EXCEPTIONNELLES.label(), a.categories(Nature.EXCEPTIONNELLES),
                "Total dépenses exceptionnelles", out, true);
        group(rows, Nature.INTERNE, "Épargne et virements vers mes autres comptes", a.categories(Nature.INTERNE, false),
                "Total versé sur mes autres comptes", out, true);
        rows.add(new GridRow(outLook, "TOTAL DES SORTIES", m -> a.depenses(m) + a.amount(Nature.INTERNE, false, m), out, true,
                Fmt.percent(out, out), false));
        rows.add(null);

        rows.add(new GridRow(Look.STRONG, "SOLDE DU MOIS  (entrées – sorties)", a::solde, a.solde(), true, "", true));
        List<Long> cumulative = new ArrayList<>();
        long running = 0;
        for (YearMonth m : months) {
            running += a.solde(m);
            cumulative.add(running);
        }
        rows.add(new GridRow(Look.CALC, "Solde cumulé depuis le début de la période", m -> cumulative.get(months.indexOf(m)), a.solde(),
                false, "", true));
        rows.add(null);

        rows.add(band("POUR COMPRENDRE LE SOLDE", WHITE_ON_INK));
        rows.add(new GridRow(Look.CALC, "Revenus – dépenses (hors virements internes)", a::resultat, a.resultat(), true, "", true));
        rows.add(new GridRow(Look.CALC, "Virements nets avec mes autres comptes",
                m -> a.amount(Nature.INTERNE, true, m) - a.amount(Nature.INTERNE, false, m), a.interneIn() - a.interneOut(), true, "", true));
        rows.add(new GridRow(Look.CALC, "Contrôle : écart avec les opérations (0 attendu)", m -> a.solde(m) - a.net(m), a.control(), false,
                "", true));

        for (int start = 0; start < months.size(); start += MONTHS_PER_GRID) {
            List<YearMonth> shown = months.subList(start, Math.min(start + MONTHS_PER_GRID, months.size()));
            boolean last = start + MONTHS_PER_GRID >= months.size();
            if (start > 0) {
                pdf.newPage();
            }
            if (months.size() > MONTHS_PER_GRID) {
                pdf.text("De " + Fmt.month(shown.getFirst()) + " à " + Fmt.month(shown.getLast())
                        + (last ? ", et totaux de la période" : " (suite page suivante)"), pdf.left(), pdf.y() - 9, pdf.italic, 8.5f, Rgb.MUTED);
                pdf.skip(15);
            }
            grid(rows, shown, last);
        }
        note("Lecture : un virement entre mes comptes n'est ni un revenu ni une dépense ; il est isolé dans les lignes grises.", Rgb.MUTED);
    }

    private static GridRow band(String text, Look look) {
        return new GridRow(look, text, null, 0, false, null, false);
    }

    /** Les catégories d'une nature qui ont des opérations sur la période, suivies de leur total. */
    private void group(List<GridRow> rows, Nature nature, String title, List<Category> categories, String totalLabel, long sideTotal,
            boolean alwaysTotal) {
        List<Category> used = categories.stream().filter(c -> a.count(c) > 0).toList();
        if (used.isEmpty()) {
            return;
        }
        rows.add(band(title, group(nature)));
        for (Category category : used) {
            long total = a.total(category);
            rows.add(new GridRow(Look.ITEM, category.name(), m -> a.amount(category, m), total, true, Fmt.percent(total, sideTotal), false));
        }
        if (alwaysTotal || used.size() > 1) {
            long total = used.stream().mapToLong(a::total).sum();
            rows.add(new GridRow(total(nature), totalLabel, m -> used.stream().mapToLong(c -> a.amount(c, m)).sum(), total, true,
                    Fmt.percent(total, sideTotal), false));
        }
    }

    /** Dessine le bilan pour ces mois ; les colonnes de total n'accompagnent que la dernière tranche. */
    private void grid(List<GridRow> rows, List<YearMonth> months, boolean withTotals) throws IOException {
        float labelWidth = 164;
        float totalsWidth = withTotals ? 58 + 52 + 36 : 0;
        float monthWidth = Math.min(62, (pdf.width() - labelWidth - totalsWidth) / months.size());
        List<Col> cols = new ArrayList<>(List.of(Col.text(labelWidth)));
        List<String> titles = new ArrayList<>(List.of("Poste"));
        for (YearMonth month : months) {
            cols.add(Col.number(monthWidth));
            titles.add(Fmt.monthShort(month));
        }
        if (withTotals) {
            cols.addAll(List.of(Col.number(58), Col.number(52), Col.number(36)));
            titles.addAll(List.of("Total", "Par mois", "Part"));
        }
        Table table = new Table(pdf, pdf.left(), labelWidth + monthWidth * months.size() + totalsWidth, 6.9f, cols.toArray(Col[]::new));
        table.header(titles.toArray(String[]::new));
        for (GridRow row : rows) {
            if (row == null) {
                table.gap(5);
            } else if (row.monthly() == null) {
                table.band(row.look(), row.label());
            } else {
                List<Object> cells = new ArrayList<>(List.of(row.label()));
                for (YearMonth month : months) {
                    cells.add(amount(row.monthly().applyAsLong(month), row.signed()));
                }
                if (withTotals) {
                    Cell total = amount(row.total(), row.signed());
                    cells.add(new Cell(total.text(), total.ink(), true, -1, null));
                    cells.add(row.average() ? amount(a.perMonth(row.total()), row.signed()) : "");
                    cells.add(Cell.ink(row.part(), Rgb.WHITE.equals(row.look().ink()) ? Rgb.WHITE : Rgb.MUTED));
                }
                table.row(row.look(), cells.toArray());
            }
        }
    }

    private static Cell amount(long cents, boolean signed) {
        return signed ? Cell.signed(Fmt.amountSigned(cents), cents) : new Cell(Fmt.amount(cents), null, null, -1, null);
    }

    // ------------------------------------------------------------------ charges fixes

    private void fixed() throws IOException {
        section("Charges fixes", true);
        long total = a.fixes();
        long transfers = Insights.fixedTransfers(a);
        tiles(tile("Total des charges fixes", Money.format(total), over().toLowerCase(), Nature.FIXES),
                tile("En moyenne", Money.format(a.perMonth(total)), "par mois", Nature.FIXES),
                soft("dont virements", Money.format(a.perMonth(transfers)), "par mois : prêt, budgets…"),
                soft("dont factures", Money.format(a.perMonth(total - transfers)), "par mois : prélèvements, carte"),
                soft("Part des revenus", Fmt.percent(total, a.revenus()), "va aux charges fixes"));

        Table table = table(8, Col.text(210), Col.center(30), Col.center(96), Col.center(120), Col.number(70), Col.number(78),
                Col.number(72), Col.text(64)).header("Poste", "Nb", "Période observée", "Montants (évolution)", "Dernier montant", over(),
                        "Moyenne / mois", "Part");
        List<Category> categories = a.categories(Nature.FIXES).stream().filter(c -> a.count(c) > 0).toList();
        long highest = categories.stream().flatMap(c -> a.postes(c).stream()).mapToLong(PosteStat::total).max().orElse(0);
        for (Category category : categories) {
            long sum = a.total(category);
            table.row(group(Nature.FIXES), category.name(), String.valueOf(a.count(category)), "", "", "", Money.format(sum),
                    Money.format(a.perMonth(sum)), Fmt.percent(sum, total));
            for (PosteStat poste : a.postes(category)) {
                table.row(Look.ITEM, poste.poste(), String.valueOf(poste.count()), Cell.muted(Fmt.months(poste.first(), poste.last())),
                        Cell.muted(Fmt.steps(poste.amountSteps())), Money.format(poste.lastAmount()), Cell.bold(Money.format(poste.total())),
                        Money.format(a.perMonth(poste.total())),
                        Cell.bar(Fmt.percent(poste.total(), total), highest <= 0 ? 0 : (double) poste.total() / highest, mid(Nature.FIXES)));
            }
        }
        if (categories.isEmpty()) {
            table.band(Look.EMPTY, "Aucune charge fixe sur cette période.");
        } else {
            table.row(grand(Nature.FIXES), "TOTAL DES CHARGES FIXES", String.valueOf(a.count(Nature.FIXES)), "", "", "", Money.format(total),
                    Money.format(a.perMonth(total)), Fmt.percent(total, total));
        }
        note("Les montants sont comptés dans le sens de la dépense : un remboursement rangé dans une charge vient en déduction.", Rgb.MUTED);
    }

    // ------------------------------------------------------------------ dépenses variables

    private void variable() throws IOException {
        section("Dépenses variables", true);
        long total = a.variables();
        int count = a.count(Nature.VARIABLES);
        tiles(tile("Total des dépenses variables", Money.format(total), over().toLowerCase(), Nature.VARIABLES),
                tile("En moyenne", Money.format(a.perMonth(total)), "par mois", Nature.VARIABLES),
                soft("Opérations", Fmt.count(count), count == 0 ? "aucune dépense" : "montant moyen " + Money.format(total / count)),
                soft("Part des revenus", Fmt.percent(total, a.revenus()), "consacrée aux dépenses variables"));

        subtitle("Par catégorie");
        Table byCategory = table(8, Col.text(190), Col.center(30), Col.number(80), Col.number(74), Col.text(64), Col.number(70), Col.wrap(232))
                .header("Catégorie", "Nb", over(), "Moyenne / mois", "Part", "Montant moyen", "Principaux postes");
        List<Category> categories = a.categories(Nature.VARIABLES).stream().filter(c -> a.count(c) > 0)
                .sorted(Comparator.comparingLong((Category c) -> a.total(c)).reversed()).toList();
        long highest = categories.isEmpty() ? 0 : a.total(categories.getFirst());
        for (Category category : categories) {
            long sum = a.total(category);
            List<PosteStat> postes = a.postes(category);
            String main = postes.stream().limit(3).map(p -> p.poste() + " (" + Fmt.euros(p.total()) + ")").collect(Collectors.joining("  ·  "))
                    + (postes.size() > 3 ? "  ·  + " + (postes.size() - 3) + " autres" : "");
            byCategory.row(Look.PLAIN, Cell.bold(category.name()), String.valueOf(a.count(category)), Cell.bold(Money.format(sum)),
                    Money.format(a.perMonth(sum)),
                    Cell.bar(Fmt.percent(sum, total), highest <= 0 ? 0 : (double) sum / highest, mid(Nature.VARIABLES)),
                    Cell.muted(Money.format(sum / a.count(category))), Cell.muted(main));
        }
        if (categories.isEmpty()) {
            byCategory.band(Look.EMPTY, "Aucune dépense variable sur cette période.");
        } else {
            byCategory.row(grand(Nature.VARIABLES), "TOTAL DES DÉPENSES VARIABLES", String.valueOf(count), Money.format(total),
                    Money.format(a.perMonth(total)), Fmt.percent(total, total), Money.format(total / count), "");
        }

        List<ClassifiedOperation> unclassified = a.operations().stream().filter(ClassifiedOperation::isUnclassified).toList();
        if (!unclassified.isEmpty()) {
            long amount = unclassified.stream().filter(o -> !o.category().inflow()).mapToLong(ClassifiedOperation::amount).sum();
            note(Fmt.plural(unclassified.size(), "opération attend", "opérations attendent") + " encore une catégorie sur cette période ("
                    + Money.format(amount) + " de dépenses). Tant qu'elles ne sont pas classées, elles sont comptées dans « à classer ».",
                    Rgb.hex(0x9a6700));
        }

        pdf.skip(GAP);
        subtitle("Les " + TOP_POSTES + " plus gros postes de dépenses variables");
        Table top = table(8, Col.text(210), Col.center(30), Col.number(80), Col.number(74), Col.text(64), Col.number(70), Col.text(212))
                .header("Poste", "Nb", over(), "Moyenne / mois", "Part", "Montant moyen", "Catégorie");
        List<PosteStat> postes = a.postes(Nature.VARIABLES).stream().limit(TOP_POSTES).toList();
        long highestPoste = postes.isEmpty() ? 0 : postes.getFirst().total();
        for (PosteStat poste : postes) {
            top.row(Look.PLAIN, Cell.bold(poste.poste()), String.valueOf(poste.count()), Cell.bold(Money.format(poste.total())),
                    Money.format(a.perMonth(poste.total())),
                    Cell.bar(Fmt.percent(poste.total(), total), highestPoste <= 0 ? 0 : (double) poste.total() / highestPoste,
                            mid(Nature.VARIABLES)),
                    Cell.muted(Money.format(poste.total() / poste.count())), Cell.muted(poste.category().name()));
        }
        if (postes.isEmpty()) {
            top.band(Look.EMPTY, "Aucune dépense variable sur cette période.");
        }
    }

    // ------------------------------------------------------------------ économies

    private void savings() throws IOException {
        section("Économies", true);
        Capacity capacity = Insights.capacity(a);

        subtitle("1  ·  Capacité d'épargne constatée sur la période");
        Table steps = table(8, Col.text(250), Col.number(96), Col.number(86), Col.wrap(338)).header("", over(), "Par mois",
                "Comment lire cette ligne");
        Look key = Look.filled(mid(Nature.REVENUS), Rgb.TEXT, true);
        Look info = new Look(pale(Nature.INTERNE), Rgb.TEXT, false, true, 0);
        for (Category category : a.categories(Nature.REVENUS)) {
            if (category.incomeKind() == IncomeKind.REGULIER && a.total(category) != 0) {
                step(steps, Look.ITEM, category.name(), a.total(category), "");
            }
        }
        step(steps, Look.STRONG, "Revenus réguliers", capacity.regular(), "Ce qui rentre de façon prévisible.");
        step(steps, Look.ITEM, "Remboursements reçus", capacity.refunds(), "Ils compensent des dépenses déjà comptées plus bas.");
        step(steps, Look.ITEM, Nature.FIXES.label(), -a.fixes(), "Détail dans la partie « Charges fixes ».");
        step(steps, Look.ITEM, Nature.VARIABLES.label(), -a.variables(), "Détail dans la partie « Dépenses variables ».");
        step(steps, key, "Reste sur le train de vie courant", capacity.current(),
                "L'équilibre « normal » du compte : revenus réguliers et remboursements, moins les dépenses courantes.");
        step(steps, Look.ITEM, "Autres entrées ponctuelles", capacity.oneOff(), "Des rentrées réelles, mais pas garanties d'une période à l'autre.");
        step(steps, key, "Capacité d'épargne hors exceptionnel", capacity.beforeExceptional(),
                "Ce qui pouvait être mis de côté, hors dépenses exceptionnelles.");
        step(steps, Look.ITEM, Nature.EXCEPTIONNELLES.label(), -a.exceptionnelles(), "Travaux, réparations, dépenses hors du commun.");
        step(steps, key, "Résultat de la période", a.resultat(),
                "S'il est négatif, la période a été financée en puisant dans les autres comptes (ligne suivante).");
        step(steps, info, "Virements nets reçus de mes autres comptes", a.interneIn() - a.interneOut(),
                "Ce qui a été repris sur l'épargne, net de ce qui y a été versé.");
        step(steps, info, "Variation du solde du compte sur la période", a.solde(),
                "Résultat + virements nets : identique à l'écart crédits – débits des opérations.");

        pdf.skip(GAP);
        subtitle("2  ·  Simulateur : et si certaines dépenses baissaient ?");
        Table simulator = table(8, Col.text(210), Col.number(86), Col.number(80), Col.center(84), Col.number(86), Col.number(80), Col.text(144))
                .header("Poste", "Dépense " + over().toLowerCase(), "Dépense par mois", "Objectif de réduction",
                        "Économie " + over().toLowerCase(), "Économie par mois", "Nature");
        long spending = 0;
        long saving = 0;
        for (Category category : Insights.simulated(a)) {
            long spent = a.total(category);
            long saved = Math.round(spent * category.savingPercent() / 100.0);
            spending += spent;
            saving += saved;
            Nature nature = category.nature();
            simulator.row(tint(nature), new Cell(category.name(), ink(nature), true, -1, null), Money.format(spent),
                    Money.format(a.perMonth(spent)), category.savingPercent() + " %", Cell.bold(Money.format(saved)),
                    Cell.bold(Money.format(a.perMonth(saved))), Cell.ink(nature.label(), ink(nature)));
        }
        simulator.row(grand(Nature.VARIABLES), "TOTAL SIMULÉ", Money.format(spending), Money.format(a.perMonth(spending)),
                Fmt.percent(saving, spending), Money.format(saving), Money.format(a.perMonth(saving)), "");
        pdf.skip(GAP);
        tiles(soft("Train de vie courant aujourd'hui", Money.formatSigned(a.perMonth(capacity.current())), "reste par mois"),
                tile("Train de vie courant avec ces économies", Money.formatSigned(a.perMonth(capacity.current() + saving)), "reste par mois",
                        Nature.REVENUS),
                tile("Capacité d'épargne avec ces économies", Money.formatSigned(a.perMonth(capacity.beforeExceptional() + saving)),
                        "par mois, hors exceptionnel", Nature.REVENUS));

        subtitle("3  ·  Pistes repérées dans les opérations");
        Table leads = table(8, Col.text(230), Col.number(96), Col.wrap(444)).header("Piste", "Montant " + over().toLowerCase(), "Constat");
        long variables = a.variables();
        boolean any = false;
        List<PosteStat> biggest = Insights.biggestVariable(a);
        if (!biggest.isEmpty()) {
            any = true;
            leads.band(group(Nature.VARIABLES), "Les plus gros postes de dépenses variables");
            for (PosteStat poste : biggest) {
                lead(leads, poste, Fmt.plural(poste.count(), "opération", "opérations") + ", soit " + Money.format(a.perMonth(poste.total()))
                        + " par mois et " + Fmt.percent(poste.total(), variables) + " des dépenses variables  ·  " + poste.category().name());
            }
        }
        List<PosteStat> frequent = Insights.frequentVariable(a);
        if (!frequent.isEmpty()) {
            any = true;
            leads.band(group(Nature.VARIABLES), "Les petites dépenses qui reviennent souvent");
            for (PosteStat poste : frequent) {
                lead(leads, poste, Fmt.plural(poste.count(), "paiement", "paiements") + " sur la période, montant moyen "
                        + Money.format(poste.total() / poste.count()) + "  ·  " + poste.category().name());
            }
        }
        List<PosteStat> recurring = Insights.recurringFixed(a);
        if (!recurring.isEmpty()) {
            any = true;
            leads.band(group(Nature.FIXES), "Les factures et abonnements à comparer ou à renégocier");
            for (PosteStat poste : recurring) {
                lead(leads, poste, Fmt.plural(poste.count(), "paiement", "paiements") + " ; dernier montant " + Money.format(poste.lastAmount())
                        + " le " + Fmt.date(poste.last()) + "  ·  " + poste.category().name());
            }
        }
        List<ClassifiedOperation> pending = Insights.pendingSpending(a);
        if (!pending.isEmpty()) {
            any = true;
            leads.band(group(Nature.INTERNE), "Les dépenses qui attendent une catégorie");
            leads.row(Look.PLAIN, Cell.bold("Opérations à classer"), Money.format(pending.stream().mapToLong(ClassifiedOperation::amount).sum()),
                    Cell.muted(Fmt.plural(pending.size(), "opération n'a", "opérations n'ont") + " pas été reconnue"
                            + (pending.size() > 1 ? "s" : "") + " : les classer rend ce bilan plus juste."));
        }
        if (!any) {
            leads.band(Look.EMPTY, "Aucune dépense sur cette période.");
        }
        note("Ces pistes sont des constats tirés des opérations, pas des recommandations personnalisées.", Rgb.MUTED);
    }

    private void step(Table table, Look look, String label, long amount, String how) throws IOException {
        table.row(look, label, Cell.signed(Money.formatSigned(amount), amount), Cell.signed(Money.formatSigned(a.perMonth(amount)), amount),
                new Cell(how, Rgb.MUTED, false, -1, null));
    }

    private static void lead(Table table, PosteStat poste, String text) throws IOException {
        table.row(Look.PLAIN, Cell.bold(poste.poste()), Cell.bold(Money.format(poste.total())), Cell.muted(text));
    }

    // ------------------------------------------------------------------ opérations

    private void operations() throws IOException {
        section("Opérations", true);
        List<ClassifiedOperation> all = a.operations().reversed();
        long in = all.stream().mapToLong(o -> o.operation().amountCents()).filter(c -> c > 0).sum();
        long out = -all.stream().mapToLong(o -> o.operation().amountCents()).filter(c -> c < 0).sum();
        pdf.text(Fmt.plural(all.size(), "opération", "opérations") + "   ·   entrées " + Money.format(in) + "   ·   sorties " + Money.format(out)
                + "   ·   solde " + Money.formatSigned(in - out), pdf.left(), pdf.y() - 9, pdf.regular, 8.5f, Rgb.MUTED);
        pdf.skip(17);
        Table table = table(7.5f, Col.text(52), Col.text(300), Col.text(170), Col.text(160), Col.number(88)).header("Date", "Libellé",
                "Catégorie", "Poste", "Montant");
        YearMonth month = null;
        for (ClassifiedOperation o : all) {
            if (!o.month().equals(month)) {
                month = o.month();
                YearMonth current = month;
                long net = a.net(current);
                table.band(Look.STRONG, Fmt.month(current) + "   ·   " + Fmt.plural((int) all.stream().filter(x -> x.month().equals(current)).count(),
                        "opération", "opérations") + "   ·   solde " + Money.formatSigned(net));
            }
            long cents = o.operation().amountCents();
            table.row(Look.PLAIN, Fmt.date(o.operation().date()), o.operation().title(),
                    Cell.ink(o.category().name(), o.isUnclassified() ? Rgb.hex(0x9a6700) : ink(o.nature())), Cell.muted(o.poste()),
                    Cell.signed(Money.formatSigned(cents), cents));
        }
    }
}
