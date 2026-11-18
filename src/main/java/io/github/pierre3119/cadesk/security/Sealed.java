package io.github.pierre3119.cadesk.security;

import io.github.pierre3119.cadesk.security.Crypto.BadKeyException;

import java.nio.charset.StandardCharsets;

/**
 * Un secret chiffré par un mot de passe (AES-256-GCM, clé tirée du mot de passe par PBKDF2) : c'est
 * sous cette forme qu'est conservée la clé privée d'un administrateur. Sel et données sont en Base64.
 */
public record Sealed(String salt, int iterations, String data) {

    /** Fait partie du format : porte l'ancien nom de l'application, et ne peut pas changer sans rendre illisibles les secrets existants. */
    private static final byte[] CONTEXT = "comptes-ca:secret".getBytes(StandardCharsets.US_ASCII);

    public static Sealed seal(char[] password, byte[] secret) {
        byte[] salt = Crypto.random(Crypto.SALT_BYTES);
        int iterations = Crypto.iterations();
        return new Sealed(Crypto.base64(salt), iterations,
                Crypto.base64(Crypto.encrypt(Crypto.derive(password, salt, iterations), secret, CONTEXT)));
    }

    /**
     * @throws WrongPasswordException si le mot de passe n'est pas celui qui a chiffré le secret
     */
    public byte[] open(char[] password) {
        try {
            return Crypto.decrypt(Crypto.derive(password, Crypto.base64(salt), iterations), Crypto.base64(data), CONTEXT);
        } catch (BadKeyException e) {
            throw new WrongPasswordException();
        }
    }
}
