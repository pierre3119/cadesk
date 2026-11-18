package io.github.pierre3119.cadesk.ui;

import atlantafx.base.theme.Styles;
import io.github.pierre3119.cadesk.domain.ImportRecord;

import java.util.function.Function;

import javafx.beans.binding.NumberBinding;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/** Historique des imports : ce que chaque fichier a apporté à la base. */
final class ImportsView extends VBox {

    ImportsView(AppModel model) {
        getStyleClass().add("page");
        Label title = new Label("Imports");
        title.getStyleClass().add(Styles.TITLE_2);
        Label help = new Label("Chaque relevé importé est rapproché de la base : seules les opérations nouvelles sont "
                + "ajoutées ; celles déjà connues sont ignorées, et un libellé retouché par la banque est modifié sans doublon.");
        help.getStyleClass().add(Styles.TEXT_MUTED);
        help.setWrapText(true);

        TableView<ImportRecord> table = new TableView<>(model.imports);
        table.getStyleClass().add(Styles.STRIPED);
        table.setPlaceholder(new Label("Aucun import pour l'instant."));
        TableColumn<ImportRecord, String> file = column("Fichier", 185, ImportRecord::fileName, false);
        table.getColumns().add(column("Importé le", 140, r -> Fmt.dateTimeShort(r.importedAt()), false));
        table.getColumns().add(file);
        table.getColumns().add(column("Période du relevé", 160,
                r -> Fmt.dateShort(r.periodStart()) + " → " + Fmt.dateShort(r.periodEnd()), false));
        table.getColumns().add(column("Lues", 60, r -> String.valueOf(r.rowsRead()), true));
        table.getColumns().add(column("Ajoutées", 82, r -> String.valueOf(r.rowsNew()), true));
        table.getColumns().add(column("Connues", 78, r -> String.valueOf(r.rowsKnown()), true));
        table.getColumns().add(column("Modifiées", 98, r -> String.valueOf(r.rowsUpdated()), true));
        table.getColumns().add(column("Solde indiqué", 125, r -> Fmt.money(r.balanceCents()), true));
        // le nom du fichier occupe la largeur restante
        NumberBinding rest = table.widthProperty().subtract(18);
        for (TableColumn<ImportRecord, ?> other : table.getColumns()) {
            if (other != file) {
                rest = rest.subtract(other.widthProperty());
            }
        }
        file.prefWidthProperty().bind(rest);
        VBox.setVgrow(table, Priority.ALWAYS);

        getChildren().addAll(title, help, table);
    }

    private static TableColumn<ImportRecord, String> column(String name, double width, Function<ImportRecord, String> value,
            boolean rightAligned) {
        TableColumn<ImportRecord, String> column = new TableColumn<>(name);
        column.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(value.apply(c.getValue())));
        column.setPrefWidth(width);
        column.setSortable(false);
        if (rightAligned) {
            column.getStyleClass().add("amount-column");
        }
        return column;
    }
}
