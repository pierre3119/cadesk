package io.github.pierre3119.cadesk.ui;

import atlantafx.base.theme.Styles;
import io.github.pierre3119.cadesk.llm.CopilotLogin;
import io.github.pierre3119.cadesk.llm.CopilotLogin.Identity;
import io.github.pierre3119.cadesk.llm.CopilotLogin.Prompt;

import java.util.Optional;
import java.util.concurrent.CancellationException;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

import org.kordamp.ikonli.feather.Feather;
import org.kordamp.ikonli.javafx.FontIcon;

/**
 * Connexion à GitHub par le navigateur, pour GitHub Copilot : la page de GitHub s'ouvre dans le navigateur
 * par défaut, et la boîte attend que l'utilisateur y autorise la connexion. Elle rend le compte connecté,
 * ou rien si l'utilisateur renonce. Si le navigateur ne peut pas renvoyer vers l'application, la connexion
 * se refait par un code à saisir sur la page.
 */
final class GitHubLoginDialog extends Dialog<Identity> {

    private static final double WIDTH = 540;

    private final AppModel model;
    private final Shell shell;
    private final Label instructions = Ui.wrapped("");
    private final Label code = Ui.label("", "login-code");
    private final HBox codeRow;
    private final ProgressIndicator waiting = new ProgressIndicator();
    private final Label state = Ui.wrapped("");
    private final Button reopen = new Button("Rouvrir la page", new FontIcon(Feather.EXTERNAL_LINK));
    private final Button copyLink = new Button("Copier le lien", new FontIcon(Feather.COPY));
    private final Button retry = new Button("Réessayer", new FontIcon(Feather.REFRESH_CW));
    private final Hyperlink withCode = new Hyperlink("La page ne s'ouvre pas, ou n'aboutit pas ? Se connecter avec un code");
    private final ButtonType cancel = new ButtonType("Annuler", ButtonData.CANCEL_CLOSE);
    private CopilotLogin login;
    private Prompt asked;
    private boolean byCode;

    GitHubLoginDialog(AppModel model, Shell shell, Node owner) {
        this.model = model;
        this.shell = shell;
        Ui.style(this, owner);
        setTitle("Connexion à GitHub");
        getDialogPane().getStyleClass().add("form-dialog");

        Button copyCode = new Button("Copier le code", new FontIcon(Feather.COPY));
        copyCode.setOnAction(_ -> copy(code.getText()));
        codeRow = new HBox(14, code, copyCode);
        codeRow.setAlignment(Pos.CENTER_LEFT);
        codeRow.managedProperty().bind(codeRow.visibleProperty());

        waiting.setPrefSize(18, 18);
        waiting.setMinSize(18, 18);
        waiting.managedProperty().bind(waiting.visibleProperty());
        HBox.setHgrow(state, Priority.ALWAYS);
        HBox progress = new HBox(10, waiting, state);
        progress.setAlignment(Pos.CENTER_LEFT);

        reopen.setOnAction(_ -> shell.browse(asked.page()));
        copyLink.setOnAction(_ -> copy(asked.page()));
        retry.setOnAction(_ -> start(byCode));
        retry.managedProperty().bind(retry.visibleProperty());
        HBox links = new HBox(8, reopen, copyLink, retry);
        links.setAlignment(Pos.CENTER_LEFT);

        withCode.setOnAction(_ -> start(true));
        withCode.managedProperty().bind(withCode.visibleProperty());
        withCode.setMinHeight(Region.USE_PREF_SIZE);

        Label storage = Ui.wrapped("Le jeton délivré est celui que demande Copilot CLI : outre Copilot, il donne accès en lecture et en "
                + "écriture à vos dépôts GitHub. Il sera enregistré dans la base de ce profil"
                + (model.session.profile().encrypted() ? ", chiffrée." : ", qui n'est pas chiffrée : pensez à chiffrer le profil."), "hint");

        getDialogPane().setContent(new DialogContent(WIDTH, 14, Ui.label("Connexion à GitHub", Styles.TITLE_4), instructions, codeRow,
                progress, links, withCode, storage));
        getDialogPane().getButtonTypes().add(cancel);
        Ui.refitOnChange(this, state.textProperty(), instructions.textProperty(), codeRow.visibleProperty(), withCode.visibleProperty());
        setResultConverter(_ -> null);
        setOnHidden(_ -> abandon());
    }

    /** Lance la connexion et attend, boîte ouverte, qu'elle aboutisse ou que l'utilisateur renonce. */
    Optional<Identity> ask() {
        start(false);
        return showAndWait();
    }

    /**
     * Lance la connexion, à la place de celle qui serait en cours.
     *
     * @param typingCode vrai pour autoriser par un code à saisir sur la page
     */
    void start(boolean typingCode) {
        abandon();
        byCode = typingCode;
        asked = null;
        instructions.setText(typingCode
                ? "La page de GitHub s'ouvre dans votre navigateur — vous pouvez aussi l'ouvrir sur un autre appareil. Saisissez-y le "
                        + "code ci-dessous, puis autorisez la connexion."
                : "La page de GitHub s'ouvre dans votre navigateur : autorisez-y la connexion, puis revenez ici. GitHub présente la "
                        + "demande au nom de Copilot CLI, dont l'application embarque le moteur.");
        codeRow.setVisible(false);
        withCode.setVisible(!typingCode);
        retry.setVisible(false);
        reopen.setDisable(true);
        copyLink.setDisable(true);
        waiting.setVisible(true);
        say("Préparation de la connexion…", false);
        ((Button) getDialogPane().lookupButton(cancel)).setText("Annuler");

        CopilotLogin[] started = new CopilotLogin[1];
        started[0] = model.signIn(typingCode, prompt -> {
            if (login == started[0]) {
                asked(prompt);
            }
        }, identity -> {
            if (login == started[0]) {
                login = null;
                setResult(identity);
                close();
            }
        }, error -> {
            if (login == started[0]) {
                login = null;
                failed(error);
            }
        });
        login = started[0];
    }

    /** La page est connue : elle s'ouvre dans le navigateur, et l'on attend l'utilisateur. */
    private void asked(Prompt prompt) {
        asked = prompt;
        if (prompt.code() != null) {
            code.setText(prompt.code());
            codeRow.setVisible(true);
        }
        reopen.setDisable(false);
        copyLink.setDisable(false);
        say("En attente de votre autorisation dans le navigateur…", false);
        shell.browse(prompt.page());
    }

    private void failed(Exception error) {
        waiting.setVisible(false);
        reopen.setDisable(true);
        copyLink.setDisable(true);
        codeRow.setVisible(false);
        retry.setVisible(true);
        say(error instanceof CancellationException ? "La connexion a été abandonnée." : AppModel.message(error), true);
        ((Button) getDialogPane().lookupButton(cancel)).setText("Fermer");
    }

    /** Renonce à la connexion en cours, s'il y en a une. */
    private void abandon() {
        if (login != null) {
            CopilotLogin abandoned = login;
            login = null;
            abandoned.cancel();
        }
    }

    private void say(String text, boolean problem) {
        state.setText(text);
        state.getStyleClass().removeAll(Styles.DANGER, Styles.TEXT_MUTED);
        state.getStyleClass().add(problem ? Styles.DANGER : Styles.TEXT_MUTED);
    }

    private static void copy(String text) {
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
    }
}
