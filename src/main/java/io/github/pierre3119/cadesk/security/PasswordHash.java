package io.github.pierre3119.cadesk.security;

import java.security.MessageDigest;

/**
 * Empreinte d'un mot de passe (PBKDF2-HMAC-SHA256, sel aléatoire) : elle permet de le vérifier sans le
 * conserver. Sel et empreinte sont en Base64.
 */
public record PasswordHash(String salt, int iterations, String hash) {

    public static PasswordHash of(char[] password) {
        byte[] salt = Crypto.random(Crypto.SALT_BYTES);
        int iterations = Crypto.iterations();
        return new PasswordHash(Crypto.base64(salt), iterations,
                Crypto.base64(Crypto.derive(password, salt, iterations).getEncoded()));
    }

    public boolean matches(char[] password) {
        byte[] candidate = Crypto.derive(password, Crypto.base64(salt), iterations).getEncoded();
        return MessageDigest.isEqual(candidate, Crypto.base64(hash));
    }
}
