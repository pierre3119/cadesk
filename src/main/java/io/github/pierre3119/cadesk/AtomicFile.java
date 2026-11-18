package io.github.pierre3119.cadesk;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Écriture d'un fichier « tout ou rien » : le contenu est écrit à côté, forcé sur le disque, puis mis à
 * la place de l'ancien d'un seul geste. Une coupure en cours d'écriture laisse l'ancien fichier intact.
 */
public final class AtomicFile {

    private AtomicFile() {
    }

    /**
     * @throws UncheckedIOException si le fichier ne peut pas être écrit ; l'ancien contenu est alors conservé
     */
    public static void write(Path file, byte[] content) {
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Path folder = file.getParent();
            if (folder != null) {
                Files.createDirectories(folder);
            }
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(content);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // le fichier temporaire sera écrasé à la prochaine écriture
            }
            throw new UncheckedIOException("Écriture impossible : " + file, e);
        }
    }
}
