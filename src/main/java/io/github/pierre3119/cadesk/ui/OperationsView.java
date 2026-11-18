package io.github.pierre3119.cadesk.ui;

import atlantafx.base.theme.Styles;
import io.github.pierre3119.cadesk.analysis.Period;
import io.github.pierre3119.cadesk.categorize.Texts;
import io.github.pierre3119.cadesk.domain.Category;
import io.github.pierre3119.cadesk.domain.ClassifiedOperation;
import io.github.pierre3119.cadesk.domain.ClassifiedOperation.Source;
import io.github.pierre3119.cadesk.domain.Money;
import io.github.pierre3119.cadesk.domain.Nature;
import io.github.pierre3119.cadesk.domain.Operation;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import org.kordamp.ikonli.feather.Feather;
import org.kordamp.ikonli.javafx.FontIcon;

/**
 * Liste des opérations avec leur catégorie : recherche, filtres par période, mois et catégorie, et
 * classement à la main d'une ou de plusieurs opérations.
 */
final class OperationsView extends VBox {

    private static final String ALL_MONTHS = "Tous les mois";
    private static final String ALL_CATEGORIES = "Toutes les catégories";

    private final AppModel model;
    private final FilteredList<ClassifiedOperation> filtered;
    private final TableView<ClassifiedOperation> table = new TableView<>();
    private final TextField search = new TextField();
    /** Période affichée ici : un filtre propre à cet écran, distinct de la période des écrans de bilan. */
    private final ObjectProperty<Period> period = new SimpleObjectProperty<>(Period.ALL);
    private final ComboBox<String> month = new ComboBox<>();
    /** Contient {@link #ALL_CATEGORIES}, des natures (toutes leurs catégories) et des catégories. */
    private final ComboBox<Object> category = new ComboBox<>();
    private final ToggleButton pendingOnly = new ToggleButton("À classer");
    private final Button categorize = new Button("Catégoriser…", new FontIcon(Feather.TAG));
    private final Label summary = new Label();
    private final ObservableList<YearMonth> monthValues = FXCollections.observableArrayList();
    private boolean refreshing;

    OperationsView(AppModel model) {
        this.model = model;
        getStyleClass().add("page");
        filtered = new FilteredList<>(model.operations);

        Label subtitle = new Label("Double-cliquez sur une opération, ou sélectionnez-en plusieurs, pour choisir sa catégorie à la main.");
        search.setPromptText("Rechercher un libellé, un poste…");
        HBox.setHgrow(search, Priority.ALWAYS);
        search.setMinWidth(140);
        category.setCellFactory(_ -> new CategoryChoiceCell(true));
        category.setButtonCell(new CategoryChoiceCell(false));
        category.setVisibleRowCount(18);
        category.setPrefWidth(250);
        pendingOnly.setGraphic(new FontIcon(Feather.HELP_CIRCLE));
        pendingOnly.setTooltip(new Tooltip("N'afficher que les opérations qu'aucune règle n'a reconnues"));
        pendingOnly.setMinWidth(Region.USE_PREF_SIZE);
        HBox filters = new HBox(10, search, new PeriodPicker(model, period), month, category, pendingOnly);
        filters.setAlignment(Pos.CENTER_LEFT);
        search.textProperty().addListener((_, _, _) -> applyFilters());
        month.valueProperty().addListener((_, _, _) -> applyFilters());
        category.valueProperty().addListener((_, _, _) -> applyFilters());
        pendingOnly.selectedProperty().addListener((_, _, _) -> applyFilters());
        period.addListener((_, _, _) -> {
            if (!refreshing) {
                refreshChoices();
            }
        });

        buildTable();
        SortedList<ClassifiedOperation> sorted = new SortedList<>(filtered);
        sorted.comparatorProperty().bind(table.comparatorProperty());
        table.setItems(sorted);
        VBox.setVgrow(table, Priority.ALWAYS);

        categorize.getStyleClass().add(Styles.ACCENT);
        categorize.setMinWidth(Region.USE_PREF_SIZE);
        categorize.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        categorize.setOnAction(_ -> categorizeSelection());
        summary.getStyleClass().add(Styles.TEXT_MUTED);
        summary.setMinWidth(0);
        HBox footer = new HBox(14, summary, Ui.spacer(), categorize);
        footer.setAlignment(Pos.CENTER_LEFT);

        getChildren().addAll(Ui.header("Opérations", subtitle), filters, table, footer);

        // Différé : à cet instant la liste filtrée n'a pas encore intégré le changement de sa source,
        // la parcourir lèverait une exception.
        model.analysis.addListener((_, _, _) -> Platform.runLater(this::refreshChoices));
        refreshChoices();
    }

    // ------------------------------------------------------------------ navigation depuis les autres écrans

    /** Affiche les opérations d'une catégorie (ou de toutes) et d'un poste (ou de tous) sur une période. */
    void show(Category wantedCategory, String poste, Period wantedPeriod) {
        refreshing = true;
        try {
            period.set(model.periods.contains(wantedPeriod) ? wantedPeriod : Period.ALL);
            pendingOnly.setSelected(false);
            search.setText(poste == null ? "" : poste);
        } finally {
            refreshing = false;
        }
        refreshChoices();
        month.setValue(ALL_MONTHS);
        category.setValue(wantedCategory == null ? ALL_CATEGORIES : category.getItems().stream()
                .filter(item -> item instanceof Category c && c.id() == wantedCategory.id()).findFirst().orElse(ALL_CATEGORIES));
    }

    /** Affiche, sur tout l'historique, les opérations qui attendent une catégorie. */
    void showUnclassified() {
        show(null, null, Period.ALL);
        pendingOnly.setSelected(true);
    }

    // ------------------------------------------------------------------ tableau

    private void buildTable() {
        table.getStyleClass().addAll(Styles.STRIPED, Styles.DENSE, "operations");
        table.setPlaceholder(new Label("Aucune opération ne correspond à ces critères."));
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        TableColumn<ClassifiedOperation, LocalDate> date = new TableColumn<>("Date");
        date.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().operation().date()));
        date.setCellFactory(_ -> new TableCell<>() {
            @Override
            protected void updateItem(LocalDate d, boolean empty) {
                super.updateItem(d, empty);
                setText(empty || d == null ? null : Fmt.date(d));
            }
        });
        date.setPrefWidth(100);

        TableColumn<ClassifiedOperation, ClassifiedOperation> label = new TableColumn<>("Libellé");
        label.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        label.setComparator(Comparator.comparing(o -> o.operation().title(), String.CASE_INSENSITIVE_ORDER));
        label.setCellFactory(_ -> new TableCell<>() {
            @Override
            protected void updateItem(ClassifiedOperation o, boolean empty) {
                super.updateItem(o, empty);
                setText(empty || o == null ? null : o.operation().title());
                setTooltip(empty || o == null ? null : new Tooltip(describe(o)));
            }
        });

        TableColumn<ClassifiedOperation, ClassifiedOperation> categoryColumn = new TableColumn<>("Catégorie");
        categoryColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        categoryColumn.setComparator(Comparator.comparing((ClassifiedOperation o) -> o.category().position())
                .thenComparing(o -> o.category().name()));
        categoryColumn.setCellFactory(_ -> new TableCell<>() {
            @Override
            protected void updateItem(ClassifiedOperation o, boolean empty) {
                super.updateItem(o, empty);
                if (empty || o == null) {
                    setGraphic(null);
                    return;
                }
                HBox box = new HBox(6, Ui.chip(o.category()));
                box.setAlignment(Pos.CENTER_LEFT);
                if (o.isPinned()) {
                    boolean assisted = o.source() == Source.ASSISTANT;
                    FontIcon mark = new FontIcon(assisted ? Feather.CPU : Feather.EDIT_3);
                    mark.getStyleClass().add("manual-mark");
                    Tooltip.install(mark, new Tooltip(assisted ? "Catégorie attribuée par l'assistant" : "Catégorie choisie à la main"));
                    box.getChildren().add(mark);
                }
                setGraphic(box);
            }
        });
        categoryColumn.setPrefWidth(292);

        TableColumn<ClassifiedOperation, String> poste = new TableColumn<>("Poste");
        poste.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().poste()));
        poste.setComparator(String.CASE_INSENSITIVE_ORDER);
        poste.setPrefWidth(190);

        TableColumn<ClassifiedOperation, Long> amount = new TableColumn<>("Montant");
        amount.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().operation().amountCents()));
        amount.setCellFactory(_ -> new TableCell<>() {
            @Override
            protected void updateItem(Long cents, boolean empty) {
                super.updateItem(cents, empty);
                getStyleClass().removeAll("amount-in", "amount-out");
                if (empty || cents == null) {
                    setText(null);
                } else {
                    setText(Money.formatSigned(cents));
                    getStyleClass().add(cents > 0 ? "amount-in" : "amount-out");
                }
            }
        });
        amount.getStyleClass().add("amount-column");
        amount.setPrefWidth(125);

        table.getColumns().add(date);
        table.getColumns().add(label);
        table.getColumns().add(categoryColumn);
        table.getColumns().add(poste);
        table.getColumns().add(amount);
        // le libellé occupe la largeur restante
        label.prefWidthProperty().bind(table.widthProperty().subtract(date.widthProperty()).subtract(categoryColumn.widthProperty())
                .subtract(poste.widthProperty()).subtract(amount.widthProperty()).subtract(18));

        MenuItem choose = new MenuItem("Catégoriser…", new FontIcon(Feather.TAG));
        choose.setOnAction(_ -> categorizeSelection());
        MenuItem automatic = new MenuItem("Revenir au classement automatique", new FontIcon(Feather.ROTATE_CCW));
        automatic.setOnAction(_ -> model.categorize(selection().stream().map(o -> o.operation().id()).toList(), null));
        ContextMenu menu = new ContextMenu(choose, automatic);
        menu.setOnShowing(_ -> automatic.setDisable(selection().stream().noneMatch(ClassifiedOperation::isPinned)));
        table.setRowFactory(_ -> {
            TableRow<ClassifiedOperation> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && !row.isEmpty()) {
                    categorizeSelection();
                }
            });
            row.contextMenuProperty().bind(Bindings.when(row.emptyProperty()).then((ContextMenu) null).otherwise(menu));
            return row;
        });
    }

    private static String describe(ClassifiedOperation o) {
        Operation op = o.operation();
        String origin = switch (o.source()) {
            case MANUAL -> "Catégorie choisie à la main";
            case ASSISTANT -> "Catégorie attribuée par l'assistant (activité semblable déjà classée, ou proposition du LLM)";
            case RULE -> "Classée par une règle" + (o.rule().keywords().isEmpty() ? " sur le type d'opération"
                    : " (mot-clé « " + String.join(" », « ", o.rule().keywords().stream().map(Texts::displayKeyword).limit(4).toList())
                            + (o.rule().keywords().size() > 4 ? " »…)" : " »)"));
            case DEFAULT -> "Reconnue par aucune règle";
        };
        return op.type() + (op.label().isBlank() ? "" : "\n" + op.label()) + "\n\n" + origin;
    }

    private List<ClassifiedOperation> selection() {
        return table.getSelectionModel().getSelectedItems().stream().filter(Objects::nonNull).toList();
    }

    private void categorizeSelection() {
        List<ClassifiedOperation> selected = selection();
        if (selected.isEmpty()) {
            return;
        }
        List<Long> ids = selected.stream().map(o -> o.operation().id()).toList();
        new CategorizeDialog(model, selected, this).showAndWait().ifPresent(choice -> {
            if (choice.rule() != null) {
                model.categorizeWithRule(ids, choice.rule());
            } else {
                model.categorize(ids, choice.category());
            }
        });
    }

    // ------------------------------------------------------------------ filtres

    /** Met à jour les listes de choix d'après les données, en conservant les choix courants. */
    private void refreshChoices() {
        refreshing = true;
        try {
            if (period.get() == null || !model.periods.contains(period.get())) {
                Period previous = period.get();
                period.set(model.periods.stream().filter(p -> previous != null && p.label().equals(previous.label())).findFirst()
                        .orElse(Period.ALL));
            }
            Period current = period.get();
            List<YearMonth> months = model.operations.stream().map(o -> o.operation().date()).filter(current::contains)
                    .map(YearMonth::from).distinct().sorted(Comparator.reverseOrder()).toList();
            monthValues.setAll(months);
            List<String> monthLabels = new ArrayList<>(List.of(ALL_MONTHS));
            months.forEach(m -> monthLabels.add(Fmt.month(m)));
            String previousMonth = month.getValue();
            month.getItems().setAll(monthLabels);
            month.setValue(monthLabels.contains(previousMonth) ? previousMonth : ALL_MONTHS);

            Object previous = category.getValue();
            List<Object> choices = new ArrayList<>(List.of(ALL_CATEGORIES));
            for (Nature nature : Nature.values()) {
                List<Category> ofNature = model.categories.stream().filter(c -> c.nature() == nature).toList();
                if (!ofNature.isEmpty()) {
                    choices.add(nature);
                    choices.addAll(ofNature);
                }
            }
            category.getItems().setAll(choices);
            category.setValue(choices.stream().filter(item -> same(item, previous)).findFirst().orElse(ALL_CATEGORIES));
        } finally {
            refreshing = false;
        }
        applyFilters();
    }

    private static boolean same(Object item, Object previous) {
        return item instanceof Category a && previous instanceof Category b ? a.id() == b.id() : item.equals(previous);
    }

    private void applyFilters() {
        if (refreshing) {
            return;
        }
        String needle = Texts.fold(search.getText());
        Period wantedPeriod = period.get() == null ? Period.ALL : period.get();
        int monthIndex = month.getSelectionModel().getSelectedIndex() - 1;
        YearMonth wantedMonth = monthIndex >= 0 && monthIndex < monthValues.size() ? monthValues.get(monthIndex) : null;
        Object wanted = category.getValue();
        boolean pending = pendingOnly.isSelected();

        filtered.setPredicate(o -> wantedPeriod.contains(o.operation().date())
                && (wantedMonth == null || YearMonth.from(o.operation().date()).equals(wantedMonth))
                && (!(wanted instanceof Nature nature) || o.nature() == nature)
                && (!(wanted instanceof Category c) || o.category().id() == c.id())
                && (!pending || o.isUnclassified())
                && (needle.isEmpty() || Texts.fold(o.operation().label()).contains(needle) || Texts.fold(o.poste()).contains(needle)
                        || Texts.fold(o.category().name()).contains(needle) || Texts.fold(o.operation().type()).contains(needle)));

        long unclassified = model.operations.stream().filter(o -> wantedPeriod.contains(o.operation().date()) && o.isUnclassified()).count();
        pendingOnly.setText(unclassified == 0 ? "À classer" : "À classer  ·  " + unclassified);
        long in = filtered.stream().mapToLong(o -> o.operation().amountCents()).filter(a -> a > 0).sum();
        long out = -filtered.stream().mapToLong(o -> o.operation().amountCents()).filter(a -> a < 0).sum();
        summary.setText(Fmt.plural(filtered.size(), "opération", "opérations")
                + "   ·   entrées " + Money.format(in) + "   ·   sorties " + Money.format(out)
                + "   ·   solde " + Money.formatSigned(in - out));
    }

    /** Ligne de la liste des catégories : une nature en gras, ses catégories en retrait (dans la liste déroulante seulement). */
    private static final class CategoryChoiceCell extends ListCell<Object> {

        private final boolean indent;

        CategoryChoiceCell(boolean indent) {
            this.indent = indent;
        }

        @Override
        protected void updateItem(Object item, boolean empty) {
            super.updateItem(item, empty);
            getStyleClass().removeAll("choice-nature", "choice-category");
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
            } else if (item instanceof Nature nature) {
                setText(nature.label());
                setGraphic(Ui.dot(nature));
                getStyleClass().add("choice-nature");
            } else if (item instanceof Category c) {
                setText(c.name());
                setGraphic(indent ? null : Ui.dot(c.nature()));
                if (indent) {
                    getStyleClass().add("choice-category");
                }
            } else {
                setText(item.toString());
                setGraphic(null);
            }
        }
    }
}
