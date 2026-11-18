package io.github.pierre3119.cadesk.llm;

import io.github.pierre3119.cadesk.llm.LlmProvider.Protocol;

import java.util.List;

/**
 * Un modèle de langage auquel on pose une question. Le client se ferme une fois le travail fini : celui
 * de GitHub Copilot fait tourner un moteur qu'il faut arrêter.
 */
@FunctionalInterface
public interface LlmClient extends AutoCloseable {

    /**
     * Le client du service choisi dans les réglages.
     *
     * @throws LlmException si les réglages sont incomplets
     */
    static LlmClient of(LlmSettings settings) {
        return settings.provider() != null && settings.provider().protocol() == Protocol.COPILOT ? new CopilotLlmClient(settings)
                : new HttpLlmClient(settings);
    }

    /**
     * @param system consigne générale : le rôle du modèle et la forme attendue de sa réponse
     * @param user   la demande
     * @return le texte de la réponse
     * @throws LlmException si le service est injoignable ou refuse la demande
     */
    String complete(String system, String user);

    /**
     * Noms des modèles que le service propose, triés.
     *
     * @throws LlmException si le service est injoignable ou ne donne pas cette liste
     */
    default List<String> models() {
        throw new LlmException("Ce service ne donne pas la liste de ses modèles.");
    }

    /** Libère ce que le client a mobilisé ; il ne sert plus ensuite. */
    @Override
    default void close() {
    }
}
