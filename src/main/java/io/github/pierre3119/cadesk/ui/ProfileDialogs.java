package io.github.pierre3119.cadesk.ui;

import io.github.pierre3119.cadesk.profile.Profile;
import io.github.pierre3119.cadesk.ui.FormDialog.Answers;

import java.io.File;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Optional;

import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.stage.FileChooser;

/** Éléments communs au choix du profil et à l'écran « Profil et sécurité ». */
final class ProfileDialogs {

    static final String NAME = "name";
    static final String PASSWORD = "password";
    static final String CONFIRMATION = "confirmation";
    static final String ENCRYPT = "encrypt";

    private static final FileChooser.ExtensionFilter ARCHIVES = new FileChooser.ExtensionFilter("Sauvegarde CaDesk", "*.zip");

    private ProfileDialogs() {
    }

    /** Formulaire de création d'un profil : nom, mot de passe facultatif, chiffrement. */
    static FormDialog newProfile(Node owner) {
        return FormDialog.of(owner, "Nouveau profil", "Chaque profil a sa propre base de données, avec ses catégories et ses règles. "
                + "Le mot de passe et le chiffrement sont facultatifs et pourront être réglés plus tard.")
                .text(NAME, "Nom du profil", "")
                .secret(PASSWORD, "Mot de passe (facultatif)")
                .secret(CONFIRMATION, "Confirmation")
                .check(ENCRYPT, "Chiffrer les données de ce profil", false)
                .rule(a -> a.text(NAME).isEmpty() ? "" : null)
                .same(PASSWORD, CONFIRMATION)
                .rule(a -> a.checked(ENCRYPT) && !a.filled(PASSWORD) ? "Un mot de passe est nécessaire pour chiffrer les données." : null)
                .button("Créer le profil");
    }

    /** Le mot de passe d'un formulaire, ou {@code null} s'il a été laissé vide. */
    static char[] passwordOrNull(Answers answers, String name) {
        return answers.filled(name) ? answers.secret(name) : null;
    }

    /** Demande où enregistrer une sauvegarde ; {@code what} entre dans le nom proposé (« Pierre », « tous-les-profils »). */
    static Optional<Path> backupTarget(Node owner, String what) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Enregistrer la sauvegarde");
        chooser.getExtensionFilters().add(ARCHIVES);
        chooser.setInitialFileName("CaDesk-" + what.strip().replaceAll("[^\\p{L}\\p{N}]+", "-") + "-" + LocalDate.now() + ".zip");
        File chosen = chooser.showSaveDialog(owner.getScene().getWindow());
        if (chosen == null) {
            return Optional.empty();
        }
        return Optional.of(chosen.getName().toLowerCase().endsWith(".zip") ? chosen.toPath() : Path.of(chosen.getPath() + ".zip"));
    }

    /** Demande quelle sauvegarde restaurer. */
    static Optional<Path> backupSource(Node owner) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Restaurer une sauvegarde");
        chooser.getExtensionFilters().addAll(ARCHIVES, new FileChooser.ExtensionFilter("Tous les fichiers", "*.*"));
        File chosen = chooser.showOpenDialog(owner.getScene().getWindow());
        return Optional.ofNullable(chosen).map(File::toPath);
    }

    /** « Administrateur  ·  données chiffrées », « Utilisateur  ·  sans mot de passe »… */
    static String describe(Profile profile) {
        return profile.role().label() + "  ·  "
                + (profile.encrypted() ? "données chiffrées" : profile.hasPassword() ? "protégé par mot de passe" : "sans mot de passe");
    }

    /** Pastille portant l'initiale du profil. */
    static Label avatar(Profile profile) {
        Label avatar = Ui.label(profile.name().substring(0, 1).toUpperCase(), "avatar");
        avatar.setMinSize(34, 34);
        avatar.setMaxSize(34, 34);
        if (profile.isAdmin()) {
            avatar.getStyleClass().add("avatar-admin");
        }
        return avatar;
    }
}
