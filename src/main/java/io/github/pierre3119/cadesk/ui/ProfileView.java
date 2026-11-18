package io.github.pierre3119.cadesk.ui;

import atlantafx.base.theme.Styles;
import io.github.pierre3119.cadesk.domain.Nature;
import io.github.pierre3119.cadesk.profile.Profile;
import io.github.pierre3119.cadesk.profile.ProfileManager;
import io.github.pierre3119.cadesk.profile.Session;
import io.github.pierre3119.cadesk.ui.AppModel.Notice;
import io.github.pierre3119.cadesk.ui.AppModel.NoticeKind;

import java.util.List;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.feather.Feather;
import org.kordamp.ikonli.javafx.FontIcon;

/**
 * Profil et sécurité : le nom du profil ouvert, son mot de passe, le chiffrement de ses données, son
 * rôle ; la sauvegarde et le vidage de sa base ; et, pour un administrateur, la gestion de tous les profils.
 *
 * <p>Quand le profil a été ouvert par un administrateur et non par son propriétaire, sa protection
 * n'est pas modifiable ici : elle reste l'affaire du propriétaire.
 */
final class ProfileView extends VBox {

    private static final String CURRENT = "current";
    private static final String ADMIN = "admin";
    private static final String ADMIN_PASSWORD = "adminPassword";
    private static final String EVERYTHING = "everything";

    private final AppModel model;
    private final Shell shell;
    private final ProfileManager profiles;
    private final Session session;
    private final VBox body = new VBox(18);

    ProfileView(AppModel model, Shell shell) {
        this.model = model;
        this.shell = shell;
        this.profiles = model.profiles;
        this.session = model.session;
        getStyleClass().add("page");
        body.getStyleClass().add("page-body");
        Label subtitle = new Label("Votre profil, la protection de vos données, leur sauvegarde.");
        getChildren().addAll(Ui.header("Profil et sécurité", subtitle), Ui.scroll(body));
        model.profile.addListener((_, _, _) -> rebuild());
        // la liste des profils a pu changer pendant que l'écran n'était pas affiché
        parentProperty().addListener((_, _, parent) -> {
            if (parent != null) {
                rebuild();
            }
        });
        rebuild();
    }

    private void rebuild() {
        Profile profile = model.profile.get();
        body.getChildren().setAll(identity(profile), session.isGuest() ? guestNote(profile) : protection(profile), data(profile));
        if (session.isAdmin()) {
            body.getChildren().add(everyone(profile));
        }
    }

    // ------------------------------------------------------------------ le profil ouvert

    private Node identity(Profile profile) {
        Label name = Ui.label(profile.name(), Styles.TITLE_3);
        Button rename = button("Renommer…", Feather.EDIT_2, () -> rename(profile));
        HBox chips = new HBox(8, Ui.chip(profile.role().label(), profile.isAdmin() ? Nature.EXCEPTIONNELLES : Nature.INTERNE),
                Ui.chip(profile.encrypted() ? "Données chiffrées" : "Données non chiffrées", profile.encrypted() ? Nature.REVENUS : Nature.INTERNE),
                Ui.chip(profile.hasPassword() ? "Mot de passe" : "Sans mot de passe", profile.hasPassword() ? Nature.FIXES : Nature.INTERNE));
        HBox row = new HBox(14, ProfileDialogs.avatar(profile), new VBox(6, name, chips), Ui.spacer(), rename);
        row.setAlignment(Pos.CENTER_LEFT);
        return Ui.card(null, row);
    }

    private Node guestNote(Profile profile) {
        Label text = Ui.wrapped("Vous consultez le profil de " + profile.name() + " en tant qu'administrateur"
                + session.administrator().map(name -> " (" + name + ")").orElse("") + ". Son mot de passe, son chiffrement et son rôle "
                + "ne se modifient que depuis ce profil, ouvert par son propriétaire ; un mot de passe oublié se réinitialise depuis "
                + "votre propre profil.");
        HBox.setHgrow(text, Priority.ALWAYS);
        text.setMaxWidth(Double.MAX_VALUE);
        HBox box = new HBox(12, new FontIcon(Feather.SHIELD), text);
        box.setAlignment(Pos.CENTER_LEFT);
        box.getStyleClass().add("callout");
        return box;
    }

    private Node protection(Profile profile) {
        List<String> admins = profiles.admins().stream().map(Profile::name).toList();

        Node password = profile.hasPassword()
                ? setting("Mot de passe", "Un mot de passe est demandé pour ouvrir ce profil.",
                        button("Modifier…", Feather.KEY, () -> changePassword(profile)),
                        profile.encrypted() || profile.isAdmin() ? null : button("Retirer", Feather.UNLOCK, () -> removePassword(profile)))
                : setting("Mot de passe", "Ce profil s'ouvre sans mot de passe.", button("Définir…", Feather.KEY, () -> changePassword(profile)));

        String rescue = admins.isEmpty() ? " Aucun administrateur n'étant désigné, un mot de passe oublié les rendrait définitivement illisibles."
                : " " + (admins.size() == 1 ? "L'administrateur (" : "Les administrateurs (") + String.join(", ", admins)
                        + ") " + (admins.size() == 1 ? "dispose" : "disposent") + " d'une clé de secours pour l'ouvrir et réinitialiser son mot de passe.";
        Node encryption = profile.encrypted()
                ? setting("Chiffrement", "Vos données sont chiffrées (AES-256) par une clé propre à ce profil, que votre mot de passe déverrouille."
                        + rescue, button("Ne plus chiffrer…", Feather.UNLOCK, () -> changeEncryption(profile, false)))
                : setting("Chiffrement", "La base de ce profil est un fichier lisible par quiconque a accès à cet ordinateur, mot de passe ou non. "
                        + "Chiffrée, elle devient illisible sans votre mot de passe.",
                        button("Chiffrer mes données…", Feather.SHIELD, () -> changeEncryption(profile, true)));

        Node role;
        if (profile.isAdmin()) {
            role = setting("Rôle : administrateur", session.isAdmin()
                    ? "Vous pouvez ouvrir tous les profils, réinitialiser un mot de passe oublié et sauvegarder l'ensemble des données."
                    : "Votre clé d'administrateur est illisible : renoncez au rôle, puis reprenez-le pour en obtenir une nouvelle.",
                    button("Renoncer au rôle…", Feather.USER_MINUS, () -> revokeAdmin(profile)));
        } else {
            Button become = button("Devenir administrateur…", Feather.USER_CHECK, () -> grantAdmin(profile));
            if (!profile.hasPassword()) {
                become.disableProperty().unbind();
                become.setDisable(true);
            }
            role = setting("Rôle : utilisateur", (admins.isEmpty() ? "Aucun administrateur n'est encore désigné. " : "")
                    + "Un administrateur peut ouvrir tous les profils, même chiffrés, réinitialiser leur mot de passe et sauvegarder "
                    + "l'ensemble des données." + (profile.hasPassword() ? (admins.isEmpty() ? "" : " L'accord d'un administrateur est nécessaire.")
                            : " Il lui faut un mot de passe : définissez-en un d'abord."), become);
        }
        return Ui.card("Protection", password, encryption, role);
    }

    private Node data(Profile profile) {
        Button clear = button("Vider la base…", Feather.TRASH_2, this::clear);
        clear.getStyleClass().add(Styles.DANGER);
        Button delete = button("Supprimer ce profil…", Feather.USER_X, () -> deleteOwn(profile));
        delete.getStyleClass().add(Styles.DANGER);
        Label path = Ui.wrapped("Base de ce profil : " + model.databaseFile(), Styles.TEXT_SMALL, Styles.TEXT_MUTED);
        return Ui.card("Données",
                setting("Sauvegarde", "Enregistre une copie de la base de ce profil dans une archive, à ranger en lieu sûr. "
                        + "Des données chiffrées y restent chiffrées.", button("Sauvegarder…", Feather.SAVE, () -> backup(profile))),
                setting("Vider la base", "Supprime les opérations importées de ce profil, et si vous le souhaitez ses catégories et ses règles.",
                        clear),
                setting("Supprimer le profil", "Supprime définitivement ce profil et toutes ses données.", delete),
                path);
    }

    // ------------------------------------------------------------------ tous les profils (administrateur)

    private Node everyone(Profile own) {
        Button create = button("Nouveau profil…", Feather.USER_PLUS, this::create);
        Button restore = button("Restaurer…", Feather.DOWNLOAD, this::restore);
        Button backupAll = button("Sauvegarde globale…", Feather.SAVE, this::backupAll);
        backupAll.getStyleClass().add(Styles.ACCENT);
        HBox header = new HBox(8, Ui.label("Tous les profils", "card-title"), Ui.spacer(), create, restore, backupAll);
        header.setAlignment(Pos.CENTER_LEFT);
        VBox rows = new VBox(2);
        for (Profile profile : profiles.profiles()) {
            VBox texts = new VBox(1, Ui.label(profile.name() + (profile.id().equals(own.id()) ? "  (vous)" : ""), "profile-name"),
                    Ui.label(ProfileDialogs.describe(profile), Styles.TEXT_SMALL, Styles.TEXT_MUTED));
            HBox row = new HBox(10, ProfileDialogs.avatar(profile), texts, Ui.spacer());
            if (!profile.id().equals(own.id())) {
                Button delete = Ui.iconButton(Feather.TRASH_2, "Supprimer ce profil et ses données", () -> deleteOther(profile));
                delete.getStyleClass().add(Styles.DANGER);
                delete.disableProperty().bind(model.busy);
                row.getChildren().addAll(button("Ouvrir", Feather.LOG_IN, () -> openAs(profile)),
                        button("Mot de passe…", Feather.KEY, () -> resetPassword(profile)), delete);
            }
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("setting-row");
            rows.getChildren().add(row);
        }
        return Ui.card(null, header, Ui.wrapped("En tant qu'administrateur, vous ouvrez chaque profil sans son mot de passe, y compris "
                + "les profils chiffrés grâce à votre clé de secours. La sauvegarde globale réunit tous les profils dans une seule archive.",
                "hint"), rows);
    }

    // ------------------------------------------------------------------ actions sur le profil ouvert

    private void rename(Profile profile) {
        FormDialog.of(this, "Renommer le profil", null).text(ProfileDialogs.NAME, "Nom du profil", profile.name())
                .rule(a -> a.text(ProfileDialogs.NAME).isEmpty() ? "" : null).button("Renommer").ask().ifPresent(a -> {
                    try {
                        profiles.rename(session, a.text(ProfileDialogs.NAME));
                        model.refreshProfile();
                    } catch (RuntimeException e) {
                        model.notice.set(new Notice(NoticeKind.ERROR, AppModel.message(e)));
                    }
                });
    }

    private void changePassword(Profile profile) {
        FormDialog form = FormDialog.of(this, profile.hasPassword() ? "Modifier le mot de passe" : "Définir un mot de passe",
                profile.encrypted() ? "Vos données restent chiffrées par la même clé : seul change le mot de passe qui la déverrouille."
                        : "Le mot de passe sera demandé à chaque ouverture de ce profil.");
        if (profile.hasPassword()) {
            form.secret(CURRENT, "Mot de passe actuel").required(CURRENT);
        }
        form.secret(ProfileDialogs.PASSWORD, "Nouveau mot de passe").secret(ProfileDialogs.CONFIRMATION, "Confirmation")
                .required(ProfileDialogs.PASSWORD).same(ProfileDialogs.PASSWORD, ProfileDialogs.CONFIRMATION).button("Enregistrer").ask()
                .ifPresent(a -> {
                    char[] current = profile.hasPassword() ? a.secret(CURRENT) : null;
                    char[] next = a.secret(ProfileDialogs.PASSWORD);
                    model.background(() -> {
                        profiles.setPassword(session, current, next);
                        return null;
                    }, _ -> changed("Le mot de passe a été enregistré."));
                });
    }

    private void removePassword(Profile profile) {
        FormDialog.of(this, "Retirer le mot de passe", "Le profil « " + profile.name() + " » s'ouvrira sans mot de passe.")
                .secret(CURRENT, "Mot de passe actuel").required(CURRENT).button("Retirer le mot de passe").ask().ifPresent(a -> {
                    char[] current = a.secret(CURRENT);
                    model.background(() -> {
                        profiles.setPassword(session, current, null);
                        return null;
                    }, _ -> changed("Le mot de passe a été retiré."));
                });
    }

    private void changeEncryption(Profile profile, boolean encrypt) {
        FormDialog form = FormDialog.of(this, encrypt ? "Chiffrer mes données" : "Ne plus chiffrer mes données", encrypt
                ? "La base de ce profil sera chiffrée (AES-256) par une clé qui lui est propre, déverrouillée par votre mot de passe. "
                        + "Sans lui, vos données sont illisibles : ne l'oubliez pas."
                : "La base redeviendra un fichier ordinaire, lisible par quiconque a accès à cet ordinateur. Votre mot de passe restera "
                        + "demandé à l'ouverture du profil.");
        if (encrypt && !profile.hasPassword()) {
            form.secret(ProfileDialogs.PASSWORD, "Mot de passe à définir").secret(ProfileDialogs.CONFIRMATION, "Confirmation")
                    .required(ProfileDialogs.PASSWORD).same(ProfileDialogs.PASSWORD, ProfileDialogs.CONFIRMATION);
        } else {
            form.secret(ProfileDialogs.PASSWORD, "Votre mot de passe").required(ProfileDialogs.PASSWORD);
        }
        form.button(encrypt ? "Chiffrer" : "Ne plus chiffrer").ask().ifPresent(a -> {
            char[] password = a.secret(ProfileDialogs.PASSWORD);
            model.background(() -> profiles.setEncryption(session, encrypt, password), reopened -> shell.show(reopened, true), this::failed);
        });
    }

    private void grantAdmin(Profile profile) {
        List<String> admins = profiles.admins().stream().map(Profile::name).toList();
        FormDialog form = FormDialog.of(this, "Devenir administrateur", "Un administrateur peut ouvrir tous les profils — y compris "
                + "chiffrés, grâce à une clé de secours —, réinitialiser un mot de passe oublié et sauvegarder l'ensemble des données. "
                + "Sa clé est protégée par son mot de passe.").secret(ProfileDialogs.PASSWORD, "Votre mot de passe")
                .required(ProfileDialogs.PASSWORD);
        if (!admins.isEmpty()) {
            form.choice(ADMIN, "Administrateur qui donne son accord", admins).secret(ADMIN_PASSWORD, "Son mot de passe").required(ADMIN_PASSWORD);
        }
        form.button("Devenir administrateur").ask().ifPresent(a -> {
            char[] password = a.secret(ProfileDialogs.PASSWORD);
            String chosen = admins.isEmpty() ? null : a.choice(ADMIN);
            Profile authorizer = profiles.admins().stream().filter(p -> p.name().equals(chosen)).findFirst().orElse(null);
            char[] authorizerPassword = admins.isEmpty() ? null : a.secret(ADMIN_PASSWORD);
            model.background(() -> {
                profiles.grantAdmin(session, password, authorizer, authorizerPassword);
                return null;
            }, _ -> changed(profile.name() + " est maintenant administrateur."));
        });
    }

    private void revokeAdmin(Profile profile) {
        boolean last = profiles.admins().size() == 1;
        if (Ui.confirm(this, "Renoncer au rôle d'administrateur ?", "Vous ne pourrez plus ouvrir les autres profils ni faire de sauvegarde globale"
                + (last ? ". Il n'y aura plus aucun administrateur : plus personne ne pourra ouvrir un profil chiffré dont le mot de passe "
                        + "serait oublié." : "."), "Renoncer")) {
            model.background(() -> {
                profiles.revokeAdmin(session);
                return null;
            }, _ -> changed(profile.name() + " n'est plus administrateur."));
        }
    }

    private void backup(Profile profile) {
        ProfileDialogs.backupTarget(this, profile.name()).ifPresent(model::backup);
    }

    private void clear() {
        FormDialog.of(this, "Vider la base", "Les opérations, les comptes et l'historique des imports de ce profil seront "
                + "définitivement supprimés. Une sauvegarde préalable permet de revenir en arrière.")
                .check(EVERYTHING, "Remettre aussi les catégories et les règles dans leur état de départ", false)
                .button("Vider la base").danger().ask().ifPresent(a -> model.clearData(a.checked(EVERYTHING)));
    }

    private void deleteOwn(Profile profile) {
        if (!Ui.confirm(this, "Supprimer le profil « " + profile.name() + " » ?",
                "Le profil et toutes ses données seront définitivement supprimés. Cette action est sans retour.", "Supprimer le profil")) {
            return;
        }
        String done = "Le profil « " + profile.name() + " » a été supprimé.";
        if (session.isGuest()) {
            model.background(() -> {
                profiles.delete(session, profile);
                return profiles.returnHome(session);
            }, home -> shell.show(home, true), this::failed);
        } else {
            model.background(() -> {
                profiles.delete(session, profile);
                return null;
            }, _ -> shell.leave(done), this::failed);
        }
    }

    // ------------------------------------------------------------------ actions d'administrateur

    private void create() {
        ProfileDialogs.newProfile(this).ask().ifPresent(a -> {
            char[] password = ProfileDialogs.passwordOrNull(a, ProfileDialogs.PASSWORD);
            model.background(() -> profiles.create(a.text(ProfileDialogs.NAME), password, a.checked(ProfileDialogs.ENCRYPT)),
                    created -> listChanged("Profil « " + created.name() + " » créé."));
        });
    }

    private void restore() {
        ProfileDialogs.backupSource(this).ifPresent(archive -> model.background(() -> profiles.restore(archive),
                restored -> listChanged(Fmt.plural(restored.size(), "profil restauré", "profils restaurés") + " : "
                        + String.join(", ", restored.stream().map(Profile::name).toList()) + ".")));
    }

    private void backupAll() {
        ProfileDialogs.backupTarget(this, "tous les profils").ifPresent(model::backupAll);
    }

    private void openAs(Profile profile) {
        model.background(() -> profiles.openAs(session, profile), guest -> shell.show(guest, false), this::failed);
    }

    private void resetPassword(Profile profile) {
        boolean removable = !profile.encrypted() && !profile.isAdmin();
        FormDialog form = FormDialog.of(this, "Réinitialiser le mot de passe de " + profile.name(), "Le nouveau mot de passe remplace "
                + "l'ancien, que vous n'avez pas à connaître." + (removable ? " Laissé vide, le profil s'ouvrira sans mot de passe." : ""))
                .secret(ProfileDialogs.PASSWORD, "Nouveau mot de passe").secret(ProfileDialogs.CONFIRMATION, "Confirmation");
        if (!removable) {
            form.required(ProfileDialogs.PASSWORD);
        }
        form.same(ProfileDialogs.PASSWORD, ProfileDialogs.CONFIRMATION).button("Réinitialiser").ask().ifPresent(a -> {
            char[] next = ProfileDialogs.passwordOrNull(a, ProfileDialogs.PASSWORD);
            model.background(() -> {
                profiles.resetPassword(session, profile, next);
                return null;
            }, _ -> listChanged("Le mot de passe de " + profile.name() + " a été " + (next == null ? "retiré." : "réinitialisé.")));
        });
    }

    private void deleteOther(Profile profile) {
        if (Ui.confirm(this, "Supprimer le profil « " + profile.name() + " » ?",
                "Le profil et toutes ses données seront définitivement supprimés. Cette action est sans retour.", "Supprimer le profil")) {
            model.background(() -> {
                profiles.delete(session, profile);
                return null;
            }, _ -> listChanged("Le profil « " + profile.name() + " » a été supprimé."));
        }
    }

    // ------------------------------------------------------------------ outils

    /** Le profil ouvert a changé : l'écran et la barre latérale sont mis à jour. */
    private void changed(String message) {
        model.refreshProfile();
        rebuild();
        model.notice.set(new Notice(NoticeKind.SUCCESS, message));
    }

    private void listChanged(String message) {
        rebuild();
        model.notice.set(new Notice(NoticeKind.SUCCESS, message));
    }

    /** Une opération a échoué : si elle a laissé le profil fermé, retour au choix du profil. */
    private void failed(Exception e) {
        if (session.isClosed()) {
            shell.leave(AppModel.message(e) + " Le profil a été fermé : rouvrez-le.");
        } else {
            model.notice.set(new Notice(NoticeKind.ERROR, AppModel.message(e)));
        }
    }

    private Button button(String text, Ikon icon, Runnable action) {
        Button button = new Button(text, new FontIcon(icon));
        button.getStyleClass().add(Styles.SMALL);
        button.setMinWidth(Region.USE_PREF_SIZE);
        button.setOnAction(_ -> action.run());
        button.disableProperty().bind(model.busy);
        return button;
    }

    /** Une ligne de réglage : son nom, ce qu'il en est, et les actions possibles (les {@code null} sont ignorés). */
    private static Node setting(String title, String description, Node... actions) {
        VBox texts = new VBox(2, Ui.label(title, "setting-title"), Ui.wrapped(description, Styles.TEXT_MUTED));
        HBox.setHgrow(texts, Priority.ALWAYS);
        texts.setMaxWidth(Double.MAX_VALUE);
        HBox row = new HBox(10, texts);
        for (Node action : actions) {
            if (action != null) {
                row.getChildren().add(action);
            }
        }
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("setting-row");
        return row;
    }
}
