package io.github.pierre3119.cadesk.ui;

import atlantafx.base.theme.Styles;
import io.github.pierre3119.cadesk.categorize.Classifier;
import io.github.pierre3119.cadesk.categorize.Texts;
import io.github.pierre3119.cadesk.domain.Category;
import io.github.pierre3119.cadesk.domain.ClassifiedOperation;
import io.github.pierre3119.cadesk.domain.Operation;
import io.github.pierre3119.cadesk.domain.Rule;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;

/** Création ou modification d'une règle de classement, avec un aperçu des opérations qu'elle reconnaît. */
final class RuleDialog extends Dialog<Rule> {

    private static final String ANY_TYPE = "Tous les types d'opération";
    private static final int EXAMPLES = 4;

    /**
     * @param rule     la règle à modifier, ou {@code null} pour en créer une
     * @param category catégorie proposée pour une nouvelle règle
     */
    RuleDialog(AppModel model, Rule rule, Category category, Node owner) {
        Ui.style(this, owner);
        boolean creation = rule == null;
        setTitle(creation ? "Nouvelle règle de classement" : "Modifier la règle");
        getDialogPane().getStyleClass().add("form-dialog");

        ComboBox<Category> target = new ComboBox<>(FXCollections.observableArrayList(model.categories));
        target.setCellFactory(_ -> new CategoryCell());
        target.setButtonCell(new CategoryCell());
        target.setVisibleRowCount(16);
        target.setMaxWidth(Double.MAX_VALUE);
        long wanted = creation ? (category == null ? -1 : category.id()) : rule.categoryId();
        model.categories.stream().filter(c -> c.id() == wanted).findFirst().ifPresent(target::setValue);

        List<String> types = new ArrayList<>(List.of(ANY_TYPE));
        model.operations.stream().map(o -> o.operation().type()).distinct().sorted().forEach(types::add);
        if (!creation && rule.operationType() != null && !types.contains(rule.operationType())) {
            types.add(rule.operationType());
        }
        ComboBox<String> type = new ComboBox<>(FXCollections.observableArrayList(types));
        type.setValue(creation ? (types.contains(Classifier.CARD_PAYMENT) ? Classifier.CARD_PAYMENT : ANY_TYPE)
                : rule.operationType() == null ? ANY_TYPE : rule.operationType());
        type.setMaxWidth(Double.MAX_VALUE);

        TextArea keywords = new TextArea(creation ? "" : rule.keywords().stream().map(Texts::displayKeyword).collect(Collectors.joining("\n")));
        keywords.setPromptText("Un mot-clé par ligne, par exemple :\nSPOTIFY\nENGIE + 000123");
        keywords.setPrefRowCount(5);
        TextField poste = new TextField(creation || rule.poste() == null ? "" : rule.poste());
        poste.setPromptText("Vide : nom du commerçant ou du tiers, tiré du libellé");

        Label help = Ui.wrapped("Un seul mot-clé suffit pour reconnaître une opération ; majuscules et accents sont ignorés. "
                + "« A + B » exige les deux textes. Entre guillemets, une espace finale compte : \"DR \" ne reconnaît pas « DRIVE ». "
                + "Sans mot-clé, la règle prend toutes les opérations du type choisi.", Styles.TEXT_SMALL, Styles.TEXT_MUTED);
        Label preview = Ui.wrapped("", Styles.TEXT_SMALL, "rule-preview");

        GridPane form = Ui.form("Catégorie", target, "Type d'opération", type, "Mots-clés", keywords, "Nom du poste", poste);
        DialogContent content = new DialogContent(560, 12, form, help, preview);
        preview.setMinHeight(34);
        getDialogPane().setContent(content);

        ButtonType save = new ButtonType(creation ? "Créer la règle" : "Enregistrer", ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);
        Runnable refresh = () -> {
            List<String> words = Texts.parseKeywords(keywords.getText());
            String chosenType = ANY_TYPE.equals(type.getValue()) ? null : type.getValue();
            boolean valid = target.getValue() != null && (chosenType != null || !words.isEmpty());
            getDialogPane().lookupButton(save).setDisable(!valid);
            if (chosenType == null && words.isEmpty()) {
                preview.setText("Indiquez au moins un mot-clé, ou choisissez un type d'opération.");
                return;
            }
            Predicate<Operation> matcher = Classifier.matcher(Rule.create(0, null, chosenType, words));
            List<ClassifiedOperation> matching = model.operations.stream().filter(o -> matcher.test(o.operation())).toList();
            preview.setText(matching.isEmpty() ? "Aucune opération de la base ne correspond pour l'instant."
                    : Fmt.plural(matching.size(), "opération de la base correspond", "opérations de la base correspondent") + " : "
                            + matching.stream().map(o -> o.operation().title()).distinct().limit(EXAMPLES).collect(Collectors.joining("  ·  "))
                            + (matching.size() > EXAMPLES ? "  ·  …" : ""));
        };
        keywords.textProperty().addListener((_, _, _) -> refresh.run());
        type.valueProperty().addListener((_, _, _) -> refresh.run());
        target.valueProperty().addListener((_, _, _) -> refresh.run());
        refresh.run();

        setResultConverter(button -> {
            if (button != save) {
                return null;
            }
            String chosenType = ANY_TYPE.equals(type.getValue()) ? null : type.getValue();
            List<String> words = Texts.parseKeywords(keywords.getText());
            return new Rule(creation ? 0 : rule.id(), target.getValue().id(), poste.getText(), chosenType, words,
                    creation ? 0 : rule.position());
        });
        keywords.requestFocus();
    }

    private static final class CategoryCell extends ListCell<Category> {

        @Override
        protected void updateItem(Category c, boolean empty) {
            super.updateItem(c, empty);
            setText(empty || c == null ? null : c.name());
            setGraphic(empty || c == null ? null : Ui.dot(c.nature()));
        }
    }
}
