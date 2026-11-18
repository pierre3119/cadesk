package io.github.pierre3119.cadesk.ui;

import atlantafx.base.theme.Styles;
import io.github.pierre3119.cadesk.domain.Category;
import io.github.pierre3119.cadesk.domain.ClassifiedOperation;
import io.github.pierre3119.cadesk.domain.Money;
import io.github.pierre3119.cadesk.llm.Assistant.NewCategory;
import io.github.pierre3119.cadesk.llm.Assistant.Origin;
import io.github.pierre3119.cadesk.llm.Assistant.Proposal;
import io.github.pierre3119.cadesk.llm.Assistant.Suggestion;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * Propositions de l'assistant, à valider avant tout changement : pour chaque activité qui changerait de
 * catégorie, la catégorie actuelle et la catégorie proposée. Seules les lignes cochées sont appliquées.
 */
final class RecategorizeDialog extends Dialog<List<Suggestion>> {

    /** Une proposition et la case qui dit si elle est retenue. */
    private record Row(Suggestion suggestion, BooleanProperty selected) {
    }

    RecategorizeDialog(Proposal proposal, Node owner) {
        Ui.style(this, owner);
        setTitle("Propositions de l'assistant");
        setResizable(true);
        getDialogPane().getStyleClass().add("form-dialog");

        ObservableList<Row> rows = FXCollections.observableArrayList();
        proposal.changes().forEach(s -> rows.add(new Row(s, new SimpleBooleanProperty(true))));
        TableView<Row> table = table(rows);

        int operations = rows.stream().mapToInt(r -> r.suggestion().changed().size()).sum();
        List<String> created = rows.stream().map(r -> r.suggestion().created()).filter(c -> c != null).map(NewCategory::name).distinct().toList();
        Label title = Ui.label(Fmt.plural(rows.size(), "activité changerait", "activités changeraient") + " de catégorie ("
                + Fmt.plural(operations, "opération", "opérations") + ")", Styles.TITLE_4);
        VBox summary = new VBox(4, title);
        if (!created.isEmpty()) {
            summary.getChildren().add(Ui.wrapped(Fmt.plural(created.size(), "catégorie serait créée", "catégories seraient créées") + " : "
                    + String.join(", ", created) + ".", Styles.TEXT_MUTED));
        }
        if (proposal.pending() > 0) {
            summary.getChildren().add(Ui.wrapped(Fmt.plural(proposal.pending(), "activité reste", "activités restent")
                    + " sans proposition : le modèle n'a pas su les classer.", Styles.TEXT_MUTED));
        }
        if (proposal.failure() != null) {
            summary.getChildren().add(Ui.wrapped("L'analyse s'est interrompue avant la fin : " + proposal.failure(), Styles.DANGER));
        }
        summary.getStyleClass().add("dialog-summary");

        CheckBox all = new CheckBox("Tout retenir");
        all.setSelected(true);
        all.setOnAction(_ -> rows.forEach(r -> r.selected().set(all.isSelected())));
        Label hint = Ui.label("Décochez les propositions que vous ne voulez pas appliquer.", Styles.TEXT_SMALL, Styles.TEXT_MUTED);
        HBox tools = new HBox(14, all, hint);
        tools.setAlignment(Pos.CENTER_LEFT);

        getDialogPane().setContent(new DialogContent(1000, 12, summary, tools, table));
        ButtonType apply = new ButtonType("Appliquer la sélection", ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(apply, ButtonType.CANCEL);
        BooleanBinding none = Bindings.createBooleanBinding(() -> rows.stream().noneMatch(r -> r.selected().get()),
                rows.stream().map(Row::selected).toArray(BooleanProperty[]::new));
        getDialogPane().lookupButton(apply).disableProperty().bind(none);
        setResultConverter(button -> button != apply ? null
                : rows.stream().filter(r -> r.selected().get()).map(Row::suggestion).toList());
    }

    private static TableView<Row> table(ObservableList<Row> rows) {
        TableView<Row> table = new TableView<>(rows);
        table.getStyleClass().addAll(Styles.STRIPED, Styles.DENSE);
        table.setEditable(true);
        table.setPrefHeight(430);

        TableColumn<Row, Boolean> selected = new TableColumn<>("");
        selected.setCellValueFactory(c -> c.getValue().selected());
        selected.setCellFactory(CheckBoxTableCell.forTableColumn(selected));
        selected.setEditable(true);
        selected.setSortable(false);
        selected.setPrefWidth(38);

        TableColumn<Row, Suggestion> activity = column("Activité", 218);
        activity.setCellFactory(_ -> new TableCell<>() {
            @Override
            protected void updateItem(Suggestion s, boolean empty) {
                super.updateItem(s, empty);
                setText(empty || s == null ? null : s.activity().poste());
                setTooltip(empty || s == null ? null : new Tooltip(s.activity().sample().operation().type() + "\n"
                        + s.activity().sample().operation().label()));
            }
        });

        TableColumn<Row, Suggestion> count = column("Nb", 52);
        count.getStyleClass().add("amount-column");
        count.setCellFactory(_ -> text(s -> Fmt.count(s.changed().size())));

        TableColumn<Row, Suggestion> amount = column("Montant", 104);
        amount.getStyleClass().add("amount-column");
        amount.setCellFactory(_ -> text(s -> Money.formatSigned(s.changed().stream().mapToLong(o -> o.operation().amountCents()).sum())));

        TableColumn<Row, Suggestion> current = column("Catégorie actuelle", 204);
        current.setCellFactory(_ -> new TableCell<>() {
            @Override
            protected void updateItem(Suggestion s, boolean empty) {
                super.updateItem(s, empty);
                if (empty || s == null) {
                    setGraphic(null);
                    return;
                }
                List<Category> categories = s.changed().stream().map(ClassifiedOperation::category).distinct().toList();
                if (categories.size() == 1) {
                    setGraphic(Ui.chip(categories.getFirst()));
                } else {
                    Label several = Ui.label(categories.size() + " catégories", Styles.TEXT_MUTED);
                    several.setTooltip(new Tooltip(categories.stream().map(Category::name).collect(Collectors.joining("\n"))));
                    setGraphic(several);
                }
            }
        });

        TableColumn<Row, Suggestion> proposed = column("Catégorie proposée", 252);
        proposed.setCellFactory(_ -> new TableCell<>() {
            @Override
            protected void updateItem(Suggestion s, boolean empty) {
                super.updateItem(s, empty);
                if (empty || s == null) {
                    setGraphic(null);
                    return;
                }
                HBox box = new HBox(6, Ui.chip(s.categoryName(), s.nature()));
                box.setAlignment(Pos.CENTER_LEFT);
                if (s.created() != null) {
                    box.getChildren().add(Ui.label("nouvelle", "count-badge"));
                }
                setGraphic(box);
            }
        });

        TableColumn<Row, Suggestion> origin = column("Origine", 124);
        origin.setCellFactory(_ -> text(s -> s.origin() == Origin.SIMILAR ? "Activité semblable" : "Modèle"));

        table.getColumns().add(selected);
        table.getColumns().add(activity);
        table.getColumns().add(count);
        table.getColumns().add(amount);
        table.getColumns().add(current);
        table.getColumns().add(proposed);
        table.getColumns().add(origin);
        return table;
    }

    private static TableColumn<Row, Suggestion> column(String title, double width) {
        TableColumn<Row, Suggestion> column = new TableColumn<>(title);
        column.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().suggestion()));
        column.setPrefWidth(width);
        column.setSortable(false);
        column.setEditable(false);
        return column;
    }

    private static TableCell<Row, Suggestion> text(Function<Suggestion, String> value) {
        return new TableCell<>() {
            @Override
            protected void updateItem(Suggestion s, boolean empty) {
                super.updateItem(s, empty);
                setText(empty || s == null ? null : value.apply(s));
            }
        };
    }
}
