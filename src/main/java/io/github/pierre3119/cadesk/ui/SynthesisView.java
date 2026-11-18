package io.github.pierre3119.cadesk.ui;

import io.github.pierre3119.cadesk.analysis.Analysis;
import io.github.pierre3119.cadesk.domain.Category;
import io.github.pierre3119.cadesk.domain.Money;
import io.github.pierre3119.cadesk.domain.Nature;
import io.github.pierre3119.cadesk.ui.DonutChart.Slice;
import io.github.pierre3119.cadesk.ui.Sheet.Col;

import java.util.List;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * Synthèse : chiffres clés, vue d'ensemble de la période, répartition des dépenses, évolution mois par
 * mois et premiers postes de dépenses.
 */
final class SynthesisView extends AnalysisPage {

    private static final int TOP = 12;
    /** Largeur à partir de laquelle la vue d'ensemble et l'anneau tiennent côte à côte. */
    private static final double SIDE_BY_SIDE = 945;
    private static final double DONUT_CARD_WIDTH = 292;

    private final HBox kpis = new HBox(14);
    private final VBox overview = Ui.card("Vue d'ensemble");
    private final DonutChart donut = new DonutChart();
    private final VBox donutCard = Ui.card("Répartition des dépenses", donut);
    private final StackPane middle = new StackPane();
    private final MonthlyChart chart = new MonthlyChart();
    private final VBox top = Ui.card("Les " + TOP + " premiers postes de dépenses");
    private Boolean sideBySide;

    SynthesisView(AppModel model, Navigator navigator) {
        super(model, navigator);
        VBox.setVgrow(donut, Priority.ALWAYS);
        HBox.setHgrow(overview, Priority.ALWAYS);
        VBox chartCard = Ui.card("Mois par mois : dépenses (barres) et revenus (ligne)", chart, legend());
        VBox body = new VBox(18, kpis, middle, chartCard, top);
        body.getStyleClass().add("page-body");
        body.widthProperty().addListener((_, _, width) -> arrange(width.doubleValue()));
        arrange(SIDE_BY_SIDE);
        getChildren().addAll(Ui.header("Synthèse", subtitle, Ui.periodChoice(model)), Ui.scroll(body));
    }

    /** Place l'anneau à droite de la vue d'ensemble si la largeur le permet, sinon dessous. */
    private void arrange(double width) {
        boolean wide = width >= SIDE_BY_SIDE;
        if (sideBySide != null && sideBySide == wide) {
            return;
        }
        sideBySide = wide;
        middle.getChildren().clear();
        donutCard.setMinWidth(wide ? DONUT_CARD_WIDTH : 0);
        donutCard.setPrefWidth(wide ? DONUT_CARD_WIDTH : Region.USE_COMPUTED_SIZE);
        donutCard.setMaxWidth(wide ? DONUT_CARD_WIDTH : Double.MAX_VALUE);
        middle.getChildren().add(wide ? new HBox(18, overview, donutCard) : new VBox(18, overview, donutCard));
    }

    @Override
    protected void rebuild(Analysis a) {
        kpis.getChildren().setAll(
                kpi(Nature.REVENUS.label(), a.revenus(), a, false, Nature.REVENUS.styleClass()),
                kpi(Nature.FIXES.label(), a.fixes(), a, false, Nature.FIXES.styleClass()),
                kpi(Nature.VARIABLES.label(), a.variables(), a, false, Nature.VARIABLES.styleClass()),
                kpi(Nature.EXCEPTIONNELLES.label(), a.exceptionnelles(), a, false, Nature.EXCEPTIONNELLES.styleClass()),
                kpi("Résultat de la période", a.resultat(), a, true, a.resultat() < 0 ? "kpi-negative" : "kpi-positive"));
        overview(a);
        donut.setSlices(List.of(
                new Slice(Nature.FIXES.label(), a.fixes(), Nature.FIXES.styleClass()),
                new Slice(Nature.VARIABLES.label(), a.variables(), Nature.VARIABLES.styleClass()),
                new Slice(Nature.EXCEPTIONNELLES.label(), a.exceptionnelles(), Nature.EXCEPTIONNELLES.styleClass())), "Dépenses");
        chart.setMonths(a.months().stream().map(m -> new MonthlyChart.Month(m, a.amount(Nature.FIXES, m),
                a.amount(Nature.VARIABLES, m), a.amount(Nature.EXCEPTIONNELLES, m), a.amount(Nature.REVENUS, m))).toList());
        top(a);
    }

    private static Node kpi(String caption, long total, Analysis a, boolean signed, String styleClass) {
        long perMonth = a.perMonth(total);
        return Ui.stat(caption, signed ? Fmt.eurosSigned(total) : Fmt.euros(total),
                "soit " + (signed ? Fmt.eurosSigned(perMonth) : Fmt.euros(perMonth)) + " par mois", "kpi", styleClass);
    }

    // ------------------------------------------------------------------ vue d'ensemble

    private void overview(Analysis a) {
        Sheet sheet = new Sheet(Col.grow(235), Col.number(132), Col.number(118), Col.number(96));
        sheet.header("", overPeriod(a), "Par mois", "% des revenus");
        line(sheet, a, "tint " + Nature.REVENUS.styleClass(), Nature.REVENUS.label(), a.revenus(), false);
        line(sheet, a, "tint " + Nature.FIXES.styleClass(), Nature.FIXES.label(), a.fixes(), false);
        line(sheet, a, "tint " + Nature.VARIABLES.styleClass(), Nature.VARIABLES.label(), a.variables(), false);
        line(sheet, a, "tint " + Nature.EXCEPTIONNELLES.styleClass(), Nature.EXCEPTIONNELLES.label(), a.exceptionnelles(), false);
        line(sheet, a, "result", "→  Résultat (revenus − dépenses)", a.resultat(), true);
        line(sheet, a, "tint " + Nature.INTERNE.styleClass(), "Reçu de mes autres comptes", a.interneIn(), false);
        line(sheet, a, "tint " + Nature.INTERNE.styleClass(), "Versé sur mes autres comptes", a.interneOut(), false);
        line(sheet, a, "result", "→  Variation du solde du compte", a.solde(), true);

        boolean consistent = a.control() == 0;
        Label control = Ui.wrapped(consistent
                ? "✔  Contrôle : le bilan retombe au centime près sur le total des opérations (crédits − débits)."
                : "⚠  Contrôle : écart de " + Money.formatSigned(a.control()) + " entre le bilan et le total des opérations.",
                "check-line", consistent ? "check-ok" : "check-ko");
        overview.getChildren().setAll(overview.getChildren().getFirst(), sheet, control);
    }

    private static void line(Sheet sheet, Analysis a, String classes, String label, long total, boolean signed) {
        Region[] cells = sheet.row(classes, label,
                signed ? Money.formatSigned(total) : Money.format(total),
                signed ? Money.formatSigned(a.perMonth(total)) : Money.format(a.perMonth(total)),
                Fmt.percent(total, a.revenus()));
        if (signed) {
            for (int i = 1; i < cells.length; i++) {
                cells[i].getStyleClass().add(total < 0 ? "amount-out" : "amount-in");
            }
        }
    }

    // ------------------------------------------------------------------ graphique

    private static Node legend() {
        HBox legend = new HBox(22);
        legend.setAlignment(Pos.CENTER);
        for (Nature nature : List.of(Nature.FIXES, Nature.VARIABLES, Nature.EXCEPTIONNELLES)) {
            Region swatch = new Region();
            swatch.getStyleClass().addAll("swatch", nature.styleClass());
            swatch.setMinSize(12, 12);
            swatch.setMaxSize(12, 12);
            legend.getChildren().add(entry(swatch, nature.label()));
        }
        Region stroke = new Region();
        stroke.getStyleClass().add("swatch-line");
        stroke.setMinSize(22, 3);
        stroke.setMaxSize(22, 3);
        legend.getChildren().add(entry(stroke, Nature.REVENUS.label()));
        return legend;
    }

    private static Node entry(Node mark, String text) {
        HBox entry = new HBox(8, mark, Ui.label(text, "legend-name"));
        entry.setAlignment(Pos.CENTER_LEFT);
        return entry;
    }

    // ------------------------------------------------------------------ premiers postes

    private void top(Analysis a) {
        Sheet sheet = new Sheet(Col.grow(200), Col.text(205), Col.number(114), Col.number(98), Col.grow(120));
        sheet.header("Poste", "Nature", overPeriod(a), "Par mois", "Part des dépenses");
        List<Category> categories = a.spendingCategories().stream().limit(TOP).toList();
        long highest = categories.isEmpty() ? 0 : a.total(categories.getFirst());
        int rank = 1;
        for (Category category : categories) {
            long total = a.total(category);
            Region[] cells = sheet.row("tint " + category.nature().styleClass(),
                    rank++ + ".  " + category.name(),
                    Ui.label(category.nature().label(), "nature-text"),
                    Money.format(total), Money.format(a.perMonth(total)),
                    Ui.bar(highest == 0 ? 0 : (double) total / highest, Fmt.percent(total, a.depenses())));
            cells[0].getStyleClass().add("strong");
            cells[2].getStyleClass().add("strong");
            Sheet.onClick(cells, () -> navigator.showOperations(category, null));
        }
        if (categories.isEmpty()) {
            sheet.band("empty", "Aucune dépense sur cette période.");
        }
        top.getChildren().setAll(top.getChildren().getFirst(), sheet);
    }
}
