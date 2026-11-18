package io.github.pierre3119.cadesk.llm;

/** Le modèle de langage n'a pas pu répondre, ou sa réponse est inexploitable ; le message s'adresse à l'utilisateur. */
public final class LlmException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public LlmException(String message) {
        super(message);
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }
}
