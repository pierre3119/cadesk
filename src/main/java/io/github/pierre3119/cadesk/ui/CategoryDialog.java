package io.github.pierre3119.cadesk.ui;

import atlantafx.base.theme.Styles;
import io.github.pierre3119.cadesk.domain.Category;
import io.github.pierre3119.cadesk.domain.Category.IncomeKind;
import io.github.pierre3119.cadesk.domain.Nature;

import java.util.List;

import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.util.StringConverter;

/** Création ou modification d'une catégorie : nom, nature, sens, et régularité pour un revenu. */
final class CategoryDialog extends Dialog<Category> {

    private static final String IN = "Entrée d'argent (reçu de mes autres comptes)";
    private static final String OUT = "Sortie d'argent (versé sur mes autres comptes)";

    /**
     * @param category la catégorie à modifier, ou {@code null} pour en créer une
     * @param preset   nature proposée pour une nouvelle catégorie
     */
    CategoryDialog(Category category, Nature preset, List<Category> existing, Node owner) {
        Ui.style(this, owner);
        boolean creation = category == null;
        setTitle(creation ? "Nouvelle catégorie" : "Modifier la catégorie");
        getDialogPane().getStyleClass().add("form-dialog");

        TextField name = new TextField(creation ? "" : category.name());
        name.setPromptText("Nom affiché dans le bilan");
        ComboBox<Nature> nature = new ComboBox<>(FXCollections.observableArrayList(Nature.values()));
        nature.setConverter(new StringConverter<>() {
            @Override
            public String toString(Nature n) {
                return n == null ? "" : n.label();
            }

            @Override
            public Nature fromString(String s) {
                return null;
            }
        });
        nature.setCellFactory(_ -> new ListCell<>() {
            @Override
            protected void updateItem(Nature n, boolean empty) {
                super.updateItem(n, empty);
                setText(empty || n == null ? null : n.label());
                setGraphic(empty || n == null ? null : Ui.dot(n));
            }
        });
        nature.setValue(creation ? preset : category.nature());
        nature.setMaxWidth(Double.MAX_VALUE);
        ComboBox<String> direction = new ComboBox<>(FXCollections.observableArrayList(IN, OUT));
        direction.setValue(creation || !category.inflow() ? OUT : IN);
        direction.setMaxWidth(Double.MAX_VALUE);
        ComboBox<IncomeKind> kind = new ComboBox<>(FXCollections.observableArrayList(IncomeKind.values()));
        kind.setConverter(new StringConverter<>() {
            @Override
            public String toString(IncomeKind k) {
                return k == null ? "" : k.label();
            }

            @Override
            public IncomeKind fromString(String s) {
                return null;
            }
        });
        kind.setValue(creation ? IncomeKind.PONCTUEL : category.incomeKind());
        kind.setMaxWidth(Double.MAX_VALUE);

        Label directionLabel = Ui.label("Sens");
        Label kindLabel = Ui.label("Régularité");
        Label help = Ui.wrapped("", Styles.TEXT_SMALL, Styles.TEXT_MUTED);
        Label error = Ui.wrapped("", Styles.TEXT_SMALL, Styles.DANGER);
        GridPane form = Ui.form("Nom", name, "Nature", nature, directionLabel, direction, kindLabel, kind);
        for (Node node : List.of(directionLabel, direction)) {
            node.visibleProperty().bind(nature.valueProperty().isEqualTo(Nature.INTERNE));
            node.managedProperty().bind(node.visibleProperty());
        }
        for (Node node : List.of(kindLabel, kind)) {
            node.visibleProperty().bind(nature.valueProperty().isEqualTo(Nature.REVENUS));
            node.managedProperty().bind(node.visibleProperty());
        }
        help.textProperty().bind(Bindings.createStringBinding(() -> describe(nature.getValue()), nature.valueProperty()));
        if (!creation && category.isFallback()) {
            // une catégorie par défaut garde sa place dans le bilan : seul son nom se modifie
            nature.setDisable(true);
            direction.setDisable(true);
        }
        DialogContent content = new DialogContent(480, 12, form, help, error);
        help.setMinHeight(50); // trois lignes : le texte le plus long
        error.setMinHeight(18);
        getDialogPane().setContent(content);
        Ui.refitOnChange(this, nature.valueProperty());

        ButtonType save = new ButtonType(creation ? "Créer" : "Enregistrer", ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);
        Runnable validate = () -> {
            String wanted = name.getText().strip();
            boolean taken = existing.stream().anyMatch(c -> c.name().equalsIgnoreCase(wanted) && (creation || c.id() != category.id()));
            error.setText(taken ? "Une autre catégorie porte déjà ce nom." : "");
            getDialogPane().lookupButton(save).setDisable(wanted.isEmpty() || taken);
        };
        name.textProperty().addListener((_, _, _) -> validate.run());
        validate.run();

        setResultConverter(button -> {
            if (button != save) {
                return null;
            }
            boolean inflow = Category.inflowFor(nature.getValue(), IN.equals(direction.getValue()));
            return creation
                    ? Category.create(name.getText().strip(), nature.getValue(), inflow, kind.getValue())
                    : new Category(category.id(), name.getText().strip(), nature.getValue(), inflow, category.position(),
                            category.role(), kind.getValue(), category.savingPercent());
        });
        name.requestFocus();
    }

    private static String describe(Nature nature) {
        return switch (nature) {
            case REVENUS -> "Ce qui rentre : salaire, remboursements, aides. La régularité sert à l'écran « Économies » : un revenu "
                    + "régulier compte dans le train de vie courant, une entrée ponctuelle non.";
            case FIXES -> "Ce qui part de façon régulière : prêt, impôts, énergie, assurances, abonnements.";
            case VARIABLES -> "Ce qui change d'un mois à l'autre : courses, restaurants, achats, loisirs.";
            case EXCEPTIONNELLES -> "Les dépenses hors du commun (travaux, grosses réparations), suivies à part pour ne pas "
                    + "fausser le train de vie courant.";
            case INTERNE -> "Les virements entre vos propres comptes et votre épargne : ni un revenu ni une dépense.";
        };
    }
}
