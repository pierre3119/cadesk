package io.github.pierre3119.cadesk.ui;

import io.github.pierre3119.cadesk.domain.Money;

import java.util.List;

import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;

/**
 * Graphique en anneau avec sa légende : la part de chaque tranche dans un total. La légende se place à
 * droite de l'anneau, ou dessous quand la largeur manque.
 */
final class DonutChart extends FlowPane {

    /** Une tranche ; {@code styleClass} fixe sa couleur (classe de nature). */
    record Slice(String label, long value, String styleClass) {
    }

    private static final double SIZE = 210;
    private static final double RADIUS = 78;
    private static final double GAP_DEGREES = 1.6;
    private static final double LABEL_WIDTH = 52;
    private static final double LABEL_HEIGHT = 20;

    private final Pane ring = new Pane();
    private final VBox legend = new VBox(12);

    DonutChart() {
        super(28, 16);
        ring.setMinSize(SIZE, SIZE);
        ring.setPrefSize(SIZE, SIZE);
        ring.setMaxSize(SIZE, SIZE);
        legend.setAlignment(Pos.CENTER_LEFT);
        setAlignment(Pos.CENTER);
        setRowValignment(VPos.CENTER);
        getChildren().addAll(ring, legend);
    }

    /** Affiche les tranches de valeur positive ; {@code centerCaption} légende le total au centre de l'anneau. */
    void setSlices(List<Slice> slices, String centerCaption) {
        ring.getChildren().clear();
        legend.getChildren().clear();
        List<Slice> shown = slices.stream().filter(s -> s.value() > 0).toList();
        long total = shown.stream().mapToLong(Slice::value).sum();
        double center = SIZE / 2;
        double gap = shown.size() > 1 ? GAP_DEGREES : 0;
        double start = 90; // midi, puis dans le sens des aiguilles d'une montre
        for (Slice slice : shown) {
            double sweep = 360.0 * slice.value() / total;
            Arc arc = new Arc(center, center, RADIUS, RADIUS, start - gap / 2, -(sweep - gap));
            arc.setType(ArcType.OPEN);
            arc.getStyleClass().addAll("donut-arc", slice.styleClass());
            Tooltip.install(arc, new Tooltip(slice.label() + " : " + Money.format(slice.value()) + " (" + Fmt.percent(slice.value(), total) + ")"));
            ring.getChildren().add(arc);
            if (sweep >= 18) { // en dessous, le pourcentage ne tient pas dans la tranche
                double middle = Math.toRadians(start - sweep / 2);
                Label percent = Ui.label(Math.round(100.0 * slice.value() / total) + "\u00a0%", "donut-label");
                percent.setAlignment(Pos.CENTER);
                percent.setPrefSize(LABEL_WIDTH, LABEL_HEIGHT);
                percent.setMouseTransparent(true);
                percent.relocate(center + RADIUS * Math.cos(middle) - LABEL_WIDTH / 2,
                        center - RADIUS * Math.sin(middle) - LABEL_HEIGHT / 2);
                ring.getChildren().add(percent);
            }
            start -= sweep;

            Region swatch = new Region();
            swatch.getStyleClass().addAll("swatch", slice.styleClass());
            swatch.setMinSize(12, 12);
            swatch.setMaxSize(12, 12);
            Label name = Ui.label(slice.label(), "legend-name");
            Label amount = Ui.label(Money.format(slice.value()) + "  ·  " + Fmt.percent(slice.value(), total), "legend-value");
            HBox row = new HBox(10, swatch, new VBox(1, name, amount));
            row.setAlignment(Pos.CENTER_LEFT);
            legend.getChildren().add(row);
        }
        VBox middle = new VBox(0, Ui.label(centerCaption, "donut-caption"), Ui.label(Fmt.euros(total), "donut-total"));
        middle.setAlignment(Pos.CENTER);
        middle.setPrefSize(SIZE, SIZE);
        middle.setMouseTransparent(true);
        ring.getChildren().add(middle);
        if (shown.isEmpty()) {
            legend.getChildren().add(Ui.label("Aucune dépense sur la période.", "legend-value"));
        }
    }
}
