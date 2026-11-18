package io.github.pierre3119.cadesk.profile;

import io.github.pierre3119.cadesk.AtomicFile;
import io.github.pierre3119.cadesk.db.Database;
import io.github.pierre3119.cadesk.profile.Profile.AdminKey;
import io.github.pierre3119.cadesk.security.Crypto;
import io.github.pierre3119.cadesk.security.PasswordHash;
import io.github.pierre3119.cadesk.security.Sealed;
import io.github.pierre3119.cadesk.security.Vault;
import io.github.pierre3119.cadesk.security.WrongPasswordException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.PublicKey;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Les profils de l'application : chaque utilisateur a le sien, avec sa propre base de données.
 *
 * <p>Le registre ({@code profils.json}, dans le dossier des données) décrit les profils ; la base de
 * chacun est un fichier distinct, dans {@code profils/}. Un profil peut être protégé par un mot de
 * passe, et sa base chiffrée par une clé qui lui est propre (voir {@link Vault}).
 *
 * <p>Un <b>administrateur</b> peut ouvrir tous les profils, réinitialiser leur mot de passe et
 * sauvegarder l'ensemble des données. Il possède une paire de clés : avec la clé publique, chaque
 * profil chiffré lui confie une clé de secours ; la clé privée est chiffrée par son mot de passe, qui
 * est donc obligatoire. Les clés de secours sont mises à jour à chaque ouverture d'un profil chiffré.
 */
public final class ProfileManager {

    private static final String REGISTRY = "profils.json";
    private static final String FOLDER = "profils";
    private static final int MAX_NAME = 40;
    private static final byte[] SQLITE_MAGIC = "SQLite format 3\0".getBytes(StandardCharsets.US_ASCII);
    private static final String OWNER_ONLY = "Seul le propriétaire du profil peut modifier sa protection.";
    private static final String NO_ESCROW = "Ce profil chiffré n'a pas encore confié de clé de secours à votre compte "
            + "d'administrateur : son propriétaire doit d'abord l'ouvrir une fois.";

    private final Path directory;
    private final Path registry;
    private final List<Profile> profiles = new ArrayList<>();

    /**
     * @param directory      dossier des données : il reçoit le registre et les bases des profils
     * @param legacyDatabase base de l'application d'avant les profils ; si elle existe et qu'il n'y a pas
     *                       encore de registre, elle devient le premier profil, sans être déplacée
     * @throws ProfileException si le registre existe mais n'est pas lisible
     */
    public ProfileManager(Path directory, Path legacyDatabase) {
        this.directory = directory.toAbsolutePath().normalize();
        this.registry = this.directory.resolve(REGISTRY);
        if (Files.exists(registry)) {
            profiles.addAll(ProfileRegistry.read(registry));
        } else if (legacyDatabase != null && Files.isRegularFile(legacyDatabase)) {
            Path legacy = legacyDatabase.toAbsolutePath().normalize();
            String file = legacy.startsWith(this.directory)
                    ? this.directory.relativize(legacy).toString().replace('\\', '/') : legacy.toString();
            profiles.add(new Profile(newId(), defaultName(), Role.USER, file, Vault.isVault(legacy), null, null, now()));
            save();
        }
    }

    // ------------------------------------------------------------------ consultation

    public Path directory() {
        return directory;
    }

    /** Les profils, dans l'ordre de leur création. */
    public synchronized List<Profile> profiles() {
        return List.copyOf(profiles);
    }

    public synchronized Optional<Profile> find(String id) {
        return profiles.stream().filter(p -> p.id().equals(id)).findFirst();
    }

    public synchronized List<Profile> admins() {
        return profiles.stream().filter(Profile::isAdmin).toList();
    }

    public synchronized boolean hasAdmin() {
        return profiles.stream().anyMatch(Profile::isAdmin);
    }

    /** Fichier de la base d'un profil. */
    public Path fileOf(Profile profile) {
        return directory.resolve(profile.file()).normalize();
    }

    // ------------------------------------------------------------------ création et ouverture

    /**
     * Crée un profil et sa base, avec les catégories de départ.
     *
     * @param password mot de passe du profil, ou {@code null} (ou vide) pour ne pas en demander
     * @param encrypt  vrai pour chiffrer la base ; un mot de passe est alors obligatoire
     */
    public synchronized Profile create(String name, char[] password, boolean encrypt) {
        String clean = validName(name, null);
        if (encrypt && blank(password)) {
            throw new ProfileException("Un mot de passe est nécessaire pour chiffrer les données.");
        }
        String id = newId();
        Profile profile = new Profile(id, clean, Role.USER, FOLDER + "/" + id + ".db", encrypt,
                encrypt || blank(password) ? null : PasswordHash.of(password), null, now());
        Path file = fileOf(profile);
        try {
            if (encrypt) {
                Vault.create(file, password, adminPublicKeys(), new byte[0]);
            }
            profiles.add(profile);
            save();
            open(profile, password).close();
            return profile;
        } catch (RuntimeException e) {
            profiles.removeIf(p -> p.id().equals(id));
            save();
            deleteFiles(file);
            throw e;
        }
    }

    /**
     * Ouvre un profil pour son propriétaire.
     *
     * @throws WrongPasswordException si le mot de passe n'est pas le bon
     * @throws ProfileException       si le profil est déjà ouvert dans une autre fenêtre
     */
    public synchronized Session open(Profile profile, char[] password) {
        Profile current = reconcile(require(profile.id()));
        Vault vault = null;
        if (current.encrypted()) {
            if (blank(password)) {
                throw new WrongPasswordException();
            }
            vault = Vault.open(fileOf(current), password);
        } else if (current.password() != null && (blank(password) || !current.password().matches(password))) {
            throw new WrongPasswordException();
        }
        return start(current, vault, adminKeys(current, password), null);
    }

    /**
     * Ouvre le profil d'un autre utilisateur pour un administrateur, sans son mot de passe. La session
     * de l'administrateur est fermée ; {@link #returnHome} y revient.
     */
    public synchronized Session openAs(Session admin, Profile target) {
        requireAdmin(admin);
        Profile current = reconcile(require(target.id()));
        if (current.id().equals(admin.profile().id())) {
            throw new ProfileException("Ce profil est déjà ouvert.");
        }
        Vault vault = null;
        if (current.encrypted()) {
            vault = Vault.open(fileOf(current), admin.keys()).orElseThrow(() -> new ProfileException(NO_ESCROW));
        }
        Session guest = start(current, vault, null,
                new Session.Origin(admin.profile().id(), admin.profile().name(), admin.keys(), admin.vault()));
        admin.close();
        return guest;
    }

    /** Ferme le profil qu'un administrateur consultait et rouvre le sien. */
    public synchronized Session returnHome(Session guest) {
        Session.Origin origin = guest.origin();
        if (origin == null) {
            throw new IllegalStateException("Cette session n'a pas été ouverte par un administrateur.");
        }
        Profile admin = reconcile(require(origin.profileId()));
        Vault vault = null;
        if (admin.encrypted()) {
            vault = Vault.open(fileOf(admin), origin.keys()).orElse(origin.vault());
            if (vault == null) {
                throw new ProfileException("Rouvrez votre profil avec votre mot de passe.");
            }
        }
        guest.close();
        return start(admin, vault, origin.keys(), null);
    }

    private Session start(Profile profile, Vault vault, KeyPair keys, Session.Origin origin) {
        Path file = fileOf(profile);
        if (vault == null) {
            return new Session(profile, new Database(file), null, keys, origin, null);
        }
        ProfileLock lock = ProfileLock.acquire(file);
        try {
            vault.setAdmins(adminPublicKeys());
            Database database = new Database(file, new Database.Storage() {
                @Override
                public byte[] load() {
                    return vault.read();
                }

                @Override
                public void store(byte[] image) {
                    vault.write(image);
                }
            });
            return new Session(profile, database, vault, keys, origin, lock);
        } catch (RuntimeException e) {
            lock.close();
            throw e;
        }
    }

    /** Met le registre en accord avec le fichier : c'est lui qui dit si la base est chiffrée. */
    private Profile reconcile(Profile profile) {
        Path file = fileOf(profile);
        if (!Files.exists(file)) {
            if (profile.encrypted()) {
                throw new ProfileException("La base chiffrée de ce profil est introuvable : " + file);
            }
            return profile;
        }
        boolean vaulted = Vault.isVault(file);
        if (vaulted == profile.encrypted()) {
            return profile;
        }
        // une conversion interrompue entre l'écriture du fichier et celle du registre
        Profile fixed = profile.withProtection(vaulted, profile.password());
        replace(fixed);
        save();
        return fixed;
    }

    // ------------------------------------------------------------------ nom, mot de passe, chiffrement

    public synchronized void rename(Session session, String name) {
        Profile profile = current(session);
        Profile renamed = profile.withName(validName(name, profile.id()));
        replace(renamed);
        save();
        session.update(renamed);
    }

    /**
     * Définit, change ou retire le mot de passe du profil ouvert.
     *
     * @param current mot de passe actuel, s'il y en a un
     * @param next    nouveau mot de passe ; {@code null} ou vide pour le retirer
     * @throws WrongPasswordException si {@code current} n'est pas le bon
     * @throws ProfileException       si le profil ne peut pas se passer de mot de passe (chiffré, ou administrateur)
     */
    public synchronized void setPassword(Session session, char[] current, char[] next) {
        Profile profile = owner(session);
        verify(session, profile, current);
        if (blank(next) && profile.encrypted()) {
            throw new ProfileException("Des données chiffrées ne peuvent pas se passer de mot de passe : désactivez d'abord le chiffrement.");
        }
        if (blank(next) && profile.isAdmin()) {
            throw new ProfileException("Un administrateur doit garder un mot de passe : il protège sa clé de secours.");
        }
        Profile updated = profile.withProtection(profile.encrypted(),
                profile.encrypted() || blank(next) ? null : PasswordHash.of(next));
        if (profile.isAdmin() && session.keys() != null) {
            updated = updated.withAdminKey(new AdminKey(profile.adminKey().publicKey(),
                    Sealed.seal(next, session.keys().getPrivate().getEncoded())));
        }
        if (profile.encrypted()) {
            session.vault().changePassword(next);
        }
        replace(updated);
        save();
        session.update(updated);
    }

    /**
     * Chiffre la base du profil ouvert, ou la remet en clair. La base doit être rouverte : la session
     * reçue est fermée et remplacée par celle que renvoie la méthode. Si la conversion échoue en cours
     * de route, la session reste fermée et le profil doit être rouvert.
     *
     * @param password le mot de passe du profil ; pour chiffrer un profil qui n'en a pas, celui qu'il aura
     * @throws WrongPasswordException si le mot de passe n'est pas celui du profil
     */
    public synchronized Session setEncryption(Session session, boolean encrypt, char[] password) {
        Profile profile = owner(session);
        if (profile.encrypted() == encrypt) {
            return session;
        }
        if (blank(password)) {
            if (profile.hasPassword()) {
                throw new WrongPasswordException();
            }
            throw new ProfileException("Un mot de passe est nécessaire pour chiffrer les données.");
        }
        verify(session, profile, password);
        Path file = fileOf(profile);
        KeyPair keys = session.keys();
        byte[] image = session.database().image();
        session.close();
        Profile updated;
        Vault vault = null;
        if (encrypt) {
            // le fichier d'abord : s'il est chiffré mais que le registre l'ignore encore, l'ouverture suivante le constate
            vault = Vault.create(file, password, adminPublicKeys(), image);
            deletePlainCopies(file);
            updated = profile.withProtection(true, null);
            replace(updated);
            save();
        } else {
            // le registre d'abord : la base ne doit jamais se trouver en clair sans mot de passe pour la garder
            updated = profile.withProtection(false, PasswordHash.of(password));
            replace(updated);
            save();
            AtomicFile.write(file, image);
        }
        return start(updated, vault, keys, null);
    }

    private static void verify(Session session, Profile profile, char[] password) {
        if (!profile.hasPassword()) {
            return;
        }
        boolean accepted = !blank(password)
                && (profile.encrypted() ? session.vault().accepts(password) : profile.password().matches(password));
        if (!accepted) {
            throw new WrongPasswordException();
        }
    }

    // ------------------------------------------------------------------ administrateurs

    /**
     * Donne le rôle d'administrateur au profil ouvert. S'il existe déjà un administrateur, l'un d'eux doit
     * donner son accord en saisissant son propre mot de passe.
     *
     * @param password           mot de passe du profil, qui protégera sa clé privée
     * @param authorizer         administrateur qui donne son accord, ou {@code null} s'il n'y en a encore aucun
     * @param authorizerPassword mot de passe de cet administrateur
     * @throws WrongPasswordException si l'un des deux mots de passe n'est pas le bon
     */
    public synchronized void grantAdmin(Session session, char[] password, Profile authorizer, char[] authorizerPassword) {
        Profile profile = owner(session);
        if (profile.isAdmin()) {
            return;
        }
        if (!profile.hasPassword()) {
            throw new ProfileException("Définissez d'abord un mot de passe : un administrateur en a besoin pour protéger sa clé de secours.");
        }
        verify(session, profile, password);
        KeyPair authorizerKeys = null;
        if (hasAdmin()) {
            Profile admin = authorizer == null ? null : find(authorizer.id()).filter(Profile::isAdmin).orElse(null);
            if (admin == null) {
                throw new ProfileException("L'accord d'un administrateur est nécessaire.");
            }
            if (blank(authorizerPassword)) {
                throw new WrongPasswordException();
            }
            authorizerKeys = unseal(admin, authorizerPassword);
        }
        KeyPair keys = Crypto.newKeyPair();
        Profile updated = profile.withAdminKey(new AdminKey(Crypto.base64(keys.getPublic().getEncoded()),
                Sealed.seal(password, keys.getPrivate().getEncoded())));
        replace(updated);
        save();
        session.update(updated, keys);
        syncEscrow(session, authorizerKeys);
    }

    /** Retire le rôle d'administrateur au profil ouvert, et ses clés de secours aux profils chiffrés. */
    public synchronized void revokeAdmin(Session session) {
        Profile profile = owner(session);
        if (!profile.isAdmin()) {
            return;
        }
        KeyPair keys = session.keys();
        Profile updated = profile.withAdminKey(null);
        replace(updated);
        save();
        session.update(updated, null);
        syncEscrow(session, keys);
    }

    /**
     * Remplace le mot de passe du profil d'un autre utilisateur, qui l'a oublié.
     *
     * @param next nouveau mot de passe ; {@code null} ou vide pour le retirer, si le profil peut s'en passer
     */
    public synchronized void resetPassword(Session admin, Profile target, char[] next) {
        requireAdmin(admin);
        Profile profile = reconcile(require(target.id()));
        if (profile.id().equals(admin.profile().id())) {
            throw new ProfileException("Pour votre propre profil, utilisez « Modifier le mot de passe ».");
        }
        if (blank(next) && (profile.encrypted() || profile.isAdmin())) {
            throw new ProfileException("Ce profil ne peut pas se passer de mot de passe : il est "
                    + (profile.encrypted() ? "chiffré." : "administrateur."));
        }
        Profile updated = profile.withProtection(profile.encrypted(),
                profile.encrypted() || blank(next) ? null : PasswordHash.of(next));
        if (profile.isAdmin()) {
            // sa clé privée était chiffrée par le mot de passe oublié : il lui faut une nouvelle paire de clés
            KeyPair fresh = Crypto.newKeyPair();
            updated = updated.withAdminKey(new AdminKey(Crypto.base64(fresh.getPublic().getEncoded()),
                    Sealed.seal(next, fresh.getPrivate().getEncoded())));
        }
        if (profile.encrypted()) {
            Path file = fileOf(profile);
            Vault vault = Vault.open(file, admin.keys()).orElseThrow(() -> new ProfileException(NO_ESCROW));
            try (ProfileLock _ = ProfileLock.acquire(file)) {
                vault.changePassword(next);
            }
        }
        replace(updated);
        save();
        if (profile.isAdmin()) {
            syncEscrow(admin, admin.keys());
        }
    }

    /**
     * Met à jour les clés de secours, une par administrateur actuel : dans le coffre de la session, puis
     * dans ceux des autres profils que {@code helper} peut ouvrir. Les coffres hors d'atteinte seront
     * mis à jour à leur prochaine ouverture.
     */
    private void syncEscrow(Session session, KeyPair helper) {
        List<PublicKey> admins = adminPublicKeys();
        if (session.vault() != null) {
            session.vault().setAdmins(admins);
        }
        if (helper == null) {
            return;
        }
        for (Profile other : profiles) {
            if (!other.encrypted() || other.id().equals(session.profile().id())) {
                continue;
            }
            Path file = fileOf(other);
            try (ProfileLock _ = ProfileLock.acquire(file)) {
                Vault.open(file, helper).ifPresent(vault -> vault.setAdmins(admins));
            } catch (RuntimeException e) {
                // coffre ouvert dans une autre fenêtre, ou illisible : il se mettra à jour à sa prochaine ouverture
            }
        }
    }

    private List<PublicKey> adminPublicKeys() {
        return profiles.stream().filter(Profile::isAdmin)
                .map(p -> Crypto.publicKey(Crypto.base64(p.adminKey().publicKey()))).toList();
    }

    /** Clés d'un administrateur à l'ouverture de son profil ; {@code null} pour un autre profil, ou si elles sont illisibles. */
    private static KeyPair adminKeys(Profile profile, char[] password) {
        if (!profile.isAdmin() || blank(password)) {
            return null;
        }
        try {
            return unseal(profile, password);
        } catch (WrongPasswordException | IllegalArgumentException e) {
            return null;
        }
    }

    private static KeyPair unseal(Profile admin, char[] password) {
        return new KeyPair(Crypto.publicKey(Crypto.base64(admin.adminKey().publicKey())),
                Crypto.privateKey(admin.adminKey().privateKey().open(password)));
    }

    // ------------------------------------------------------------------ suppression

    /**
     * Supprime un profil et sa base. Chacun peut supprimer le sien — sa session est alors fermée ; seul
     * un administrateur peut supprimer celui d'un autre.
     */
    public synchronized void delete(Session actor, Profile target) {
        Profile profile = require(target.id());
        boolean own = profile.id().equals(actor.profile().id());
        if (!own && !actor.isAdmin()) {
            throw new ProfileException("Seul un administrateur peut supprimer le profil d'un autre utilisateur.");
        }
        if (own) {
            actor.close();
        }
        profiles.removeIf(p -> p.id().equals(profile.id()));
        save();
        deleteFiles(fileOf(profile));
        if (!own && profile.isAdmin()) {
            syncEscrow(actor, actor.keys());
        }
    }

    /** Supprime la base et ce qui l'accompagne : copies de sécurité d'avant mise à niveau, journal, verrou. */
    private static void deleteFiles(Path file) {
        try {
            deletePlainCopies(file);
            Files.deleteIfExists(file.resolveSibling(file.getFileName() + ".lock"));
            Files.deleteIfExists(file.resolveSibling(file.getFileName() + ".tmp"));
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Suppression impossible : " + file, e);
        }
    }

    /** Supprime les copies en clair laissées à côté d'une base : elles n'ont plus lieu d'être une fois la base chiffrée. */
    private static void deletePlainCopies(Path file) {
        Path folder = file.getParent();
        if (folder == null || !Files.isDirectory(folder)) {
            return;
        }
        String name = String.valueOf(file.getFileName());
        try (DirectoryStream<Path> copies = Files.newDirectoryStream(folder, candidate -> {
            String other = String.valueOf(candidate.getFileName());
            return other.startsWith(name + ".sauvegarde-v") || other.equals(name + "-journal");
        })) {
            for (Path copy : copies) {
                Files.deleteIfExists(copy);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Suppression impossible dans " + folder, e);
        }
    }

    // ------------------------------------------------------------------ sauvegarde et restauration

    /** Sauvegarde le profil ouvert dans une archive zip ; une base chiffrée y reste chiffrée. */
    public synchronized void backup(Session session, Path zip) {
        writeBackup(zip, List.of(item(current(session), session)));
    }

    /** Sauvegarde tous les profils et leurs clés dans une seule archive. Réservé aux administrateurs. */
    public synchronized void backupAll(Session admin, Path zip) {
        requireAdmin(admin);
        List<Backup.Item> items = new ArrayList<>();
        for (Profile profile : profiles) {
            items.add(item(profile, profile.id().equals(admin.profile().id()) ? admin : null));
        }
        writeBackup(zip, items);
    }

    private Backup.Item item(Profile profile, Session session) {
        if (session != null && !profile.encrypted()) {
            return new Backup.Item(profile, session.database().image());
        }
        // une base chiffrée est à jour sur le disque après chaque modification ; les autres profils sont fermés
        Path file = fileOf(profile);
        if (!Files.isRegularFile(file)) {
            throw new ProfileException("La base du profil « " + profile.name() + " » est introuvable : " + file);
        }
        try {
            return new Backup.Item(profile, Files.readAllBytes(file));
        } catch (IOException e) {
            throw new UncheckedIOException("Lecture impossible : " + file, e);
        }
    }

    private static void writeBackup(Path zip, List<Backup.Item> items) {
        try {
            Backup.write(zip, items);
        } catch (IOException e) {
            throw new UncheckedIOException("Écriture de la sauvegarde impossible : " + zip, e);
        }
    }

    /**
     * Restaure les profils d'une sauvegarde en créant de nouveaux profils : rien d'existant n'est
     * remplacé. Chaque profil retrouve le mot de passe qu'il avait au moment de la sauvegarde. Les rôles
     * d'administrateur ne sont repris que s'il n'y a encore aucun administrateur ici.
     *
     * @return les profils créés
     * @throws ProfileException si le fichier n'est pas une sauvegarde lisible
     */
    public synchronized List<Profile> restore(Path zip) {
        List<Backup.Item> items;
        try {
            items = Backup.read(zip);
        } catch (IOException e) {
            throw new UncheckedIOException("Lecture de la sauvegarde impossible : " + zip, e);
        }
        if (items.isEmpty()) {
            throw new ProfileException("Cette sauvegarde ne contient aucun profil.");
        }
        for (Backup.Item item : items) {
            if (!Vault.isVault(item.data()) && !isSqlite(item.data())) {
                throw new ProfileException("Sauvegarde illisible : la base du profil « " + item.profile().name()
                        + " » n'est pas reconnue.");
            }
        }
        boolean keepRoles = !hasAdmin();
        List<Profile> restored = new ArrayList<>();
        for (Backup.Item item : items) {
            Profile source = item.profile();
            boolean vaulted = Vault.isVault(item.data());
            String id = newId();
            Profile profile = new Profile(id, freeName(source.name()), Role.USER, FOLDER + "/" + id + ".db", vaulted,
                    vaulted ? null : source.password(), null, source.createdAt() == null ? now() : source.createdAt());
            if (keepRoles && source.isAdmin()) {
                profile = profile.withAdminKey(source.adminKey());
            }
            AtomicFile.write(fileOf(profile), item.data());
            profiles.add(profile);
            restored.add(profile);
        }
        save();
        return restored;
    }

    private static boolean isSqlite(byte[] data) {
        return data.length >= SQLITE_MAGIC.length && Arrays.equals(data, 0, SQLITE_MAGIC.length, SQLITE_MAGIC, 0, SQLITE_MAGIC.length);
    }

    // ------------------------------------------------------------------ outils

    private Profile require(String id) {
        return find(id).orElseThrow(() -> new ProfileException("Ce profil n'existe plus."));
    }

    private Profile current(Session session) {
        return require(session.profile().id());
    }

    /** Le profil de la session, à condition qu'elle ait été ouverte par son propriétaire. */
    private Profile owner(Session session) {
        if (session.isGuest()) {
            throw new ProfileException(OWNER_ONLY);
        }
        return current(session);
    }

    private static void requireAdmin(Session session) {
        if (!session.isAdmin()) {
            throw new ProfileException("Cette action est réservée aux administrateurs.");
        }
    }

    private void replace(Profile updated) {
        profiles.replaceAll(p -> p.id().equals(updated.id()) ? updated : p);
    }

    private void save() {
        ProfileRegistry.write(registry, profiles);
    }

    private String validName(String name, String ownId) {
        String clean = name == null ? "" : name.strip().replaceAll("\\s+", " ");
        if (clean.isEmpty()) {
            throw new ProfileException("Donnez un nom au profil.");
        }
        if (clean.length() > MAX_NAME) {
            throw new ProfileException("Le nom du profil ne peut pas dépasser " + MAX_NAME + " caractères.");
        }
        if (profiles.stream().anyMatch(p -> p.name().equalsIgnoreCase(clean) && !p.id().equals(ownId))) {
            throw new ProfileException("Un autre profil porte déjà ce nom.");
        }
        return clean;
    }

    /** Nom libre le plus proche de {@code wanted} : « Pierre », sinon « Pierre (restauré) », « Pierre (restauré 2) »… */
    private String freeName(String wanted) {
        String candidate = wanted;
        for (int n = 1; nameTaken(candidate); n++) {
            candidate = wanted + (n == 1 ? " (restauré)" : " (restauré " + n + ")");
        }
        return candidate;
    }

    private boolean nameTaken(String name) {
        return profiles.stream().anyMatch(p -> p.name().equalsIgnoreCase(name));
    }

    private String newId() {
        String id;
        do {
            id = HexFormat.of().formatHex(Crypto.random(4));
        } while (find(id).isPresent());
        return id;
    }

    /** Nom du profil créé pour la base d'avant les profils : celui de l'utilisateur du système. */
    private static String defaultName() {
        String user = System.getProperty("user.name", "").strip();
        if (user.isEmpty() || user.length() > MAX_NAME) {
            return "Mon profil";
        }
        return user.substring(0, 1).toUpperCase(Locale.ROOT) + user.substring(1);
    }

    private static LocalDateTime now() {
        return LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
    }

    private static boolean blank(char[] password) {
        return password == null || password.length == 0;
    }
}
