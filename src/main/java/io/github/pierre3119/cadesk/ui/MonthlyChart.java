package io.github.pierre3119.cadesk.ui;

import io.github.pierre3119.cadesk.analysis.Scale;
import io.github.pierre3119.cadesk.domain.Money;
import io.github.pierre3119.cadesk.domain.Nature;

import java.time.YearMonth;
import java.util.List;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Pane;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.Polyline;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

/**
 * Graphique mois par mois : les dépenses en barres empilées (charges fixes, dépenses variables,
 * dépenses exceptionnelles) et les revenus en ligne. Dessiné à la main, pour superposer barres et ligne
 * sur la même échelle.
 */
final class MonthlyChart extends Pane {

    /** Les montants d'un mois, en centimes. */
    record Month(YearMonth month, long fixes, long variables, long exceptionnelles, long revenus) {

        long spending() {
            return Math.max(0, fixes) + Math.max(0, variables) + Math.max(0, exceptionnelles);
        }
    }

    private static final double LEFT = 70;
    private static final double RIGHT = 12;
    private static final double TOP = 12;
    private static final double BOTTOM = 30;
    private static final double MAX_BAR = 54;
    private static final double MIN_LABEL_SLOT = 46;

    private List<Month> months = List.of();

    MonthlyChart() {
        getStyleClass().add("monthly-chart");
        setMinHeight(280);
        widthProperty().addListener(_ -> draw());
        heightProperty().addListener(_ -> draw());
    }

    void setMonths(List<Month> months) {
        this.months = List.copyOf(months);
        draw();
    }

    // la taille souhaitée ne dépend pas du dessin, sinon chaque tracé agrandirait le graphique
    @Override
    protected double computePrefWidth(double height) {
        return 640;
    }

    @Override
    protected double computePrefHeight(double width) {
        return 320;
    }

    private void draw() {
        getChildren().clear();
        double plotWidth = getWidth() - LEFT - RIGHT;
        double plotHeight = getHeight() - TOP - BOTTOM;
        if (months.isEmpty() || plotWidth <= 0 || plotHeight <= 0) {
            return;
        }
        long highest = months.stream().mapToLong(m -> Math.max(m.spending(), m.revenus())).max().orElse(0);
        double step = Scale.niceStep(Math.max(highest, 100) / 6.0);
        double top = Math.ceil(Math.max(highest, 100) / step) * step;
        double scale = plotHeight / top;
        double base = TOP + plotHeight;

        for (double value = 0; value <= top + step / 2; value += step) {
            double y = Math.round(base - value * scale) + 0.5;
            Line grid = new Line(LEFT, y, LEFT + plotWidth, y);
            grid.getStyleClass().add(value == 0 ? "chart-baseline" : "chart-grid");
            Label tick = Ui.label(Fmt.euros(value), "chart-axis");
            tick.setAlignment(Pos.CENTER_RIGHT);
            tick.setPrefSize(LEFT - 10, 18);
            tick.relocate(0, y - 9);
            getChildren().addAll(grid, tick);
        }

        double slot = plotWidth / months.size();
        double barWidth = Math.min(MAX_BAR, slot * 0.62);
        int labelEvery = (int) Math.ceil(MIN_LABEL_SLOT / slot);
        Polyline line = new Polyline();
        line.getStyleClass().add("chart-line");
        for (int i = 0; i < months.size(); i++) {
            Month m = months.get(i);
            double middle = LEFT + slot * (i + 0.5);
            double y = base;
            y = segment(m, Nature.FIXES, m.fixes(), middle - barWidth / 2, y, barWidth, scale);
            y = segment(m, Nature.VARIABLES, m.variables(), middle - barWidth / 2, y, barWidth, scale);
            segment(m, Nature.EXCEPTIONNELLES, m.exceptionnelles(), middle - barWidth / 2, y, barWidth, scale);
            line.getPoints().addAll(middle, base - Math.max(0, m.revenus()) * scale);
            if (i % labelEvery == 0) {
                Label label = Ui.label(Fmt.monthShort(m.month()), "chart-axis");
                label.setAlignment(Pos.CENTER);
                label.setPrefSize(slot * labelEvery, 18);
                label.relocate(middle - slot * labelEvery / 2, base + 8);
                getChildren().add(label);
            }
        }
        getChildren().add(line);
        for (int i = 0; i < months.size(); i++) {
            Month m = months.get(i);
            Circle marker = new Circle(LEFT + slot * (i + 0.5), base - Math.max(0, m.revenus()) * scale, 4.5);
            marker.getStyleClass().add("chart-marker");
            tooltip(marker, Fmt.month(m.month()) + "\n" + Nature.REVENUS.label() + " : " + Money.format(m.revenus()));
            getChildren().add(marker);
        }
    }

    /** Dessine un étage de la barre et renvoie l'ordonnée de son sommet. */
    private double segment(Month m, Nature nature, long value, double x, double bottom, double width, double scale) {
        if (value <= 0) {
            return bottom;
        }
        double height = value * scale;
        Rectangle bar = new Rectangle(x, bottom - height, width, height);
        bar.getStyleClass().addAll("chart-segment", nature.styleClass());
        tooltip(bar, Fmt.month(m.month()) + "\n" + nature.label() + " : " + Money.format(value)
                + "\nTotal des dépenses : " + Money.format(m.spending()));
        getChildren().add(bar);
        return bottom - height;
    }

    private static void tooltip(Node node, String text) {
        Tooltip tooltip = new Tooltip(text);
        tooltip.setShowDelay(Duration.millis(120));
        Tooltip.install(node, tooltip);
    }
}
