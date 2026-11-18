package io.github.pierre3119.cadesk.ui;

import javafx.scene.Node;
import javafx.scene.layout.VBox;

/**
 * Contenu d'une boîte de dialogue, de largeur fixe.
 *
 * <p>Une fenêtre de dialogue demande à son contenu sa hauteur « sans largeur connue » ; un texte qui passe
 * à la ligne répond alors comme s'il tenait sur une seule ligne, et la fenêtre s'ouvre trop petite. Ici
 * la hauteur est toujours calculée pour la largeur fixée.
 */
final class DialogContent extends VBox {

    DialogContent(double width, double spacing, Node... children) {
        super(spacing, children);
        setPrefWidth(width);
    }

    @Override
    protected double computePrefHeight(double width) {
        return super.computePrefHeight(width < 0 ? getPrefWidth() : width);
    }

    @Override
    protected double computeMinHeight(double width) {
        return super.computeMinHeight(width < 0 ? getPrefWidth() : width);
    }
}
