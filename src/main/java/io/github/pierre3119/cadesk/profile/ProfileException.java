package io.github.pierre3119.cadesk.profile;

/** Une opération sur les profils qui ne peut pas aboutir ; le message s'adresse à l'utilisateur. */
public final class ProfileException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ProfileException(String message) {
        super(message);
    }

    public ProfileException(String message, Throwable cause) {
        super(message, cause);
    }
}
