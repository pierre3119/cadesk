package io.github.pierre3119.cadesk.ui;

import io.github.pierre3119.cadesk.analysis.Analysis;
import io.github.pierre3119.cadesk.domain.Category;
import io.github.pierre3119.cadesk.domain.Nature;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.function.ToLongFunction;

import javafx.beans.binding.Bindings;
import javafx.beans.binding.DoubleBinding;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * Bilan mensuel : ce qui entre et ce qui sort du compte, par catégorie et par mois, avec sous-totaux,
 * solde du mois et solde cumulé. La ligne de titres et la colonne des postes restent visibles quand on
 * fait défiler le tableau.
 */
final class BilanView extends AnalysisPage {

    private static final double LABEL_WIDTH = 318;
    private static final double MONTH_WIDTH = 98;
    private static final double TOTAL_WIDTH = 108;
    private static final double PART_WIDTH = 68;

    private final ScrollPane scroll = new ScrollPane();

    BilanView(AppModel model, Navigator navigator) {
        super(model, navigator);
        scroll.getStyleClass().add("bilan-scroll");
        VBox card = new VBox(scroll);
        card.getStyleClass().addAll("card", "card-flush");
        VBox.setVgrow(scroll, Priority.ALWAYS);
        VBox.setVgrow(card, Priority.ALWAYS);
        Label note = Ui.wrapped("Lecture : un virement entre mes comptes n'est ni un revenu ni une dépense ; il est isolé dans "
                + "les lignes grises. Cliquez sur une catégorie pour voir ses opérations.", "hint");
        getChildren().addAll(Ui.header("Bilan mensuel", subtitle, Ui.periodChoice(model)), card, note);
    }

    @Override
    protected void rebuild(Analysis a) {
        Grid grid = new Grid(a);
        List<YearMonth> months = a.months();
        long in = a.revenus() + a.interneIn();
        long out = a.depenses() + a.interneOut();

        grid.band("ENTRÉES  —  ce qui rentre", "band band-in");
        grid.group(Nature.REVENUS, Nature.REVENUS.label(), a.categories(Nature.REVENUS), "Total revenus et remboursements", in, true);
        grid.group(Nature.INTERNE, "Virements depuis mes autres comptes", a.categories(Nature.INTERNE, true),
                "Total reçu de mes autres comptes", in, false);
        grid.values("grand grand-in", "TOTAL DES ENTRÉES", m -> a.amount(Nature.REVENUS, m) + a.amount(Nature.INTERNE, true, m),
                in, true, Fmt.percent(in, in), false, null);
        grid.gap();

        grid.band("SORTIES  —  ce qui sort", "band band-out");
        grid.group(Nature.FIXES, Nature.FIXES.label(), a.categories(Nature.FIXES), "Total charges fixes", out, true);
        grid.group(Nature.VARIABLES, Nature.VARIABLES.label(), a.categories(Nature.VARIABLES), "Total dépenses variables", out, true);
        grid.group(Nature.EXCEPTIONNELLES, Nature.EXCEPTIONNELLES.label(), a.categories(Nature.EXCEPTIONNELLES),
                "Total dépenses exceptionnelles", out, true);
        grid.group(Nature.INTERNE, "Épargne et virements vers mes autres comptes", a.categories(Nature.INTERNE, false),
                "Total versé sur mes autres comptes", out, true);
        grid.values("grand grand-out", "TOTAL DES SORTIES", m -> a.depenses(m) + a.amount(Nature.INTERNE, false, m),
                out, true, Fmt.percent(out, out), false, null);
        grid.gap();

        grid.values("solde", "SOLDE DU MOIS  (entrées − sorties)", a::solde, a.solde(), true, "", true, null);
        long[] running = new long[1];
        List<Long> cumulative = new ArrayList<>();
        for (YearMonth m : months) {
            running[0] += a.solde(m);
            cumulative.add(running[0]);
        }
        grid.values("calc", "Solde cumulé depuis le début de la période", m -> cumulative.get(months.indexOf(m)),
                a.solde(), false, "", true, null);
        grid.gap();

        grid.band("POUR COMPRENDRE LE SOLDE", "band band-info");
        grid.values("calc", "Revenus − dépenses (hors virements internes)", a::resultat, a.resultat(), true, "", true, null);
        grid.values("calc", "Virements nets avec mes autres comptes",
                m -> a.amount(Nature.INTERNE, true, m) - a.amount(Nature.INTERNE, false, m), a.interneIn() - a.interneOut(),
                true, "", true, null);
        grid.values("calc check", "Contrôle : écart avec les opérations (0 attendu)", m -> a.solde(m) - a.net(m), a.control(),
                false, "", true, null);

        scroll.setContent(grid);
        scroll.setHvalue(0);
        scroll.setVvalue(0);
        grid.stick();
    }

    /** Le tableau en construction. */
    private final class Grid extends GridPane {

        private final Analysis a;
        private final List<YearMonth> months;
        private final int columns;
        private final List<Region> firstColumn = new ArrayList<>();
        private final List<Region> header = new ArrayList<>();
        private int rows;

        Grid(Analysis a) {
            this.a = a;
            this.months = a.months();
            this.columns = months.size() + 4;
            getStyleClass().addAll("sheet", "bilan");
            column(LABEL_WIDTH);
            months.forEach(_ -> column(MONTH_WIDTH));
            column(TOTAL_WIDTH);
            column(TOTAL_WIDTH);
            column(PART_WIDTH);

            header.add(cell("Poste", 0, "sheet-header", Pos.CENTER_LEFT));
            for (YearMonth m : months) {
                header.add(cell(Fmt.monthShort(m), header.size(), "sheet-header", Pos.CENTER_RIGHT));
            }
            header.add(cell("Total", header.size(), "sheet-header", Pos.CENTER_RIGHT));
            header.add(cell("Par mois", header.size(), "sheet-header", Pos.CENTER_RIGHT));
            header.add(cell("Part", header.size(), "sheet-header", Pos.CENTER_RIGHT));
            for (int i = 0; i < header.size(); i++) {
                add(header.get(i), i, 0);
            }
            rows = 1;
        }

        private void column(double width) {
            ColumnConstraints constraints = new ColumnConstraints(width);
            constraints.setHgrow(Priority.NEVER);
            getColumnConstraints().add(constraints);
        }

        private Label cell(String text, int column, String classes, Pos alignment) {
            Label label = new Label(text);
            label.setAlignment(alignment);
            label.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
            label.getStyleClass().add("sheet-cell");
            label.getStyleClass().addAll(classes.split(" "));
            if (column == 0) {
                label.getStyleClass().add("first");
            }
            if (column == columns - 1) {
                label.getStyleClass().add("last");
            }
            return label;
        }

        /** Bandeau de section : son titre reste dans la colonne figée, le reste de la ligne prolonge sa couleur. */
        void band(String text, String classes) {
            Label title = cell(text, 0, classes, Pos.CENTER_LEFT);
            title.setMinWidth(LABEL_WIDTH);
            firstColumn.add(title);
            add(title, 0, rows);
            add(cell("", columns - 1, classes, Pos.CENTER_LEFT), 1, rows, columns - 1, 1);
            rows++;
        }

        void gap() {
            Region gap = new Region();
            gap.setMinHeight(10);
            add(gap, 0, rows++, columns, 1);
        }

        /** Les catégories d'une nature qui ont des opérations sur la période, suivies de leur total. */
        void group(Nature nature, String title, List<Category> categories, String totalLabel, long sideTotal, boolean alwaysTotal) {
            List<Category> used = categories.stream().filter(c -> a.count(c) > 0).toList();
            if (used.isEmpty()) {
                return;
            }
            band(title, "group " + nature.styleClass());
            for (Category category : used) {
                long total = a.total(category);
                values("item", category.name(), m -> a.amount(category, m), total, true, Fmt.percent(total, sideTotal), false,
                        () -> navigator.showOperations(category, null));
            }
            if (alwaysTotal || used.size() > 1) {
                long total = used.stream().mapToLong(a::total).sum();
                values("total " + nature.styleClass(), totalLabel, m -> used.stream().mapToLong(c -> a.amount(c, m)).sum(), total,
                        true, Fmt.percent(total, sideTotal), false, null);
            }
        }

        /**
         * Une ligne de montants : un par mois, puis le total, la moyenne mensuelle et la part.
         *
         * @param signed vrai pour afficher le signe et colorer les montants (soldes)
         */
        void values(String classes, String label, ToLongFunction<YearMonth> monthly, long total, boolean average, String part,
                boolean signed, Runnable onClick) {
            Label name = cell(label, 0, classes, Pos.CENTER_LEFT);
            if (label.length() > 38) {
                name.setTooltip(new Tooltip(label));
            }
            if (onClick != null) {
                name.getStyleClass().add("link");
                name.setCursor(Cursor.HAND);
                name.setOnMouseClicked(_ -> onClick.run());
            }
            firstColumn.add(name);
            add(name, 0, rows);
            int column = 1;
            for (YearMonth m : months) {
                add(amount(monthly.applyAsLong(m), column, classes, signed), column++, rows);
            }
            add(amount(total, column, classes + " strong", signed), column++, rows);
            add(average ? amount(a.perMonth(total), column, classes, signed) : cell("", column, classes, Pos.CENTER_RIGHT), column++, rows);
            add(cell(part, column, classes + " muted", Pos.CENTER_RIGHT), column, rows);
            rows++;
        }

        private Label amount(long cents, int column, String classes, boolean signed) {
            Label label = cell(signed ? Fmt.amountSigned(cents) : Fmt.amount(cents), column, classes, Pos.CENTER_RIGHT);
            if (signed && cents != 0) {
                label.getStyleClass().add(cents > 0 ? "amount-in" : "amount-out");
            }
            return label;
        }

        /** Fige la ligne de titres et la colonne des postes : elles suivent le défilement. */
        void stick() {
            DoubleBinding dx = Bindings.createDoubleBinding(
                    () -> (double) Math.round(Math.max(0, getWidth() - scroll.getViewportBounds().getWidth()) * scroll.getHvalue()),
                    scroll.hvalueProperty(), scroll.viewportBoundsProperty(), widthProperty());
            DoubleBinding dy = Bindings.createDoubleBinding(
                    () -> (double) Math.round(Math.max(0, getHeight() - scroll.getViewportBounds().getHeight()) * scroll.getVvalue()),
                    scroll.vvalueProperty(), scroll.viewportBoundsProperty(), heightProperty());
            for (Region cell : firstColumn) {
                cell.translateXProperty().bind(dx);
                cell.setViewOrder(-1);
            }
            for (Region cell : header) {
                cell.translateYProperty().bind(dy);
                cell.setViewOrder(-1);
            }
            Region corner = header.getFirst();
            corner.translateXProperty().bind(dx);
            corner.setViewOrder(-2);
        }
    }
}
