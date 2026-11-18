package io.github.pierre3119.cadesk.ui;

import atlantafx.base.theme.Styles;
import io.github.pierre3119.cadesk.categorize.Classifier;
import io.github.pierre3119.cadesk.categorize.Texts;
import io.github.pierre3119.cadesk.domain.Category;
import io.github.pierre3119.cadesk.domain.ClassifiedOperation;
import io.github.pierre3119.cadesk.domain.Money;
import io.github.pierre3119.cadesk.domain.Operation;
import io.github.pierre3119.cadesk.domain.Rule;

import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Choix d'une catégorie pour une ou plusieurs opérations, avec la possibilité de créer au passage une
 * règle qui classera de la même façon les opérations semblables.
 */
final class CategorizeDialog extends Dialog<CategorizeDialog.Choice> {

    /**
     * @param category catégorie choisie, ou {@code null} pour revenir au classement automatique
     * @param rule     règle à créer, ou {@code null} pour ne ranger que les opérations choisies
     */
    record Choice(Category category, Rule rule) {
    }

    private static final int SHOWN = 3;

    CategorizeDialog(AppModel model, List<ClassifiedOperation> selection, Node owner) {
        Ui.style(this, owner);
        setTitle("Catégoriser");
        getDialogPane().getStyleClass().add("form-dialog");
        Operation first = selection.getFirst().operation();

        // ---- les opérations concernées
        VBox summary = new VBox(4, Ui.label("Catégoriser " + Fmt.plural(selection.size(), "opération", "opérations"), Styles.TITLE_4));
        selection.stream().limit(SHOWN).forEach(o -> {
            Label title = Ui.label(o.operation().title());
            HBox.setHgrow(title, Priority.ALWAYS);
            title.setMaxWidth(Double.MAX_VALUE);
            Label amount = Ui.label(Money.formatSigned(o.operation().amountCents()), o.operation().isCredit() ? "amount-in" : "amount-out");
            amount.setMinWidth(Label.USE_PREF_SIZE);
            HBox line = new HBox(12, Ui.label(Fmt.date(o.operation().date()), Styles.TEXT_MUTED), title, amount);
            line.setAlignment(Pos.CENTER_LEFT);
            summary.getChildren().add(line);
        });
        if (selection.size() > SHOWN) {
            summary.getChildren().add(Ui.label("… et " + Fmt.plural(selection.size() - SHOWN, "autre", "autres") + ", pour un total de "
                    + Money.formatSigned(selection.stream().mapToLong(o -> o.operation().amountCents()).sum()), Styles.TEXT_MUTED));
        }
        summary.getStyleClass().add("dialog-summary");

        // ---- la catégorie : celles du sens des opérations d'abord
        boolean credit = selection.stream().allMatch(o -> o.operation().isCredit());
        boolean debit = selection.stream().noneMatch(o -> o.operation().isCredit());
        List<Category> ordered = model.categories.stream()
                .sorted(Comparator.comparing((Category c) -> credit ? !c.inflow() : debit && c.inflow())).toList();
        FilteredList<Category> shown = new FilteredList<>(FXCollections.observableArrayList(ordered));
        TextField filter = new TextField();
        filter.setPromptText("Filtrer les catégories…");
        ListView<Category> list = new ListView<>(shown);
        list.setPrefHeight(264);
        list.setCellFactory(_ -> new ListCell<>() {
            @Override
            protected void updateItem(Category c, boolean empty) {
                super.updateItem(c, empty);
                if (empty || c == null) {
                    setGraphic(null);
                    return;
                }
                HBox row = new HBox(10, Ui.dot(c.nature()), Ui.label(c.name()), Ui.spacer(),
                        Ui.label(c.nature().label(), Styles.TEXT_SMALL, Styles.TEXT_MUTED));
                row.setAlignment(Pos.CENTER_LEFT);
                setGraphic(row);
            }
        });
        filter.textProperty().addListener((_, _, text) -> {
            String needle = Texts.fold(text);
            shown.setPredicate(c -> needle.isEmpty() || Texts.fold(c.name()).contains(needle) || Texts.fold(c.nature().label()).contains(needle));
            if (shown.size() == 1) {
                list.getSelectionModel().selectFirst();
            }
        });
        Category common = selection.stream().map(ClassifiedOperation::category).distinct().count() == 1
                ? selection.getFirst().category() : null;
        if (common != null && !common.isFallback()) {
            list.getSelectionModel().select(common);
            list.scrollTo(common);
        }

        // ---- la règle facultative
        CheckBox similar = new CheckBox("Classer aussi les opérations semblables, passées et à venir (crée une règle)");
        TextField keyword = new TextField(Classifier.suggestKeyword(first));
        keyword.setPromptText("Texte à reconnaître dans le libellé");
        TextField poste = new TextField(selection.getFirst().poste());
        poste.setPromptText("Nom du commerçant ou du tiers");
        Label reach = Ui.label("", Styles.TEXT_SMALL, Styles.TEXT_MUTED);
        reach.setWrapText(true);
        reach.setMinHeight(34); // deux lignes : la fenêtre ne change pas de taille quand le texte s'allonge
        GridPane ruleForm = Ui.form("Mot-clé", keyword, "Nom du poste", poste);
        ruleForm.add(reach, 1, 2);
        ruleForm.getStyleClass().add("rule-form");
        ruleForm.visibleProperty().bind(similar.selectedProperty());
        ruleForm.managedProperty().bind(similar.selectedProperty());
        Runnable preview = () -> {
            List<String> keywords = Texts.parseKeywords(keyword.getText());
            if (keywords.isEmpty()) {
                reach.setText("Indiquez le texte qui permet de reconnaître ces opérations.");
                return;
            }
            Predicate<Operation> matcher = Classifier.matcher(Rule.create(0, null, first.type(), keywords));
            long total = model.operations.stream().filter(o -> matcher.test(o.operation())).count();
            long chosen = selection.stream().filter(o -> matcher.test(o.operation())).count();
            reach.setText("Une règle « " + first.type() + " » sera créée : " + Fmt.plural((int) total, "opération", "opérations")
                    + " de la base contiennent ce mot-clé, dont " + chosen + " sur les " + selection.size() + " choisies.");
        };
        keyword.textProperty().addListener((_, _, _) -> preview.run());
        preview.run();
        Ui.refitOnChange(this, similar.selectedProperty());

        DialogContent content = new DialogContent(580, 14, summary, new VBox(6, filter, list), similar, ruleForm);
        getDialogPane().setContent(content);

        ButtonType ok = new ButtonType("Catégoriser", ButtonData.OK_DONE);
        ButtonType automatic = new ButtonType("Classement automatique", ButtonData.LEFT);
        getDialogPane().getButtonTypes().addAll(ok, ButtonType.CANCEL);
        if (selection.stream().anyMatch(ClassifiedOperation::isPinned)) {
            getDialogPane().getButtonTypes().add(automatic);
        }
        getDialogPane().lookupButton(ok).disableProperty().bind(list.getSelectionModel().selectedItemProperty().isNull()
                .or(similar.selectedProperty().and(Bindings.createBooleanBinding(
                        () -> Texts.parseKeywords(keyword.getText()).isEmpty(), keyword.textProperty()))));
        setResultConverter(button -> {
            if (button == automatic) {
                return new Choice(null, null);
            }
            Category chosen = list.getSelectionModel().getSelectedItem();
            if (button != ok || chosen == null) {
                return null;
            }
            return new Choice(chosen, !similar.isSelected() ? null
                    : Rule.create(chosen.id(), poste.getText(), first.type(), Texts.parseKeywords(keyword.getText())));
        });
        filter.requestFocus();
    }
}
