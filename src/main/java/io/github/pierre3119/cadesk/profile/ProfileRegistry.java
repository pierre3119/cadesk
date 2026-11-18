package io.github.pierre3119.cadesk.profile;

import io.github.pierre3119.cadesk.AtomicFile;
import io.github.pierre3119.cadesk.profile.Profile.AdminKey;
import io.github.pierre3119.cadesk.security.PasswordHash;
import io.github.pierre3119.cadesk.security.Sealed;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Lecture et écriture du registre des profils ({@code profils.json}) : la liste des profils, avec pour
 * chacun son rôle, le fichier de sa base et de quoi vérifier son mot de passe. Aucune donnée bancaire
 * ni aucun secret en clair n'y figure.
 */
final class ProfileRegistry {

    private static final int FORMAT = 1;

    private ProfileRegistry() {
    }

    /**
     * @throws ProfileException si le fichier n'est pas un registre lisible
     */
    static List<Profile> read(Path file) {
        try {
            return profiles(JsonParser.parseString(Files.readString(file)).getAsJsonObject());
        } catch (IOException | RuntimeException e) {
            throw new ProfileException("Le registre des profils est illisible : " + file + " (" + e.getMessage() + ")", e);
        }
    }

    static void write(Path file, List<Profile> profiles) {
        JsonObject root = new JsonObject();
        root.addProperty("format", FORMAT);
        root.add("profiles", json(profiles));
        AtomicFile.write(file, new GsonBuilder().setPrettyPrinting().create().toJson(root).getBytes(StandardCharsets.UTF_8));
    }

    static List<Profile> profiles(JsonObject root) {
        if (root.get("format").getAsInt() > FORMAT) {
            throw new IllegalArgumentException("format créé par une version plus récente de l'application");
        }
        List<Profile> profiles = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray("profiles")) {
            profiles.add(profile(element.getAsJsonObject()));
        }
        return profiles;
    }

    static JsonArray json(List<Profile> profiles) {
        JsonArray array = new JsonArray();
        profiles.forEach(p -> array.add(json(p)));
        return array;
    }

    static JsonObject json(Profile p) {
        JsonObject o = new JsonObject();
        o.addProperty("id", p.id());
        o.addProperty("name", p.name());
        o.addProperty("role", p.role().name());
        o.addProperty("file", p.file());
        o.addProperty("encrypted", p.encrypted());
        o.addProperty("createdAt", p.createdAt().toString());
        if (p.password() != null) {
            JsonObject password = new JsonObject();
            password.addProperty("salt", p.password().salt());
            password.addProperty("iterations", p.password().iterations());
            password.addProperty("hash", p.password().hash());
            o.add("password", password);
        }
        if (p.adminKey() != null) {
            JsonObject privateKey = new JsonObject();
            privateKey.addProperty("salt", p.adminKey().privateKey().salt());
            privateKey.addProperty("iterations", p.adminKey().privateKey().iterations());
            privateKey.addProperty("data", p.adminKey().privateKey().data());
            JsonObject key = new JsonObject();
            key.addProperty("publicKey", p.adminKey().publicKey());
            key.add("privateKey", privateKey);
            o.add("adminKey", key);
        }
        return o;
    }

    static Profile profile(JsonObject o) {
        PasswordHash password = null;
        if (o.has("password")) {
            JsonObject p = o.getAsJsonObject("password");
            password = new PasswordHash(p.get("salt").getAsString(), p.get("iterations").getAsInt(), p.get("hash").getAsString());
        }
        AdminKey adminKey = null;
        if (o.has("adminKey")) {
            JsonObject k = o.getAsJsonObject("adminKey");
            JsonObject s = k.getAsJsonObject("privateKey");
            adminKey = new AdminKey(k.get("publicKey").getAsString(),
                    new Sealed(s.get("salt").getAsString(), s.get("iterations").getAsInt(), s.get("data").getAsString()));
        }
        Role role = adminKey == null ? Role.USER : Role.valueOf(o.get("role").getAsString());
        return new Profile(o.get("id").getAsString(), o.get("name").getAsString(), role, o.get("file").getAsString(),
                o.get("encrypted").getAsBoolean(), password, role == Role.ADMIN ? adminKey : null,
                LocalDateTime.parse(o.get("createdAt").getAsString()));
    }
}
