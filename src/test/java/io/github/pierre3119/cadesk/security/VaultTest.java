package io.github.pierre3119.cadesk.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Coffre chiffré, empreinte de mot de passe et secret scellé. */
class VaultTest {

    private static final byte[] CONTENT = "relevé de compte : BOULANGERIE DUPAIN 4,50".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path dir;

    private static char[] pw(String password) {
        return password.toCharArray();
    }

    @Test
    void theContentIsUnreadableOnDiskAndComesBackWithThePassword() throws Exception {
        Path file = dir.resolve("coffre.db");
        Vault.create(file, pw("sésame"), List.of(), CONTENT);

        assertThat(Vault.isVault(file)).isTrue();
        assertThat(new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1)).doesNotContain("BOULANGERIE");
        assertThat(Vault.open(file, pw("sésame")).read()).isEqualTo(CONTENT);
    }

    @Test
    void aWrongPasswordIsRejected() {
        Path file = dir.resolve("coffre.db");
        Vault.create(file, pw("sésame"), List.of(), CONTENT);

        assertThatThrownBy(() -> Vault.open(file, pw("sesame"))).isInstanceOf(WrongPasswordException.class);
        assertThat(Vault.open(file, pw("sésame")).accepts(pw("autre"))).isFalse();
        assertThat(Vault.open(file, pw("sésame")).accepts(pw("sésame"))).isTrue();
    }

    @Test
    void theContentCanBeReplaced() {
        Path file = dir.resolve("coffre.db");
        Vault vault = Vault.create(file, pw("sésame"), List.of(), CONTENT);

        vault.write("autre contenu".getBytes(StandardCharsets.UTF_8));

        assertThat(Vault.open(file, pw("sésame")).read()).asString(StandardCharsets.UTF_8).isEqualTo("autre contenu");
        assertThat(dir).as("aucun fichier temporaire ne reste").isDirectoryNotContaining("glob:**.tmp");
    }

    @Test
    void theKeyOfOneVaultDoesNotOpenAnother() throws Exception {
        Path first = dir.resolve("a.db");
        Path second = dir.resolve("b.db");
        Vault vault = Vault.create(first, pw("même mot de passe"), List.of(), CONTENT);
        Vault.create(second, pw("même mot de passe"), List.of(), CONTENT);

        // le fichier du second coffre mis à la place du premier : la clé du premier ne le déchiffre pas
        Files.write(first, Files.readAllBytes(second));

        assertThatThrownBy(vault::read).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void changingThePasswordKeepsTheContent() {
        Path file = dir.resolve("coffre.db");
        Vault vault = Vault.create(file, pw("ancien"), List.of(), CONTENT);

        vault.changePassword(pw("nouveau"));

        assertThatThrownBy(() -> Vault.open(file, pw("ancien"))).isInstanceOf(WrongPasswordException.class);
        assertThat(Vault.open(file, pw("nouveau")).read()).isEqualTo(CONTENT);
        assertThat(vault.read()).as("le coffre déjà ouvert reste utilisable").isEqualTo(CONTENT);
    }

    @Test
    void anAdministratorOpensTheVaultWithItsRescueKeyOnlyWhileTrusted() {
        Path file = dir.resolve("coffre.db");
        KeyPair admin = Crypto.newKeyPair();
        KeyPair stranger = Crypto.newKeyPair();
        Vault vault = Vault.create(file, pw("sésame"), List.of(admin.getPublic()), CONTENT);

        assertThat(vault.trusts(admin.getPublic())).isTrue();
        assertThat(Vault.open(file, admin)).hasValueSatisfying(v -> assertThat(v.read()).isEqualTo(CONTENT));
        assertThat(Vault.open(file, stranger)).isEmpty();

        // l'administrateur change le mot de passe oublié sans connaître l'ancien
        Vault.open(file, admin).orElseThrow().changePassword(pw("réinitialisé"));
        assertThat(Vault.open(file, pw("réinitialisé")).read()).isEqualTo(CONTENT);

        Vault reopened = Vault.open(file, pw("réinitialisé"));
        assertThat(reopened.setAdmins(List.of(admin.getPublic()))).as("rien à changer").isFalse();
        assertThat(reopened.setAdmins(List.of(stranger.getPublic()))).isTrue();
        assertThat(Vault.open(file, admin)).as("clé de secours retirée").isEmpty();
        assertThat(Vault.open(file, stranger)).hasValueSatisfying(v -> assertThat(v.read()).isEqualTo(CONTENT));
    }

    @Test
    void aTamperedFileIsDetected() throws Exception {
        Path file = dir.resolve("coffre.db");
        Vault vault = Vault.create(file, pw("sésame"), List.of(), CONTENT);
        byte[] bytes = Files.readAllBytes(file);

        bytes[bytes.length - 1] ^= 1;
        Files.write(file, bytes);
        assertThatThrownBy(vault::read).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("endommagée");

        // l'en-tête est authentifié lui aussi : y toucher invalide le contenu
        bytes[bytes.length - 1] ^= 1;
        int iterations = new String(bytes, StandardCharsets.ISO_8859_1).indexOf("\"iterations\":") + "\"iterations\":".length();
        bytes[iterations] = (byte) (bytes[iterations] == '9' ? '8' : '9');
        Files.write(file, bytes);
        assertThatThrownBy(vault::read).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anOrdinaryFileIsNotAVault() throws Exception {
        Path file = Files.write(dir.resolve("texte.db"), CONTENT);

        assertThat(Vault.isVault(file)).isFalse();
        assertThat(Vault.isVault(dir.resolve("absent.db"))).isFalse();
        assertThatThrownBy(() -> Vault.open(file, pw("x"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aPasswordHashVerifiesWithoutStoringThePassword() {
        PasswordHash hash = PasswordHash.of(pw("sésame"));

        assertThat(hash.matches(pw("sésame"))).isTrue();
        assertThat(hash.matches(pw("Sésame"))).isFalse();
        assertThat(hash.hash()).doesNotContain("sésame");
        assertThat(PasswordHash.of(pw("sésame")).hash()).as("sel aléatoire").isNotEqualTo(hash.hash());
    }

    @Test
    void aSealedSecretOpensOnlyWithItsPassword() {
        Sealed sealed = Sealed.seal(pw("sésame"), CONTENT);

        assertThat(sealed.open(pw("sésame"))).isEqualTo(CONTENT);
        assertThatThrownBy(() -> sealed.open(pw("autre"))).isInstanceOf(WrongPasswordException.class);
    }
}
