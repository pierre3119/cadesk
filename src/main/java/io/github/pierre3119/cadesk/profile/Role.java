package io.github.pierre3119.cadesk.profile;

/** Rôle d'un profil. */
public enum Role {

    USER("Utilisateur"),
    /** Peut ouvrir tous les profils, réinitialiser leur mot de passe et sauvegarder l'ensemble des données. */
    ADMIN("Administrateur");

    private final String label;

    Role(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
