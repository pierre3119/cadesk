package io.github.pierre3119.cadesk.statement;

/** Le fichier n'est pas un relevé exploitable. Le message, en français, est destiné à l'utilisateur. */
public class StatementFormatException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public StatementFormatException(String message) {
        super(message);
    }

    public StatementFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
