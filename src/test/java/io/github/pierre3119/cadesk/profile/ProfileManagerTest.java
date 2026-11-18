package io.github.pierre3119.cadesk.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pierre3119.cadesk.Samples;
import io.github.pierre3119.cadesk.db.AccountRepository;
import io.github.pierre3119.cadesk.db.Database;
import io.github.pierre3119.cadesk.db.OperationRepository;
import io.github.pierre3119.cadesk.domain.Account;
import io.github.pierre3119.cadesk.importer.ImportService;
import io.github.pierre3119.cadesk.security.Vault;
import io.github.pierre3119.cadesk.security.WrongPasswordException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.List;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Profils : bases séparées, mot de passe, chiffrement, administrateurs, sauvegarde et restauration. */
class ProfileManagerTest {

    private static final String MARKER = "BOULANGERIE DUPAIN";

    @TempDir
    Path dir;
    private Path data;
    private ProfileManager manager;

    @BeforeEach
    void createManager() {
        data = dir.resolve("data");
        manager = new ProfileManager(data, data.resolve("comptes.db"));
    }

    private static char[] pw(String password) {
        return password.toCharArray();
    }

    private void importInto(Session session) throws IOException {
        new ImportService(session.database()).importFile(Samples.write(dir, "releve.csv", Samples.statement(Samples.BAKERY, Samples.SALARY)));
    }

    private static int operationCount(Session session) {
        return session.database().inTransaction(c -> {
            int count = 0;
            for (Account account : new AccountRepository().findAll(c)) {
                count += new OperationRepository().findByAccount(c, account.id()).size();
            }
            return count;
        });
    }

    private static String onDisk(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
    }

    /** Un administrateur « Admin » (mot de passe « admin ») ; sa session est fermée. */
    private Profile admin() {
        Profile profile = manager.create("Admin", pw("admin"), false);
        try (Session session = manager.open(profile, pw("admin"))) {
            manager.grantAdmin(session, pw("admin"), null, null);
            return session.profile();
        }
    }

    // ------------------------------------------------------------------ profils et bases

    @Test
    void theBaseOfTheSingleUserApplicationBecomesTheFirstProfileWhereItIs() throws IOException {
        Path legacy = data.resolve("comptes.db");
        try (Database db = new Database(legacy)) {
            new ImportService(db).importFile(Samples.write(dir, "a.csv", Samples.statement(Samples.BAKERY)));
        }

        ProfileManager upgraded = new ProfileManager(data, legacy);

        assertThat(upgraded.profiles()).singleElement().satisfies(p -> {
            assertThat(p.hasPassword()).isFalse();
            assertThat(p.role()).isEqualTo(Role.USER);
            assertThat(upgraded.fileOf(p)).isEqualTo(legacy.toAbsolutePath().normalize());
        });
        try (Session session = upgraded.open(upgraded.profiles().getFirst(), null)) {
            assertThat(operationCount(session)).isEqualTo(1);
        }
        assertThat(new ProfileManager(data, legacy).profiles()).as("le registre est relu tel quel").isEqualTo(upgraded.profiles());
    }

    @Test
    void eachProfileHasItsOwnBase() throws IOException {
        Profile pierre = manager.create("Pierre", null, false);
        Profile camille = manager.create("  Camille ", null, false);

        try (Session session = manager.open(pierre, null)) {
            importInto(session);
            assertThat(operationCount(session)).isEqualTo(2);
        }
        try (Session session = manager.open(camille, null)) {
            assertThat(operationCount(session)).isZero();
        }
        assertThat(camille.name()).isEqualTo("Camille");
        assertThat(manager.fileOf(pierre)).isNotEqualTo(manager.fileOf(camille)).exists();
        assertThat(manager.profiles()).extracting(Profile::name).containsExactly("Pierre", "Camille");
    }

    @Test
    void profileNamesAreRequiredAndUnique() {
        manager.create("Pierre", null, false);

        assertThatThrownBy(() -> manager.create("pierre", null, false)).isInstanceOf(ProfileException.class).hasMessageContaining("déjà");
        assertThatThrownBy(() -> manager.create("  ", null, false)).isInstanceOf(ProfileException.class);
        assertThat(manager.profiles()).hasSize(1);
    }

    @Test
    void aProfileCanBeRenamed() {
        Profile pierre = manager.create("Pierre", null, false);
        manager.create("Camille", null, false);

        try (Session session = manager.open(pierre, null)) {
            manager.rename(session, "Pierre M.");
            assertThat(session.profile().name()).isEqualTo("Pierre M.");
            assertThatThrownBy(() -> manager.rename(session, "camille")).isInstanceOf(ProfileException.class);
        }
        assertThat(new ProfileManager(data, null).profiles()).extracting(Profile::name).containsExactly("Pierre M.", "Camille");
    }

    // ------------------------------------------------------------------ mot de passe

    @Test
    void aPasswordIsOptionalAndCheckedWhenSet() {
        Profile open = manager.create("Sans", null, false);
        Profile closed = manager.create("Avec", pw("sésame"), false);

        assertThat(open.hasPassword()).isFalse();
        manager.open(open, null).close();
        assertThat(closed.hasPassword()).isTrue();
        assertThatThrownBy(() -> manager.open(closed, null)).isInstanceOf(WrongPasswordException.class);
        assertThatThrownBy(() -> manager.open(closed, pw("autre"))).isInstanceOf(WrongPasswordException.class);
        manager.open(closed, pw("sésame")).close();
    }

    @Test
    void thePasswordCanBeSetChangedAndRemoved() {
        Profile profile = manager.create("Pierre", null, false);
        try (Session session = manager.open(profile, null)) {
            manager.setPassword(session, null, pw("un"));
            assertThat(session.profile().hasPassword()).isTrue();

            assertThatThrownBy(() -> manager.setPassword(session, pw("faux"), pw("deux"))).isInstanceOf(WrongPasswordException.class);
            manager.setPassword(session, pw("un"), pw("deux"));
        }
        assertThatThrownBy(() -> manager.open(profile, pw("un"))).isInstanceOf(WrongPasswordException.class);
        try (Session session = manager.open(profile, pw("deux"))) {
            manager.setPassword(session, pw("deux"), null);
        }
        manager.open(profile, null).close();
    }

    // ------------------------------------------------------------------ chiffrement

    @Test
    void encryptingABaseHidesItOnDiskAndKeepsItsData() throws IOException {
        Profile profile = manager.create("Pierre", pw("sésame"), false);
        Path file = manager.fileOf(profile);
        Session session = manager.open(profile, pw("sésame"));
        importInto(session);
        Files.writeString(file.resolveSibling(file.getFileName() + ".sauvegarde-v2"), MARKER);
        assertThat(onDisk(file)).contains(MARKER);

        assertThatThrownBy(() -> manager.setEncryption(session, true, pw("faux"))).isInstanceOf(WrongPasswordException.class);
        Session encrypted = manager.setEncryption(session, true, pw("sésame"));

        assertThat(encrypted.profile().encrypted()).isTrue();
        assertThat(Vault.isVault(file)).isTrue();
        assertThat(onDisk(file)).doesNotContain(MARKER);
        assertThat(file.resolveSibling(file.getFileName() + ".sauvegarde-v2")).as("copie en clair supprimée").doesNotExist();
        assertThat(operationCount(encrypted)).isEqualTo(2);

        // une modification est enregistrée chiffrée, et relue à la réouverture
        encrypted.database().clearOperations();
        importInto(encrypted);
        encrypted.close();
        assertThat(onDisk(file)).doesNotContain(MARKER);
        assertThatThrownBy(() -> manager.open(profile, pw("faux"))).isInstanceOf(WrongPasswordException.class);
        try (Session reopened = manager.open(profile, pw("sésame"))) {
            assertThat(operationCount(reopened)).isEqualTo(2);
        }
    }

    @Test
    void aNewProfileCanBeEncryptedFromTheStartAndDecryptedLater() throws IOException {
        assertThatThrownBy(() -> manager.create("Sans mot de passe", null, true)).isInstanceOf(ProfileException.class);
        assertThat(manager.profiles()).isEmpty();

        Profile profile = manager.create("Camille", pw("sésame"), true);
        Path file = manager.fileOf(profile);
        assertThat(Vault.isVault(file)).isTrue();
        Session session = manager.open(profile, pw("sésame"));
        importInto(session);
        assertThatThrownBy(() -> manager.setPassword(session, pw("sésame"), null)).isInstanceOf(ProfileException.class);

        Session plain = manager.setEncryption(session, false, pw("sésame"));

        assertThat(plain.profile().encrypted()).isFalse();
        assertThat(onDisk(file)).contains(MARKER);
        assertThat(operationCount(plain)).isEqualTo(2);
        plain.close();
        assertThatThrownBy(() -> manager.open(profile, null)).as("le mot de passe reste demandé").isInstanceOf(WrongPasswordException.class);
        manager.open(profile, pw("sésame")).close();
    }

    @Test
    void encryptingAProfileWithoutPasswordGivesItOne() {
        Profile profile = manager.create("Pierre", null, false);
        Session session = manager.open(profile, null);

        assertThatThrownBy(() -> manager.setEncryption(session, true, null)).isInstanceOf(ProfileException.class);
        manager.setEncryption(session, true, pw("nouveau")).close();

        assertThatThrownBy(() -> manager.open(profile, null)).isInstanceOf(WrongPasswordException.class);
        manager.open(profile, pw("nouveau")).close();
    }

    @Test
    void theEncryptionPasswordCanBeChanged() throws IOException {
        Profile profile = manager.create("Pierre", pw("ancien"), true);
        try (Session session = manager.open(profile, pw("ancien"))) {
            importInto(session);
            manager.setPassword(session, pw("ancien"), pw("nouveau"));
            importInto(session);
        }
        assertThatThrownBy(() -> manager.open(profile, pw("ancien"))).isInstanceOf(WrongPasswordException.class);
        try (Session session = manager.open(profile, pw("nouveau"))) {
            assertThat(operationCount(session)).isEqualTo(2);
        }
    }

    @Test
    void anEncryptedProfileOpensInOneWindowAtATime() {
        Profile profile = manager.create("Pierre", pw("sésame"), true);
        try (Session first = manager.open(profile, pw("sésame"))) {
            assertThatThrownBy(() -> manager.open(profile, pw("sésame"))).isInstanceOf(ProfileException.class)
                    .hasMessageContaining("déjà ouvert");
            assertThat(first.profile().name()).isEqualTo("Pierre");
        }
        manager.open(profile, pw("sésame")).close();
    }

    @Test
    void anInterruptedConversionIsNoticedAtTheNextOpening() throws IOException {
        Profile profile = manager.create("Pierre", pw("sésame"), false);
        Path file = manager.fileOf(profile);
        byte[] image;
        try (Session session = manager.open(profile, pw("sésame"))) {
            importInto(session);
            image = session.database().image();
        }
        // le fichier a été chiffré, mais l'application s'est arrêtée avant de l'inscrire au registre
        Vault.create(file, pw("sésame"), List.of(), image);

        try (Session session = manager.open(profile, pw("sésame"))) {
            assertThat(session.profile().encrypted()).isTrue();
            assertThat(operationCount(session)).isEqualTo(2);
        }
        assertThat(new ProfileManager(data, null).profiles().getFirst().encrypted()).isTrue();
    }

    // ------------------------------------------------------------------ administrateurs

    @Test
    void theFirstAdministratorNeedsAPasswordAndTheNextOnesAnAgreement() {
        Profile pierre = manager.create("Pierre", null, false);
        Profile camille = manager.create("Camille", pw("camille"), false);
        try (Session session = manager.open(pierre, null)) {
            assertThatThrownBy(() -> manager.grantAdmin(session, null, null, null)).isInstanceOf(ProfileException.class)
                    .hasMessageContaining("mot de passe");
            manager.setPassword(session, null, pw("pierre"));
            assertThatThrownBy(() -> manager.grantAdmin(session, pw("faux"), null, null)).isInstanceOf(WrongPasswordException.class);

            manager.grantAdmin(session, pw("pierre"), null, null);

            assertThat(session.isAdmin()).isTrue();
            assertThatThrownBy(() -> manager.setPassword(session, pw("pierre"), null)).isInstanceOf(ProfileException.class);
        }
        Profile admin = manager.find(pierre.id()).orElseThrow();
        try (Session session = manager.open(camille, pw("camille"))) {
            assertThat(session.isAdmin()).isFalse();
            assertThatThrownBy(() -> manager.grantAdmin(session, pw("camille"), null, null)).isInstanceOf(ProfileException.class);
            assertThatThrownBy(() -> manager.grantAdmin(session, pw("camille"), admin, pw("faux"))).isInstanceOf(WrongPasswordException.class);

            manager.grantAdmin(session, pw("camille"), admin, pw("pierre"));

            assertThat(session.isAdmin()).isTrue();
        }
        assertThat(manager.admins()).extracting(Profile::name).containsExactly("Pierre", "Camille");
        try (Session session = manager.open(admin, pw("pierre"))) {
            assertThat(session.isAdmin()).as("les clés de l'administrateur se rouvrent avec son mot de passe").isTrue();
        }
    }

    @Test
    void anAdministratorKeepsItsRightsAfterChangingItsPassword() {
        Profile admin = admin();
        Profile camille = manager.create("Camille", pw("camille"), true);
        try (Session session = manager.open(admin, pw("admin"))) {
            manager.setPassword(session, pw("admin"), pw("nouveau"));
        }
        try (Session session = manager.open(admin, pw("nouveau"))) {
            assertThat(session.isAdmin()).isTrue();
            manager.openAs(session, camille).close();
        }
    }

    @Test
    void anAdministratorOpensEveryProfileWithoutItsPassword() throws IOException {
        Profile admin = admin();
        Profile plain = manager.create("Paul", pw("paul"), false);
        Profile secret = manager.create("Camille", pw("camille"), true);
        try (Session session = manager.open(secret, pw("camille"))) {
            importInto(session);
        }

        Session adminSession = manager.open(admin, pw("admin"));
        Session guest = manager.openAs(adminSession, secret);

        assertThat(guest.isGuest()).isTrue();
        assertThat(guest.isAdmin()).isFalse();
        assertThat(guest.administrator()).contains("Admin");
        assertThat(guest.profile().name()).isEqualTo("Camille");
        assertThat(operationCount(guest)).isEqualTo(2);
        assertThatThrownBy(() -> manager.setPassword(guest, null, pw("x"))).as("la protection reste l'affaire du propriétaire")
                .isInstanceOf(ProfileException.class);
        assertThatThrownBy(() -> manager.setEncryption(guest, false, pw("x"))).isInstanceOf(ProfileException.class);

        Session home = manager.returnHome(guest);
        assertThat(home.profile().name()).isEqualTo("Admin");
        assertThat(home.isAdmin()).isTrue();
        Session other = manager.openAs(home, plain);
        assertThat(other.profile().name()).isEqualTo("Paul");
        other.close();
    }

    @Test
    void onlyAnAdministratorOpensTheProfilesOfOthers() {
        Profile pierre = manager.create("Pierre", pw("pierre"), false);
        Profile camille = manager.create("Camille", pw("camille"), false);

        try (Session session = manager.open(pierre, pw("pierre"))) {
            assertThatThrownBy(() -> manager.openAs(session, camille)).isInstanceOf(ProfileException.class);
            assertThatThrownBy(() -> manager.resetPassword(session, camille, pw("x"))).isInstanceOf(ProfileException.class);
            assertThatThrownBy(() -> manager.backupAll(session, dir.resolve("tout.zip"))).isInstanceOf(ProfileException.class);
            assertThatThrownBy(() -> manager.delete(session, camille)).isInstanceOf(ProfileException.class);
        }
        assertThat(manager.profiles()).hasSize(2);
    }

    @Test
    void aProfileEncryptedBeforeTheAdministratorExistedSharesItsKeyAtItsNextOpening() {
        Profile camille = manager.create("Camille", pw("camille"), true);
        Profile admin = admin();

        try (Session session = manager.open(admin, pw("admin"))) {
            assertThatThrownBy(() -> manager.openAs(session, camille)).isInstanceOf(ProfileException.class)
                    .hasMessageContaining("clé de secours");
        }
        manager.open(camille, pw("camille")).close();
        try (Session session = manager.open(admin, pw("admin"))) {
            manager.openAs(session, camille).close();
        }
    }

    @Test
    void anAdministratorWhoGrantsTheRoleSharesTheRescueKeysAtOnce() {
        Profile admin = admin();
        Profile camille = manager.create("Camille", pw("camille"), true);
        Profile paul = manager.create("Paul", pw("paul"), false);

        try (Session session = manager.open(paul, pw("paul"))) {
            manager.grantAdmin(session, pw("paul"), admin, pw("admin"));
            // sans que Camille ait rouvert son profil
            manager.openAs(session, camille).close();
        }
    }

    @Test
    void anAdministratorResetsAForgottenPassword() throws IOException {
        Profile admin = admin();
        Profile secret = manager.create("Camille", pw("oublié"), true);
        Profile plain = manager.create("Paul", pw("oublié"), false);
        try (Session session = manager.open(secret, pw("oublié"))) {
            importInto(session);
        }

        try (Session session = manager.open(admin, pw("admin"))) {
            manager.resetPassword(session, secret, pw("nouveau"));
            manager.resetPassword(session, plain, null);
            assertThatThrownBy(() -> manager.resetPassword(session, secret, null)).isInstanceOf(ProfileException.class);
        }

        assertThatThrownBy(() -> manager.open(secret, pw("oublié"))).isInstanceOf(WrongPasswordException.class);
        try (Session session = manager.open(secret, pw("nouveau"))) {
            assertThat(operationCount(session)).isEqualTo(2);
        }
        manager.open(plain, null).close();
    }

    @Test
    void resettingThePasswordOfAnotherAdministratorGivesItNewKeys() {
        Profile admin = admin();
        Profile second = manager.create("Camille", pw("oublié"), false);
        Profile secret = manager.create("Paul", pw("paul"), true);
        try (Session session = manager.open(second, pw("oublié"))) {
            manager.grantAdmin(session, pw("oublié"), admin, pw("admin"));
        }

        try (Session session = manager.open(admin, pw("admin"))) {
            manager.resetPassword(session, second, pw("nouveau"));
        }

        try (Session session = manager.open(second, pw("nouveau"))) {
            assertThat(session.isAdmin()).isTrue();
            manager.openAs(session, secret).close();
        }
    }

    @Test
    void renouncingTheRoleTakesTheRescueKeysBack() {
        Profile admin = admin();
        Profile secret = manager.create("Camille", pw("camille"), true);
        KeyPair keys;
        try (Session session = manager.open(admin, pw("admin"))) {
            keys = session.keys();
            assertThat(Vault.open(manager.fileOf(secret), keys)).isPresent();

            manager.revokeAdmin(session);

            assertThat(session.isAdmin()).isFalse();
        }
        assertThat(manager.hasAdmin()).isFalse();
        assertThat(Vault.open(manager.fileOf(secret), keys)).as("l'ancien administrateur n'ouvre plus le coffre").isEmpty();
        manager.open(secret, pw("camille")).close();
    }

    // ------------------------------------------------------------------ suppression

    @Test
    void aProfileIsDeletedWithItsBase() {
        Profile admin = admin();
        Profile pierre = manager.create("Pierre", null, false);
        Profile camille = manager.create("Camille", pw("camille"), true);
        Path pierreFile = manager.fileOf(pierre);
        Path camilleFile = manager.fileOf(camille);

        Session own = manager.open(pierre, null);
        manager.delete(own, pierre);
        try (Session session = manager.open(admin, pw("admin"))) {
            manager.delete(session, camille);
        }

        assertThat(pierreFile).doesNotExist();
        assertThat(camilleFile).doesNotExist();
        assertThat(manager.profiles()).extracting(Profile::name).containsExactly("Admin");
        assertThatThrownBy(() -> manager.open(pierre, null)).isInstanceOf(ProfileException.class);
    }

    // ------------------------------------------------------------------ sauvegarde et restauration

    @Test
    void aBackupRestoresAProfileWithItsDataAndItsPassword() throws IOException {
        Profile profile = manager.create("Pierre", pw("sésame"), false);
        Path zip = dir.resolve("sauvegarde.zip");
        try (Session session = manager.open(profile, pw("sésame"))) {
            importInto(session);
            manager.backup(session, zip);
            session.database().clearOperations();
        }

        List<Profile> restored = manager.restore(zip);

        assertThat(restored).singleElement().satisfies(p -> {
            assertThat(p.name()).isEqualTo("Pierre (restauré)");
            assertThat(p.id()).isNotEqualTo(profile.id());
        });
        assertThatThrownBy(() -> manager.open(restored.getFirst(), null)).isInstanceOf(WrongPasswordException.class);
        try (Session session = manager.open(restored.getFirst(), pw("sésame"))) {
            assertThat(operationCount(session)).isEqualTo(2);
        }
        try (Session session = manager.open(profile, pw("sésame"))) {
            assertThat(operationCount(session)).as("le profil d'origine n'est pas touché").isZero();
        }
    }

    @Test
    void theBackupOfAnEncryptedProfileStaysEncrypted() throws IOException {
        Profile profile = manager.create("Camille", pw("sésame"), true);
        Path zip = dir.resolve("sauvegarde.zip");
        try (Session session = manager.open(profile, pw("sésame"))) {
            importInto(session);
            manager.backup(session, zip);
        }
        try (ZipFile archive = new ZipFile(zip.toFile())) {
            for (var entry : java.util.Collections.list(archive.entries())) {
                assertThat(new String(archive.getInputStream(entry).readAllBytes(), StandardCharsets.ISO_8859_1))
                        .as(entry.getName()).doesNotContain(MARKER);
            }
        }

        ProfileManager elsewhere = new ProfileManager(dir.resolve("ailleurs"), null);
        Profile restored = elsewhere.restore(zip).getFirst();

        assertThat(restored.name()).isEqualTo("Camille");
        assertThat(restored.encrypted()).isTrue();
        assertThatThrownBy(() -> elsewhere.open(restored, pw("faux"))).isInstanceOf(WrongPasswordException.class);
        try (Session session = elsewhere.open(restored, pw("sésame"))) {
            assertThat(operationCount(session)).isEqualTo(2);
        }
    }

    @Test
    void anAdministratorBacksUpEverythingAndTheRestoredAdministratorStillOpensEveryProfile() throws IOException {
        Profile admin = admin();
        Profile secret = manager.create("Camille", pw("camille"), true);
        Profile plain = manager.create("Paul", null, false);
        try (Session session = manager.open(secret, pw("camille"))) {
            importInto(session);
        }
        Path zip = dir.resolve("tout.zip");
        try (Session session = manager.open(admin, pw("admin"))) {
            importInto(session);
            manager.backupAll(session, zip);
        }
        assertThat(plain.encrypted()).isFalse();

        ProfileManager elsewhere = new ProfileManager(dir.resolve("ailleurs"), null);
        List<Profile> restored = elsewhere.restore(zip);

        assertThat(restored).extracting(Profile::name, Profile::role, Profile::encrypted).containsExactly(
                org.assertj.core.api.Assertions.tuple("Admin", Role.ADMIN, false),
                org.assertj.core.api.Assertions.tuple("Camille", Role.USER, true),
                org.assertj.core.api.Assertions.tuple("Paul", Role.USER, false));
        Session session = elsewhere.open(restored.get(0), pw("admin"));
        assertThat(session.isAdmin()).isTrue();
        assertThat(operationCount(session)).isEqualTo(2);
        Session guest = elsewhere.openAs(session, restored.get(1));
        assertThat(operationCount(guest)).isEqualTo(2);
        guest.close();

        // restaurée là où un administrateur existe déjà, la sauvegarde ne lui en ajoute pas d'autre
        assertThat(manager.restore(zip)).extracting(Profile::role).containsOnly(Role.USER);
        assertThat(manager.admins()).hasSize(1);
    }

    @Test
    void aFileThatIsNotABackupIsRefused() throws IOException {
        Path text = Files.writeString(dir.resolve("notes.zip"), "ceci n'est pas une archive");

        assertThatThrownBy(() -> manager.restore(text)).isInstanceOf(ProfileException.class);
        assertThat(manager.profiles()).isEmpty();
    }
}
