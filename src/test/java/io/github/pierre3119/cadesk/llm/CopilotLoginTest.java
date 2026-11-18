package io.github.pierre3119.cadesk.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pierre3119.cadesk.llm.CopilotLogin.Identity;
import io.github.pierre3119.cadesk.llm.CopilotLogin.Prompt;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;

/**
 * Connexion à GitHub par le navigateur, jouée contre le {@linkplain FakeCopilotRuntime faux moteur} : la
 * page donnée à ouvrir, le jeton remis une fois la connexion autorisée, et ce qu'il reste du moteur — rien —
 * que la connexion aboutisse, soit refusée, abandonnée ou jamais autorisée.
 */
class CopilotLoginTest {

    @TempDir
    Path directory;

    private final List<Prompt> prompts = new ArrayList<>();

    private Path journal() {
        return directory.resolve("journal.jsonl");
    }

    private CopilotLogin login(boolean byCode, String... scenario) {
        return FakeCopilot.login(journal(), byCode, scenario);
    }

    /** L'utilisateur à qui l'on donne une page l'ouvre et y autorise la connexion. */
    private void authorizes(Prompt prompt) {
        prompts.add(prompt);
        FakeCopilotRuntime.authorize(journal());
    }

    private List<JsonObject> launches() {
        return FakeCopilotRuntime.noted(journal()).stream().filter(line -> line.has("args")).toList();
    }

    private List<JsonObject> requests(String method) {
        return FakeCopilotRuntime.noted(journal()).stream().filter(line -> line.has("method") && line.get("method").getAsString().equals(method))
                .map(line -> line.getAsJsonObject("params")).toList();
    }

    private static List<String> arguments(JsonObject launch) {
        List<String> values = new ArrayList<>();
        launch.getAsJsonArray("args").forEach(value -> values.add(value.getAsString()));
        return values;
    }

    /** Le moteur est arrêté, et son dossier — où il a gardé le jeton — n'existe plus. */
    private void nothingIsLeft() {
        for (JsonObject launch : launches()) {
            assertThat(ProcessHandle.of(launch.get("pid").getAsLong()).filter(ProcessHandle::isAlive)).as("moteur arrêté").isEmpty();
            Path home = Path.of(launch.getAsJsonObject("env").get("COPILOT_HOME").getAsString());
            assertThat(home.getParent().getFileName().toString()).startsWith(CopilotScratch.PREFIX);
            assertThat(home.getParent()).as("dossier du moteur").doesNotExist();
        }
    }

    // ------------------------------------------------------------------ connexion réussie

    @Test
    void authorizingInTheBrowserHandsOverTheTokenOfTheAccount() {
        Identity identity = login(false).signIn(this::authorizes);

        assertThat(identity.login()).isEqualTo(FakeCopilotRuntime.LOGIN);
        assertThat(identity.token()).isEqualTo(FakeCopilotRuntime.DELIVERED);
        assertThat(identity).as("le jeton ne s'écrit pas par mégarde").asString().doesNotContain(FakeCopilotRuntime.DELIVERED);
        assertThat(prompts).as("une seule page, sans code à saisir").hasSize(1);
        assertThat(prompts.getFirst().page()).startsWith("https://github.com/login/oauth/authorize?");
        assertThat(prompts.getFirst().code()).isNull();

        JsonObject begun = requests("session.accounts.login.begin").getFirst();
        assertThat(begun.get("kind").getAsString()).isEqualTo("githubDotCom");
        assertThat(begun.get("method").getAsString()).isEqualTo("web");
        // le moteur demande s'il peut garder le jeton dans son dossier : oui, le temps de le remettre
        assertThat(requests("session.accounts.login.advance")).extracting(advance -> advance.has("input") ? advance.get("input").getAsString() : null)
                .containsExactly(null, "allow");
        assertThat(requests("session.accounts.login.cancel")).isEmpty();
        nothingIsLeft();
    }

    @Test
    void theEngineHasNoIdentityAndNoToolWhileSigningIn() {
        login(false).signIn(this::authorizes);

        List<JsonObject> launches = launches();
        assertThat(launches).as("le moteur de la connexion, puis le même relancé pour remettre le jeton").hasSize(2);
        assertThat(arguments(launches.get(0))).contains("--no-auto-login");
        assertThat(arguments(launches.get(1))).doesNotContain("--no-auto-login");
        for (JsonObject launch : launches) {
            assertThat(arguments(launch)).doesNotContain("--auth-token-env");
            assertThat(launch.getAsJsonObject("env").keySet()).as("rien de l'environnement de Copilot ou de GitHub de la machine")
                    .containsExactlyInAnyOrder("COPILOT_HOME", "COPILOT_CACHE_HOME", "COPILOT_TELEMETRY_DISABLED", "COPILOT_DISABLE_KEYTAR");
        }
        assertThat(launches.get(1).getAsJsonObject("env").get("COPILOT_HOME")).as("même dossier")
                .isEqualTo(launches.get(0).getAsJsonObject("env").get("COPILOT_HOME"));

        JsonObject conversation = requests("session.create").getFirst();
        assertThat(conversation.has("model")).as("aucun modèle : aucune question n'est posée").isFalse();
        assertThat(conversation.getAsJsonObject("tools").getAsJsonArray("availableTools")).isEmpty();
        assertThat(requests("session.sendAndWait")).isEmpty();
        assertThat(requests("session.delete")).hasSize(1);
    }

    @Test
    void aCodeCanBeTypedWhenTheBrowserIsElsewhere() {
        CopilotLogin login = login(true);

        Identity identity = login.signIn(this::authorizes);

        assertThat(login.byCode()).isTrue();
        assertThat(identity.token()).isEqualTo(FakeCopilotRuntime.DELIVERED);
        assertThat(prompts).containsExactly(new Prompt("https://github.com/login/device", FakeCopilotRuntime.CODE));
        assertThat(requests("session.accounts.login.begin").getFirst().get("method").getAsString()).isEqualTo("device-code");
        nothingIsLeft();
    }

    @Test
    void anEngineThatKeepsTheTokenWithoutAskingIsFineToo() {
        Identity identity = login(false, FakeCopilotRuntime.KEPT).signIn(this::authorizes);

        assertThat(identity.token()).isEqualTo(FakeCopilotRuntime.DELIVERED);
        assertThat(requests("session.accounts.login.advance")).hasSize(1);
        nothingIsLeft();
    }

    // ------------------------------------------------------------------ connexions qui n'aboutissent pas

    @Test
    void aRefusalOnThePageOfGitHubIsReported() {
        assertThatThrownBy(() -> login(false, FakeCopilotRuntime.DENIED).signIn(this::authorizes)).isInstanceOf(LlmException.class)
                .hasMessageContaining("n'a pas accordé la connexion").hasMessageContaining("access_denied");

        assertThat(launches()).as("le moteur n'est pas relancé").hasSize(1);
        nothingIsLeft();
    }

    @Test
    void aSignInAbandonedWhileWaitingStopsAtOnce() throws Exception {
        CopilotLogin login = login(false);
        CountDownLatch asked = new CountDownLatch(1);
        CompletableFuture<Identity> pending = CompletableFuture.supplyAsync(() -> login.signIn(prompt -> {
            prompts.add(prompt);
            asked.countDown();
        }));
        assertThat(asked.await(30, TimeUnit.SECONDS)).as("page donnée à ouvrir").isTrue();
        assertThat(pending).as("en attente de l'autorisation").isNotDone();

        login.cancel();

        assertThatThrownBy(() -> pending.get(30, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(CancellationException.class);
        assertThat(requests("session.accounts.login.cancel")).as("le moteur est prévenu").hasSize(1);
        nothingIsLeft();
    }

    @Test
    void aSignInAbandonedBeforeItStartsLaunchesNothing() {
        CopilotLogin login = login(false);
        login.cancel();

        assertThatThrownBy(() -> login.signIn(this::authorizes)).isInstanceOf(CancellationException.class);
        assertThat(journal()).doesNotExist();
        assertThat(prompts).isEmpty();
    }

    @Test
    void anAuthorizationThatNeverComesIsGivenUp() {
        CopilotLogin impatient = new CopilotLogin(false, FakeCopilotRuntime.command(journal()), Duration.ofMillis(700));

        assertThatThrownBy(() -> impatient.signIn(prompts::add)).isInstanceOf(LlmException.class).hasMessageContaining("pas été autorisée à temps");

        assertThat(prompts).hasSize(1);
        assertThat(requests("session.accounts.login.cancel")).hasSize(1);
        nothingIsLeft();
    }

    @Test
    void onlyAPageOfGitHubIsEverOpened() {
        assertThatThrownBy(() -> login(false, FakeCopilotRuntime.ELSEWHERE).signIn(this::authorizes)).isInstanceOf(LlmException.class)
                .hasMessageContaining("page de connexion inattendue");

        assertThat(prompts).as("rien n'est donné à ouvrir").isEmpty();
        nothingIsLeft();
    }

    @Test
    void aSignInThatYieldsNoTokenIsReported() {
        assertThatThrownBy(() -> login(false, FakeCopilotRuntime.NO_TOKEN).signIn(this::authorizes)).isInstanceOf(LlmException.class)
                .hasMessageContaining("n'a pas remis de jeton");

        nothingIsLeft();
    }

    @Test
    void anEngineThatCannotStartIsReported() {
        CopilotLogin broken = new CopilotLogin(false, List.of(directory.resolve("introuvable").toString()), Duration.ofSeconds(5));

        assertThatThrownBy(() -> broken.signIn(this::authorizes)).isInstanceOf(LlmException.class).hasMessageContaining("n'a pas démarré");
        assertThat(prompts).isEmpty();
    }

    @Test
    void theDeliveredTokenThenServesAsAnyOther() {
        Identity identity = login(false).signIn(this::authorizes);

        LlmSettings settings = new LlmSettings(LlmProvider.COPILOT, "", identity.token(), "gpt-test", false);
        assertThat(settings.isConfigured()).isTrue();
        try (LlmClient client = LlmClient.of(settings)) {
            assertThat(client).isInstanceOf(CopilotLlmClient.class);
        }
    }
}
