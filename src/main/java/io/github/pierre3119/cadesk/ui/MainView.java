package io.github.pierre3119.cadesk.ui;

import atlantafx.base.theme.PrimerDark;
import atlantafx.base.theme.PrimerLight;
import atlantafx.base.theme.Styles;
import io.github.pierre3119.cadesk.AppConfig;
import io.github.pierre3119.cadesk.domain.Account;
import io.github.pierre3119.cadesk.domain.Category;
import io.github.pierre3119.cadesk.domain.ClassifiedOperation;
import io.github.pierre3119.cadesk.statement.StatementReader;
import io.github.pierre3119.cadesk.ui.AppModel.Notice;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javafx.application.Application;
import javafx.beans.InvalidationListener;
import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.DragEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.feather.Feather;
import org.kordamp.ikonli.javafx.FontIcon;

/** Fenêtre principale d'un profil ouvert : barre latérale de navigation, bandeau de message, écran courant. */
final class MainView extends BorderPane implements AnalysisPage.Navigator {

    static final String THEME_SETTING = "theme";

    private final AppModel model;
    private final Shell shell;
    private final StackPane content = new StackPane();
    private final VBox noticeBar = new VBox();
    private final ToggleGroup navigation = new ToggleGroup();
    private final List<ToggleButton> items = new ArrayList<>();
    private final List<Node> pages = new ArrayList<>();
    private final Label pendingBadge = new Label();
    private final OperationsView operations;
    private final ProfileView profile;
    private final Node welcome;
    private boolean dark;

    MainView(AppModel model, Shell shell) {
        this.model = model;
        this.shell = shell;
        this.dark = model.setting(THEME_SETTING).map("dark"::equals).orElse(false);
        applyTheme();

        operations = new OperationsView(model);
        profile = new ProfileView(model, shell);
        welcome = welcome();

        ProgressBar progress = new ProgressBar();
        progress.setMaxWidth(Double.MAX_VALUE);
        progress.getStyleClass().add(Styles.SMALL);
        progress.visibleProperty().bind(model.busy);

        VBox center = new VBox(progress, noticeBar, content);
        if (model.session.isGuest()) {
            center.getChildren().add(1, guestBar());
        }
        VBox.setVgrow(content, Priority.ALWAYS);
        content.getStyleClass().add("content");
        setLeft(sidebar());
        setCenter(center);
        acceptDroppedStatements();

        navigation.selectedToggleProperty().addListener((_, old, selected) -> {
            if (selected == null) {
                navigation.selectToggle(old); // un écran reste toujours sélectionné
            } else {
                showPage();
            }
        });
        model.accounts.addListener((InvalidationListener) _ -> showPage());
        model.analysis.addListener((_, _, _) -> updatePendingBadge());
        model.notice.addListener((_, _, notice) -> showNotice(notice));
        updatePendingBadge();
        showPage();
    }

    private void showPage() {
        int index = Math.max(items.indexOf((ToggleButton) navigation.getSelectedToggle()), 0);
        Node page = pages.get(index);
        // sans aucun relevé, les écrans de données laissent la place à l'invitation à importer ; les réglages restent accessibles
        boolean settings = page == profile || page instanceof AssistantView;
        Node shown = model.accounts.isEmpty() && !settings ? welcome : page;
        if (content.getChildren().size() != 1 || content.getChildren().getFirst() != shown) {
            content.getChildren().setAll(shown);
        }
    }

    /** Affiche l'écran « Profil et sécurité ». */
    void showProfile() {
        navigation.selectToggle(items.get(pages.indexOf(profile)));
    }

    /** Un relevé, CSV ou OFX, déposé sur la fenêtre est importé. */
    private void acceptDroppedStatements() {
        setOnDragOver(e -> {
            if (!statementFiles(e).isEmpty() && !model.busy.get()) {
                e.acceptTransferModes(TransferMode.COPY);
            }
            e.consume();
        });
        setOnDragDropped(e -> {
            List<Path> files = statementFiles(e);
            if (!files.isEmpty()) {
                model.importFiles(files);
            }
            e.setDropCompleted(!files.isEmpty());
            e.consume();
        });
    }

    private static List<Path> statementFiles(DragEvent e) {
        return !e.getDragboard().hasFiles() ? List.of() : e.getDragboard().getFiles().stream()
                .filter(f -> f.isFile() && StatementReader.hasStatementExtension(f.getName())).map(File::toPath).toList();
    }

    // ------------------------------------------------------------------ navigation entre écrans

    @Override
    public void showOperations(Category category, String poste) {
        operations.show(category, poste, model.period.get());
        navigation.selectToggle(items.get(pages.indexOf(operations)));
    }

    @Override
    public void showUnclassified() {
        operations.showUnclassified();
        navigation.selectToggle(items.get(pages.indexOf(operations)));
    }

    private void updatePendingBadge() {
        long pending = model.operations.stream().filter(ClassifiedOperation::isUnclassified).count();
        pendingBadge.setText(String.valueOf(pending));
        pendingBadge.setVisible(pending > 0);
        Tooltip.install(pendingBadge, new Tooltip(Fmt.plural((int) pending, "opération à classer", "opérations à classer")));
    }

    // ------------------------------------------------------------------ barre latérale

    private Node sidebar() {
        FontIcon logo = new FontIcon(Feather.CREDIT_CARD);
        logo.getStyleClass().add("brand-icon");
        Label name = new Label(App.NAME);
        name.getStyleClass().addAll(Styles.TITLE_3, "brand-name");
        HBox brand = new HBox(10, logo, name);
        brand.setAlignment(Pos.CENTER_LEFT);
        brand.getStyleClass().add("brand");

        ComboBox<Account> accountChoice = new ComboBox<>(model.accounts);
        accountChoice.setMaxWidth(Double.MAX_VALUE);
        accountChoice.setConverter(new StringConverter<>() {
            @Override
            public String toString(Account a) {
                return a == null ? "" : a.label() + "  " + a.maskedNumber();
            }

            @Override
            public Account fromString(String s) {
                return null;
            }
        });
        accountChoice.valueProperty().bindBidirectional(model.account);
        accountChoice.visibleProperty().bind(Bindings.size(model.accounts).greaterThan(1));
        accountChoice.managedProperty().bind(accountChoice.visibleProperty());

        VBox box = new VBox(4, brand, accountChoice);
        box.getChildren().add(Ui.label("ANALYSE", "nav-section"));
        box.getChildren().add(navItem("Synthèse", Feather.PIE_CHART, new SynthesisView(model, this)));
        box.getChildren().add(navItem("Bilan mensuel", Feather.GRID, new BilanView(model, this)));
        box.getChildren().add(navItem("Charges fixes", Feather.REPEAT, new FixedView(model, this)));
        box.getChildren().add(navItem("Dépenses variables", Feather.SHOPPING_CART, new VariableView(model, this)));
        box.getChildren().add(navItem("Économies", Feather.TRENDING_UP, new SavingsView(model, this)));
        box.getChildren().add(Ui.label("DONNÉES", "nav-section"));
        ToggleButton operationsItem = navItem("Opérations", Feather.LIST, operations);
        pendingBadge.getStyleClass().add("nav-badge");
        StackPane.setAlignment(pendingBadge, Pos.CENTER_RIGHT);
        StackPane.setMargin(pendingBadge, new Insets(0, 10, 0, 0));
        pendingBadge.setOnMouseClicked(_ -> showUnclassified());
        box.getChildren().add(new StackPane(operationsItem, pendingBadge));
        box.getChildren().add(navItem("Catégories", Feather.TAG, new CategoriesView(model, this)));
        box.getChildren().add(navItem("Imports", Feather.ARCHIVE, new ImportsView(model)));
        box.getChildren().add(Ui.label("RÉGLAGES", "nav-section"));
        box.getChildren().add(navItem("Assistant", Feather.CPU, new AssistantView(model, shell)));
        box.getChildren().add(navItem("Profil et sécurité", Feather.USER, profile));
        items.getFirst().setSelected(true);

        Button importButton = new Button("Importer un relevé", new FontIcon(Feather.UPLOAD));
        importButton.getStyleClass().addAll(Styles.ACCENT, "import-button");
        importButton.setMaxWidth(Double.MAX_VALUE);
        importButton.setOnAction(_ -> shell.chooseFiles());
        importButton.disableProperty().bind(model.busy);

        // l'assistant se coupe et se remet d'un clic, là où l'on importe
        CheckBox assisted = new CheckBox("Classer avec l'assistant");
        assisted.getStyleClass().add("import-option");
        assisted.setTooltip(new Tooltip("Classer automatiquement les opérations nouvelles à chaque import : d'après les activités "
                + "semblables déjà classées, puis avec le modèle de langage réglé dans « Assistant »"));
        assisted.setSelected(model.llm.get().onImport());
        assisted.setOnAction(_ -> model.saveLlm(model.llm.get().withOnImport(assisted.isSelected())));
        assisted.visibleProperty().bind(Bindings.createBooleanBinding(() -> model.llm.get().isConfigured(), model.llm));
        assisted.managedProperty().bind(assisted.visibleProperty());
        model.llm.addListener((_, _, settings) -> assisted.setSelected(settings.onImport()));

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        FontIcon avatar = new FontIcon(Feather.USER);
        Label who = new Label();
        who.getStyleClass().add("profile-name");
        who.textProperty().bind(Bindings.createStringBinding(() -> model.profile.get().name(), model.profile));
        who.setMinWidth(0);
        HBox.setHgrow(who, Priority.ALWAYS);
        who.setMaxWidth(Double.MAX_VALUE);
        Button leave = Ui.iconButton(Feather.LOG_OUT, "Fermer ce profil et en choisir un autre", () -> shell.leave(null));
        leave.disableProperty().bind(model.busy);
        HBox profileBar = new HBox(8, avatar, who, leave);
        profileBar.setAlignment(Pos.CENTER_LEFT);
        profileBar.getStyleClass().add("profile-bar");
        profileBar.setOnMouseClicked(_ -> showProfile());
        VBox.setMargin(profileBar, new Insets(8, 0, 4, 0));

        Path file = model.databaseFile();
        Label base = new Label("Base : " + (file.startsWith(AppConfig.home()) ? AppConfig.home().relativize(file) : file));
        base.getStyleClass().addAll(Styles.TEXT_SMALL, Styles.TEXT_MUTED);
        base.setWrapText(true);
        base.setTooltip(new Tooltip("Base de données du profil : " + file));
        Button theme = new Button();
        theme.getStyleClass().addAll(Styles.BUTTON_ICON, Styles.FLAT, "theme-button");
        theme.setOnAction(_ -> {
            dark = !dark;
            applyTheme();
            updateThemeButton(theme);
            model.saveSetting(THEME_SETTING, dark ? "dark" : "light");
        });
        updateThemeButton(theme);
        Button about = Ui.iconButton(Feather.INFO, "À propos de " + App.NAME, () -> new AboutDialog(this, shell::browse).showAndWait());
        about.getStyleClass().add("about-button");
        HBox footer = new HBox(4, base, about, theme);
        footer.setAlignment(Pos.BOTTOM_LEFT);
        HBox.setHgrow(base, Priority.ALWAYS);

        box.getChildren().addAll(spacer, importButton, assisted, profileBar, footer);
        box.getStyleClass().add("sidebar");
        return box;
    }

    /** Rappel permanent qu'un administrateur consulte le profil d'un autre, avec le chemin du retour. */
    private Node guestBar() {
        Label text = new Label("Vous consultez le profil de " + model.session.profile().name() + " en tant qu'administrateur"
                + model.session.administrator().map(name -> " (" + name + ")").orElse("") + ".");
        text.setWrapText(true);
        HBox.setHgrow(text, Priority.ALWAYS);
        text.setMaxWidth(Double.MAX_VALUE);
        Button back = new Button("Revenir à mon profil", new FontIcon(Feather.CORNER_UP_LEFT));
        back.getStyleClass().add(Styles.SMALL);
        back.setMinWidth(Region.USE_PREF_SIZE);
        back.disableProperty().bind(model.busy);
        back.setOnAction(_ -> model.background(() -> model.profiles.returnHome(model.session), home -> shell.show(home, true)));
        HBox bar = new HBox(12, new FontIcon(Feather.SHIELD), text, back);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().addAll("notice", "notice-guest");
        return bar;
    }

    /** Crée une entrée de navigation et lui associe son écran. */
    private ToggleButton navItem(String text, Ikon icon, Node page) {
        ToggleButton b = new ToggleButton(text, new FontIcon(icon));
        b.setToggleGroup(navigation);
        b.setMaxWidth(Double.MAX_VALUE);
        b.setAlignment(Pos.CENTER_LEFT);
        b.getStyleClass().add("nav-item");
        items.add(b);
        pages.add(page);
        return b;
    }

    private void applyTheme() {
        Application.setUserAgentStylesheet((dark ? new PrimerDark() : new PrimerLight()).getUserAgentStylesheet());
        getStyleClass().remove("dark");
        if (dark) {
            getStyleClass().add("dark");
        }
    }

    private void updateThemeButton(Button theme) {
        theme.setGraphic(new FontIcon(dark ? Feather.SUN : Feather.MOON));
        theme.setTooltip(new Tooltip(dark ? "Passer au thème clair" : "Passer au thème sombre"));
    }

    // ------------------------------------------------------------------ bandeau de message

    private void showNotice(Notice notice) {
        noticeBar.getChildren().clear();
        if (notice == null) {
            return;
        }
        Ikon icon = switch (notice.kind()) {
            case SUCCESS -> Feather.CHECK_CIRCLE;
            case INFO -> Feather.INFO;
            case ERROR -> Feather.ALERT_TRIANGLE;
        };
        Label text = new Label(notice.text());
        text.setWrapText(true);
        HBox.setHgrow(text, Priority.ALWAYS);
        text.setMaxWidth(Double.MAX_VALUE);
        Button close = new Button(null, new FontIcon(Feather.X));
        close.getStyleClass().addAll(Styles.BUTTON_ICON, Styles.FLAT, Styles.SMALL);
        close.setOnAction(_ -> model.notice.set(null));
        HBox bar = new HBox(12, new FontIcon(icon), text, close);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().addAll("notice", "notice-" + notice.kind().name().toLowerCase());
        noticeBar.getChildren().add(bar);
    }

    // ------------------------------------------------------------------ accueil (base vide)

    private Node welcome() {
        FontIcon icon = new FontIcon(Feather.UPLOAD_CLOUD);
        icon.getStyleClass().add("welcome-icon");
        Label title = new Label("Importez votre premier relevé");
        title.getStyleClass().add(Styles.TITLE_2);
        Label text = new Label("Glissez-déposez ici le fichier de vos opérations (CSV ou OFX), téléchargé depuis votre espace "
                + "Crédit Agricole, ou choisissez-le avec le bouton ci-dessous.\n"
                + "Vous pourrez réimporter des relevés qui se recouvrent : les opérations déjà connues ne sont pas dupliquées.");
        text.setWrapText(true);
        text.getStyleClass().add(Styles.TEXT_MUTED);
        text.setMaxWidth(520);
        text.setStyle("-fx-text-alignment: center;");
        Button choose = new Button("Choisir un fichier…", new FontIcon(Feather.UPLOAD));
        choose.getStyleClass().addAll(Styles.ACCENT, Styles.LARGE);
        choose.setOnAction(_ -> shell.chooseFiles());
        choose.disableProperty().bind(model.busy);
        VBox box = new VBox(16, icon, title, text, choose);
        box.setAlignment(Pos.CENTER);
        box.getStyleClass().add("welcome");
        box.setMaxSize(640, 380);
        return box;
    }
}
