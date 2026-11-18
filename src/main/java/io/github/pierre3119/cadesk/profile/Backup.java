package io.github.pierre3119.cadesk.profile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Archive de sauvegarde : un fichier zip qui contient la base de chaque profil sauvegardé, telle qu'elle
 * est sur le disque (une base chiffrée y reste chiffrée), et un manifeste {@code sauvegarde.json} qui
 * décrit ces profils.
 */
final class Backup {

    /** Un profil dans une sauvegarde : sa description et le contenu du fichier de sa base. */
    record Item(Profile profile, byte[] data) {
    }

    private static final String MANIFEST = "sauvegarde.json";
    private static final int FORMAT = 1;
    /** Garde-fou contre une archive piégée : aucune base réelle n'approche cette taille. */
    private static final long MAX_ENTRY_BYTES = 1L << 30;

    private Backup() {
    }

    /** Écrit l'archive ; elle n'apparaît à son emplacement qu'une fois complète. */
    static void write(Path zip, List<Item> items) throws IOException {
        JsonArray profiles = new JsonArray();
        Path temporary = zip.resolveSibling(zip.getFileName() + ".tmp");
        try {
            try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(temporary), StandardCharsets.UTF_8)) {
                for (int i = 0; i < items.size(); i++) {
                    String entry = "profils/" + (i + 1) + ".db";
                    JsonObject profile = ProfileRegistry.json(items.get(i).profile());
                    profile.addProperty("entry", entry);
                    profiles.add(profile);
                    out.putNextEntry(new ZipEntry(entry));
                    out.write(items.get(i).data());
                    out.closeEntry();
                }
                JsonObject manifest = new JsonObject();
                manifest.addProperty("application", "CaDesk");
                manifest.addProperty("format", FORMAT);
                manifest.addProperty("createdAt", LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString());
                manifest.add("profiles", profiles);
                out.putNextEntry(new ZipEntry(MANIFEST));
                out.write(new GsonBuilder().setPrettyPrinting().create().toJson(manifest).getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
            Files.move(temporary, zip, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * @throws ProfileException si le fichier n'est pas une sauvegarde de l'application
     */
    static List<Item> read(Path zip) throws IOException {
        try (ZipFile archive = new ZipFile(zip.toFile(), StandardCharsets.UTF_8)) {
            ZipEntry manifestEntry = archive.getEntry(MANIFEST);
            if (manifestEntry == null) {
                throw new ProfileException("Ce fichier n'est pas une sauvegarde de CaDesk.");
            }
            List<Item> items = new ArrayList<>();
            try {
                JsonObject manifest = JsonParser.parseString(new String(bytes(archive, manifestEntry), StandardCharsets.UTF_8))
                        .getAsJsonObject();
                if (manifest.get("format").getAsInt() > FORMAT) {
                    throw new ProfileException("Cette sauvegarde a été créée par une version plus récente de l'application.");
                }
                for (JsonElement element : manifest.getAsJsonArray("profiles")) {
                    JsonObject profile = element.getAsJsonObject();
                    ZipEntry data = archive.getEntry(profile.get("entry").getAsString());
                    if (data == null) {
                        throw new ProfileException("Sauvegarde incomplète : la base du profil « "
                                + profile.get("name").getAsString() + " » est absente.");
                    }
                    items.add(new Item(ProfileRegistry.profile(profile), bytes(archive, data)));
                }
            } catch (ProfileException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new ProfileException("Sauvegarde illisible : " + e.getMessage(), e);
            }
            return items;
        } catch (java.util.zip.ZipException e) {
            throw new ProfileException("Ce fichier n'est pas une sauvegarde de CaDesk.", e);
        }
    }

    private static byte[] bytes(ZipFile archive, ZipEntry entry) throws IOException {
        try (InputStream in = archive.getInputStream(entry)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
                total += read;
                if (total > MAX_ENTRY_BYTES) {
                    throw new ProfileException("Sauvegarde illisible : « " + entry.getName() + " » est démesuré.");
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }
}
