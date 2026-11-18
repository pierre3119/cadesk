package io.github.pierre3119.cadesk.profile;

import io.github.pierre3119.cadesk.db.Database;
import io.github.pierre3119.cadesk.security.Vault;

import java.security.KeyPair;
import java.util.Optional;

/**
 * Un profil ouvert : sa base de données et, en mémoire seulement, les clés obtenues à l'ouverture.
 * Une session est créée par le {@link ProfileManager} et doit être fermée.
 */
public final class Session implements AutoCloseable {

    /** Ce qu'il faut à un administrateur pour revenir à son profil sans ressaisir son mot de passe. */
    record Origin(String profileId, String name, KeyPair keys, Vault vault) {
    }

    private Profile profile;
    private final Database database;
    private final Vault vault;
    private KeyPair keys;
    private final Origin origin;
    private final ProfileLock lock;
    private boolean closed;

    Session(Profile profile, Database database, Vault vault, KeyPair keys, Origin origin, ProfileLock lock) {
        this.profile = profile;
        this.database = database;
        this.vault = vault;
        this.keys = keys;
        this.origin = origin;
        this.lock = lock;
    }

    /** Le profil ouvert, dans son état courant (nom, rôle, protection). */
    public synchronized Profile profile() {
        return profile;
    }

    public Database database() {
        return database;
    }

    /** Vrai si le profil a été ouvert par un administrateur et non par son propriétaire. */
    public boolean isGuest() {
        return origin != null;
    }

    /** Nom de l'administrateur qui a ouvert ce profil, le cas échéant. */
    public Optional<String> administrator() {
        return Optional.ofNullable(origin).map(Origin::name);
    }

    /** Vrai si la session peut agir en administrateur : le profil en a le rôle et a été ouvert par son propriétaire. */
    public synchronized boolean isAdmin() {
        return origin == null && profile.isAdmin() && keys != null;
    }

    Vault vault() {
        return vault;
    }

    synchronized KeyPair keys() {
        return keys;
    }

    Origin origin() {
        return origin;
    }

    synchronized void update(Profile changed) {
        profile = changed;
    }

    synchronized void update(Profile changed, KeyPair newKeys) {
        profile = changed;
        keys = newKeys;
    }

    /** Vrai une fois la session fermée : sa base n'est plus utilisable. */
    public synchronized boolean isClosed() {
        return closed;
    }

    /** Ferme la base et libère le profil pour une autre fenêtre. Sans effet si la session est déjà fermée. */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            database.close();
        } finally {
            if (lock != null) {
                lock.close();
            }
        }
    }
}
