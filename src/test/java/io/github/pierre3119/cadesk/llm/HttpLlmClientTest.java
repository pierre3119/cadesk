package io.github.pierre3119.cadesk.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Échanges HTTP avec un service de modèle de langage, joués contre un faux serveur local. */
class HttpLlmClientTest {

    /** Serveur HTTP minimal : répond la même chose à chaque demande et garde la trace de ce qu'il a reçu. */
    private static final class FakeServer implements AutoCloseable {

        private final ServerSocket socket;
        private final List<String> requests = new ArrayList<>();
        private final List<String> bodies = new ArrayList<>();
        private volatile int status = 200;
        private volatile String response = "{}";

        FakeServer() throws IOException {
            socket = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
            Thread thread = new Thread(this::serve, "faux-llm");
            thread.setDaemon(true);
            thread.start();
        }

        String url() {
            return "http://127.0.0.1:" + socket.getLocalPort() + "/v1/";
        }

        private void serve() {
            while (!socket.isClosed()) {
                try (Socket client = socket.accept()) {
                    InputStream in = client.getInputStream();
                    ByteArrayOutputStream head = new ByteArrayOutputStream();
                    while (!head.toString(StandardCharsets.ISO_8859_1).endsWith("\r\n\r\n")) {
                        int b = in.read();
                        if (b < 0) {
                            break;
                        }
                        head.write(b);
                    }
                    String headers = head.toString(StandardCharsets.ISO_8859_1);
                    int length = 0;
                    for (String line : headers.split("\r\n")) {
                        if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                            length = Integer.parseInt(line.substring(line.indexOf(':') + 1).strip());
                        }
                    }
                    synchronized (this) {
                        requests.add(headers);
                        bodies.add(new String(in.readNBytes(length), StandardCharsets.UTF_8));
                    }
                    byte[] body = response.getBytes(StandardCharsets.UTF_8);
                    OutputStream out = client.getOutputStream();
                    out.write(("HTTP/1.1 " + status + " X\r\nContent-Type: application/json\r\nContent-Length: " + body.length
                            + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                    out.write(body);
                    out.flush();
                } catch (IOException e) {
                    // le serveur s'arrête, ou le client a raccroché
                }
            }
        }

        synchronized String lastRequest() {
            return requests.getLast();
        }

        synchronized JsonObject lastBody() {
            return JsonParser.parseString(bodies.getLast()).getAsJsonObject();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    private FakeServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = new FakeServer();
    }

    @AfterEach
    void stopServer() throws IOException {
        server.close();
    }

    private HttpLlmClient client(LlmProvider provider, String key) {
        return new HttpLlmClient(new LlmSettings(provider, server.url(), key, "modele-test", true));
    }

    @Test
    void anOpenAiCompatibleServiceReceivesSystemAndUserMessages() {
        server.response = """
                {"choices":[{"message":{"role":"assistant","content":"réponse du modèle"}}]}""";

        String answer = client(LlmProvider.CUSTOM, "cle-secrete").complete("consigne", "demande : é");

        assertThat(answer).isEqualTo("réponse du modèle");
        assertThat(server.lastRequest()).startsWith("POST /v1/chat/completions HTTP/1.1").containsIgnoringCase("authorization: Bearer cle-secrete");
        JsonObject body = server.lastBody();
        assertThat(body.get("model").getAsString()).isEqualTo("modele-test");
        assertThat(body.getAsJsonArray("messages")).hasSize(2);
        assertThat(body.getAsJsonArray("messages").get(0).getAsJsonObject().get("role").getAsString()).isEqualTo("system");
        assertThat(body.getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString()).isEqualTo("demande : é");
    }

    @Test
    void aLocalServiceNeedsNoKey() {
        server.response = """
                {"choices":[{"message":{"content":"ok"}}]}""";

        assertThat(client(LlmProvider.OLLAMA, "").complete("s", "u")).isEqualTo("ok");
        assertThat(server.lastRequest()).doesNotContainIgnoringCase("authorization:");
    }

    @Test
    void anthropicReceivesItsOwnHeadersAndASeparateSystemPrompt() {
        server.response = """
                {"content":[{"type":"text","text":"première partie, "},{"type":"text","text":"seconde partie"}]}""";

        String answer = client(LlmProvider.ANTHROPIC, "cle-anthropic").complete("consigne", "demande");

        assertThat(answer).isEqualTo("première partie, seconde partie");
        assertThat(server.lastRequest()).startsWith("POST /v1/messages HTTP/1.1").containsIgnoringCase("x-api-key: cle-anthropic")
                .containsIgnoringCase("anthropic-version:").doesNotContainIgnoringCase("authorization:");
        JsonObject body = server.lastBody();
        assertThat(body.get("system").getAsString()).isEqualTo("consigne");
        assertThat(body.get("max_tokens").getAsInt()).isPositive();
        assertThat(body.getAsJsonArray("messages")).singleElement()
                .satisfies(m -> assertThat(m.getAsJsonObject().get("role").getAsString()).isEqualTo("user"));
    }

    @Test
    void theModelsOfTheServiceAreListed() {
        server.response = """
                {"data":[{"id":"zeta"},{"id":"Alpha"},{"id":"beta"}]}""";

        assertThat(client(LlmProvider.OPENAI, "k").models()).containsExactly("Alpha", "beta", "zeta");
        assertThat(server.lastRequest()).startsWith("GET /v1/models HTTP/1.1");
    }

    @Test
    void aRefusalIsReportedWithTheReasonGivenByTheService() {
        server.status = 401;
        server.response = """
                {"error":{"message":"Clé d'API invalide","type":"invalid_request_error"}}""";

        assertThatThrownBy(() -> client(LlmProvider.OPENAI, "mauvaise-cle").complete("s", "u")).isInstanceOf(LlmException.class)
                .hasMessageContaining("401").hasMessageContaining("Clé d'API invalide")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("mauvaise-cle"));
        assertThatThrownBy(() -> client(LlmProvider.OPENAI, "clé\nsur deux lignes").complete("s", "u")).isInstanceOf(LlmException.class)
                .hasMessageContaining("clé d'API");
    }

    @Test
    void anUnexpectedAnswerOrAnUnreachableServiceIsAnLlmException() throws IOException {
        server.response = """
                {"choices":[]}""";
        assertThatThrownBy(() -> client(LlmProvider.OPENAI, "k").complete("s", "u")).isInstanceOf(LlmException.class);
        server.response = "<html>pas du JSON</html>";
        assertThatThrownBy(() -> client(LlmProvider.OPENAI, "k").complete("s", "u")).isInstanceOf(LlmException.class);

        HttpLlmClient client = client(LlmProvider.OPENAI, "k");
        server.close();
        assertThatThrownBy(() -> client.complete("s", "u")).isInstanceOf(LlmException.class).hasMessageContaining("injoignable");
    }

    @Test
    void incompleteSettingsAreRefused() {
        assertThat(LlmSettings.NONE.isConfigured()).isFalse();
        assertThat(new LlmSettings(LlmProvider.OPENAI, "https://api.openai.com/v1", "", "m", true).isConfigured()).as("clé manquante").isFalse();
        assertThat(new LlmSettings(LlmProvider.OLLAMA, "http://localhost:11434/v1", "", "m", true).classifiesImports()).isTrue();
        assertThat(new LlmSettings(LlmProvider.OLLAMA, "http://localhost:11434/v1", "", "m", false).classifiesImports()).isFalse();
        assertThatThrownBy(() -> new HttpLlmClient(LlmSettings.NONE)).isInstanceOf(LlmException.class);
        assertThatThrownBy(() -> new HttpLlmClient(new LlmSettings(LlmProvider.CUSTOM, "ftp://exemple", "", "m", true)).complete("s", "u"))
                .isInstanceOf(LlmException.class).hasMessageContaining("http");
    }

    @Test
    void settingsSurviveBeingSavedAsPreferences() {
        LlmSettings settings = new LlmSettings(LlmProvider.MISTRAL, " https://api.mistral.ai/v1 ", "clé", "mistral-small", true);

        assertThat(LlmSettings.of(settings.toSettings()::get)).isEqualTo(settings);
        assertThat(settings.url()).isEqualTo("https://api.mistral.ai/v1");
        assertThat(LlmSettings.of(_ -> null)).isEqualTo(LlmSettings.NONE);
    }
}
