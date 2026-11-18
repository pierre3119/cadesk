package io.github.pierre3119.cadesk.security;

import io.github.pierre3119.cadesk.AtomicFile;
import io.github.pierre3119.cadesk.security.Crypto.BadKeyException;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.Collection;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Coffre : le fichier chiffré qui contient la base d'un profil.
 *
 * <p>Le contenu est chiffré en AES-256-GCM par une clé de données tirée au hasard, propre au coffre.
 * Cette clé n'est jamais écrite en clair : l'en-tête en conserve un exemplaire chiffré par la clé tirée
 * du mot de passe du propriétaire (PBKDF2), et un exemplaire chiffré pour chaque administrateur par sa
 * clé publique (RSA-OAEP) — la « clé de secours ». Changer de mot de passe ou d'administrateurs ne fait
 * donc que remplacer ces exemplaires.
 *
 * <p>Format du fichier : {@code CAVAULT1} (8 octets), longueur de l'en-tête (4 octets), en-tête JSON
 * (UTF-8), puis le contenu chiffré. L'en-tête est authentifié avec le contenu : il ne peut pas être
 * modifié sans la clé de données.
 */
public final class Vault {

    private static final byte[] MAGIC = "CAVAULT1".getBytes(StandardCharsets.US_ASCII);
    /** Fait partie du format : porte l'ancien nom de l'application, et ne peut pas changer sans rendre illisibles les bases chiffrées existantes. */
    private static final byte[] KEY_CONTEXT = "comptes-ca:cle".getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_HEADER = 1 << 20;

    /** Le fichier tel qu'il est sur le disque : son début en clair (authentifié) et son contenu chiffré. */
    private record Content(byte[] prefix, JsonObject header, byte[] sealed) {
    }

    private final Path file;
    private final SecretKey key;
    /** Verrou propre au coffre : celui de l'instance, public, pourrait être tenu par un appelant. Il garde tout ce qui suit. */
    private final Object lock = new Object();
    private byte[] salt;
    private int iterations;
    private byte[] passwordSlot;
    /** Clé de données chiffrée pour chaque administrateur, par empreinte de sa clé publique. */
    private final SortedMap<String, byte[]> adminSlots = new TreeMap<>();

    private Vault(Path file, SecretKey key) {
        this.file = file;
        this.key = key;
    }

    // ------------------------------------------------------------------ création et ouverture

    /** Vrai si le fichier existe et est un coffre. */
    public static boolean isVault(Path file) {
        if (!Files.isRegularFile(file)) {
            return false;
        }
        try (InputStream in = Files.newInputStream(file)) {
            return isVault(in.readNBytes(MAGIC.length));
        } catch (IOException e) {
            return false;
        }
    }

    /** Vrai si ces octets sont (le début d')un coffre. */
    public static boolean isVault(byte[] content) {
        return content.length >= MAGIC.length && Arrays.equals(content, 0, MAGIC.length, MAGIC, 0, MAGIC.length);
    }

    /** Crée le coffre (en remplaçant le fichier s'il existe) et y enferme {@code image}. */
    public static Vault create(Path file, char[] password, Collection<PublicKey> admins, byte[] image) {
        Vault vault = new Vault(file, Crypto.newKey());
        vault.protect(password);
        admins.forEach(vault::addAdmin);
        vault.write(image);
        return vault;
    }

    /**
     * Ouvre le coffre avec le mot de passe de son propriétaire.
     *
     * @throws WrongPasswordException   si le mot de passe n'est pas le bon
     * @throws IllegalArgumentException si le fichier n'est pas un coffre lisible
     */
    public static Vault open(Path file, char[] password) {
        JsonObject header = load(file).header();
        byte[] salt = bytes(header, "salt", file);
        int iterations = header.get("iterations").getAsInt();
        try {
            SecretKey key = new SecretKeySpec(
                    Crypto.decrypt(Crypto.derive(password, salt, iterations), bytes(header, "key", file), KEY_CONTEXT), "AES");
            return new Vault(file, key).withHeader(header);
        } catch (BadKeyException e) {
            throw new WrongPasswordException();
        }
    }

    /** Ouvre le coffre avec la clé de secours d'un administrateur, si le coffre lui en a confié une. */
    public static Optional<Vault> open(Path file, KeyPair admin) {
        JsonObject header = load(file).header();
        JsonElement slot = header.getAsJsonObject("admins").get(Crypto.fingerprint(admin.getPublic()));
        if (slot == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(new Vault(file, Crypto.unwrap(admin.getPrivate(), Crypto.base64(slot.getAsString()))).withHeader(header));
        } catch (BadKeyException e) {
            return Optional.empty();
        }
    }

    private Vault withHeader(JsonObject header) {
        synchronized (lock) {
            salt = bytes(header, "salt", file);
            iterations = header.get("iterations").getAsInt();
            passwordSlot = bytes(header, "key", file);
            header.getAsJsonObject("admins").entrySet()
                    .forEach(e -> adminSlots.put(e.getKey(), Crypto.base64(e.getValue().getAsString())));
        }
        return this;
    }

    public Path file() {
        return file;
    }

    // ------------------------------------------------------------------ contenu

    /**
     * Déchiffre le contenu du coffre.
     *
     * @throws IllegalArgumentException si le fichier a été altéré
     */
    public byte[] read() {
        synchronized (lock) {
            Content content = load(file);
            try {
                return Crypto.decrypt(key, content.sealed(), content.prefix());
            } catch (BadKeyException e) {
                throw damaged(file);
            }
        }
    }

    /** Remplace le contenu du coffre ; l'ancien fichier reste intact si l'écriture échoue. */
    public void write(byte[] image) {
        synchronized (lock) {
            JsonObject admins = new JsonObject();
            adminSlots.forEach((fingerprint, slot) -> admins.addProperty(fingerprint, Crypto.base64(slot)));
            JsonObject header = new JsonObject();
            header.addProperty("kdf", "PBKDF2WithHmacSHA256");
            header.addProperty("iterations", iterations);
            header.addProperty("salt", Crypto.base64(salt));
            header.addProperty("key", Crypto.base64(passwordSlot));
            header.add("admins", admins);
            byte[] json = header.toString().getBytes(StandardCharsets.UTF_8);
            byte[] prefix = ByteBuffer.allocate(MAGIC.length + 4 + json.length).put(MAGIC).putInt(json.length).put(json).array();
            byte[] sealed = Crypto.encrypt(key, image, prefix);
            AtomicFile.write(file, ByteBuffer.allocate(prefix.length + sealed.length).put(prefix).put(sealed).array());
        }
    }

    // ------------------------------------------------------------------ accès

    /** Vrai si ce mot de passe est celui du propriétaire. */
    public boolean accepts(char[] password) {
        synchronized (lock) {
            try {
                Crypto.decrypt(Crypto.derive(password, salt, iterations), passwordSlot, KEY_CONTEXT);
                return true;
            } catch (BadKeyException e) {
                return false;
            }
        }
    }

    /** Remplace le mot de passe du propriétaire ; le contenu n'est pas rechiffré, sa clé ne change pas. */
    public void changePassword(char[] password) {
        synchronized (lock) {
            byte[] image = read();
            protect(password);
            write(image);
        }
    }

    /**
     * Confie une clé de secours à ces administrateurs, et à eux seuls.
     *
     * @return vrai si le coffre a été modifié
     */
    public boolean setAdmins(Collection<PublicKey> admins) {
        SortedMap<String, PublicKey> wanted = new TreeMap<>();
        admins.forEach(k -> wanted.put(Crypto.fingerprint(k), k));
        synchronized (lock) {
            if (wanted.keySet().equals(adminSlots.keySet())) {
                return false;
            }
            byte[] image = read();
            adminSlots.keySet().retainAll(wanted.keySet());
            wanted.forEach((fingerprint, publicKey) -> {
                if (!adminSlots.containsKey(fingerprint)) {
                    addAdmin(publicKey);
                }
            });
            write(image);
            return true;
        }
    }

    /** Vrai si cet administrateur détient une clé de secours du coffre. */
    public boolean trusts(PublicKey admin) {
        synchronized (lock) {
            return adminSlots.containsKey(Crypto.fingerprint(admin));
        }
    }

    private void protect(char[] password) {
        synchronized (lock) {
            salt = Crypto.random(Crypto.SALT_BYTES);
            iterations = Crypto.iterations();
            passwordSlot = Crypto.encrypt(Crypto.derive(password, salt, iterations), key.getEncoded(), KEY_CONTEXT);
        }
    }

    private void addAdmin(PublicKey admin) {
        synchronized (lock) {
            adminSlots.put(Crypto.fingerprint(admin), Crypto.wrap(admin, key));
        }
    }

    // ------------------------------------------------------------------ lecture du fichier

    private static Content load(Path file) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Lecture impossible : " + file, e);
        }
        int start = MAGIC.length + 4;
        if (bytes.length < start || !isVault(bytes)) {
            throw new IllegalArgumentException("Ce fichier n'est pas une base chiffrée de CaDesk : " + file);
        }
        int length = ByteBuffer.wrap(bytes, MAGIC.length, 4).getInt();
        if (length <= 0 || length > MAX_HEADER || start + length > bytes.length) {
            throw damaged(file);
        }
        try {
            JsonObject header = JsonParser.parseString(new String(bytes, start, length, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!header.has("salt") || !header.has("iterations") || !header.has("key") || !header.has("admins")) {
                throw damaged(file);
            }
            return new Content(Arrays.copyOf(bytes, start + length), header, Arrays.copyOfRange(bytes, start + length, bytes.length));
        } catch (com.google.gson.JsonParseException | IllegalStateException e) {
            throw damaged(file);
        }
    }

    private static byte[] bytes(JsonObject header, String name, Path file) {
        try {
            return Crypto.base64(header.get(name).getAsString());
        } catch (RuntimeException e) {
            throw damaged(file);
        }
    }

    private static IllegalArgumentException damaged(Path file) {
        return new IllegalArgumentException("La base chiffrée est endommagée : " + file);
    }
}
