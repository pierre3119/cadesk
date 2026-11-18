package io.github.pierre3119.cadesk.llm;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Faux moteur de GitHub Copilot, lancé à la place du vrai par les tests : un programme à part qui parle
 * comme lui au SDK (JSON-RPC sur l'entrée et la sortie standard) sans rien demander à GitHub.
 *
 * <p>Il note dans un journal, une ligne de JSON à la fois, la façon dont il a été lancé puis chaque demande
 * reçue, et laisse derrière lui, comme le vrai, le fil de ses échanges dans son dossier. Son premier
 * argument est le fichier du journal, le deuxième, facultatif, un scénario de connexion ; les suivants
 * sont ceux que le SDK ajoute.
 *
 * <p><b>Questions.</b> Seul {@link #TOKEN} est un jeton valable. La question décide de la réponse :
 * {@link #SILENT} n'en reçoit aucune, {@link #EMPTY} en reçoit une vide, {@link #QUOTA} est refusée pour
 * quota épuisé.
 *
 * <p><b>Connexion à GitHub.</b> Comme le vrai, il donne la page à ouvrir puis attend l'autorisation de
 * l'utilisateur — ici, un appel à {@link #authorize}. Faute du trousseau du système, il demande alors s'il
 * peut garder le jeton en clair dans son dossier, et ne montre ce jeton qu'une fois relancé sur ce dossier
 * sans l'option {@code --no-auto-login}.
 *
 * <p>Ce programme tourne sans les classes de l'application : il ne doit en nommer aucune.
 */
public final class FakeCopilotRuntime {

    static final String TOKEN = "github_pat_jeton_de_test";
    static final String SILENT = "MUET";
    static final String EMPTY = "VIDE";
    static final String QUOTA = "QUOTA";

    /** Compte que connecte une autorisation, et jeton que GitHub lui délivre. */
    public static final String LOGIN = "octocat";
    public static final String DELIVERED = "gho_JetonDelivreParGitHub0123456789abcdefgh";
    /** Code à saisir quand la connexion se fait par code. */
    public static final String CODE = "ABCD-1234";

    /** Scénario : l'utilisateur refuse la connexion sur la page de GitHub. */
    static final String DENIED = "refus";
    /** Scénario : le moteur garde le jeton sans rien demander. */
    static final String KEPT = "garde";
    /** Scénario : la page proposée n'est pas une page de GitHub. */
    static final String ELSEWHERE = "ailleurs";
    /** Scénario : la connexion aboutit, mais aucun jeton n'est remis. */
    static final String NO_TOKEN = "sans-jeton";

    private static final String HOST = "https://github.com";
    private static final Object OUTPUT = new Object();

    private FakeCopilotRuntime() {
    }

    // ------------------------------------------------------------------ côté tests

    /** La commande qui lance le faux moteur : la JVM des tests, sur les classes de test et Gson. */
    public static List<String> command(Path journal, String... scenario) {
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
                location(FakeCopilotRuntime.class) + File.pathSeparator + location(Gson.class), FakeCopilotRuntime.class.getName(),
                journal.toString()));
        command.addAll(List.of(scenario));
        return command;
    }

    /** Ce que fait l'utilisateur dans son navigateur : il autorise la connexion en cours. */
    public static void authorize(Path journal) {
        try {
            Files.writeString(authorization(journal), "");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Ce que le faux moteur a noté : chaque lancement, puis chaque demande. */
    public static List<JsonObject> noted(Path journal) {
        try {
            return Files.readAllLines(journal).stream().map(line -> JsonParser.parseString(line).getAsJsonObject()).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path authorization(Path journal) {
        return journal.resolveSibling(journal.getFileName() + ".autorise");
    }

    /** Le dossier ou l'archive d'où vient une classe. */
    private static String location(Class<?> type) {
        String resource = type.getName().replace('.', '/') + ".class";
        try {
            String url = type.getClassLoader().getResource(resource).toString();
            if (url.startsWith("jar:")) {
                return Path.of(new URI(url.substring("jar:".length(), url.indexOf("!/")))).toString();
            }
            return Path.of(new URI(url.substring(0, url.length() - resource.length()))).toString();
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ le faux moteur

    public static void main(String[] args) throws IOException {
        Path journal = Path.of(args[0]);
        String scenario = args.length > 1 && !args[1].startsWith("--") ? args[1] : "";
        Path credentials = Path.of(System.getenv("COPILOT_HOME"), "config.json");
        String given = System.getenv("COPILOT_SDK_AUTH_TOKEN");
        // sans jeton confié, il reprend la connexion gardée dans son dossier, sauf si on le lui interdit
        boolean resumed = given == null && !Arrays.asList(args).contains("--no-auto-login") && Files.exists(credentials);
        boolean authenticated = TOKEN.equals(given) || resumed;

        JsonObject launch = new JsonObject();
        JsonArray arguments = new JsonArray();
        for (String argument : args) {
            arguments.add(argument);
        }
        // de l'environnement, seulement ce qui regarde Copilot et GitHub : le reste ne concerne pas les tests
        JsonObject environment = new JsonObject();
        System.getenv().forEach((name, value) -> {
            String upper = name.toUpperCase(Locale.ROOT);
            if (upper.startsWith("COPILOT_") || upper.startsWith("GH_") || upper.startsWith("GITHUB_")) {
                environment.addProperty(upper, value);
            }
        });
        launch.add("args", arguments);
        launch.add("env", environment);
        launch.addProperty("cwd", Path.of("").toAbsolutePath().toString());
        launch.addProperty("pid", ProcessHandle.current().pid());
        note(journal, launch);

        Map<String, String> instructions = new HashMap<>();
        AtomicBoolean abandoned = new AtomicBoolean();
        AtomicInteger stage = new AtomicInteger();
        InputStream in = new BufferedInputStream(System.in);
        JsonObject request;
        while ((request = read(in)) != null) {
            String method = request.get("method").getAsString();
            JsonObject params = request.has("params") && request.get("params").isJsonObject() ? request.getAsJsonObject("params")
                    : new JsonObject();
            JsonObject line = new JsonObject();
            line.addProperty("method", method);
            line.add("params", params);
            note(journal, line);
            if (!request.has("id")) {
                continue;
            }
            JsonElement id = request.get("id");
            String session = params.has("sessionId") ? params.get("sessionId").getAsString() : null;
            switch (method) {
                case "connect" -> result(id, object("protocolVersion", 4));
                case "models.list" -> {
                    if (authenticated) {
                        JsonArray models = new JsonArray();
                        models.add(model("gpt-test", null));
                        models.add(model("Claude-test", "enabled"));
                        models.add(model("modele-interdit", "disabled"));
                        JsonObject list = new JsonObject();
                        list.add("models", models);
                        result(id, list);
                    } else {
                        error(id, "Request models.list failed with message: Not authenticated. Please authenticate first.", null);
                    }
                }
                case "session.create" -> {
                    if (given == null || authenticated) {
                        instructions.put(session, params.getAsJsonObject("systemMessage").get("content").getAsString());
                        result(id, object("sessionId", session));
                    } else {
                        error(id, "Request session.create failed with message: Authentication failed: Failed to validate SDK token "
                                + "(401): GitHub returned: Bad credentials for " + given, null);
                    }
                }
                case "session.sendAndWait" -> {
                    String prompt = params.get("prompt").getAsString();
                    Path transcript = Path.of(System.getenv("COPILOT_HOME"), "session-state", session, "events.jsonl");
                    Files.createDirectories(transcript.getParent());
                    Files.writeString(transcript, prompt);
                    if (prompt.contains(QUOTA)) {
                        JsonObject data = object("errorType", "quota");
                        data.addProperty("statusCode", 402);
                        error(id, "Request session.sendAndWait failed with message: You have exceeded your quota.", data);
                    } else if (!prompt.contains(SILENT)) {
                        JsonObject message = object("messageId", "reponse");
                        message.addProperty("content", prompt.contains(EMPTY) ? "" : instructions.get(session) + " / " + prompt);
                        JsonObject response = object("type", "assistant.message");
                        response.addProperty("id", UUID.randomUUID().toString());
                        response.addProperty("timestamp", OffsetDateTime.now().toString());
                        response.add("data", message);
                        JsonObject completion = object("messageId", "question");
                        completion.addProperty("aborted", false);
                        completion.add("response", response);
                        result(id, completion);
                    }
                }
                case "session.accounts.login.begin" -> {
                    JsonObject step;
                    if (params.has("method") && params.get("method").getAsString().equals("device-code")) {
                        step = object("kind", "device-code");
                        step.addProperty("userCode", CODE);
                        step.addProperty("verificationUri", HOST + "/login/device");
                        step.addProperty("expiresIn", 899);
                        step.addProperty("interval", 5);
                    } else {
                        step = object("kind", "open-url");
                        step.addProperty("url", (scenario.equals(ELSEWHERE) ? "https://exemple.org" : HOST)
                                + "/login/oauth/authorize?client_id=faux&redirect_uri=http%3A%2F%2F127.0.0.1%3A1%2Fcallback&state=etat");
                    }
                    JsonObject begun = object("flowId", "connexion-1");
                    begun.add("step", step);
                    result(id, begun);
                }
                case "session.accounts.login.advance" -> {
                    String input = params.has("input") ? params.get("input").getAsString() : null;
                    // le vrai moteur ne répond qu'une fois la connexion autorisée, tout en restant à l'écoute
                    Thread waiting = new Thread(() -> advance(id, scenario, input, stage, abandoned, authorization(journal), credentials));
                    waiting.setDaemon(true);
                    waiting.start();
                }
                case "session.accounts.login.cancel" -> {
                    abandoned.set(true);
                    result(id, new JsonObject());
                }
                case "account.getAllUsers" -> {
                    JsonArray users = new JsonArray();
                    if (resumed) {
                        JsonObject identity = object("type", "user");
                        identity.addProperty("host", HOST);
                        identity.addProperty("login", LOGIN);
                        JsonObject user = object("selectionId", "1");
                        user.add("authInfo", identity);
                        if (Files.readString(credentials).contains(DELIVERED)) {
                            user.addProperty("token", DELIVERED);
                        }
                        users.add(user);
                    }
                    result(id, users);
                }
                case "session.detach", "session.delete" -> result(id, object("success", true));
                default -> result(id, new JsonObject());
            }
        }
    }

    /** L'étape suivante de la connexion, une fois que l'utilisateur l'a autorisée. */
    private static void advance(JsonElement id, String scenario, String input, AtomicInteger stage, AtomicBoolean abandoned,
            Path authorization, Path credentials) {
        try {
            JsonObject step = object("kind", "completed");
            switch (stage.get()) {
                case 0 -> {
                    while (!Files.exists(authorization)) {
                        if (abandoned.get()) {
                            JsonObject cancelled = object("kind", "error");
                            cancelled.addProperty("message", "Login cancelled.");
                            result(id, cancelled);
                            return;
                        }
                        Thread.sleep(20);
                    }
                    if (scenario.equals(DENIED)) {
                        step = object("kind", "error");
                        step.addProperty("message", "access_denied: The user has denied your application access.");
                    } else if (scenario.equals(KEPT)) {
                        stage.set(2);
                        step.add("result", signedIn(scenario, credentials));
                    } else {
                        stage.set(1);
                        step.add("result", object("status", "needs-plaintext-consent"));
                    }
                }
                case 1 -> {
                    if ("allow".equals(input)) {
                        stage.set(2);
                        step.add("result", signedIn(scenario, credentials));
                    } else {
                        step.add("result", object("status", "declined"));
                    }
                }
                default -> {
                    step = object("kind", "error");
                    step.addProperty("message", "Login flow already completed.");
                }
            }
            result(id, step);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Garde la connexion dans le dossier du moteur, à la manière du vrai : un fichier JSON précédé d'un commentaire. */
    private static JsonObject signedIn(String scenario, Path credentials) throws IOException {
        JsonObject user = object("host", HOST);
        user.addProperty("login", LOGIN);
        JsonArray users = new JsonArray();
        users.add(user);
        JsonObject stored = new JsonObject();
        stored.add("logged_in_users", users);
        if (!scenario.equals(NO_TOKEN)) {
            stored.add("copilot_tokens", object(HOST + ":" + LOGIN, DELIVERED));
        }
        Files.writeString(credentials, "// Fichier du faux moteur de Copilot\n" + stored + "\n");
        JsonObject result = object("status", "completed");
        result.addProperty("host", HOST);
        result.addProperty("login", LOGIN);
        return result;
    }

    private static JsonObject object(String name, Object value) {
        JsonObject object = new JsonObject();
        if (value instanceof Number number) {
            object.addProperty(name, number);
        } else if (value instanceof Boolean flag) {
            object.addProperty(name, flag);
        } else {
            object.addProperty(name, (String) value);
        }
        return object;
    }

    private static JsonObject model(String id, String policy) {
        JsonObject model = object("id", id);
        model.addProperty("name", id);
        if (policy != null) {
            model.add("policy", object("state", policy));
        }
        return model;
    }

    private static void note(Path journal, JsonObject line) throws IOException {
        Files.writeString(journal, line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static void result(JsonElement id, JsonElement result) throws IOException {
        JsonObject response = object("jsonrpc", "2.0");
        response.add("id", id);
        response.add("result", result);
        write(response);
    }

    private static void error(JsonElement id, String message, JsonObject data) throws IOException {
        JsonObject error = object("code", -32603);
        error.addProperty("message", message);
        if (data != null) {
            error.add("data", data);
        }
        JsonObject response = object("jsonrpc", "2.0");
        response.add("id", id);
        response.add("error", error);
        write(response);
    }

    private static void write(JsonObject message) throws IOException {
        byte[] body = message.toString().getBytes(StandardCharsets.UTF_8);
        synchronized (OUTPUT) {
            OutputStream out = System.out;
            out.write(("Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(body);
            out.flush();
        }
    }

    /** Le message suivant, ou {@code null} quand le SDK a fermé l'entrée. */
    private static JsonObject read(InputStream in) throws IOException {
        int length = -1;
        while (true) {
            ByteArrayOutputStream header = new ByteArrayOutputStream();
            int b;
            while ((b = in.read()) != '\n') {
                if (b < 0) {
                    return null;
                }
                if (b != '\r') {
                    header.write(b);
                }
            }
            String line = header.toString(StandardCharsets.US_ASCII);
            if (line.isEmpty()) {
                if (length >= 0) {
                    break;
                }
            } else if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                length = Integer.parseInt(line.substring(line.indexOf(':') + 1).strip());
            }
        }
        byte[] body = in.readNBytes(length);
        if (body.length < length) {
            return null;
        }
        return JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
    }
}
