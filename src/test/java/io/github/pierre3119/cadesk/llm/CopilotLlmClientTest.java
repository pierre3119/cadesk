package io.github.pierre3119.cadesk.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;

/**
 * GitHub Copilot par son SDK, joué contre un {@linkplain FakeCopilotRuntime faux moteur} : ce que le
 * moteur reçoit (jeton, environnement, conversation sans outil), ce que l'utilisateur lit quand il fait
 * défaut, et ce qu'il reste de lui une fois le travail fini.
 */
class CopilotLlmClientTest {

    @TempDir
    Path directory;

    private CopilotLlmClient client(String token, Duration timeout) {
        return new CopilotLlmClient(settings(token), FakeCopilotRuntime.command(journal()), timeout);
    }

    private CopilotLlmClient client() {
        return client(FakeCopilotRuntime.TOKEN, Duration.ofSeconds(30));
    }

    private static LlmSettings settings(String token) {
        return new LlmSettings(LlmProvider.COPILOT, "", token, "gpt-test", true);
    }

    private Path journal() {
        return directory.resolve("journal.jsonl");
    }

    /** Ce que le faux moteur a noté : son lancement, puis chaque demande. */
    private List<JsonObject> noted() {
        return FakeCopilotRuntime.noted(journal());
    }

    private JsonObject launch() {
        return noted().getFirst();
    }

    private List<JsonObject> requests(String method) {
        return noted().stream().filter(line -> line.has("method") && line.get("method").getAsString().equals(method))
                .map(line -> line.getAsJsonObject("params")).toList();
    }

    private static List<String> strings(JsonObject object, String name) {
        List<String> values = new ArrayList<>();
        object.getAsJsonArray(name).forEach(value -> values.add(value.getAsString()));
        return values;
    }

    // ------------------------------------------------------------------ question et réponse

    @Test
    void theModelAnswersWithTheInstructionsReplacingThoseOfTheAgent() throws IOException {
        try (CopilotLlmClient client = client()) {
            assertThat(client.complete("consigne : é", "demande 1")).isEqualTo("consigne : é / demande 1");
            assertThat(client.complete("autre consigne", "demande 2")).isEqualTo("autre consigne / demande 2");
        }

        assertThat(noted().stream().filter(line -> line.has("args"))).as("un seul moteur pour les deux questions").hasSize(1);
        List<JsonObject> created = requests("session.create");
        assertThat(created).as("une conversation neuve par question").hasSize(2);
        JsonObject conversation = created.getFirst();
        assertThat(conversation.get("model").getAsString()).isEqualTo("gpt-test");
        assertThat(conversation.getAsJsonObject("systemMessage").get("mode").getAsString()).isEqualTo("replace");
        assertThat(conversation.getAsJsonObject("systemMessage").get("content").getAsString()).isEqualTo("consigne : é");
        assertThat(requests("session.sendAndWait").getFirst().get("prompt").getAsString()).isEqualTo("demande 1");
    }

    @Test
    void theConversationHasNoToolAndIsDeletedAfterTheAnswer() throws IOException {
        try (CopilotLlmClient client = client()) {
            client.complete("consigne", "demande");
        }

        JsonObject conversation = requests("session.create").getFirst();
        JsonObject tools = conversation.getAsJsonObject("tools");
        assertThat(strings(tools, "availableTools")).as("outils accordés").isEmpty();
        assertThat(strings(tools, "excludedTools")).as("outils interdits").containsExactlyInAnyOrder("builtin:*", "mcp:*", "custom:*");
        assertThat(conversation.getAsJsonObject("permissions").get("request").getAsBoolean()).as("permissions demandées à l'application")
                .isTrue();
        assertThat(conversation.getAsJsonObject("mcp").has("servers")).as("serveurs MCP").isFalse();
        assertThat(conversation.getAsJsonObject("skills").get("enabled").getAsBoolean()).as("compétences de l'agent").isFalse();
        assertThat(conversation.getAsJsonObject("state").getAsJsonObject("memory").get("enabled").getAsBoolean())
                .as("mémoire d'une conversation à l'autre").isFalse();
        assertThat(requests("session.options.update").getFirst().getAsJsonObject("instructions").get("skipCustomInstructions")
                .getAsBoolean()).as("consignes personnelles de Copilot ignorées").isTrue();
        assertThat(Path.of(conversation.get("workingDirectory").getAsString())).as("dossier courant de la conversation")
                .isEqualTo(Path.of(launch().get("cwd").getAsString()));

        String id = conversation.get("sessionId").getAsString();
        assertThat(requests("session.delete")).extracting(deleted -> deleted.get("sessionId").getAsString()).containsExactly(id);
    }

    @Test
    void theEngineOnlyKnowsTheTokenOfTheProfile() throws IOException {
        try (CopilotLlmClient client = client()) {
            client.complete("consigne", "demande");
        }

        JsonObject launch = launch();
        assertThat(strings(launch, "args")).contains("--stdio", "--no-auto-login", "--no-auto-update")
                .containsSubsequence("--auth-token-env", "COPILOT_SDK_AUTH_TOKEN").containsSubsequence("--log-level", "none");
        JsonObject environment = launch.getAsJsonObject("env");
        // rien de l'environnement de Copilot ou de GitHub de la machine : seulement ce que l'application fixe
        assertThat(environment.keySet()).containsExactlyInAnyOrder("COPILOT_SDK_AUTH_TOKEN", "COPILOT_HOME", "COPILOT_CACHE_HOME",
                "COPILOT_TELEMETRY_DISABLED", "COPILOT_DISABLE_KEYTAR");
        assertThat(environment.get("COPILOT_SDK_AUTH_TOKEN").getAsString()).isEqualTo(FakeCopilotRuntime.TOKEN);
        assertThat(environment.get("COPILOT_TELEMETRY_DISABLED").getAsString()).isEqualTo("true");
    }

    @Test
    void theEnvironmentOfTheEngineIsStrippedOfCopilotAndGitHubSettings() {
        Map<String, String> inherited = Map.of("PATH", "/usr/bin", "HTTPS_PROXY", "http://proxy:3128", "GH_TOKEN", "a",
                "GITHUB_TOKEN", "b", "COPILOT_GITHUB_TOKEN", "c", "COPILOT_ALLOW_ALL", "1", "copilot_home", "/ailleurs",
                "Github_Copilot_Api_Token", "d", "GHOSTTY", "terminal");

        Map<String, String> environment = CopilotEngine.environment(inherited, Path.of("moteur"));

        assertThat(environment).containsEntry("PATH", "/usr/bin").containsEntry("HTTPS_PROXY", "http://proxy:3128")
                .containsEntry("GHOSTTY", "terminal").containsEntry("COPILOT_TELEMETRY_DISABLED", "true")
                .containsEntry("COPILOT_CACHE_HOME", Path.of("moteur", "cache").toString());
        assertThat(environment.keySet()).doesNotContain("GH_TOKEN", "GITHUB_TOKEN", "COPILOT_GITHUB_TOKEN", "COPILOT_ALLOW_ALL",
                "copilot_home", "Github_Copilot_Api_Token");
    }

    @Test
    void theModelsAllowedByTheSubscriptionAreListedInOrder() {
        try (CopilotLlmClient client = client()) {
            assertThat(client.models()).containsExactly("Claude-test", "gpt-test");
        }
    }

    // ------------------------------------------------------------------ ce qu'il reste du moteur

    @Test
    void closingStopsTheEngineAndErasesItsFiles() throws IOException {
        Path home;
        try (CopilotLlmClient client = client()) {
            client.complete("consigne", "une opération bancaire");
            JsonObject launch = launch();
            home = Path.of(launch.getAsJsonObject("env").get("COPILOT_HOME").getAsString());
            assertThat(ProcessHandle.of(launch.get("pid").getAsLong())).as("moteur en marche").isPresent();
            try (Stream<Path> files = Files.walk(home)) {
                assertThat(files.filter(file -> file.endsWith("events.jsonl"))).as("fil des échanges, tant que le moteur tourne").hasSize(1);
            }
        }

        assertThat(home.getParent()).as("dossier du moteur").doesNotExist();
        assertThat(home.getParent().getFileName().toString()).startsWith(CopilotScratch.PREFIX);
        assertThat(ProcessHandle.of(launch().get("pid").getAsLong()).filter(ProcessHandle::isAlive)).as("moteur arrêté").isEmpty();
    }

    @Test
    void aClientThatNeverAskedAnythingStartsNoEngine() {
        client().close();

        assertThat(journal()).doesNotExist();
    }

    @Test
    void anAbandonedFolderIsRemovedByTheNextOne() throws IOException {
        Path abandoned = Files.createDirectory(directory.resolve(CopilotScratch.PREFIX + "abandonne"));
        Files.writeString(Files.createDirectory(abandoned.resolve("moteur")).resolve("events.jsonl"), "opérations en clair");
        Files.createFile(abandoned.resolve("verrou"));
        Path unfinished = Files.createDirectory(directory.resolve(CopilotScratch.PREFIX + "en-creation"));
        Path foreign = Files.createDirectory(directory.resolve(CopilotScratch.PREFIX + "ailleurs"));

        // le verrou d'une autre fenêtre de l'application ne peut se simuler ici : celui-ci est tenu par ce test
        try (FileChannel held = FileChannel.open(foreign.resolve("verrou"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            assertThat(held.tryLock()).isNotNull();
            try (CopilotScratch first = CopilotScratch.create(directory); CopilotScratch second = CopilotScratch.create(directory)) {
                assertThat(abandoned).as("dossier abandonné").doesNotExist();
                assertThat(unfinished).as("dossier sans verrou : en cours de création").exists();
                assertThat(foreign).as("dossier dont le verrou est tenu").exists();
                assertThat(first.home()).as("dossier en service, à la création du suivant").isDirectory();
                assertThat(second.work()).isEmptyDirectory();
                assertThat(first.home().getParent()).isNotEqualTo(second.home().getParent());
            }
        }
        try (Stream<Path> left = Files.list(directory)) {
            assertThat(left).as("les dossiers fermés disparaissent").containsExactlyInAnyOrder(unfinished, foreign);
        }
    }

    // ------------------------------------------------------------------ défaillances

    @Test
    void aRefusedTokenIsExplainedWithoutBeingRepeated() throws IOException {
        String token = "github_pat_perime";
        try (CopilotLlmClient client = client(token, Duration.ofSeconds(30))) {
            assertThatThrownBy(() -> client.complete("consigne", "demande")).isInstanceOf(LlmException.class)
                    .hasMessageContaining("a refusé le jeton").hasMessageContaining("Copilot Requests")
                    .hasMessageContaining("Bad credentials").hasMessageNotContaining(token);
            assertThatThrownBy(client::models).isInstanceOf(LlmException.class).hasMessageContaining("a refusé le jeton");
        }
        assertThat(requests("session.sendAndWait")).as("rien n'est envoyé au modèle").isEmpty();
    }

    @Test
    void aClassicTokenOrIncompleteSettingsAreRejectedBeforeStartingAnything() {
        assertThatThrownBy(() -> new CopilotLlmClient(settings("ghp_classique"))).isInstanceOf(LlmException.class)
                .hasMessageContaining("github_pat_");
        assertThatThrownBy(() -> new CopilotLlmClient(settings(""))).isInstanceOf(LlmException.class).hasMessageContaining("jeton");
        assertThatThrownBy(() -> new CopilotLlmClient(new LlmSettings(LlmProvider.COPILOT, "", "github_pat_x", "", true)))
                .isInstanceOf(LlmException.class);
        assertThat(journal()).doesNotExist();
    }

    @Test
    void anExhaustedQuotaAndAnEmptyAnswerAreReported() {
        try (CopilotLlmClient client = client()) {
            assertThatThrownBy(() -> client.complete("consigne", FakeCopilotRuntime.QUOTA)).isInstanceOf(LlmException.class)
                    .hasMessageContaining("quota").hasMessageContaining("exceeded your quota");
            assertThatThrownBy(() -> client.complete("consigne", FakeCopilotRuntime.EMPTY)).isInstanceOf(LlmException.class)
                    .hasMessageContaining("réponse vide");
            assertThat(client.complete("consigne", "demande")).as("le client sert encore après un refus").endsWith("demande");
        }
    }

    @Test
    void aModelThatDoesNotAnswerInTimeIsGivenUp() throws IOException {
        try (CopilotLlmClient client = client(FakeCopilotRuntime.TOKEN, Duration.ofMillis(600))) {
            assertThatThrownBy(() -> client.complete("consigne", FakeCopilotRuntime.SILENT)).isInstanceOf(LlmException.class)
                    .hasMessageContaining("pas répondu à temps");
        }
        assertThat(ProcessHandle.of(launch().get("pid").getAsLong()).filter(ProcessHandle::isAlive)).as("moteur arrêté").isEmpty();
    }

    @Test
    void anEngineThatCannotStartIsReported() {
        LlmSettings settings = settings(FakeCopilotRuntime.TOKEN);
        try (CopilotLlmClient missing = new CopilotLlmClient(settings, List.of(directory.resolve("introuvable").toString()),
                Duration.ofSeconds(5))) {
            assertThatThrownBy(() -> missing.complete("consigne", "demande")).isInstanceOf(LlmException.class)
                    .hasMessageContaining("n'a pas démarré");
        }
        // les tests n'embarquent pas le moteur de Copilot : c'est le cas d'une application lancée sans sa distribution
        try (CopilotLlmClient absent = new CopilotLlmClient(settings)) {
            assertThatThrownBy(() -> absent.complete("consigne", "demande")).isInstanceOf(LlmException.class)
                    .hasMessageContaining("absent de cette installation");
        }
    }

    // ------------------------------------------------------------------ réglages

    @Test
    void copilotNeedsATokenAndAModelButNoAddress() {
        assertThat(settings("github_pat_x").isConfigured()).isTrue();
        assertThat(settings("").isConfigured()).as("jeton manquant").isFalse();
        assertThat(LlmProvider.COPILOT.urlRequired()).isFalse();
        assertThat(LlmProvider.OPENAI.urlRequired()).isTrue();
        try (LlmClient copilot = LlmClient.of(settings("github_pat_x"));
                LlmClient other = LlmClient.of(new LlmSettings(LlmProvider.OLLAMA, LlmProvider.OLLAMA.defaultUrl(), "", "m", false))) {
            assertThat(copilot).isInstanceOf(CopilotLlmClient.class);
            assertThat(other).isInstanceOf(HttpLlmClient.class);
        }
    }
}
