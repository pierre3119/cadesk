package io.github.pierre3119.cadesk.llm;

/**
 * Services de modèles de langage proposés. La plupart se pilotent par l'interface « chat completions »
 * d'OpenAI, et le choix « Autre » accepte n'importe quel service qui la reprend ; Anthropic a la sienne ;
 * GitHub Copilot n'a pas d'adresse à interroger, il passe par son propre moteur.
 */
public enum LlmProvider {

    OPENAI("OpenAI", Protocol.OPENAI, "https://api.openai.com/v1", true),
    ANTHROPIC("Anthropic (Claude)", Protocol.ANTHROPIC, "https://api.anthropic.com/v1", true),
    MISTRAL("Mistral AI", Protocol.OPENAI, "https://api.mistral.ai/v1", true),
    GEMINI("Google Gemini", Protocol.OPENAI, "https://generativelanguage.googleapis.com/v1beta/openai", true),
    COPILOT("GitHub Copilot", Protocol.COPILOT, "", true),
    OLLAMA("Ollama (modèle local)", Protocol.OPENAI, "http://localhost:11434/v1", false),
    CUSTOM("Autre service compatible OpenAI", Protocol.OPENAI, "", false);

    /** Forme des échanges avec le service. */
    public enum Protocol { OPENAI, ANTHROPIC, COPILOT }

    private final String label;
    private final Protocol protocol;
    private final String defaultUrl;
    private final boolean keyRequired;

    LlmProvider(String label, Protocol protocol, String defaultUrl, boolean keyRequired) {
        this.label = label;
        this.protocol = protocol;
        this.defaultUrl = defaultUrl;
        this.keyRequired = keyRequired;
    }

    public String label() {
        return label;
    }

    public Protocol protocol() {
        return protocol;
    }

    /** Adresse habituelle du service, proposée par défaut. */
    public String defaultUrl() {
        return defaultUrl;
    }

    /** Vrai si le service s'interroge à une adresse ; faux pour GitHub Copilot, joint par son moteur. */
    public boolean urlRequired() {
        return protocol != Protocol.COPILOT;
    }

    /** Vrai si le service ne répond pas sans clé d'API (pour GitHub Copilot : un jeton d'accès GitHub). */
    public boolean keyRequired() {
        return keyRequired;
    }

    @Override
    public String toString() {
        return label;
    }
}
