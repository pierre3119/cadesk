package io.github.pierre3119.cadesk.ui;

import atlantafx.base.theme.Styles;
import io.github.pierre3119.cadesk.profile.Profile;
import io.github.pierre3119.cadesk.profile.ProfileManager;
import io.github.pierre3119.cadesk.profile.Session;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressBar;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import org.kordamp.ikonli.feather.Feather;
import org.kordamp.ikonli.javafx.FontIcon;

/**
 * Choix du profil à l'ouverture de l'application : la liste des profils, le mot de passe de celui qui en
 * a un, la création d'un profil et la restauration d'une sauvegarde.
 */
final class ProfileChooser extends StackPane {

    private final ProfileManager profiles;
    private final Consumer<Session> onOpen;
    private final ListView<Profile> list = new ListView<>();
    private final PasswordField password = new PasswordField();
    private final Label message = Ui.wrapped("", Styles.TEXT_SMALL);
    private final Label subtitle = Ui.wrapped("", Styles.TEXT_MUTED);
    private final Button open = new Button("Ouvrir", new FontIcon(Feather.LOG_IN));
    private final BooleanProperty working = new SimpleBooleanProperty();

    /**
     * @param onOpen  reçoit la session du profil ouvert
     * @param browser ouvre une page web dans le navigateur de l'utilisateur
     * @param problem message à afficher d'emblée (pourquoi l'on revient ici), ou {@code null}
     */
    ProfileChooser(ProfileManager profiles, Consumer<Session> onOpen, Consumer<String> browser, String problem) {
        this.profiles = profiles;
        this.onOpen = onOpen;
        getStyleClass().add("chooser");

        FontIcon logo = new FontIcon(Feather.CREDIT_CARD);
        logo.getStyleClass().add("brand-icon");
        Button about = Ui.iconButton(Feather.INFO, "À propos de " + App.NAME, () -> new AboutDialog(this, browser).showAndWait());
        about.getStyleClass().add("about-button");
        HBox brand = new HBox(10, logo, Ui.label(App.NAME, Styles.TITLE_2), Ui.spacer(), about);
        brand.setAlignment(Pos.CENTER_LEFT);

        list.setCellFactory(_ -> new ProfileCell());
        list.setPlaceholder(new Label("Aucun profil pour l'instant."));
        list.setPrefHeight(248);
        list.getSelectionModel().selectedItemProperty().addListener((_, _, selected) -> select(selected));
        list.setOnMouseClicked(e -> {
            Profile selected = list.getSelectionModel().getSelectedItem();
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && selected != null && !selected.hasPassword()) {
                open();
            }
        });

        password.setPromptText("Mot de passe du profil");
        password.managedProperty().bind(password.visibleProperty());
        password.setOnAction(_ -> open());
        message.setMinHeight(18);

        open.getStyleClass().add(Styles.ACCENT);
        open.setDefaultButton(true);
        open.setOnAction(_ -> open());
        open.disableProperty().bind(list.getSelectionModel().selectedItemProperty().isNull().or(working));
        Button create = new Button("Nouveau profil…", new FontIcon(Feather.USER_PLUS));
        create.setOnAction(_ -> create());
        create.disableProperty().bind(working);
        Button restore = new Button("Restaurer une sauvegarde…", new FontIcon(Feather.DOWNLOAD));
        restore.getStyleClass().add(Styles.FLAT);
        restore.setOnAction(_ -> restore());
        restore.disableProperty().bind(working);
        for (Button button : List.of(open, create, restore)) {
            button.setMinWidth(Region.USE_PREF_SIZE);
        }
        HBox buttons = new HBox(10, restore, Ui.spacer(), create, open);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        ProgressBar progress = new ProgressBar();
        progress.setMaxWidth(Double.MAX_VALUE);
        progress.getStyleClass().add(Styles.SMALL);
        progress.visibleProperty().bind(working);

        VBox card = new VBox(14, brand, subtitle, list, password, message, progress, buttons);
        VBox.setVgrow(list, Priority.ALWAYS);
        card.getStyleClass().add("chooser-card");
        card.setMaxSize(560, Region.USE_PREF_SIZE);
        getChildren().add(card);

        refresh(null);
        if (problem != null) {
            say(problem, true);
        }
    }

    /** Relit la liste des profils et sélectionne celui-ci, ou à défaut le premier. */
    private void refresh(Profile wanted) {
        List<Profile> all = profiles.profiles();
        list.getItems().setAll(all);
        subtitle.setText(all.isEmpty() ? "Bienvenue ! Créez votre profil pour commencer : chaque utilisateur a le sien, avec ses propres données."
                : "Choisissez votre profil.");
        Profile selected = wanted == null ? null : all.stream().filter(p -> p.id().equals(wanted.id())).findFirst().orElse(null);
        if (selected != null) {
            list.getSelectionModel().select(selected);
        } else if (!all.isEmpty()) {
            list.getSelectionModel().selectFirst();
        } else {
            select(null);
        }
    }

    private void select(Profile selected) {
        password.clear();
        password.setVisible(selected != null && selected.hasPassword());
        say("", false);
        if (password.isVisible()) {
            Platform.runLater(password::requestFocus);
        }
    }

    private void say(String text, boolean problem) {
        message.setText(text);
        message.getStyleClass().removeAll(Styles.DANGER, Styles.SUCCESS);
        message.getStyleClass().add(problem ? Styles.DANGER : Styles.SUCCESS);
    }

    // ------------------------------------------------------------------ actions

    private void open() {
        Profile selected = list.getSelectionModel().getSelectedItem();
        if (selected == null || working.get()) {
            return;
        }
        if (selected.hasPassword() && password.getText().isEmpty()) {
            say("Saisissez le mot de passe de ce profil.", true);
            password.requestFocus();
            return;
        }
        char[] secret = selected.hasPassword() ? password.getText().toCharArray() : null;
        work(() -> profiles.open(selected, secret), onOpen, () -> {
            password.clear();
            password.requestFocus();
        });
    }

    private void create() {
        ProfileDialogs.newProfile(this).ask().ifPresent(answers -> {
            char[] secret = ProfileDialogs.passwordOrNull(answers, ProfileDialogs.PASSWORD);
            work(() -> profiles.create(answers.text(ProfileDialogs.NAME), secret, answers.checked(ProfileDialogs.ENCRYPT)), created -> {
                refresh(created);
                if (created.hasPassword()) {
                    say("Profil « " + created.name() + " » créé. Saisissez son mot de passe pour l'ouvrir.", false);
                } else {
                    open();
                }
            }, () -> { });
        });
    }

    private void restore() {
        ProfileDialogs.backupSource(this).ifPresent(this::restore);
    }

    private void restore(Path archive) {
        work(() -> profiles.restore(archive), restored -> {
            refresh(restored.getFirst());
            say(Fmt.plural(restored.size(), "profil restauré", "profils restaurés") + " : "
                    + String.join(", ", restored.stream().map(Profile::name).toList())
                    + ". Chacun s'ouvre avec le mot de passe qu'il avait au moment de la sauvegarde.", false);
        }, () -> { });
    }

    /** Exécute une tâche hors du fil JavaFX ; pendant ce temps les commandes sont inactives. */
    private <T> void work(Callable<T> task, Consumer<T> done, Runnable afterFailure) {
        working.set(true);
        Thread thread = new Thread(() -> {
            try {
                T result = task.call();
                Platform.runLater(() -> {
                    working.set(false);
                    done.accept(result);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    working.set(false);
                    say(AppModel.message(e), true);
                    afterFailure.run();
                });
            }
        }, "cadesk-profil");
        thread.setDaemon(true);
        thread.start();
    }

    /** Ligne d'un profil : sa pastille, son nom, son rôle et sa protection. */
    private static final class ProfileCell extends ListCell<Profile> {

        @Override
        protected void updateItem(Profile profile, boolean empty) {
            super.updateItem(profile, empty);
            if (empty || profile == null) {
                setGraphic(null);
                return;
            }
            VBox texts = new VBox(1, Ui.label(profile.name(), "profile-name"),
                    Ui.label(ProfileDialogs.describe(profile), Styles.TEXT_SMALL, Styles.TEXT_MUTED));
            HBox row = new HBox(12, ProfileDialogs.avatar(profile), texts, Ui.spacer());
            if (profile.hasPassword()) {
                row.getChildren().add(new FontIcon(profile.encrypted() ? Feather.SHIELD : Feather.LOCK));
            }
            row.setAlignment(Pos.CENTER_LEFT);
            setGraphic(row);
        }
    }
}
