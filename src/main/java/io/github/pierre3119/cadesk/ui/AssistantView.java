package io.github.pierre3119.cadesk.ui;

import atlantafx.base.theme.Styles;
import io.github.pierre3119.cadesk.analysis.Period;
import io.github.pierre3119.cadesk.llm.Assistant.Proposal;
import io.github.pierre3119.cadesk.llm.CopilotLogin.Identity;
import io.github.pierre3119.cadesk.llm.LlmProvider;
import io.github.pierre3119.cadesk.llm.LlmSettings;
import io.github.pierre3119.cadesk.ui.AppModel.Notice;
import io.github.pierre3119.cadesk.ui.AppModel.NoticeKind;
import io.github.pierre3119.cadesk.ui.AppModel.Reach;

import java.util.Arrays;

import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import org.kordamp.ikonli.feather.Feather;
import org.kordamp.ikonli.javafx.FontIcon;

/**
 * Assistant : le choix du modèle de langage (LLM) qui aide à classer les opérations, et la passe de
 * recatégorisation des opérations déjà importées, sur tout l'historique ou entre deux dates.
 */
final class AssistantView extends VBox {

    private static final String KEY_TITLE = "Clé d'API";
    private static final String URL_PROMPT = "https://…/v1";
    private static final String COPILOT_NOTE = "GitHub Copilot demande un abonnement Copilot. « Se connecter à GitHub » ouvre votre "
            + "navigateur pour y autoriser la connexion, et en rapporte le jeton ; vous pouvez aussi coller un jeton d'accès personnel à "
            + "permissions fines (github_pat_…) doté de la permission « Copilot Requests » — les jetons classiques (ghp_…) ne conviennent "
            + "pas. Chaque question posée au modèle est décomptée de l'abonnement. Le moteur de Copilot, embarqué dans l'application, "
            + "s'installe à la première utilisation dans le dossier .copilot/runtime-cache de votre dossier personnel ; il est lancé "
            + "sans aucun outil ni accès à vos fichiers, et ne se sert ni des réglages ni de la connexion de Copilot CLI.";

    private final AppModel model;
    private final Shell shell;
    private final ComboBox<LlmProvider> provider = new ComboBox<>(FXCollections.observableArrayList(LlmProvider.values()));
    private final TextField url = new TextField();
    private final Label keyTitle = new Label(KEY_TITLE);
    private final PasswordField key = new PasswordField();
    private final Label providerNote = Ui.wrapped("", "hint");
    private final ComboBox<String> modelName = new ComboBox<>();
    private final CheckBox onImport = new CheckBox("Classer automatiquement les opérations nouvelles à chaque import");
    private final Label status = Ui.wrapped("", Styles.TEXT_SMALL);
    private final RadioButton everything = new RadioButton("Tout l'historique");
    private final RadioButton between = new RadioButton("Entre deux dates");
    private final DatePicker from = Ui.datePicker("Du");
    private final DatePicker to = Ui.datePicker("Au");
    private final ComboBox<Reach> reach = new ComboBox<>(FXCollections.observableArrayList(Reach.values()));

    AssistantView(AppModel model, Shell shell) {
        this.model = model;
        this.shell = shell;
        getStyleClass().add("page");
        Label subtitle = new Label("Un modèle de langage de votre choix aide à classer les opérations que les règles ne reconnaissent pas.");
        VBox body = new VBox(18, settings(), pass());
        body.getStyleClass().add("page-body");
        getChildren().addAll(Ui.header("Assistant", subtitle), Ui.scroll(body));
        show(model.llm.get());
        // réglages modifiés ailleurs (la case de la barre latérale ne touche qu'au classement à l'import)
        model.llm.addListener((_, _, saved) -> {
            if (saved.withOnImport(false).equals(entered().withOnImport(false))) {
                onImport.setSelected(saved.onImport());
            } else {
                show(saved);
            }
        });
    }

    // ------------------------------------------------------------------ réglages du modèle

    private Node settings() {
        provider.setPromptText("Choisir un service");
        provider.setMaxWidth(Double.MAX_VALUE);
        provider.valueProperty().addListener((_, previous, chosen) -> providerChanged(previous, chosen));
        url.setPromptText(URL_PROMPT);
        providerNote.managedProperty().bind(providerNote.visibleProperty());
        providerNote.visibleProperty().bind(providerNote.textProperty().isNotEmpty());
        modelName.setEditable(true);
        modelName.setPromptText("Nom du modèle");
        modelName.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(modelName, Priority.ALWAYS);
        Button list = new Button("Lister", new FontIcon(Feather.LIST));
        list.setTooltip(new Tooltip("Demander au service la liste de ses modèles"));
        list.setMinWidth(Region.USE_PREF_SIZE);
        list.disableProperty().bind(model.busy.or(provider.valueProperty().isNull()));
        list.setOnAction(_ -> listModels());
        HBox modelRow = new HBox(8, modelName, list);

        // GitHub Copilot : le jeton s'obtient aussi en se connectant à GitHub dans le navigateur
        Button signIn = new Button("Se connecter à GitHub…", new FontIcon(Feather.GITHUB));
        signIn.setTooltip(new Tooltip("Autoriser la connexion dans votre navigateur : le jeton est rapporté ici"));
        signIn.setMinWidth(Region.USE_PREF_SIZE);
        signIn.visibleProperty().bind(provider.valueProperty().isEqualTo(LlmProvider.COPILOT));
        signIn.managedProperty().bind(signIn.visibleProperty());
        signIn.disableProperty().bind(model.busy);
        signIn.setOnAction(_ -> signIn());
        key.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(key, Priority.ALWAYS);
        HBox keyRow = new HBox(8, key, signIn);

        GridPane form = Ui.form("Service", provider, "Adresse de l'API", url, keyTitle, keyRow, "Modèle", modelRow);
        form.setMaxWidth(720);

        Button test = new Button("Tester la connexion", new FontIcon(Feather.ZAP));
        test.disableProperty().bind(model.busy.or(provider.valueProperty().isNull()));
        test.setOnAction(_ -> test());
        Button save = new Button("Enregistrer", new FontIcon(Feather.CHECK));
        save.getStyleClass().add(Styles.ACCENT);
        save.disableProperty().bind(provider.valueProperty().isNull());
        save.setOnAction(_ -> save());
        Button forget = new Button("Oublier ces réglages");
        forget.getStyleClass().add(Styles.FLAT);
        forget.disableProperty().bind(Bindings.createBooleanBinding(() -> model.llm.get().provider() == null, model.llm));
        forget.setOnAction(_ -> forget());
        HBox buttons = new HBox(10, save, test, forget);
        buttons.setAlignment(Pos.CENTER_LEFT);

        return Ui.card("Modèle de langage (LLM)",
                Ui.wrapped("L'assistant reprend d'abord la catégorie des activités semblables déjà classées dans votre base ; pour les "
                        + "autres, il interroge le modèle, qui choisit parmi vos catégories et n'en propose une nouvelle que si aucune ne "
                        + "convient.", Styles.TEXT_MUTED),
                form, providerNote, onImport, buttons, status,
                Ui.wrapped("Confidentialité : pour classer une opération, son type, son libellé et son montant sont envoyés au service "
                        + "choisi, avec le nom de vos catégories et de commerçants déjà classés. Avec Ollama, rien ne quitte votre "
                        + "ordinateur. La clé d'API (ou le jeton GitHub) est enregistrée dans la base de ce profil"
                        + (model.session.profile().encrypted() ? ", chiffrée comme le reste." : " : chiffrez le profil pour la protéger."), "hint"));
    }

    private void show(LlmSettings settings) {
        provider.setValue(settings.provider());
        url.setText(settings.url());
        key.setText(settings.apiKey());
        modelName.setValue(settings.model());
        onImport.setSelected(settings.onImport());
    }

    /**
     * Adapte le formulaire au service choisi : son adresse habituelle, tant que l'adresse n'a pas été saisie
     * à la main, ou aucune adresse pour GitHub Copilot, qui se joint par son moteur et un jeton GitHub.
     */
    private void providerChanged(LlmProvider previous, LlmProvider chosen) {
        boolean copilot = chosen == LlmProvider.COPILOT;
        boolean addressed = chosen == null || chosen.urlRequired();
        url.setDisable(!addressed);
        url.setPromptText(addressed ? URL_PROMPT : "Sans objet pour ce service");
        keyTitle.setText(copilot ? "Jeton GitHub" : KEY_TITLE);
        providerNote.setText(copilot ? COPILOT_NOTE : "");
        if (chosen == null) {
            return;
        }
        boolean untouched = url.getText().isBlank() || previous != null && url.getText().strip().equals(previous.defaultUrl())
                || Arrays.stream(LlmProvider.values()).anyMatch(p -> !p.defaultUrl().isEmpty() && p.defaultUrl().equals(url.getText().strip()));
        if (untouched || !addressed) {
            url.setText(chosen.defaultUrl());
        }
        key.setPromptText(copilot ? "github_pat_…" : chosen.keyRequired() ? "Clé fournie par le service" : "Facultative pour ce service");
    }

    private LlmSettings entered() {
        String name = modelName.getEditor().getText();
        return new LlmSettings(provider.getValue(), url.getText(), key.getText(), name, onImport.isSelected());
    }

    private void save() {
        LlmSettings entered = entered();
        model.saveLlm(entered);
        String missing = entered.provider().urlRequired() && entered.url().isEmpty() ? "l'adresse de l'API"
                : entered.model().isEmpty() ? "le nom du modèle" : entered.provider() == LlmProvider.COPILOT ? "le jeton GitHub" : "la clé d'API";
        say(entered.isConfigured() ? "Réglages enregistrés." : "Réglages enregistrés, mais incomplets : il manque " + missing
                + " pour que l'assistant fonctionne.", !entered.isConfigured());
    }

    private void forget() {
        model.saveLlm(LlmSettings.NONE);
        show(LlmSettings.NONE);
        modelName.getItems().clear();
        say("L'assistant est désactivé : ses réglages ont été effacés de ce profil.", false);
    }

    private void test() {
        say("", false);
        model.testLlm(entered(), answer -> say("Le modèle répond : « " + shorten(answer) + " ». La connexion fonctionne.", false));
    }

    /** Connexion à GitHub par le navigateur, pour GitHub Copilot. */
    private void signIn() {
        say("", false);
        new GitHubLoginDialog(model, shell, this).ask().ifPresent(this::connected);
    }

    /**
     * Retient le jeton d'une connexion à GitHub. Il est enregistré aussitôt avec le reste des réglages : il
     * ne se redemande pas à GitHub sans une nouvelle connexion.
     */
    void connected(Identity identity) {
        key.setText(identity.token());
        LlmSettings entered = entered();
        model.saveLlm(entered);
        String done = "Connecté à GitHub en tant que « " + identity.login() + " » : le jeton est enregistré dans ce profil.";
        say(done, false);
        if (entered.model().isEmpty()) {
            listModels(done + " ");
        }
    }

    private void listModels() {
        say("", false);
        listModels("");
    }

    /**
     * @param before ce qui vient d'être fait, rappelé devant le nombre de modèles trouvés
     */
    private void listModels(String before) {
        LlmSettings entered = entered();
        // la liste des modèles se demande sans en avoir encore choisi un
        LlmSettings probe = new LlmSettings(entered.provider(), entered.url(), entered.apiKey(), entered.model().isEmpty() ? "-" : entered.model(),
                false);
        model.listModels(probe, names -> {
            String current = modelName.getEditor().getText();
            modelName.getItems().setAll(names);
            modelName.setValue(current);
            say(before + Fmt.plural(names.size(), "modèle proposé", "modèles proposés") + " par le service : choisissez-en un dans la liste"
                    + (before.isEmpty() ? "." : ", puis enregistrez."), false);
            if (!names.isEmpty()) {
                modelName.show();
            }
        });
    }

    private void say(String text, boolean problem) {
        status.setText(text);
        status.getStyleClass().removeAll(Styles.DANGER, Styles.SUCCESS);
        status.getStyleClass().add(problem ? Styles.DANGER : Styles.SUCCESS);
    }

    private static String shorten(String answer) {
        String line = answer.strip().replaceAll("\\s+", " ");
        return line.length() > 80 ? line.substring(0, 80) + "…" : line;
    }

    // ------------------------------------------------------------------ recatégorisation

    private Node pass() {
        ToggleGroup range = new ToggleGroup();
        everything.setToggleGroup(range);
        between.setToggleGroup(range);
        everything.setSelected(true);
        from.disableProperty().bind(between.selectedProperty().not());
        to.disableProperty().bind(between.selectedProperty().not());
        HBox period = new HBox(14, everything, between, from, to);
        period.setAlignment(Pos.CENTER_LEFT);
        reach.setValue(Reach.AUTOMATIC);
        reach.setMaxWidth(380);
        GridPane form = Ui.form("Période", period, "Opérations réexaminées", reach);

        Button analyse = new Button("Analyser…", new FontIcon(Feather.CPU));
        analyse.getStyleClass().add(Styles.ACCENT);
        analyse.setMinWidth(Region.USE_PREF_SIZE);
        analyse.disableProperty().bind(model.busy.or(Bindings.isEmpty(model.operations))
                .or(Bindings.createBooleanBinding(() -> !model.llm.get().isConfigured(), model.llm)));
        analyse.setOnAction(_ -> analyse());
        Label progress = Ui.label("", Styles.TEXT_MUTED);
        progress.textProperty().bind(Bindings.when(Bindings.createBooleanBinding(() -> model.llm.get().isConfigured(), model.llm))
                .then(model.progress).otherwise("Réglez et enregistrez d'abord un modèle ci-dessus."));
        HBox action = new HBox(14, analyse, progress);
        action.setAlignment(Pos.CENTER_LEFT);

        return Ui.card("Recatégoriser les opérations",
                Ui.wrapped("Une passe de l'assistant réexamine les opérations déjà importées du compte affiché, sur tout l'historique ou "
                        + "entre deux dates. Rien n'est modifié d'office : ses propositions vous sont présentées, et vous retenez celles "
                        + "que vous voulez.", Styles.TEXT_MUTED),
                form, action,
                Ui.wrapped("Les opérations classées à la main servent de référence : hors de la passe, elles disent à l'assistant où "
                        + "ranger les activités semblables. Une catégorie attribuée par l'assistant se reconnaît à son icône dans l'écran "
                        + "« Opérations », et se corrige comme les autres.", "hint"));
    }

    private void analyse() {
        Period range = everything.isSelected() ? Period.ALL : Period.between(from.getValue(), to.getValue());
        model.propose(range, reach.getValue(), this::review);
    }

    /** Présente les propositions ; celles que l'utilisateur retient sont enregistrées. */
    private void review(Proposal proposal) {
        if (proposal.changes().isEmpty()) {
            String examined = proposal.activities() == 0 ? "Aucune opération à examiner sur cette période."
                    : "L'assistant a examiné " + Fmt.plural(proposal.activities(), "activité", "activités") + " : aucun changement à proposer"
                            + (proposal.pending() == 0 ? "." : " (" + proposal.pending() + " sans proposition).");
            model.notice.set(proposal.failure() == null ? new Notice(NoticeKind.INFO, examined)
                    : new Notice(NoticeKind.ERROR, examined + " L'analyse s'est interrompue : " + proposal.failure()));
            return;
        }
        new RecategorizeDialog(proposal, this).showAndWait().filter(accepted -> !accepted.isEmpty()).ifPresent(model::applySuggestions);
    }
}
