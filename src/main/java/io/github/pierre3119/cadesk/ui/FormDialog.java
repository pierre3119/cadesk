package io.github.pierre3119.cadesk.ui;

import atlantafx.base.theme.Styles;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

import javafx.beans.Observable;
import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

/**
 * Petit formulaire en boîte de dialogue : quelques champs (texte, mot de passe, case à cocher, liste de
 * choix), des règles de validation, un bouton d'action. Sert aux réglages des profils.
 *
 * <pre>{@code
 * FormDialog.of(owner, "Renommer le profil", null).text("name", "Nom", current)
 *         .rule(a -> a.text("name").isEmpty() ? "" : null).button("Renommer").ask()
 *         .ifPresent(a -> rename(a.text("name")));
 * }</pre>
 */
final class FormDialog {

    /** Les réponses du formulaire, par nom de champ. */
    record Answers(Map<String, Object> values) {

        String text(String name) {
            return ((String) values.get(name)).strip();
        }

        /** Un mot de passe, tel qu'il a été saisi. */
        char[] secret(String name) {
            return ((String) values.get(name)).toCharArray();
        }

        boolean filled(String name) {
            return !((String) values.get(name)).isEmpty();
        }

        boolean checked(String name) {
            return (Boolean) values.get(name);
        }

        @SuppressWarnings("unchecked")
        <T> T choice(String name) {
            return (T) values.get(name);
        }
    }

    private record Field(String name, String label, Node control, Supplier<Object> value, Observable changes) {
    }

    private static final double WIDTH = 470;

    private final Node owner;
    private final String title;
    private final String intro;
    private final List<Field> fields = new ArrayList<>();
    private final List<Function<Answers, String>> rules = new ArrayList<>();
    private String action = "Enregistrer";
    private boolean danger;

    private FormDialog(Node owner, String title, String intro) {
        this.owner = owner;
        this.title = title;
        this.intro = intro;
    }

    /**
     * @param intro texte d'explication affiché au-dessus des champs, ou {@code null}
     */
    static FormDialog of(Node owner, String title, String intro) {
        return new FormDialog(owner, title, intro);
    }

    FormDialog text(String name, String label, String initial) {
        TextField field = new TextField(initial == null ? "" : initial);
        fields.add(new Field(name, label, field, field::getText, field.textProperty()));
        return this;
    }

    FormDialog secret(String name, String label) {
        PasswordField field = new PasswordField();
        fields.add(new Field(name, label, field, field::getText, field.textProperty()));
        return this;
    }

    FormDialog check(String name, String label, boolean initial) {
        CheckBox box = new CheckBox(label);
        box.setSelected(initial);
        box.setWrapText(true);
        fields.add(new Field(name, null, box, box::isSelected, box.selectedProperty()));
        return this;
    }

    /** Liste de choix ; le premier est proposé d'emblée. */
    <T> FormDialog choice(String name, String label, List<T> options) {
        ComboBox<T> box = new ComboBox<>(FXCollections.observableArrayList(options));
        if (!options.isEmpty()) {
            box.setValue(options.getFirst());
        }
        fields.add(new Field(name, label, box, box::getValue, box.valueProperty()));
        return this;
    }

    /**
     * Ajoute une règle de validation : elle renvoie {@code null} si la saisie convient, un message si elle
     * est fautive, ou une chaîne vide si elle est simplement incomplète (le bouton reste alors inactif, sans
     * message).
     */
    FormDialog rule(Function<Answers, String> rule) {
        rules.add(rule);
        return this;
    }

    /** Exige que deux saisies d'un même mot de passe soient identiques. */
    FormDialog same(String password, String confirmation) {
        return rule(a -> {
            String first = (String) a.values().get(password);
            String second = (String) a.values().get(confirmation);
            return first.equals(second) ? null : second.isEmpty() ? "" : "Les deux saisies du mot de passe ne sont pas identiques.";
        });
    }

    /** Exige que ces champs soient remplis. */
    FormDialog required(String... names) {
        return rule(a -> {
            for (String name : names) {
                if (!a.filled(name)) {
                    return "";
                }
            }
            return null;
        });
    }

    /** Libellé du bouton d'action. */
    FormDialog button(String text) {
        action = text;
        return this;
    }

    /** Signale une action sans retour : le bouton d'action prend la couleur du danger. */
    FormDialog danger() {
        danger = true;
        return this;
    }

    /** Ouvre le formulaire ; les réponses ne sont rendues que si l'utilisateur valide une saisie correcte. */
    Optional<Answers> ask() {
        return dialog().showAndWait();
    }

    /** Construit la boîte de dialogue sans l'ouvrir. */
    Dialog<Answers> dialog() {
        Dialog<Answers> dialog = new Dialog<>();
        Ui.style(dialog, owner);
        dialog.setTitle(title);
        dialog.getDialogPane().getStyleClass().add("form-dialog");

        List<Object> labelled = new ArrayList<>();
        VBox checks = new VBox(8);
        for (Field field : fields) {
            if (field.label() == null) {
                checks.getChildren().add(field.control());
            } else {
                labelled.add(field.label());
                labelled.add(field.control());
            }
        }
        Label error = Ui.wrapped("", Styles.TEXT_SMALL, Styles.DANGER);
        error.setMinHeight(18);
        DialogContent content = new DialogContent(WIDTH, 12);
        if (intro != null) {
            content.getChildren().add(Ui.wrapped(intro, Styles.TEXT_MUTED));
        }
        if (!labelled.isEmpty()) {
            content.getChildren().add(Ui.form(labelled.toArray()));
        }
        if (!checks.getChildren().isEmpty()) {
            content.getChildren().add(checks);
        }
        content.getChildren().add(error);
        dialog.getDialogPane().setContent(content);

        ButtonType ok = new ButtonType(action, ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(ok, ButtonType.CANCEL);
        Node okButton = dialog.getDialogPane().lookupButton(ok);
        if (danger) {
            okButton.getStyleClass().add(Styles.DANGER);
        }
        Runnable validate = () -> {
            Answers answers = answers();
            String problem = null;
            for (Function<Answers, String> rule : rules) {
                problem = rule.apply(answers);
                if (problem != null) {
                    break;
                }
            }
            error.setText(problem == null ? "" : problem);
            okButton.setDisable(problem != null);
        };
        fields.forEach(field -> field.changes().addListener(_ -> validate.run()));
        validate.run();
        Ui.refitOnChange(dialog, error.textProperty());
        dialog.setResultConverter(button -> button == ok ? answers() : null);
        if (!fields.isEmpty()) {
            fields.getFirst().control().requestFocus();
        }
        return dialog;
    }

    private Answers answers() {
        Map<String, Object> values = new LinkedHashMap<>();
        fields.forEach(field -> values.put(field.name(), field.value().get()));
        return new Answers(values);
    }
}
