package io.github.pierre3119.cadesk.llm;

import java.time.Duration;
import java.util.List;

import com.microsoft.copilot.CopilotSession;
import com.microsoft.copilot.generated.AssistantMessageEvent;
import com.microsoft.copilot.generated.rpc.Model;
import com.microsoft.copilot.generated.rpc.ModelPolicyState;
import com.microsoft.copilot.rpc.MessageOptions;

/**
 * GitHub Copilot comme modèle de langage. Copilot n'a pas d'interface HTTP de simple question-réponse :
 * les questions passent par son {@linkplain CopilotEngine moteur}, lancé à la première et arrêté à la
 * fermeture, à qui est confié le jeton GitHub du profil. Chaque question ouvre une conversation neuve,
 * sans outil, supprimée aussitôt la réponse reçue.
 */
public final class CopilotLlmClient implements LlmClient {

    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(3);

    private final LlmSettings settings;
    private final Duration requestTimeout;
    private final CopilotEngine engine;

    /**
     * @throws LlmException si les réglages sont incomplets, ou si le jeton est d'un genre que Copilot n'accepte pas
     */
    public CopilotLlmClient(LlmSettings settings) {
        this(settings, List.of(), REQUEST_TIMEOUT);
    }

    /**
     * @param command        commande qui lance le moteur ; vide pour le moteur embarqué dans l'application
     * @param requestTimeout temps laissé au modèle pour répondre à une question
     */
    CopilotLlmClient(LlmSettings settings, List<String> command, Duration requestTimeout) {
        if (!settings.isConfigured()) {
            throw new LlmException("L'assistant n'est pas configuré : GitHub Copilot demande un jeton d'accès GitHub et un modèle.");
        }
        if (settings.apiKey().startsWith("ghp_")) {
            throw new LlmException("GitHub Copilot n'accepte pas les jetons classiques (ghp_…) : connectez-vous à GitHub depuis "
                    + "l'application, ou créez un jeton à permissions fines (github_pat_…) doté de la permission « Copilot Requests ».");
        }
        this.settings = settings;
        this.requestTimeout = requestTimeout;
        this.engine = new CopilotEngine(settings.apiKey(), command);
    }

    @Override
    public synchronized String complete(String system, String user) {
        CopilotSession session = engine.await(engine.started().createSession(engine.conversation(settings.model(), system)),
                CopilotEngine.START_TIMEOUT);
        try {
            AssistantMessageEvent answer = engine.await(session.sendAndWait(new MessageOptions().setPrompt(user), requestTimeout.toMillis()),
                    requestTimeout.plus(CopilotEngine.SHORT_TIMEOUT));
            String text = answer == null || answer.getData() == null ? null : answer.getData().content();
            if (text == null || text.isBlank()) {
                throw new LlmException("Le modèle a renvoyé une réponse vide.");
            }
            return text;
        } finally {
            engine.forget(session);
        }
    }

    /** Modèles que l'abonnement Copilot du jeton permet d'utiliser. */
    @Override
    public synchronized List<String> models() {
        List<Model> models = engine.await(engine.started().listModels(), CopilotEngine.SHORT_TIMEOUT);
        return models.stream()
                .filter(model -> model.id() != null && (model.policy() == null || !ModelPolicyState.DISABLED.equals(model.policy().state())))
                .map(Model::id).distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    /** Arrête le moteur et supprime son dossier. */
    @Override
    public synchronized void close() {
        engine.close();
    }
}
