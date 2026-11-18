package io.github.pierre3119.cadesk.ui;

import atlantafx.base.theme.Styles;
import io.github.pierre3119.cadesk.About;

import java.util.function.Consumer;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.feather.Feather;
import org.kordamp.ikonli.javafx.FontIcon;

/** « À propos » : l'application, sa version, et son auteur avec ses pages LinkedIn et GitHub. */
final class AboutDialog extends Dialog<Void> {

    private static final double WIDTH = 440;

    /**
     * @param browser ouvre une page web dans le navigateur de l'utilisateur
     */
    AboutDialog(Node owner, Consumer<String> browser) {
        Ui.style(this, owner);
        setTitle("À propos de " + About.NAME);
        getDialogPane().getStyleClass().add("form-dialog");

        FontIcon logo = new FontIcon(Feather.CREDIT_CARD);
        logo.getStyleClass().add("brand-icon");
        VBox titles = new VBox(2, Ui.label(About.NAME, Styles.TITLE_3));
        About.version().ifPresent(version -> titles.getChildren().add(Ui.label("Version " + version, Styles.TEXT_MUTED)));
        HBox brand = new HBox(12, logo, titles);
        brand.setAlignment(Pos.CENTER_LEFT);

        getDialogPane().setContent(new DialogContent(WIDTH, 16, brand,
                Ui.wrapped("Vos relevés du Crédit Agricole classés par catégories, et le bilan qui en découle : ce qui rentre, "
                        + "ce qui sort, et ce qu'il est possible d'économiser."),
                Ui.form("Auteur", Ui.label(About.AUTHOR, "profile-name"),
                        "LinkedIn", link(Feather.LINKEDIN, About.AUTHOR_LINKEDIN, browser),
                        "GitHub", link(Feather.GITHUB, About.AUTHOR_GITHUB, browser))));
        getDialogPane().getButtonTypes().add(new ButtonType("Fermer", ButtonData.CANCEL_CLOSE));
    }

    private static Hyperlink link(Ikon icon, String url, Consumer<String> browser) {
        Hyperlink link = new Hyperlink(shown(url), new FontIcon(icon));
        link.setTooltip(new Tooltip("Ouvrir " + url + " dans le navigateur"));
        link.setOnAction(_ -> browser.accept(url));
        return link;
    }

    /** L'adresse telle qu'on la lit : sans son protocole ni sa barre finale. */
    static String shown(String url) {
        return url.replaceFirst("^https?://(www\\.)?", "").replaceFirst("/$", "");
    }
}
