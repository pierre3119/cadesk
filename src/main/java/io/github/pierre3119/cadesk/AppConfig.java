package io.github.pierre3119.cadesk;

import java.nio.file.Files;
import java.nio.file.Path;

/** Emplacements de l'application. */
public final class AppConfig {

    /** Dossier des données dans le dossier local de l'utilisateur, sous Windows. */
    static final String LOCAL_FOLDER = "CaDesk";
    /** Ce même dossier du temps où l'application s'appelait « Comptes CA » : s'il existe, les données y sont restées. */
    static final String FORMER_LOCAL_FOLDER = "ComptesCA";

    private AppConfig() {
    }

    /**
     * Dossier de l'application : la propriété système {@code cadesk.home}, fixée par les lanceurs
     * ({@code start.sh}, {@code start.bat}) sur la racine du projet, sinon le dossier courant.
     */
    public static Path home() {
        return Path.of(System.getProperty("cadesk.home", System.getProperty("user.dir"))).toAbsolutePath().normalize();
    }

    /**
     * Fichier de la base SQLite d'origine, celle de l'application d'avant les profils : elle devient le
     * premier profil, et son dossier est le {@linkplain #dataDirectory() dossier des données}.
     * <ol>
     *   <li>celui que désigne la propriété système {@code cadesk.db} ou la variable d'environnement {@code CADESK_DB} ;</li>
     *   <li>sinon {@code data/comptes.db} dans le dossier de l'application ;</li>
     *   <li>sauf si ce dossier est sur un partage réseau Windows (chemin {@code \\serveur\partage}, dont
     *       {@code \\wsl.localhost}) : SQLite ne peut pas y verrouiller un fichier, la base est alors placée
     *       dans le dossier local de l'utilisateur ({@code %LOCALAPPDATA%\CaDesk}, ou
     *       {@code %LOCALAPPDATA%\ComptesCA} si une version d'avant le changement de nom y a laissé ses données).</li>
     * </ol>
     */
    public static Path databaseFile() {
        String override = System.getProperty("cadesk.db", System.getenv("CADESK_DB"));
        if (override != null && !override.isBlank()) {
            return Path.of(override).toAbsolutePath().normalize();
        }
        Path home = home();
        if (isNetworkShare(home.toString())) {
            String local = System.getenv("LOCALAPPDATA");
            Path base = local != null && !local.isBlank() ? Path.of(local) : Path.of(System.getProperty("user.home"));
            return localFolder(base).resolve("comptes.db");
        }
        return home.resolve("data").resolve("comptes.db");
    }

    /** Le dossier des données sous {@code base} : celui d'avant le changement de nom tant qu'il est le seul à exister. */
    static Path localFolder(Path base) {
        Path folder = base.resolve(LOCAL_FOLDER);
        Path former = base.resolve(FORMER_LOCAL_FOLDER);
        return Files.isDirectory(former) && !Files.exists(folder) ? former : folder;
    }

    /**
     * Dossier des données : celui de la base. Il contient aussi le registre des profils et, dans
     * {@code profils/}, la base de chacun d'eux.
     */
    public static Path dataDirectory() {
        return databaseFile().getParent();
    }

    /** Vrai pour un chemin UNC Windows ({@code \\serveur\partage\…}). */
    static boolean isNetworkShare(String path) {
        return path.startsWith("\\\\");
    }
}
