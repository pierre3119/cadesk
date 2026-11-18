package io.github.pierre3119.cadesk.db;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.sqlite.SQLiteConnection;

/**
 * La base SQLite de l'application : une connexion, un schéma versionné.
 *
 * <p>La base est d'ordinaire un fichier SQLite. Celle d'un profil chiffré vit au contraire en mémoire :
 * son image est lue dans un {@link Storage} à l'ouverture, puis y est réécrite en entier après chaque
 * modification — rien n'est jamais écrit en clair sur le disque.
 *
 * <p>Le numéro de version du schéma est conservé dans {@code PRAGMA user_version} ; à l'ouverture, les
 * scripts de migration non encore appliqués le sont dans l'ordre, chacun dans sa transaction.
 * Les accès sont sérialisés par le verrou de l'instance.
 */
public final class Database implements AutoCloseable {

    /** Scripts de migration, dans l'ordre : le n-ième porte le schéma à la version n. */
    private static final List<String> MIGRATIONS = List.of("V1__schema.sql", "V2__categories.sql", "V3__assistant.sql");

    /** Travail exécuté dans une transaction. */
    @FunctionalInterface
    public interface Work<T> {
        T run(Connection connection) throws SQLException;
    }

    /** Là où est conservée l'image d'une base qui n'est pas un simple fichier SQLite (base chiffrée). */
    public interface Storage {

        /** Image SQLite de la base, ou un tableau vide pour une base neuve. */
        byte[] load();

        /** Remplace l'image conservée ; l'ancienne doit rester intacte en cas d'échec. */
        void store(byte[] image);
    }

    private final Path file;
    private final Storage storage;
    private final Connection connection;

    /** Ouvre (ou crée) la base contenue dans ce fichier SQLite. */
    public Database(Path file) {
        this(file, null);
    }

    /**
     * @param file    fichier de la base ; avec un {@code storage}, il ne sert qu'à la désigner et à placer
     *                la copie de sécurité faite avant une mise à niveau
     * @param storage où lire et écrire l'image de la base, ou {@code null} pour un fichier SQLite ordinaire
     */
    public Database(Path file, Storage storage) {
        this.file = file.toAbsolutePath().normalize();
        this.storage = storage;
        try {
            Path folder = this.file.getParent();
            if (folder != null) {
                Files.createDirectories(folder);
            }
            connection = DriverManager.getConnection(storage == null ? "jdbc:sqlite:" + this.file : "jdbc:sqlite::memory:");
            try {
                if (storage != null) {
                    byte[] image = storage.load();
                    if (image.length > 0) {
                        sqlite().deserialize("main", image);
                    }
                }
                try (Statement st = connection.createStatement()) {
                    st.execute("PRAGMA foreign_keys = ON");
                    st.execute("PRAGMA busy_timeout = 5000");
                }
                migrate();
            } catch (SQLException | RuntimeException e) {
                connection.close();
                throw e;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Impossible de créer le dossier de la base : " + this.file.getParent(), e);
        } catch (SQLException e) {
            throw new DatabaseException("Impossible d'ouvrir la base " + this.file, e);
        }
    }

    public Path file() {
        return file;
    }

    /** Exécute {@code work} dans une transaction : validée s'il réussit, annulée s'il échoue. */
    public synchronized <T> T inTransaction(Work<T> work) {
        long before = storage == null ? 0 : totalChanges();
        T result = transact(work);
        if (storage != null && totalChanges() != before) {
            persist();
        }
        return result;
    }

    /**
     * Image de la base : le contenu exact qu'a, ou qu'aurait, son fichier SQLite. Sert aux sauvegardes
     * et au passage d'une base en clair à une base chiffrée (et inversement).
     */
    public synchronized byte[] image() {
        try {
            return sqlite().serialize("main");
        } catch (SQLException e) {
            throw new DatabaseException("Lecture de la base impossible : " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ vidage

    /**
     * Supprime les opérations, les imports et les comptes. Les catégories, les règles et les préférences
     * sont conservées ; la place libérée est rendue, de sorte que rien ne subsiste dans le fichier.
     */
    public synchronized void clearOperations() {
        transact(c -> {
            try (Statement st = c.createStatement()) {
                st.execute("DELETE FROM operation");
                st.execute("DELETE FROM statement_import");
                st.execute("DELETE FROM account");
            }
            return null;
        });
        compact();
    }

    /**
     * Remet la base dans son état de départ : plus aucune opération ni règle, et les catégories
     * d'origine. Seules les préférences (thème, réglages de l'assistant…) sont conservées.
     */
    public synchronized void reset() {
        try {
            Map<String, String> settings = new LinkedHashMap<>();
            List<String> tables = new ArrayList<>();
            try (Statement st = connection.createStatement()) {
                try (ResultSet rs = st.executeQuery("SELECT name, value FROM setting")) {
                    while (rs.next()) {
                        settings.put(rs.getString(1), rs.getString(2));
                    }
                }
                try (ResultSet rs = st.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'")) {
                    while (rs.next()) {
                        tables.add(rs.getString(1));
                    }
                }
                // les tables se référencent entre elles : les contraintes sont suspendues le temps de les supprimer
                st.execute("PRAGMA foreign_keys = OFF");
            }
            try {
                transact(c -> {
                    try (Statement st = c.createStatement()) {
                        for (String table : tables) {
                            // un nom de table ne se passe pas en paramètre : il est écrit entre guillemets, les siens doublés
                            st.execute("DROP TABLE \"" + table.replace("\"", "\"\"") + "\"");
                        }
                        st.execute("PRAGMA user_version = 0");
                    }
                    return null;
                });
            } finally {
                try (Statement st = connection.createStatement()) {
                    st.execute("PRAGMA foreign_keys = ON");
                }
            }
            apply(0);
            transact(c -> {
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO setting (name, value) VALUES (?, ?)")) {
                    for (Map.Entry<String, String> setting : settings.entrySet()) {
                        ps.setString(1, setting.getKey());
                        ps.setString(2, setting.getValue());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                return null;
            });
            compact();
        } catch (SQLException e) {
            throw new DatabaseException("Réinitialisation de la base impossible : " + e.getMessage(), e);
        }
    }

    /** Reconstruit le fichier sans les pages libérées, puis enregistre l'image d'une base en mémoire. */
    private void compact() {
        try (Statement st = connection.createStatement()) {
            st.execute("VACUUM");
        } catch (SQLException e) {
            throw new DatabaseException("Erreur d'accès à la base : " + e.getMessage(), e);
        }
        if (storage != null) {
            persist();
        }
    }

    // ------------------------------------------------------------------ transactions et image

    private <T> T transact(Work<T> work) {
        try {
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new DatabaseException("Erreur d'accès à la base : " + e.getMessage(), e);
        }
    }

    /** Nombre de lignes insérées, modifiées ou supprimées par cette connexion depuis son ouverture. */
    private long totalChanges() {
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery("SELECT total_changes()")) {
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new DatabaseException("Erreur d'accès à la base : " + e.getMessage(), e);
        }
    }

    private void persist() {
        storage.store(image());
    }

    private SQLiteConnection sqlite() throws SQLException {
        return connection.unwrap(SQLiteConnection.class);
    }

    // ------------------------------------------------------------------ schéma

    private void migrate() throws SQLException {
        int version;
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery("PRAGMA user_version")) {
            version = rs.getInt(1);
        }
        if (version > MIGRATIONS.size()) {
            throw new DatabaseException("La base " + file + " a été créée par une version plus récente de l'application "
                    + "(schéma " + version + ", attendu " + MIGRATIONS.size() + " au plus).", null);
        }
        if (version > 0 && version < MIGRATIONS.size()) {
            // la base contient déjà des données : copie de sécurité avant de modifier son schéma
            Path backup = file.resolveSibling(file.getFileName() + ".sauvegarde-v" + version);
            try {
                Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new DatabaseException("Sauvegarde impossible avant la mise à niveau de la base (" + backup
                        + ") : la base n'a pas été modifiée.", e);
            }
        }
        apply(version);
        if (storage != null && version < MIGRATIONS.size()) {
            persist();
        }
    }

    /** Applique les scripts de migration à partir de la version {@code from}. */
    private void apply(int from) {
        for (int v = from; v < MIGRATIONS.size(); v++) {
            String script = resource("/db/migration/" + MIGRATIONS.get(v));
            int target = v + 1;
            transact(c -> {
                try (Statement st = c.createStatement()) {
                    for (String sql : script.replaceAll("(?m)^\\s*--.*$", "").split(";")) {
                        if (!sql.isBlank()) {
                            st.execute(sql);
                        }
                    }
                    st.execute("PRAGMA user_version = " + target);
                }
                return null;
            });
        }
    }

    private static String resource(String path) {
        try (InputStream in = Database.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Script de migration introuvable : " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public synchronized void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            throw new DatabaseException("Erreur à la fermeture de la base", e);
        }
    }
}
