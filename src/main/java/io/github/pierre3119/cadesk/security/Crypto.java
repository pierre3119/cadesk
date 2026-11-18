package io.github.pierre3119.cadesk.security;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;

/**
 * Primitives cryptographiques de l'application, toutes fournies par le JDK :
 * <ul>
 *   <li>PBKDF2-HMAC-SHA256 pour tirer une clé d'un mot de passe ;</li>
 *   <li>AES-256-GCM pour chiffrer (confidentialité et intégrité) ;</li>
 *   <li>RSA-OAEP (SHA-256) pour confier une clé à un administrateur à partir de sa seule clé publique.</li>
 * </ul>
 */
public final class Crypto {

    /** Taille des clés AES, en octets. */
    public static final int KEY_BYTES = 32;

    static final int SALT_BYTES = 16;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int RSA_BITS = 3072;
    private static final int DEFAULT_ITERATIONS = 600_000;
    private static final int MIN_ITERATIONS = 1_000;
    private static final OAEPParameterSpec OAEP =
            new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);
    private static final SecureRandom RANDOM = new SecureRandom();

    private Crypto() {
    }

    /**
     * Nombre d'itérations PBKDF2 des nouvelles clés ; celui d'une clé existante est enregistré avec elle.
     * La propriété système {@code cadesk.kdf.iterations} permet de l'alléger pour les tests.
     */
    public static int iterations() {
        return Math.max(MIN_ITERATIONS, Integer.getInteger("cadesk.kdf.iterations", DEFAULT_ITERATIONS));
    }

    public static byte[] random(int bytes) {
        byte[] value = new byte[bytes];
        RANDOM.nextBytes(value);
        return value;
    }

    public static SecretKey newKey() {
        return new SecretKeySpec(random(KEY_BYTES), "AES");
    }

    /** Clé AES tirée d'un mot de passe. */
    public static SecretKey derive(char[] password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_BYTES * 8);
        try {
            return new SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(), "AES");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        } finally {
            spec.clearPassword();
        }
    }

    /**
     * Chiffre {@code plain} ; {@code context} est authentifié sans être chiffré (il devra être fourni à
     * l'identique pour déchiffrer). Le résultat commence par le vecteur d'initialisation, tiré au hasard.
     */
    public static byte[] encrypt(SecretKey key, byte[] plain, byte[] context) {
        byte[] iv = random(IV_BYTES);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(context);
            byte[] sealed = cipher.doFinal(plain);
            byte[] result = Arrays.copyOf(iv, IV_BYTES + sealed.length);
            System.arraycopy(sealed, 0, result, IV_BYTES, sealed.length);
            return result;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * @throws BadKeyException si la clé n'est pas la bonne, ou si les données ou leur contexte ont été altérés
     */
    public static byte[] decrypt(SecretKey key, byte[] sealed, byte[] context) {
        if (sealed.length < IV_BYTES + TAG_BITS / 8) {
            throw new BadKeyException();
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES));
            cipher.updateAAD(context);
            return cipher.doFinal(sealed, IV_BYTES, sealed.length - IV_BYTES);
        } catch (AEADBadTagException e) {
            throw new BadKeyException();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ clés d'administrateur

    public static KeyPair newKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(RSA_BITS, RANDOM);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Chiffre une clé AES pour le détenteur de la clé privée correspondante. */
    public static byte[] wrap(PublicKey recipient, SecretKey key) {
        try {
            Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
            cipher.init(Cipher.ENCRYPT_MODE, recipient, OAEP);
            return cipher.doFinal(key.getEncoded());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * @throws BadKeyException si cette clé privée n'est pas celle du destinataire
     */
    public static SecretKey unwrap(PrivateKey recipient, byte[] wrapped) {
        try {
            Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
            cipher.init(Cipher.DECRYPT_MODE, recipient, OAEP);
            return new SecretKeySpec(cipher.doFinal(wrapped), "AES");
        } catch (GeneralSecurityException e) {
            throw new BadKeyException();
        }
    }

    /** Empreinte SHA-256 d'une clé publique, en hexadécimal : elle l'identifie sans ambiguïté. */
    public static String fingerprint(PublicKey key) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getEncoded()));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static PublicKey publicKey(byte[] x509) {
        try {
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(x509));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Clé publique illisible.", e);
        }
    }

    public static PrivateKey privateKey(byte[] pkcs8) {
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Clé privée illisible.", e);
        }
    }

    // ------------------------------------------------------------------ texte

    public static String base64(byte[] value) {
        return Base64.getEncoder().encodeToString(value);
    }

    public static byte[] base64(String value) {
        return Base64.getDecoder().decode(value);
    }

    /** Une clé qui n'ouvre pas ce qu'elle devait ouvrir. */
    public static final class BadKeyException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        BadKeyException() {
            super("Clé incorrecte ou données altérées.");
        }
    }
}
