package io.github.pierre3119.cadesk.security;

/** Le mot de passe saisi n'est pas le bon. */
public final class WrongPasswordException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public WrongPasswordException() {
        super("Mot de passe incorrect.");
    }
}
