package io.github.pierre3119.cadesk.ui;

import io.github.pierre3119.cadesk.analysis.Analysis;
import io.github.pierre3119.cadesk.analysis.Period;
import io.github.pierre3119.cadesk.domain.Category;

import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

/**
 * Base des écrans de bilan : un écran n'est redessiné que lorsqu'il est affiché, et seulement si les
 * données ou la période ont changé depuis son dernier dessin.
 */
abstract class AnalysisPage extends VBox {

    /** Permet à un écran de bilan d'ouvrir la liste des opérations sur ce qu'il montre. */
    interface Navigator {

        /** Affiche les opérations de la période courante pour cette catégorie (ou toutes) et ce poste (ou tous). */
        void showOperations(Category category, String poste);

        /** Affiche les opérations qui attendent une catégorie. */
        void showUnclassified();
    }

    protected final AppModel model;
    protected final Navigator navigator;
    protected final Label subtitle = new Label();
    private boolean dirty = true;

    AnalysisPage(AppModel model, Navigator navigator) {
        this.model = model;
        this.navigator = navigator;
        getStyleClass().add("page");
        model.analysis.addListener((_, _, _) -> {
            dirty = true;
            refreshIfShown();
        });
        parentProperty().addListener((_, _, _) -> refreshIfShown());
    }

    private void refreshIfShown() {
        if (dirty && getParent() != null) {
            dirty = false;
            Analysis analysis = model.analysis.get();
            subtitle.setText(describe(model.period.get(), analysis));
            rebuild(analysis);
        }
    }

    /** Redessine l'écran pour ce bilan. */
    protected abstract void rebuild(Analysis analysis);

    /**
     * « 12 mois, de nov. 25 à oct. 26  ·  789 opérations » ; pour la période choisie entre deux dates, ses
     * bornes exactes — « Du 05/01/2026 au 20/03/2026  ·  44 opérations » —, qui confirment les dates retenues.
     */
    static String describe(Period period, Analysis a) {
        if (period == null || !period.custom()) {
            return Fmt.span(a);
        }
        return Fmt.period(period) + "  ·  " + (a.isEmpty() ? "aucune opération" : Fmt.plural(a.operations().size(), "opération", "opérations"));
    }

    /** « Sur 12 mois », « Sur 1 mois » : titre des colonnes de total. */
    static String overPeriod(Analysis a) {
        return Fmt.overPeriod(a);
    }
}
