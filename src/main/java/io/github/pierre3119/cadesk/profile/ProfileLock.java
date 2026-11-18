package io.github.pierre3119.cadesk.profile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Verrou qui réserve une base chiffrée à une seule fenêtre de l'application : son image est réécrite en
 * entier à chaque modification, deux fenêtres s'écraseraient l'une l'autre. (Une base SQLite ordinaire
 * n'en a pas besoin : SQLite arbitre lui-même les accès.)
 *
 * <p>Le verrou porte sur un fichier {@code .lock} voisin de la base. Les verrous tenus par cette
 * application sont aussi notés ici : sous Linux, rouvrir puis refermer le fichier d'un verrou que l'on
 * tient déjà le libérerait.
 */
final class ProfileLock implements AutoCloseable {

    private static final Set<Path> HELD = ConcurrentHashMap.newKeySet();

    private final Path file;
    private final FileChannel channel;

    private ProfileLock(Path file, FileChannel channel) {
        this.file = file;
        this.channel = channel;
    }

    /**
     * @throws ProfileException si la base est déjà ouverte, ici ou dans une autre fenêtre de l'application
     */
    static ProfileLock acquire(Path database) {
        Path file = database.resolveSibling(database.getFileName() + ".lock").toAbsolutePath().normalize();
        if (HELD.add(file)) {
            try {
                FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                try {
                    if (channel.tryLock() != null) {
                        return new ProfileLock(file, channel);
                    }
                } catch (OverlappingFileLockException e) {
                    // tenu par cette application sans être passé par ici : traité comme un verrou étranger
                }
                channel.close();
                HELD.remove(file);
            } catch (IOException e) {
                HELD.remove(file);
                throw new UncheckedIOException("Verrouillage impossible : " + file, e);
            }
        }
        throw new ProfileException("Ce profil est déjà ouvert dans une autre fenêtre de l'application.");
    }

    @Override
    public void close() {
        try {
            channel.close();
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // un fichier de verrou resté sur place n'empêche pas la prochaine ouverture
        } finally {
            HELD.remove(file);
        }
    }
}
