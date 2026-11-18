package io.github.pierre3119.cadesk.domain;

/** Grande famille d'une catégorie : c'est elle qui structure le bilan. */
public enum Nature {

    REVENUS("Revenus et remboursements", "nat-revenus"),
    FIXES("Charges fixes", "nat-fixes"),
    VARIABLES("Dépenses variables", "nat-variables"),
    EXCEPTIONNELLES("Dépenses exceptionnelles", "nat-except"),
    INTERNE("Épargne et virements entre mes comptes", "nat-interne");

    private final String label;
    private final String styleClass;

    Nature(String label, String styleClass) {
        this.label = label;
        this.styleClass = styleClass;
    }

    public String label() {
        return label;
    }

    /** Classe CSS associée (couleur de la nature dans l'interface). */
    public String styleClass() {
        return styleClass;
    }

    /** Vrai pour les natures qui sont de vraies dépenses (hors mouvements entre comptes). */
    public boolean isSpending() {
        return this == FIXES || this == VARIABLES || this == EXCEPTIONNELLES;
    }
}
