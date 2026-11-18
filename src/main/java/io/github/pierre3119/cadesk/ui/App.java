package io.github.pierre3119.cadesk.ui;

import atlantafx.base.theme.PrimerLight;
import io.github.pierre3119.cadesk.About;
import io.github.pierre3119.cadesk.AppConfig;
import io.github.pierre3119.cadesk.profile.Profile;
import io.github.pierre3119.cadesk.profile.ProfileManager;
import io.github.pierre3119.cadesk.profile.Session;
import io.github.pierre3119.cadesk.statement.StatementReader;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.layout.StackPane;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

/**
 * Application JavaFX : choix du profil, puis fenêtre principale du profil ouvert. Un seul profil est
 * ouvert à la fois ; en changer remplace le contenu de la fenêtre.
 */
public final class App extends Application implements Shell {

    static final String NAME = About.NAME;
    private static final String LAST_FOLDER_SETTING = "import.folder";

    private Stage stage;
    private Scene scene;
    private ProfileManager profiles;
    private Session session;
    private AppModel model;

    @Override
    public void start(Stage primaryStage) {
        stage = primaryStage;
        try {
            profiles = new ProfileManager(AppConfig.dataDirectory(), AppConfig.databaseFile());
        } catch (RuntimeException e) {
            Alert alert = new Alert(Alert.AlertType.ERROR, e.getMessage());
            alert.setTitle(NAME);
            alert.setHeaderText("Les profils ne peuvent pas être lus");
            alert.showAndWait();
            Platform.exit();
            return;
        }
        Application.setUserAgentStylesheet(new PrimerLight().getUserAgentStylesheet());
        scene = new Scene(new StackPane(), 1340, 860);
        scene.getStylesheets().add(stylesheet());
        stage.setTitle(NAME);
        stage.setMinWidth(1120);
        stage.setMinHeight(640);
        stage.setScene(scene);
        stage.show();

        // un profil unique et sans mot de passe s'ouvre directement, comme avant les profils
        List<Profile> all = profiles.profiles();
        if (all.size() == 1 && !all.getFirst().hasPassword()) {
            try {
                show(profiles.open(all.getFirst(), null), false);
            } catch (RuntimeException e) {
                showChooser(AppModel.message(e));
            }
        } else {
            showChooser(null);
        }
    }

    private void showChooser(String message) {
        // le thème est une préférence du profil : hors de tout profil, le thème clair
        Application.setUserAgentStylesheet(new PrimerLight().getUserAgentStylesheet());
        stage.setTitle(NAME);
        scene.setRoot(new ProfileChooser(profiles, opened -> show(opened, false), this::browse, message));
    }

    // ------------------------------------------------------------------ ce que demande la fenêtre principale

    @Override
    public void show(Session opened, boolean onProfilePage) {
        closeCurrent(opened);
        session = opened;
        model = new AppModel(profiles, opened);
        MainView view = new MainView(model, this);
        if (onProfilePage) {
            view.showProfile();
        }
        stage.setTitle(NAME + "  –  " + opened.profile().name());
        scene.setRoot(view);
        model.reload(null);
    }

    @Override
    public void leave(String message) {
        closeCurrent(null);
        showChooser(message);
    }

    @Override
    public void chooseFiles() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Importer un ou plusieurs relevés du Crédit Agricole (CSV ou OFX)");
        // les deux casses : sous Linux, le filtre du sélecteur de fichiers les distingue
        List<String> patterns = StatementReader.EXTENSIONS.stream()
                .flatMap(extension -> Stream.of("*." + extension, "*." + extension.toUpperCase(Locale.ROOT))).toList();
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Relevés CSV et OFX", patterns),
                new FileChooser.ExtensionFilter("Tous les fichiers", "*.*"));
        model.setting(LAST_FOLDER_SETTING).map(Path::of).filter(Files::isDirectory)
                .ifPresent(dir -> chooser.setInitialDirectory(dir.toFile()));
        List<File> files = chooser.showOpenMultipleDialog(stage);
        if (files != null && !files.isEmpty()) {
            model.saveSetting(LAST_FOLDER_SETTING, files.getFirst().getParent());
            model.importFiles(files.stream().map(File::toPath).toList());
        }
    }

    @Override
    public void browse(String url) {
        getHostServices().showDocument(url);
    }

    /** Arrête le modèle du profil affiché et ferme sa session, sauf si c'est celle qui prend la suite. */
    private void closeCurrent(Session next) {
        if (model != null) {
            model.close();
            model = null;
        }
        if (session != null && session != next) {
            session.close();
        }
        session = null;
    }

    // ------------------------------------------------------------------ scène

    /** Construit la scène d'un profil ouvert, sans application autour : pour le rendu hors écran des tests. */
    static Scene createScene(AppModel model, Runnable chooseFiles, double width, double height) {
        Scene scene = new Scene(new MainView(model, new Shell() {
            @Override
            public void chooseFiles() {
                chooseFiles.run();
            }

            @Override
            public void show(Session session, boolean onProfilePage) {
                // sans application, la session reste celle du modèle
            }

            @Override
            public void leave(String message) {
                // sans application, pas de choix du profil
            }

            @Override
            public void browse(String url) {
                // sans application, pas de navigateur
            }
        }), width, height);
        scene.getStylesheets().add(stylesheet());
        return scene;
    }

    private static String stylesheet() {
        return Objects.requireNonNull(App.class.getResource("styles.css")).toExternalForm();
    }

    @Override
    public void stop() {
        closeCurrent(null);
    }
}
