package io.github.pierre3119.cadesk.llm;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.microsoft.copilot.CopilotClient;
import com.microsoft.copilot.CopilotSession;
import com.microsoft.copilot.JsonRpcException;
import com.microsoft.copilot.SystemMessageMode;
import com.microsoft.copilot.rpc.CopilotClientMode;
import com.microsoft.copilot.rpc.CopilotClientOptions;
import com.microsoft.copilot.rpc.CreateSessionOptions;
import com.microsoft.copilot.rpc.PermissionHandler;
import com.microsoft.copilot.rpc.PermissionRequestResult;
import com.microsoft.copilot.rpc.RuntimeConnection;
import com.microsoft.copilot.rpc.SpawnedStdioRuntimeConnection;
import com.microsoft.copilot.rpc.SystemMessageConfig;
import com.microsoft.copilot.rpc.ToolSet;

/**
 * Le moteur de GitHub Copilot, piloté par son SDK officiel. C'est celui de Copilot CLI, un agent capable de
 * lire des fichiers ou de lancer des commandes ; l'application ne lui demande que de répondre à des
 * questions et de la connecter à GitHub, et le tient pour cela à l'écart de tout le reste :
 * <ul>
 *   <li>il tourne dans un processus à part, lancé au premier besoin et arrêté à la fermeture ;</li>
 *   <li>ses conversations n'ont aucun outil et toute demande de permission y est refusée : un libellé
 *       d'opération tourné comme un ordre ne peut rien lui faire exécuter ;</li>
 *   <li>il ignore les réglages et la connexion de Copilot CLI ou de {@code gh} présents sur l'ordinateur
 *       (mode « vide » du SDK, variables d'environnement de Copilot et de GitHub retirées) : il ne connaît
 *       que le jeton qu'on lui confie, ou aucun ;</li>
 *   <li>ses fichiers, dont le fil des échanges, vont dans un {@linkplain CopilotScratch dossier jetable}.</li>
 * </ul>
 */
final class CopilotEngine implements AutoCloseable {

    static final Duration START_TIMEOUT = Duration.ofMinutes(2);
    static final Duration SHORT_TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_ERROR_LENGTH = 300;
    private static final String CLIENT_NAME = "cadesk";
    /**
     * Le SDK écrit ses échecs, pile d'appels comprise, sur la sortie d'erreur ; ils sont déjà rapportés à
     * l'utilisateur. (Référence gardée : sans elle, le réglage du journal serait oublié.)
     */
    private static final Logger SDK_LOG = Logger.getLogger("com.microsoft.copilot");
    private static final PermissionHandler REFUSE = (_, _) -> CompletableFuture
            .completedFuture(PermissionRequestResult.reject("Aucune action n'est permise : réponds seulement par du texte."));

    static {
        SDK_LOG.setLevel(Level.OFF);
    }

    private final String token;
    private final List<String> command;
    private volatile CopilotScratch scratch;
    private volatile CopilotClient client;
    private Thread atExit;

    /**
     * @param token   jeton GitHub confié au moteur ; vide pour un moteur sans identité, le temps d'une connexion
     * @param command commande qui lance le moteur (programme, puis ses premiers arguments) ; vide pour le
     *                moteur embarqué dans l'application
     */
    CopilotEngine(String token, List<String> command) {
        this.token = token;
        this.command = List.copyOf(command);
    }

    /** Le moteur, lancé au premier besoin. */
    synchronized CopilotClient started() {
        if (client != null) {
            return client;
        }
        CopilotScratch folder;
        try {
            folder = CopilotScratch.create();
        } catch (IOException | RuntimeException e) {
            throw new LlmException("Le dossier de travail de GitHub Copilot n'a pas pu être créé" + detail(e.getMessage()), e);
        }
        CopilotClient starting = null;
        try {
            starting = new CopilotClient(options(folder, false));
            await(starting.start(), START_TIMEOUT);
        } catch (RuntimeException e) {
            stop(starting, true);
            folder.close();
            throw failure(e);
        }
        scratch = folder;
        client = starting;
        // si l'application se ferme en plein travail, le moteur et son dossier ne lui survivent pas
        atExit = new Thread(() -> {
            stop(client, true);
            CopilotScratch left = scratch;
            if (left != null) {
                left.close();
            }
        }, "cadesk-copilot-arret");
        Runtime.getRuntime().addShutdownHook(atExit);
        return starting;
    }

    /**
     * Relance le moteur sur le même dossier en le laissant, cette fois, reprendre la connexion à GitHub
     * qu'il y a enregistrée : c'est ainsi qu'il remet le jeton d'une connexion qu'il vient de mener.
     */
    synchronized CopilotClient resumed() {
        CopilotScratch folder = scratch;
        if (folder == null) {
            throw new IllegalStateException("Le moteur de Copilot n'est pas lancé.");
        }
        stop(client, false);
        client = null;
        CopilotClient starting = null;
        try {
            starting = new CopilotClient(options(folder, true));
            await(starting.start(), START_TIMEOUT);
        } catch (RuntimeException e) {
            stop(starting, true);
            throw failure(e);
        }
        client = starting;
        return starting;
    }

    /** Arrête le moteur et supprime son dossier ; va à son terme même si le travail en cours a été interrompu. */
    @Override
    public synchronized void close() {
        CopilotClient running = client;
        CopilotScratch folder = scratch;
        client = null;
        scratch = null;
        if (atExit != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(atExit);
            } catch (IllegalStateException e) {
                // l'application s'arrête : le crochet fait déjà ce travail
            }
            atExit = null;
        }
        boolean interrupted = Thread.interrupted();
        try {
            stop(running, false);
            if (folder != null) {
                folder.close();
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void stop(CopilotClient running, boolean force) {
        if (running == null) {
            return;
        }
        try {
            if (force) {
                running.forceStop().get(SHORT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } else {
                running.close();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            // le moteur s'arrête de lui-même quand l'application qui le pilote disparaît
        }
    }

    /**
     * @param signedIn vrai pour que le moteur reprenne la connexion enregistrée dans son dossier ; faux pour
     *                 qu'il ne connaisse que le jeton confié
     */
    private CopilotClientOptions options(CopilotScratch folder, boolean signedIn) {
        SpawnedStdioRuntimeConnection runtime = RuntimeConnection.forSpawnedStdio().setBaseDirectory(folder.home().toString())
                .setWorkingDirectory(folder.work().toString()).setEnvironment(environment(System.getenv(), folder.home()));
        if (!command.isEmpty()) {
            runtime.setPath(command.getFirst()).setArgs(command.subList(1, command.size()));
        }
        CopilotClientOptions options = new CopilotClientOptions().setMode(CopilotClientMode.EMPTY).setRuntime(runtime);
        options.getGitHub().setUseLoggedInUser(signedIn);
        if (!token.isEmpty()) {
            options.getGitHub().setToken(token);
        }
        options.getObservability().setLogLevel("none");
        return options;
    }

    /**
     * Environnement du moteur : celui de l'application (réglages de proxy compris), sans les variables de
     * Copilot, de {@code gh} et de GitHub, qui lui donneraient un autre jeton, d'autres réglages ou
     * l'autorisation de tout faire ({@code COPILOT_ALLOW_ALL}).
     */
    static Map<String, String> environment(Map<String, String> inherited, Path home) {
        Map<String, String> environment = new HashMap<>(inherited);
        environment.keySet().removeIf(name -> {
            String upper = name.toUpperCase(Locale.ROOT);
            return upper.startsWith("COPILOT_") || upper.startsWith("GH_") || upper.startsWith("GITHUB_");
        });
        environment.put("COPILOT_CACHE_HOME", home.resolve("cache").toString());
        environment.put("COPILOT_TELEMETRY_DISABLED", "true");
        return environment;
    }

    // ------------------------------------------------------------------ conversations

    /**
     * Une conversation sans outil, dont la consigne remplace celle de l'agent de Copilot ; le moteur doit
     * être lancé.
     *
     * @param model modèle à interroger, ou {@code null} pour une conversation où aucune question ne sera posée
     */
    CreateSessionOptions conversation(String model, String system) {
        CreateSessionOptions conversation = new CreateSessionOptions().setModel(model).setClientName(CLIENT_NAME)
                .setWorkingDirectory(scratch.work().toString()).setEnableConfigDiscovery(false)
                .setSystemMessage(new SystemMessageConfig().setMode(SystemMessageMode.REPLACE).setContent(system));
        conversation.getTools().setAvailableTools(List.of()).setExcludedTools(new ToolSet().addBuiltIn("*").addMcp("*").addCustom("*"));
        conversation.getPermissions().setOnRequest(REFUSE);
        conversation.getGitHub().setTelemetryEnabled(false);
        return conversation;
    }

    /** Ferme la conversation et en efface la trace chez le moteur. */
    void forget(CopilotSession session) {
        String id = session.getSessionId();
        try {
            session.close();
        } catch (RuntimeException e) {
            // la conversation disparaît de toute façon avec le dossier du moteur
        }
        CopilotClient running = client;
        if (running == null) {
            return;
        }
        try {
            running.deleteSession(id).get(SHORT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            // même chose
        }
    }

    // ------------------------------------------------------------------ attente et erreurs

    /**
     * Attend la réponse du moteur.
     *
     * @throws LlmException s'il fait défaut, tarde trop (la cause est alors une {@link TimeoutException}) ou
     *                      si l'attente est interrompue
     */
    <T> T await(CompletableFuture<T> pending, Duration timeout) {
        try {
            return pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new LlmException("La demande à GitHub Copilot a été interrompue.", e);
        } catch (TimeoutException | ExecutionException | RuntimeException e) {
            pending.cancel(true);
            throw failure(e);
        }
    }

    /** L'échec, expliqué à l'utilisateur. */
    LlmException failure(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof ExecutionException || cause instanceof CompletionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof LlmException known) {
            return known;
        }
        if (cause instanceof TimeoutException) {
            return new LlmException("GitHub Copilot n'a pas répondu à temps.", cause);
        }
        if (cause instanceof JsonRpcException refusal) {
            return new LlmException(refusal(refusal), cause);
        }
        for (Throwable origin = cause; origin != null; origin = origin.getCause()) {
            if (origin instanceof FileNotFoundException) {
                return new LlmException("Le moteur de GitHub Copilot est absent de cette installation : reconstruisez l'application "
                        + "(build.sh ou build.bat) et lancez-la par start.sh ou start.bat.", cause);
            }
        }
        if (cause instanceof IOException) {
            return new LlmException("Le moteur de GitHub Copilot n'a pas démarré ou s'est arrêté" + detail(cause.getMessage()), cause);
        }
        return new LlmException("GitHub Copilot a fait défaut" + detail(cause.getMessage()), cause);
    }

    /** Le refus du moteur ou du service, d'après sa catégorie quand il la donne. */
    private String refusal(JsonRpcException refusal) {
        String message = refusal.getMessage() == null ? "" : refusal.getMessage().replaceFirst("^Request \\S+ failed with message: ", "");
        JsonNode data = refusal.getData();
        String type = data == null ? "" : data.path("errorType").asText("");
        long status = data == null ? 0 : data.path("statusCode").asLong(0);
        String lower = message.toLowerCase(Locale.ROOT);
        String reason;
        if (type.equals("authentication") || type.equals("authorization") || status == 401 || status == 403
                || lower.contains("not authenticated") || lower.contains("authentication failed") || lower.contains("bad credentials")) {
            reason = "GitHub Copilot a refusé le jeton : reconnectez-vous à GitHub, ou collez un jeton à permissions fines "
                    + "(github_pat_…) doté de la permission « Copilot Requests » ; le compte doit avoir un abonnement Copilot";
        } else if (type.equals("quota")) {
            reason = "Le quota de votre abonnement GitHub Copilot est épuisé";
        } else if (type.equals("rate_limit")) {
            reason = "GitHub Copilot limite le rythme des demandes : réessayez dans un moment";
        } else if (type.equals("policy")) {
            reason = "Les règles de votre abonnement GitHub Copilot n'autorisent pas cette demande";
        } else {
            reason = "GitHub Copilot a refusé la demande";
        }
        return reason + detail(message);
    }

    /** Le détail donné par le moteur, sans le jeton s'il venait à y figurer. */
    String detail(String message) {
        String text = message == null ? "" : message.strip().replaceAll("\\s+", " ");
        if (!token.isEmpty()) {
            text = text.replace(token, "…");
        }
        if (text.isEmpty()) {
            return ".";
        }
        return " : " + (text.length() > MAX_ERROR_LENGTH ? text.substring(0, MAX_ERROR_LENGTH) + "…" : text);
    }
}
