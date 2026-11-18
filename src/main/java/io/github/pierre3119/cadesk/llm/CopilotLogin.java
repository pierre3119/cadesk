package io.github.pierre3119.cadesk.llm;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.copilot.CopilotSession;
import com.microsoft.copilot.generated.rpc.AccountAllUsers;
import com.microsoft.copilot.generated.rpc.AuthLoginMethod;
import com.microsoft.copilot.generated.rpc.LoginProviderKind;
import com.microsoft.copilot.generated.rpc.SessionAccountsLoginAdvanceParams;
import com.microsoft.copilot.generated.rpc.SessionAccountsLoginApi;
import com.microsoft.copilot.generated.rpc.SessionAccountsLoginBeginParams;
import com.microsoft.copilot.generated.rpc.SessionAccountsLoginBeginResult;
import com.microsoft.copilot.generated.rpc.SessionAccountsLoginCancelParams;

/**
 * Connexion à GitHub par le navigateur, pour obtenir le jeton dont GitHub Copilot a besoin sans avoir à
 * le créer puis à le recopier. C'est le {@linkplain CopilotEngine moteur} de Copilot qui la mène, comme
 * Copilot CLI : il donne la page à ouvrir, attend que l'utilisateur y autorise la connexion, puis remet
 * le jeton. Deux façons d'autoriser :
 * <ul>
 *   <li>la page de GitHub renvoie d'elle-même vers le moteur, qui l'attend sur cet ordinateur — le
 *       navigateur doit donc y tourner lui aussi ;</li>
 *   <li>ou l'utilisateur saisit sur la page un code à usage unique, ce qui marche depuis n'importe quel
 *       navigateur.</li>
 * </ul>
 * Le moteur est lancé sans identité, dans son dossier jetable : le jeton n'y reste que le temps de la
 * connexion. Les permissions du jeton sont celles que demande Copilot CLI ; l'application ne les choisit pas.
 */
public final class CopilotLogin {

    /**
     * Ce qui est attendu de l'utilisateur.
     *
     * @param page adresse de la page de GitHub à ouvrir dans un navigateur
     * @param code code à saisir sur cette page, ou {@code null} s'il suffit d'y autoriser la connexion
     */
    public record Prompt(String page, String code) {
    }

    /**
     * Le compte GitHub connecté.
     *
     * @param login nom du compte
     * @param token jeton d'accès remis par GitHub
     */
    public record Identity(String login, String token) {

        /** Sans le jeton, qui ne doit pas se retrouver par mégarde dans un message. */
        @Override
        public String toString() {
            return "Identity[" + login + "]";
        }
    }

    private static final Duration AUTHORIZATION_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration PAUSE = Duration.ofSeconds(1);
    private static final String PURPOSE = "Connexion à GitHub : aucune question ne sera posée.";
    private static final String GITHUB = "github.com";
    /**
     * Réponse qui permet au moteur de garder le jeton dans son dossier. Lancé sans le trousseau du système,
     * il demande s'il peut l'y écrire en clair : oui, ce dossier est supprimé dès que le jeton est remis.
     */
    private static final String KEEP_IN_FOLDER = "allow";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final boolean byCode;
    private final List<String> command;
    private final Duration timeout;
    private volatile boolean cancelled;
    private volatile CompletableFuture<?> pending;

    /**
     * @param byCode vrai pour autoriser par un code à saisir, faux pour que la page renvoie vers le moteur
     */
    public CopilotLogin(boolean byCode) {
        this(byCode, List.of(), AUTHORIZATION_TIMEOUT);
    }

    /**
     * @param command commande qui lance le moteur ; vide pour le moteur embarqué dans l'application
     * @param timeout temps laissé à l'utilisateur pour autoriser la connexion
     */
    CopilotLogin(boolean byCode, List<String> command, Duration timeout) {
        this.byCode = byCode;
        this.command = List.copyOf(command);
        this.timeout = timeout;
    }

    /** Vrai si l'autorisation passe par un code à saisir. */
    public boolean byCode() {
        return byCode;
    }

    /**
     * Mène la connexion jusqu'à son terme ; l'appel ne rend la main qu'une fois le jeton obtenu.
     *
     * @param prompt reçoit, sur le fil de l'appel, la page à ouvrir et le code éventuel
     * @throws LlmException          si la connexion n'aboutit pas ; le message s'adresse à l'utilisateur
     * @throws CancellationException si elle a été {@linkplain #cancel() abandonnée}
     */
    public Identity signIn(Consumer<Prompt> prompt) {
        try (CopilotEngine engine = new CopilotEngine("", command)) {
            proceed();
            CopilotSession session = await(engine, engine.started().createSession(engine.conversation(null, PURPOSE)),
                    CopilotEngine.START_TIMEOUT);
            SessionAccountsLoginApi login = session.getRpc().accounts.login;
            String flow = null;
            String name;
            try {
                SessionAccountsLoginBeginResult begun = await(engine, login.begin(new SessionAccountsLoginBeginParams(null,
                        LoginProviderKind.GITHUBDOTCOM, byCode ? AuthLoginMethod.DEVICE_CODE : AuthLoginMethod.WEB, null, null)),
                        CopilotEngine.SHORT_TIMEOUT);
                flow = begun.flowId();
                name = authorize(engine, login, flow, JSON.valueToTree(begun.step()), prompt);
                flow = null;
            } finally {
                if (flow != null) {
                    abandon(login, flow);
                }
                engine.forget(session);
            }
            return identity(engine, name);
        }
    }

    /** Abandonne la connexion en cours, depuis n'importe quel fil : {@link #signIn} s'interrompt aussitôt. */
    public void cancel() {
        cancelled = true;
        CompletableFuture<?> waiting = pending;
        if (waiting != null) {
            waiting.cancel(true);
        }
    }

    // ------------------------------------------------------------------ étapes

    /** Suit les étapes que donne le moteur, jusqu'à la connexion ; rend le nom du compte connecté. */
    private String authorize(CopilotEngine engine, SessionAccountsLoginApi login, String flow, JsonNode first, Consumer<Prompt> prompt) {
        long deadline = System.nanoTime() + timeout.toNanos();
        boolean kept = false;
        boolean chosen = false;
        Prompt shown = null;
        JsonNode step = first;
        while (true) {
            String answer = null;
            String kind = step.path("kind").asText();
            switch (kind) {
                case "open-url", "device-code" -> {
                    Prompt asked = kind.equals("open-url") ? new Prompt(page(step.path("url").asText()), null)
                            : new Prompt(page(step.path("verificationUri").asText()), step.path("userCode").asText());
                    if (asked.equals(shown)) {
                        pause(); // la même demande, répétée : le navigateur n'est pas rouvert
                    } else {
                        shown = asked;
                        prompt.accept(asked);
                    }
                }
                case "awaiting", "needs-interaction" -> pause();
                case "completed" -> {
                    JsonNode result = step.path("result");
                    String status = result.path("status").asText();
                    switch (status) {
                        case "completed" -> {
                            return result.path("login").asText("");
                        }
                        case "needs-plaintext-consent" -> {
                            if (kept) {
                                throw new LlmException("Le moteur de Copilot n'a pas pu garder le jeton le temps de le remettre.");
                            }
                            kept = true;
                            answer = KEEP_IN_FOLDER;
                        }
                        case "needs-account-selection" -> {
                            // plusieurs comptes derrière une même connexion : le premier que propose le moteur
                            answer = result.path("accounts").path(0).path("selectionId").asText(null);
                            if (chosen || answer == null) {
                                throw new LlmException("GitHub propose plusieurs comptes, et le moteur de Copilot n'a pas su en retenir un.");
                            }
                            chosen = true;
                        }
                        default -> throw new LlmException("La connexion à GitHub n'a pas abouti (" + status + ").");
                    }
                }
                case "error" -> throw new LlmException("GitHub n'a pas accordé la connexion" + engine.detail(step.path("message").asText()));
                default -> throw new LlmException("Le moteur de Copilot demande une étape que l'application ne sait pas mener (" + kind + ").");
            }
            Duration left = Duration.ofNanos(deadline - System.nanoTime());
            if (!left.isPositive()) {
                throw late(null);
            }
            try {
                step = JSON.valueToTree(await(engine, login.advance(new SessionAccountsLoginAdvanceParams(null, flow, answer)), left));
            } catch (LlmException e) {
                if (e.getCause() instanceof TimeoutException) {
                    throw late(e);
                }
                throw e;
            }
        }
    }

    private static LlmException late(Throwable cause) {
        return new LlmException("La connexion à GitHub n'a pas été autorisée à temps : recommencez quand vous êtes prêt.", cause);
    }

    /** L'adresse à ouvrir, si c'est bien une page de GitHub : rien d'autre n'est confié au navigateur. */
    private static String page(String address) {
        try {
            URI uri = new URI(address);
            if ("https".equalsIgnoreCase(uri.getScheme()) && GITHUB.equalsIgnoreCase(uri.getHost())) {
                return uri.toASCIIString();
            }
        } catch (URISyntaxException e) {
            // traitée comme une adresse inattendue
        }
        throw new LlmException("Le moteur de Copilot a proposé une page de connexion inattendue : rien n'a été ouvert.");
    }

    /**
     * Le jeton du compte que le moteur vient de connecter. Le moteur qui a mené la connexion ne le montre
     * pas : il faut le relancer sur son dossier, où il reprend cette connexion et donne alors ses comptes.
     */
    private Identity identity(CopilotEngine engine, String login) {
        Identity found = null;
        for (AccountAllUsers user : await(engine, engine.resumed().getRpc().account.getAllUsers(), CopilotEngine.SHORT_TIMEOUT)) {
            JsonNode identity = JSON.valueToTree(user.authInfo());
            String name = identity.path("login").asText("");
            // seulement le compte de cette connexion : pas une identité que le moteur aurait trouvée ailleurs
            boolean wanted = identity.path("type").asText().equals("user") && (login.isEmpty() || login.equalsIgnoreCase(name));
            if (wanted && user.token() != null && !user.token().isBlank()) {
                found = new Identity(name, user.token().strip());
            }
        }
        if (found == null) {
            throw new LlmException("GitHub a accordé la connexion, mais le moteur de Copilot n'a pas remis de jeton : créez à la place "
                    + "un jeton à permissions fines (github_pat_…) doté de la permission « Copilot Requests ».");
        }
        return found;
    }

    /** Dit au moteur de ne plus attendre l'autorisation ; sans effet s'il ne répond plus. */
    private static void abandon(SessionAccountsLoginApi login, String flow) {
        try {
            login.cancel(new SessionAccountsLoginCancelParams(null, flow)).orTimeout(5, TimeUnit.SECONDS).join();
        } catch (RuntimeException e) {
            // le moteur est arrêté juste après
        }
    }

    // ------------------------------------------------------------------ attente

    private void proceed() {
        if (cancelled) {
            throw new CancellationException("Connexion à GitHub abandonnée.");
        }
    }

    /** Attend le moteur ; l'attente cesse dès que la connexion est abandonnée. */
    private <T> T await(CopilotEngine engine, CompletableFuture<T> waiting, Duration limit) {
        pending = waiting;
        try {
            if (cancelled) {
                waiting.cancel(true);
            }
            return engine.await(waiting, limit);
        } catch (LlmException e) {
            proceed();
            throw e;
        } finally {
            pending = null;
        }
    }

    private void pause() {
        try {
            Thread.sleep(PAUSE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("La connexion à GitHub a été interrompue.", e);
        }
        proceed();
    }
}
