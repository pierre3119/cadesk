package io.github.pierre3119.cadesk.profile;

import io.github.pierre3119.cadesk.security.PasswordHash;
import io.github.pierre3119.cadesk.security.Sealed;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Un profil : un utilisateur de l'application et sa base de données.
 *
 * @param id        identifiant stable, attribué à la création
 * @param file      fichier de la base, relatif au dossier des données
 * @param encrypted vrai si la base est un coffre chiffré ; le mot de passe est alors vérifié par le coffre
 * @param password  empreinte du mot de passe d'un profil non chiffré, ou {@code null}
 * @param adminKey  clés d'un administrateur, ou {@code null}
 */
public record Profile(String id, String name, Role role, String file, boolean encrypted, PasswordHash password,
        AdminKey adminKey, LocalDateTime createdAt) {

    /**
     * Clés d'un administrateur : la clé publique (X.509, Base64), avec laquelle les profils chiffrés lui
     * confient une clé de secours, et la clé privée chiffrée par son mot de passe.
     */
    public record AdminKey(String publicKey, Sealed privateKey) {
    }

    public Profile {
        Objects.requireNonNull(id);
        Objects.requireNonNull(name);
        Objects.requireNonNull(role);
        Objects.requireNonNull(file);
    }

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }

    /** Vrai si un mot de passe est demandé pour ouvrir le profil. */
    public boolean hasPassword() {
        return encrypted || password != null;
    }

    Profile withName(String newName) {
        return new Profile(id, newName, role, file, encrypted, password, adminKey, createdAt);
    }

    /** Le profil avec un autre mode de protection : chiffré (sans empreinte), ou en clair avec cette empreinte. */
    Profile withProtection(boolean nowEncrypted, PasswordHash newPassword) {
        return new Profile(id, name, role, file, nowEncrypted, nowEncrypted ? null : newPassword, adminKey, createdAt);
    }

    Profile withAdminKey(AdminKey newKey) {
        return new Profile(id, name, newKey == null ? Role.USER : Role.ADMIN, file, encrypted, password, newKey, createdAt);
    }
}
