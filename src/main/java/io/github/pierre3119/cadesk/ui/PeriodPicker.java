package io.github.pierre3119.cadesk.ui;

import io.github.pierre3119.cadesk.analysis.Period;

import javafx.beans.property.ObjectProperty;
import javafx.collections.ListChangeListener;
import javafx.geometry.Pos;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;

/**
 * Choix d'une période : la liste des périodes proposées et, quand la période « entre deux dates » est
 * choisie, ses deux bornes. Ces bornes sont communes à toute l'application : les modifier ici les
 * modifie partout. Une borne laissée vide est sans limite.
 *
 * <p>Il n'y a rien à valider : une borne s'applique dès qu'un jour est choisi dans le calendrier ; saisie
 * au clavier, dès la touche Entrée ou la sortie du champ.
 */
final class PeriodPicker extends HBox {

    private final AppModel model;
    private final ObjectProperty<Period> value;
    private final DatePicker from = Ui.datePicker("Du");
    private final DatePicker to = Ui.datePicker("Au");
    private boolean showing;

    /**
     * @param value la période choisie : celle des écrans de bilan, ou celle d'un filtre propre à un écran
     */
    PeriodPicker(AppModel model, ObjectProperty<Period> value) {
        super(8);
        this.model = model;
        this.value = value;
        ComboBox<Period> choice = new ComboBox<>(model.periods);
        choice.valueProperty().bindBidirectional(value);
        choice.setTooltip(new Tooltip("Période analysée"));
        choice.setMinWidth(Region.USE_PREF_SIZE);
        String applied = " La date s'applique dès qu'elle est choisie ; saisie au clavier, validez-la par Entrée.";
        from.setTooltip(new Tooltip("Premier jour de la période (vide : sans limite)." + applied));
        to.setTooltip(new Tooltip("Dernier jour de la période (vide : sans limite)." + applied));
        for (DatePicker bound : new DatePicker[] {from, to}) {
            bound.managedProperty().bind(bound.visibleProperty());
            bound.valueProperty().addListener((_, _, _) -> {
                if (!showing) {
                    model.setCustomRange(from.getValue(), to.getValue());
                }
            });
        }
        setAlignment(Pos.CENTER_LEFT);
        getChildren().addAll(choice, from, to);

        value.addListener((_, _, period) -> show(period));
        model.periods.addListener((ListChangeListener<Period>) _ -> follow());
        show(value.get());
    }

    private void show(Period period) {
        boolean custom = period != null && period.custom();
        from.setVisible(custom);
        to.setVisible(custom);
        if (custom) {
            showing = true;
            try {
                from.setValue(period.from());
                to.setValue(period.to());
            } finally {
                showing = false;
            }
        }
    }

    /** Les bornes de la période « entre deux dates » ont changé : si c'est elle qui est choisie, la sélection suit. */
    private void follow() {
        Period current = value.get();
        if (current != null && current.custom() && !model.periods.contains(current)) {
            model.periods.stream().filter(Period::custom).findFirst().ifPresent(value::set);
        }
    }
}
