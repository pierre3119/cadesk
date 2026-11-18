package io.github.pierre3119.cadesk.ui;

import atlantafx.base.theme.Styles;
import io.github.pierre3119.cadesk.analysis.Analysis;
import io.github.pierre3119.cadesk.categorize.Texts;
import io.github.pierre3119.cadesk.domain.Category;
import io.github.pierre3119.cadesk.domain.ClassifiedOperation;
import io.github.pierre3119.cadesk.domain.Money;
import io.github.pierre3119.cadesk.domain.Nature;
import io.github.pierre3119.cadesk.domain.Rule;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import org.kordamp.ikonli.feather.Feather;
import org.kordamp.ikonli.javafx.FontIcon;

/**
 * Paramétrage du classement : à gauche les catégories, regroupées par nature ; à droite la catégorie
 * choisie et ses règles, ou la liste de toutes les règles dans leur ordre de priorité.
 */
final class CategoriesView extends AnalysisPage {

    /** Choix « Toutes les règles » dans la liste de gauche. */
    private static final Object ALL_RULES = new Object();

    private final VBox list = new VBox(1);
    private final VBox detail = new VBox(14);
    private final VBox allCards = new VBox(8);
    private final ScrollPane allCardsScroll = Ui.scroll(allCards);
    private final TextField ruleSearch = new TextField();
    private final Map<Object, Node> rows = new HashMap<>();
    private final Map<Long, Integer> reach = new HashMap<>();
    /** {@link #ALL_RULES}, l'identifiant d'une catégorie, ou {@code null} tant que rien n'est choisi. */
    private Object selection;
    private Analysis analysis;

    CategoriesView(AppModel model, Navigator navigator) {
        super(model, navigator);
        Button add = new Button("Nouvelle catégorie", new FontIcon(Feather.PLUS));
        add.getStyleClass().add(Styles.SMALL);
        add.setOnAction(_ -> editCategory(null));
        HBox listHeader = new HBox(Ui.label("Catégories", "card-title"), Ui.spacer(), add);
        listHeader.setAlignment(Pos.CENTER_LEFT);
        list.getStyleClass().add("list-body");
        ScrollPane listScroll = Ui.scroll(list);
        VBox left = new VBox(12, listHeader, listScroll);
        left.getStyleClass().add("card");
        left.setMinWidth(350);
        left.setPrefWidth(380);
        left.setMaxWidth(380);

        detail.getStyleClass().add("card");
        HBox.setHgrow(detail, Priority.ALWAYS);
        HBox body = new HBox(18, left, detail);
        VBox.setVgrow(body, Priority.ALWAYS);

        ruleSearch.setPromptText("Rechercher une règle : mot-clé, poste, catégorie…");
        ruleSearch.textProperty().addListener((_, _, _) -> {
            if (selection == ALL_RULES) {
                fillAllRules();
            }
        });
        getChildren().addAll(Ui.header("Catégories et règles", subtitle), body);
    }

    @Override
    protected void rebuild(Analysis a) {
        analysis = a;
        subtitle.setText(Fmt.plural(model.categories.size(), "catégorie", "catégories") + "  ·  "
                + Fmt.plural(model.rules.size(), "règle", "règles") + "  ·  une opération va dans la catégorie de la première règle qui la reconnaît");
        reach.clear();
        for (ClassifiedOperation o : model.operations) {
            if (o.rule() != null) {
                reach.merge(o.rule().id(), 1, Integer::sum);
            }
        }
        if (selection == null || selection instanceof Long id && model.categories.stream().noneMatch(c -> c.id() == id)) {
            selection = model.categories.isEmpty() ? ALL_RULES : (Object) model.categories.getFirst().id();
        }

        rows.clear();
        list.getChildren().clear();
        FontIcon all = new FontIcon(Feather.LIST);
        addRow(ALL_RULES, all, "Toutes les règles", Fmt.count(model.rules.size()));
        for (Nature nature : Nature.values()) {
            List<Category> categories = model.categories.stream().filter(c -> c.nature() == nature).toList();
            if (categories.isEmpty()) {
                continue;
            }
            list.getChildren().add(Ui.label(nature.label().toUpperCase(), "list-section", nature.styleClass()));
            for (Category category : categories) {
                addRow(category.id(), Ui.dot(nature), category.name(), a.count(category) == 0 ? "" : Fmt.count(a.count(category)));
            }
        }
        showDetail();
    }

    private void addRow(Object key, Node mark, String text, String count) {
        Label name = Ui.label(text);
        name.setMaxWidth(Double.MAX_VALUE);
        name.setMinWidth(0);
        HBox.setHgrow(name, Priority.ALWAYS);
        Label total = Ui.label(count, Styles.TEXT_SMALL, Styles.TEXT_MUTED);
        total.setMinWidth(Region.USE_PREF_SIZE);
        HBox row = new HBox(10, mark, name, total);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("list-row");
        row.setOnMouseClicked(_ -> {
            selection = key;
            showDetail();
        });
        rows.put(key, row);
        list.getChildren().add(row);
    }

    private void showDetail() {
        rows.forEach((key, row) -> {
            row.getStyleClass().remove("selected");
            if (key.equals(selection)) {
                row.getStyleClass().add("selected");
            }
        });
        if (selection instanceof Long id) {
            model.categories.stream().filter(c -> c.id() == id).findFirst().ifPresent(this::showCategory);
        } else {
            showAllRules();
        }
    }

    // ------------------------------------------------------------------ une catégorie

    private void showCategory(Category category) {
        List<Category> group = model.categories.stream()
                .filter(c -> c.nature() == category.nature() && c.inflow() == category.inflow()).toList();
        int index = group.indexOf(category);
        Button up = Ui.iconButton(Feather.ARROW_UP, "Monter dans le bilan", () -> model.swapCategories(category, group.get(index - 1)));
        up.setDisable(index <= 0);
        Button down = Ui.iconButton(Feather.ARROW_DOWN, "Descendre dans le bilan", () -> model.swapCategories(category, group.get(index + 1)));
        down.setDisable(index < 0 || index >= group.size() - 1);
        Button edit = new Button("Modifier", new FontIcon(Feather.EDIT_2));
        edit.getStyleClass().add(Styles.SMALL);
        edit.setMinWidth(Region.USE_PREF_SIZE);
        edit.setOnAction(_ -> editCategory(category));
        Button delete = Ui.iconButton(Feather.TRASH_2, category.isFallback() ? "Une catégorie par défaut ne se supprime pas"
                : "Supprimer la catégorie", () -> deleteCategory(category));
        delete.getStyleClass().add(Styles.DANGER);
        delete.setDisable(category.isFallback());

        Label name = Ui.label(category.name(), Styles.TITLE_3);
        name.setMinWidth(40);
        name.setTooltip(new Tooltip(category.name()));
        HBox title = new HBox(8, name, Ui.spacer(), up, down, edit, delete);
        title.setAlignment(Pos.CENTER_LEFT);

        int count = analysis.count(category);
        String role = category.nature() == Nature.REVENUS ? category.incomeKind().label() + "  ·  "
                : category.nature() == Nature.INTERNE ? (category.inflow() ? "Reçu de mes comptes  ·  " : "Versé sur mes comptes  ·  ") : "";
        Label info = Ui.label(role + (count == 0 ? "aucune opération sur la période"
                : Fmt.plural(count, "opération", "opérations") + " sur la période  ·  " + Money.format(analysis.total(category))),
                Styles.TEXT_MUTED);
        info.setMinWidth(0);
        Button see = new Button("Voir les opérations", new FontIcon(Feather.LIST));
        see.getStyleClass().addAll(Styles.SMALL, Styles.FLAT, Styles.ACCENT);
        see.setMinWidth(Region.USE_PREF_SIZE);
        see.setOnAction(_ -> navigator.showOperations(category, null));
        see.setDisable(count == 0);
        HBox infoLine = new HBox(10, Ui.chip(category.nature().label(), category.nature()), info, Ui.spacer(), see);
        infoLine.setAlignment(Pos.CENTER_LEFT);

        List<Rule> rules = model.rules.stream().filter(r -> r.categoryId() == category.id()).toList();
        Button addRule = new Button("Nouvelle règle", new FontIcon(Feather.PLUS));
        addRule.getStyleClass().addAll(Styles.SMALL, Styles.ACCENT);
        addRule.setMinWidth(Region.USE_PREF_SIZE);
        addRule.setOnAction(_ -> editRule(null, category));
        HBox rulesHeader = new HBox(10, Ui.label("Règles de classement", "card-title"),
                Ui.label(rules.isEmpty() ? "" : String.valueOf(rules.size()), "count-badge"), Ui.spacer(), addRule);
        rulesHeader.setAlignment(Pos.CENTER_LEFT);

        VBox cards = new VBox(8);
        rules.forEach(r -> cards.getChildren().add(ruleCard(r, false)));
        if (rules.isEmpty()) {
            cards.getChildren().add(Ui.wrapped(category.isFallback()
                    ? "Aucune règle n'est nécessaire ici."
                    : "Aucune règle : cette catégorie ne reçoit que les opérations que vous y rangez à la main.", "empty-text"));
        }
        detail.getChildren().setAll(title, infoLine);
        if (category.isFallback()) {
            detail.getChildren().add(Ui.wrapped("Catégorie par défaut : elle recueille les " + (category.inflow() ? "entrées" : "sorties")
                    + " qu'aucune règle ne reconnaît, en attendant que vous les classiez depuis l'écran « Opérations ».", "callout"));
        }
        detail.getChildren().addAll(rulesHeader, Ui.scroll(cards),
                Ui.wrapped("Une nouvelle règle est placée en tête et passe avant les autres. L'ordre se règle dans "
                        + "« Toutes les règles ».", "hint"));
    }

    // ------------------------------------------------------------------ toutes les règles

    private void showAllRules() {
        Button addRule = new Button("Nouvelle règle", new FontIcon(Feather.PLUS));
        addRule.getStyleClass().addAll(Styles.SMALL, Styles.ACCENT);
        addRule.setOnAction(_ -> editRule(null, null));
        HBox title = new HBox(10, Ui.label("Toutes les règles", Styles.TITLE_3), Ui.label(String.valueOf(model.rules.size()), "count-badge"),
                Ui.spacer(), addRule);
        title.setAlignment(Pos.CENTER_LEFT);
        Label help = Ui.wrapped("Les règles sont essayées de haut en bas : la première qui reconnaît une opération l'emporte. "
                + "Placez donc les règles précises avant les règles générales (l'ordre se modifie quand la recherche est vide).",
                Styles.TEXT_MUTED);
        fillAllRules();
        detail.getChildren().setAll(title, help, ruleSearch, allCardsScroll);
    }

    /** Remplit la liste de toutes les règles d'après la recherche, sans toucher au champ de recherche. */
    private void fillAllRules() {
        String needle = Texts.fold(ruleSearch.getText());
        Map<Long, Category> byId = new HashMap<>();
        model.categories.forEach(c -> byId.put(c.id(), c));
        allCards.getChildren().clear();
        for (Rule rule : model.rules) {
            Category category = byId.get(rule.categoryId());
            String haystack = Texts.fold(String.join(" ", rule.keywords()) + " " + (rule.poste() == null ? "" : rule.poste()) + " "
                    + (category == null ? "" : category.name()) + " " + (rule.operationType() == null ? "" : rule.operationType()));
            if (needle.isEmpty() || haystack.contains(needle)) {
                allCards.getChildren().add(ruleCard(rule, true));
            }
        }
        if (allCards.getChildren().isEmpty()) {
            allCards.getChildren().add(Ui.label("Aucune règle ne correspond à cette recherche.", "empty-text"));
        }
    }

    /**
     * @param global vrai dans la liste de toutes les règles : la carte montre alors la catégorie et les
     *               commandes de priorité
     */
    private Node ruleCard(Rule rule, boolean global) {
        Label poste = rule.poste() != null ? Ui.label(rule.poste(), "rule-poste")
                : Ui.label("Nom du commerçant ou du tiers", "rule-poste", "rule-poste-auto");
        poste.setMinWidth(40);
        HBox head = new HBox(8, poste);
        head.setAlignment(Pos.CENTER_LEFT);
        int count = reach.getOrDefault(rule.id(), 0);
        Label used = Ui.label(count == 0 ? "aucune opération" : Fmt.plural(count, "opération", "opérations"), Styles.TEXT_SMALL,
                count == 0 ? "rule-unused" : Styles.TEXT_MUTED);
        used.setMinWidth(Region.USE_PREF_SIZE);
        head.getChildren().addAll(Ui.spacer(), used);
        if (global) {
            int index = model.rules.indexOf(rule);
            boolean ordered = Texts.fold(ruleSearch.getText()).isEmpty();
            Button first = Ui.iconButton(Feather.CHEVRONS_UP, "Placer en tête : la règle passera avant toutes les autres",
                    () -> model.moveRuleFirst(rule));
            Button up = Ui.iconButton(Feather.ARROW_UP, "Monter d'un rang", () -> model.swapRules(rule, model.rules.get(index - 1)));
            Button down = Ui.iconButton(Feather.ARROW_DOWN, "Descendre d'un rang", () -> model.swapRules(rule, model.rules.get(index + 1)));
            first.setDisable(!ordered || index <= 0);
            up.setDisable(!ordered || index <= 0);
            down.setDisable(!ordered || index < 0 || index >= model.rules.size() - 1);
            head.getChildren().addAll(first, up, down);
        }
        Button delete = Ui.iconButton(Feather.TRASH_2, "Supprimer la règle", () -> deleteRule(rule));
        delete.getStyleClass().add(Styles.DANGER);
        head.getChildren().addAll(Ui.iconButton(Feather.EDIT_2, "Modifier la règle", () -> editRule(rule, null)), delete);

        FlowPane keywords = new FlowPane(6, 6);
        if (global) {
            model.categories.stream().filter(c -> c.id() == rule.categoryId()).findFirst().ifPresent(c -> {
                Label chip = Ui.chip(c);
                chip.setOnMouseClicked(_ -> {
                    selection = c.id();
                    showDetail();
                });
                chip.getStyleClass().add("clickable");
                keywords.getChildren().addAll(chip, new FontIcon(Feather.ARROW_LEFT));
            });
        }
        if (rule.operationType() != null) {
            keywords.getChildren().add(Ui.label(rule.operationType(), "type-badge"));
        }
        if (rule.keywords().isEmpty()) {
            keywords.getChildren().add(Ui.label("toutes les opérations de ce type", Styles.TEXT_SMALL, Styles.TEXT_MUTED));
        }
        rule.keywords().forEach(k -> keywords.getChildren().add(Ui.label(Texts.displayKeyword(k), "keyword")));
        keywords.setAlignment(Pos.CENTER_LEFT);
        VBox card = new VBox(8, head, keywords);
        card.getStyleClass().add("rule-card");
        return card;
    }

    // ------------------------------------------------------------------ actions

    private void editCategory(Category category) {
        Nature preset = selection instanceof Long id
                ? model.categories.stream().filter(c -> c.id() == id).map(Category::nature).findFirst().orElse(Nature.VARIABLES)
                : Nature.VARIABLES;
        new CategoryDialog(category, preset, List.copyOf(model.categories), this).showAndWait().ifPresent(model::saveCategory);
    }

    private void deleteCategory(Category category) {
        long rules = model.rules.stream().filter(r -> r.categoryId() == category.id()).count();
        long operations = model.operations.stream().filter(o -> o.category().id() == category.id()).count();
        String consequence = (rules == 0 ? "" : Fmt.plural((int) rules, "règle sera supprimée", "règles seront supprimées") + " avec elle. ")
                + (operations == 0 ? "Aucune opération n'y est classée."
                        : Fmt.plural((int) operations, "opération y est classée : elle sera reclassée", "opérations y sont classées : elles seront reclassées")
                                + " par les autres règles, ou à défaut dans « à classer ».");
        if (confirm("Supprimer la catégorie « " + category.name() + " » ?", consequence)) {
            selection = null;
            model.deleteCategory(category);
        }
    }

    private void editRule(Rule rule, Category category) {
        new RuleDialog(model, rule, category, this).showAndWait().ifPresent(model::saveRule);
    }

    private void deleteRule(Rule rule) {
        int count = reach.getOrDefault(rule.id(), 0);
        String name = rule.poste() != null ? rule.poste() : rule.keywords().isEmpty() ? rule.operationType()
                : Texts.displayKeyword(rule.keywords().getFirst());
        if (confirm("Supprimer la règle « " + name + " » ?", count == 0 ? "Elle ne classe aucune opération pour l'instant."
                : Fmt.plural(count, "opération sera reclassée", "opérations seront reclassées") + " par les règles suivantes, ou à défaut dans « à classer ».")) {
            model.deleteRule(rule);
        }
    }

    private boolean confirm(String question, String consequence) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, consequence, new ButtonType("Supprimer", ButtonType.OK.getButtonData()),
                ButtonType.CANCEL);
        Ui.style(alert, this);
        alert.setTitle(App.NAME);
        alert.setHeaderText(question);
        return alert.showAndWait().filter(b -> b.getButtonData() == ButtonType.OK.getButtonData()).isPresent();
    }
}
