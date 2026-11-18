package io.github.pierre3119.cadesk.llm;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Dossier jetable où travaille le moteur de GitHub Copilot : il y range ses réglages et le fil de ses
 * échanges, qui reprend en clair les opérations à classer. Le dossier, réservé à l'utilisateur, est
 * supprimé à la fermeture ; s'il survit à un arrêt brutal de l'application, il l'est à l'utilisation
 * suivante.
 *
 * <p>Un fichier verrouillé distingue le dossier en service, ici ou dans une autre fenêtre, du dossier
 * abandonné. Les dossiers de cette application sont aussi notés ici : sous Linux, rouvrir puis refermer
 * le fichier d'un verrou que l'on tient déjà le libérerait.
 */
final class CopilotScratch implements AutoCloseable {

    static final String PREFIX = "cadesk-copilot-";
    private static final String LOCK = "verrou";
    private static final Set<Path> LIVE = ConcurrentHashMap.newKeySet();

    private final Path root;
    private final FileChannel lock;

    private CopilotScratch(Path root, FileChannel lock) {
        this.root = root;
        this.lock = lock;
    }

    /** Crée un dossier neuf dans le dossier temporaire du système, après avoir supprimé ceux qui y sont abandonnés. */
    static CopilotScratch create() throws IOException {
        return create(Path.of(System.getProperty("java.io.tmpdir")));
    }

    static CopilotScratch create(Path parent) throws IOException {
        Path directory = parent.toAbsolutePath().normalize();
        purge(directory);
        Path root = Files.createTempDirectory(directory, PREFIX);
        LIVE.add(root);
        FileChannel lock = null;
        try {
            lock = FileChannel.open(root.resolve(LOCK), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            lock.lock();
            CopilotScratch scratch = new CopilotScratch(root, lock);
            Files.createDirectory(scratch.home());
            Files.createDirectory(scratch.work());
            return scratch;
        } catch (IOException | RuntimeException e) {
            if (lock != null) {
                lock.close();
            }
            delete(root);
            LIVE.remove(root);
            throw e;
        }
    }

    /** Dossier des réglages et des données du moteur. */
    Path home() {
        return root.resolve("moteur");
    }

    /** Dossier courant du moteur : vide, il n'a rien à y lire. */
    Path work() {
        return root.resolve("travail");
    }

    @Override
    public void close() {
        try {
            lock.close();
        } catch (IOException ignored) {
            // le dossier est supprimé de toute façon
        }
        delete(root);
        LIVE.remove(root);
    }

    private static void purge(Path directory) {
        try (DirectoryStream<Path> leftovers = Files.newDirectoryStream(directory, PREFIX + "*")) {
            for (Path leftover : leftovers) {
                if (!LIVE.contains(leftover) && abandoned(leftover)) {
                    delete(leftover);
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // le ménage attendra la prochaine utilisation
        }
    }

    /** Vrai si plus personne ne tient le verrou du dossier ; un dossier sans verrou est en cours de création. */
    private static boolean abandoned(Path folder) {
        Path file = folder.resolve(LOCK);
        if (!Files.isRegularFile(file)) {
            return false;
        }
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            return channel.tryLock() != null;
        } catch (IOException | OverlappingFileLockException e) {
            return false;
        }
    }

    /** Supprime le dossier ; son verrou part en dernier, pour qu'un dossier supprimé à moitié reste reconnu comme abandonné. */
    private static void delete(Path folder) {
        Path lockFile = folder.resolve(LOCK);
        List<Path> entries;
        try (Stream<Path> tree = Files.walk(folder)) {
            entries = tree.filter(entry -> !entry.equals(folder) && !entry.equals(lockFile)).sorted(Comparator.reverseOrder()).toList();
        } catch (IOException | RuntimeException e) {
            return;
        }
        boolean emptied = true;
        for (Path entry : entries) {
            try {
                Files.deleteIfExists(entry);
            } catch (IOException e) {
                // un fichier encore tenu par le moteur : le dossier sera supprimé à la prochaine utilisation
                emptied = false;
            }
        }
        if (emptied) {
            try {
                Files.deleteIfExists(lockFile);
                Files.deleteIfExists(folder);
            } catch (IOException ignored) {
                // même chose
            }
        }
    }
}
