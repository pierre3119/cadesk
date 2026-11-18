package io.github.pierre3119.cadesk.db;

/** Erreur d'accès à la base de données. */
public class DatabaseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DatabaseException(String message, Throwable cause) {
        super(message, cause);
    }
}
