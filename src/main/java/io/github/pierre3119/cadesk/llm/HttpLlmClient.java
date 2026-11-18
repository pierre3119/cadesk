package io.github.pierre3119.cadesk.llm;

import io.github.pierre3119.cadesk.llm.LlmProvider.Protocol;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/**
 * Accès à un modèle de langage par HTTP, selon le service choisi : interface « chat completions »
 * d'OpenAI (reprise par Mistral, Gemini, Ollama et bien d'autres) ou interface « messages » d'Anthropic.
 */
public final class HttpLlmClient implements LlmClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(3);
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    /** Longueur maximale de la réponse, que l'interface d'Anthropic oblige à fixer. */
    private static final int ANTHROPIC_MAX_TOKENS = 4096;
    private static final int MAX_ERROR_LENGTH = 300;
    private static final HttpClient HTTP =
            HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(CONNECT_TIMEOUT).build();

    private final LlmSettings settings;

    /**
     * @throws LlmException si les réglages sont incomplets
     */
    public HttpLlmClient(LlmSettings settings) {
        if (!settings.isConfigured()) {
            throw new LlmException("L'assistant n'est pas configuré : choisissez un service, un modèle et, s'il en faut une, une clé d'API.");
        }
        this.settings = settings;
    }

    @Override
    public String complete(String system, String user) {
        boolean anthropic = settings.provider().protocol() == Protocol.ANTHROPIC;
        JsonObject body = new JsonObject();
        body.addProperty("model", settings.model());
        JsonArray messages = new JsonArray();
        if (anthropic) {
            body.addProperty("max_tokens", ANTHROPIC_MAX_TOKENS);
            body.addProperty("system", system);
        } else {
            messages.add(message("system", system));
        }
        messages.add(message("user", user));
        body.add("messages", messages);

        JsonObject response = send(request(anthropic ? "/messages" : "/chat/completions")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build());
        String text;
        try {
            text = anthropic ? text(response.getAsJsonArray("content"))
                    : text(response.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message").get("content"));
        } catch (RuntimeException e) {
            throw new LlmException("La réponse du service n'a pas la forme attendue.", e);
        }
        if (text.isBlank()) {
            throw new LlmException("Le modèle a renvoyé une réponse vide.");
        }
        return text;
    }

    /** Noms des modèles que le service propose, triés. */
    @Override
    public List<String> models() {
        JsonObject response = send(request("/models").GET().build());
        List<String> names = new ArrayList<>();
        try {
            for (JsonElement model : response.getAsJsonArray("data")) {
                names.add(model.getAsJsonObject().get("id").getAsString());
            }
        } catch (RuntimeException e) {
            throw new LlmException("La liste des modèles n'a pas la forme attendue.", e);
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    private static JsonObject message(String role, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", content);
        return message;
    }

    /** Le texte d'un contenu : une chaîne, ou une suite de blocs dont on garde ceux de texte. */
    private static String text(JsonElement content) {
        if (content == null || content.isJsonNull()) {
            return "";
        }
        if (content.isJsonPrimitive()) {
            return content.getAsString();
        }
        StringBuilder text = new StringBuilder();
        for (JsonElement block : content.getAsJsonArray()) {
            JsonElement part = block.isJsonObject() ? block.getAsJsonObject().get("text") : null;
            if (part != null && part.isJsonPrimitive()) {
                text.append(part.getAsString());
            }
        }
        return text.toString();
    }

    private HttpRequest.Builder request(String path) {
        String base = settings.url().replaceAll("/+$", "");
        URI uri;
        try {
            uri = URI.create(base + path);
        } catch (IllegalArgumentException e) {
            throw new LlmException("L'adresse du service n'est pas valide : " + settings.url(), e);
        }
        if (uri.getHost() == null || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
            throw new LlmException("L'adresse du service doit commencer par http:// ou https:// : " + settings.url());
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json").header("Accept", "application/json");
        try {
            if (settings.provider().protocol() == Protocol.ANTHROPIC) {
                request.header("x-api-key", settings.apiKey()).header("anthropic-version", ANTHROPIC_VERSION);
            } else if (!settings.apiKey().isEmpty()) {
                request.header("Authorization", "Bearer " + settings.apiKey());
            }
        } catch (IllegalArgumentException e) {
            throw new LlmException("La clé d'API contient des caractères qui ne peuvent pas être transmis : vérifiez-la.");
        }
        return request;
    }

    private static JsonObject send(HttpRequest request) {
        String host = request.uri().getHost();
        try {
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                throw new LlmException("Le service " + host + " a refusé la demande (code " + response.statusCode() + ")"
                        + detail(response.body()));
            }
            return JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (HttpTimeoutException e) {
            throw new LlmException("Le service " + host + " n'a pas répondu à temps.", e);
        } catch (IOException e) {
            throw new LlmException("Le service " + host + " est injoignable" + (e.getMessage() == null ? "." : " : " + e.getMessage()), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("La demande au service a été interrompue.", e);
        } catch (JsonParseException | IllegalStateException e) {
            throw new LlmException("La réponse du service " + host + " est illisible.", e);
        }
    }

    /** Le motif du refus, tel que le service le donne. */
    private static String detail(String body) {
        String message = body == null ? "" : body.strip();
        try {
            JsonElement error = JsonParser.parseString(message).getAsJsonObject().get("error");
            if (error != null && error.isJsonObject() && error.getAsJsonObject().has("message")) {
                message = error.getAsJsonObject().get("message").getAsString();
            } else if (error != null && error.isJsonPrimitive()) {
                message = error.getAsString();
            }
        } catch (RuntimeException e) {
            // le corps n'est pas du JSON : il est repris tel quel
        }
        if (message.isEmpty()) {
            return ".";
        }
        return " : " + (message.length() > MAX_ERROR_LENGTH ? message.substring(0, MAX_ERROR_LENGTH) + "…" : message);
    }
}
