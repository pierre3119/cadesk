package io.github.pierre3119.cadesk.ui;

import atlantafx.base.theme.Styles;
import io.github.pierre3119.cadesk.domain.Category;
import io.github.pierre3119.cadesk.domain.Nature;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

import javafx.application.Platform;
import javafx.beans.Observable;
import javafx.beans.binding.Bindings;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.feather.Feather;
import org.kordamp.ikonli.javafx.FontIcon;

/** Petits composants partagés par les écrans. */
final class Ui {

    private Ui() {
    }

    static Label label(String text, String... styleClasses) {
        Label label = new Label(text);
        label.getStyleClass().addAll(styleClasses);
        return label;
    }

    /** Texte qui passe à la ligne au lieu d'être tronqué. */
    static Label wrapped(String text, String... styleClasses) {
        Label label = label(text, styleClasses);
        label.setWrapText(true);
        label.setMinHeight(Region.USE_PREF_SIZE);
        return label;
    }

    static Region spacer() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return spacer;
    }

    /** Étiquette arrondie, colorée selon la nature. */
    static Label chip(String text, Nature nature) {
        Label chip = label(text, "chip", nature.styleClass());
        chip.setMinWidth(Region.USE_PREF_SIZE);
        return chip;
    }

    static Label chip(Category category) {
        Label chip = chip(category.name(), category.nature());
        if (category.isFallback()) {
            chip.getStyleClass().add("chip-pending");
        }
        return chip;
    }

    /** Pastille de la couleur de la nature. */
    static Region dot(Nature nature) {
        Region dot = new Region();
        dot.getStyleClass().addAll("dot", nature.styleClass());
        dot.setMinSize(10, 10);
        dot.setPrefSize(10, 10);
        dot.setMaxSize(10, 10);
        return dot;
    }

    /** Carte : un titre facultatif et un contenu, sur fond clair à coins arrondis. */
    static VBox card(String title, Node... content) {
        VBox card = new VBox(12);
        card.getStyleClass().add("card");
        if (title != null) {
            card.getChildren().add(label(title, "card-title"));
        }
        card.getChildren().addAll(content);
        return card;
    }

    /** Chiffre clé : légende, valeur, précision. Dans une rangée, toutes les tuiles prennent la même largeur. */
    static VBox stat(String caption, String value, String note, String... styleClasses) {
        Label captionLabel = label(caption, "stat-caption");
        captionLabel.setMinWidth(0);
        Label valueLabel = label(value, "stat-value");
        valueLabel.setMinWidth(Region.USE_PREF_SIZE);
        VBox box = new VBox(4, captionLabel, valueLabel);
        if (note != null) {
            Label noteLabel = label(note, Styles.TEXT_SMALL, "stat-note");
            noteLabel.setMinWidth(0);
            box.getChildren().add(noteLabel);
        }
        box.getStyleClass().add("stat");
        box.getStyleClass().addAll(styleClasses);
        HBox.setHgrow(box, Priority.ALWAYS);
        box.setPrefWidth(150);
        box.setMaxWidth(Double.MAX_VALUE);
        return box;
    }

    /** Formulaire de boîte de dialogue : une ligne par champ, l'intitulé à gauche. */
    static GridPane form(Object... labelsAndFields) {
        GridPane form = new GridPane();
        form.setHgap(14);
        form.setVgap(10);
        for (int i = 0; i < labelsAndFields.length; i += 2) {
            Node title = labelsAndFields[i] instanceof Node node ? node : label(labelsAndFields[i].toString());
            ((Region) title).setMinWidth(Region.USE_PREF_SIZE);
            Node field = (Node) labelsAndFields[i + 1];
            GridPane.setHgrow(field, Priority.ALWAYS);
            if (field instanceof Region region) {
                region.setMaxWidth(Double.MAX_VALUE);
            }
            form.addRow(i / 2, title, field);
        }
        return form;
    }

    /** Défilement d'un contenu qui prend toute la largeur ; une fenêtre trop étroite fait défiler aussi en largeur. */
    static ScrollPane scroll(Node content) {
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("page-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);
        return scroll;
    }

    /** Barre horizontale proportionnelle à {@code fraction} (0 à 1), avec son texte par-dessus. */
    static StackPane bar(double fraction, String text) {
        StackPane pane = new StackPane();
        Region bar = new Region();
        bar.getStyleClass().add("data-bar");
        bar.setMinWidth(0);
        bar.setMaxHeight(20);
        bar.maxWidthProperty().bind(pane.widthProperty().multiply(Math.clamp(fraction, 0, 1)));
        StackPane.setAlignment(bar, Pos.CENTER_LEFT);
        Label label = label(text, "data-bar-text");
        StackPane.setAlignment(label, Pos.CENTER_LEFT);
        pane.getChildren().addAll(bar, label);
        pane.getStyleClass().add("data-bar-cell");
        pane.setMinWidth(60);
        return pane;
    }

    static Button iconButton(Ikon icon, String tooltip, Runnable action) {
        Button button = new Button(null, new FontIcon(icon));
        button.getStyleClass().addAll(Styles.BUTTON_ICON, Styles.FLAT, Styles.SMALL);
        button.setTooltip(new Tooltip(tooltip));
        button.setOnAction(_ -> action.run());
        return button;
    }

    /**
     * Choix de la période d'analyse, commun à tous les écrans de bilan, suivi du bouton qui exporte le
     * rapport PDF de cette période.
     */
    static Node periodChoice(AppModel model) {
        Button export = new Button("Rapport PDF", new FontIcon(Feather.FILE_TEXT));
        export.setTooltip(new Tooltip("Exporter en PDF le rapport complet de la période affichée"));
        export.setMinWidth(Region.USE_PREF_SIZE);
        export.disableProperty().bind(model.busy.or(Bindings.isEmpty(model.operations)));
        export.setOnAction(_ -> ReportExport.ask(model, export));
        HBox box = new HBox(8, new PeriodPicker(model, model.period), export);
        box.setAlignment(Pos.CENTER_RIGHT);
        return box;
    }

    /** Champ de date au format français ; vidé, il vaut {@code null}. */
    static DatePicker datePicker(String prompt) {
        DateTimeFormatter format = DateTimeFormatter.ofPattern("dd/MM/yyyy");
        DatePicker picker = new DatePicker();
        picker.setPromptText(prompt);
        picker.setPrefWidth(142);
        picker.setConverter(new StringConverter<>() {
            @Override
            public String toString(LocalDate date) {
                return date == null ? "" : format.format(date);
            }

            @Override
            public LocalDate fromString(String text) {
                if (text == null || text.isBlank()) {
                    return null;
                }
                try {
                    return LocalDate.parse(text.strip(), format);
                } catch (DateTimeParseException e) {
                    return picker.getValue(); // saisie illisible : la date précédente est gardée
                }
            }
        });
        return picker;
    }

    /** Demande confirmation d'une action sans retour ; {@code action} est le libellé du bouton qui la déclenche. */
    static boolean confirm(Node owner, String question, String consequence, String action) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, consequence, new ButtonType(action, ButtonType.OK.getButtonData()),
                ButtonType.CANCEL);
        style(alert, owner);
        alert.setTitle(App.NAME);
        alert.setHeaderText(question);
        return alert.showAndWait().filter(b -> b.getButtonData() == ButtonType.OK.getButtonData()).isPresent();
    }

    /** Signale une erreur qui interrompt ce que l'utilisateur faisait. */
    static void error(Node owner, String what, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message);
        style(alert, owner);
        alert.setTitle(App.NAME);
        alert.setHeaderText(what);
        alert.showAndWait();
    }

    /** En-tête d'écran : titre, sous-titre, et à droite des commandes éventuelles. */
    static HBox header(String title, Label subtitle, Node... right) {
        subtitle.getStyleClass().add(Styles.TEXT_MUTED);
        VBox titles = new VBox(2, label(title, Styles.TITLE_2), subtitle);
        HBox header = new HBox(12, titles, spacer());
        header.getChildren().addAll(right);
        header.setAlignment(Pos.CENTER_LEFT);
        return header;
    }

    /** Réajuste la fenêtre d'une boîte de dialogue à son contenu quand l'une de ces valeurs change (zone qui apparaît, texte qui s'allonge). */
    static void refitOnChange(Dialog<?> dialog, Observable... triggers) {
        for (Observable trigger : triggers) {
            trigger.addListener(_ -> Platform.runLater(() -> {
                Scene scene = dialog.getDialogPane().getScene();
                if (scene != null && scene.getWindow() != null && scene.getWindow().isShowing()) {
                    scene.getWindow().sizeToScene();
                }
            }));
        }
    }

    /** Donne à une boîte de dialogue la fenêtre, la feuille de styles et le thème de l'écran qui l'ouvre. */
    static void style(Dialog<?> dialog, Node owner) {
        Scene scene = owner.getScene();
        if (scene == null) {
            return;
        }
        if (scene.getWindow() != null) {
            dialog.initOwner(scene.getWindow());
        }
        dialog.getDialogPane().getStylesheets().addAll(scene.getStylesheets());
        if (scene.getRoot().getStyleClass().contains("dark")) {
            dialog.getDialogPane().getStyleClass().add("dark");
        }
    }
}
