package io.github.pierre3119.cadesk.domain;

import java.util.Objects;

/**
 * Une catégorie du bilan, paramétrable par l'utilisateur.
 *
 * @param id            identifiant en base ; 0 pour une catégorie pas encore enregistrée
 * @param inflow        sens naturel : {@code true} pour une entrée d'argent, {@code false} pour une sortie
 * @param position      rang d'affichage dans le bilan
 * @param incomeKind    pour un revenu, sa régularité (utilisée par l'écran « Économies »)
 * @param savingPercent objectif de réduction proposé dans le simulateur d'économies, de 0 à 100
 */
public record Category(long id, String name, Nature nature, boolean inflow, int position, Role role,
        IncomeKind incomeKind, int savingPercent) {

    /** Rôle particulier d'une catégorie dans le classement. */
    public enum Role {
        STANDARD,
        /** Reçoit les sorties qu'aucune règle ne reconnaît. */
        DEFAULT_OUT,
        /** Reçoit les entrées qu'aucune règle ne reconnaît. */
        DEFAULT_IN
    }

    /** Régularité d'un revenu. */
    public enum IncomeKind {
        REGULIER("Revenu régulier"),
        REMBOURSEMENT("Remboursement reçu"),
        PONCTUEL("Entrée ponctuelle");

        private final String label;

        IncomeKind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public Category {
        Objects.requireNonNull(name);
        Objects.requireNonNull(nature);
        Objects.requireNonNull(role);
        Objects.requireNonNull(incomeKind);
        if (nature != Nature.INTERNE && inflow != (nature == Nature.REVENUS)) {
            throw new IllegalArgumentException("Le sens d'une catégorie « " + nature.label() + " » est imposé par sa nature.");
        }
        if (savingPercent < 0 || savingPercent > 100) {
            throw new IllegalArgumentException("Objectif de réduction hors de 0–100 : " + savingPercent);
        }
    }

    /** Nouvelle catégorie ordinaire, pas encore enregistrée. */
    public static Category create(String name, Nature nature, boolean inflow, IncomeKind incomeKind) {
        return new Category(0, name, nature, inflow, 0, Role.STANDARD, incomeKind, 0);
    }

    /** Sens imposé par la nature ; pour les mouvements entre comptes, {@code wanted} est conservé. */
    public static boolean inflowFor(Nature nature, boolean wanted) {
        return nature == Nature.INTERNE ? wanted : nature == Nature.REVENUS;
    }

    /** Vrai pour les deux catégories « à classer », qui recueillent ce qu'aucune règle ne reconnaît. */
    public boolean isFallback() {
        return role != Role.STANDARD;
    }

    /**
     * Montant d'une opération compté dans le sens naturel de la catégorie : une dépense est positive
     * dans une catégorie de sortie, et un remboursement rangé au même endroit vient en déduction.
     */
    public long signed(long amountCents) {
        return inflow ? amountCents : -amountCents;
    }

    public Category withSavingPercent(int percent) {
        return new Category(id, name, nature, inflow, position, role, incomeKind, percent);
    }

    @Override
    public String toString() {
        return name;
    }
}
