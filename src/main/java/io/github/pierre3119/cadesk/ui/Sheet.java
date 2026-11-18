package io.github.pierre3119.cadesk.ui;

import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.text.TextAlignment;

/**
 * Tableau de bilan : des lignes de cellules alignées en colonnes, colorées par classes CSS
 * ({@code group}, {@code tint}, {@code total}, {@code grand}… combinées à une classe de nature).
 * Contrairement à un {@code TableView}, toutes les lignes sont affichées, sans tri ni sélection.
 */
final class Sheet extends GridPane {

    enum Align { LEFT, CENTER, RIGHT }

    /** Une colonne : largeur fixe, ou largeur minimale extensible ; {@code wrap} fait passer le texte à la ligne. */
    record Col(double width, boolean grow, Align align, boolean wrap) {

        static Col text(double width) {
            return new Col(width, false, Align.LEFT, false);
        }

        static Col number(double width) {
            return new Col(width, false, Align.RIGHT, false);
        }

        static Col center(double width) {
            return new Col(width, false, Align.CENTER, false);
        }

        /** Colonne de texte qui prend la largeur restante. */
        static Col grow(double minWidth) {
            return new Col(minWidth, true, Align.LEFT, false);
        }

        /** Colonne de texte long, qui prend la largeur restante et passe à la ligne au lieu d'être tronqué. */
        static Col wrap(double minWidth) {
            return new Col(minWidth, true, Align.LEFT, true);
        }
    }

    private static final int TOOLTIP_FROM = 18;

    private final Col[] cols;
    private int rows;

    Sheet(Col... cols) {
        this.cols = cols;
        getStyleClass().add("sheet");
        for (Col col : cols) {
            ColumnConstraints constraints = new ColumnConstraints();
            constraints.setMinWidth(col.width());
            constraints.setPrefWidth(col.width());
            constraints.setHgrow(col.grow() ? Priority.ALWAYS : Priority.NEVER);
            constraints.setFillWidth(true);
            getColumnConstraints().add(constraints);
        }
    }

    int columnCount() {
        return cols.length;
    }

    /** Ligne de titres de colonnes ; un titre trop long passe sur deux lignes. */
    Region[] header(String... titles) {
        Region[] cells = row("sheet-header", (Object[]) titles);
        for (int i = 0; i < cells.length; i++) {
            Label title = (Label) cells[i];
            title.setWrapText(true);
            title.setTextAlignment(switch (cols[i].align()) {
                case LEFT -> TextAlignment.LEFT;
                case CENTER -> TextAlignment.CENTER;
                case RIGHT -> TextAlignment.RIGHT;
            });
        }
        return cells;
    }

    /**
     * Ajoute une ligne. Chaque cellule est un texte, un nœud, ou {@code null} pour une cellule vide ;
     * {@code classes} (séparées par des espaces) s'appliquent à toutes les cellules de la ligne.
     */
    Region[] row(String classes, Object... cells) {
        Region[] nodes = new Region[cols.length];
        for (int i = 0; i < cols.length; i++) {
            nodes[i] = cell(i < cells.length ? cells[i] : null, i, classes);
            add(nodes[i], i, rows);
        }
        rows++;
        return nodes;
    }

    /** Ligne d'une seule cellule qui occupe toute la largeur. */
    Region band(String classes, String text) {
        Region node = cell(text, 0, classes);
        node.getStyleClass().add("last");
        add(node, 0, rows++, cols.length, 1);
        return node;
    }

    /** Rend une ligne cliquable. */
    static void onClick(Region[] cells, Runnable action) {
        for (Region cell : cells) {
            cell.setCursor(Cursor.HAND);
            cell.setOnMouseClicked(_ -> action.run());
        }
        cells[0].getStyleClass().add("link");
    }

    private Region cell(Object content, int column, String classes) {
        Pos position = switch (cols[column].align()) {
            case LEFT -> Pos.CENTER_LEFT;
            case CENTER -> Pos.CENTER;
            case RIGHT -> Pos.CENTER_RIGHT;
        };
        Region node;
        if (content instanceof Node inner) {
            StackPane pane = new StackPane(inner);
            pane.setAlignment(position);
            node = pane;
        } else {
            String text = content == null ? "" : content.toString();
            Label label = new Label(text);
            label.setAlignment(position);
            if (cols[column].wrap()) {
                label.setWrapText(true);
            } else if (text.length() >= TOOLTIP_FROM) {
                label.setTooltip(new Tooltip(text));
            }
            node = label;
        }
        node.getStyleClass().add("sheet-cell");
        if (!classes.isBlank()) {
            node.getStyleClass().addAll(classes.strip().split("\\s+"));
        }
        if (column == 0) {
            node.getStyleClass().add("first");
        }
        if (column == cols.length - 1) {
            node.getStyleClass().add("last");
        }
        node.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        GridPane.setFillWidth(node, true);
        GridPane.setFillHeight(node, true);
        return node;
    }
}
