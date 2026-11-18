package io.github.pierre3119.cadesk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppConfigTest {

    @AfterEach
    void clearProperties() {
        System.clearProperty("cadesk.home");
        System.clearProperty("cadesk.db");
    }

    @Test
    void theDatabaseLivesInTheDataFolderOfTheApplication(@TempDir Path dir) {
        assumeTrue(System.getenv("CADESK_DB") == null, "CADESK_DB est défini dans cet environnement");
        System.setProperty("cadesk.home", dir.toString());

        assertThat(AppConfig.databaseFile()).isEqualTo(dir.toAbsolutePath().normalize().resolve("data").resolve("comptes.db"));
    }

    @Test
    void anExplicitDatabasePathWins(@TempDir Path dir) {
        System.setProperty("cadesk.home", dir.toString());
        System.setProperty("cadesk.db", dir.resolve("ailleurs.db").toString());

        assertThat(AppConfig.databaseFile()).isEqualTo(dir.toAbsolutePath().normalize().resolve("ailleurs.db"));
    }

    @Test
    void onlyWindowsNetworkSharesAreTreatedAsUnableToHostTheDatabase() {
        assertThat(AppConfig.isNetworkShare("\\\\wsl.localhost\\Ubuntu-22.04\\home\\jean\\cadesk")).isTrue();
        assertThat(AppConfig.isNetworkShare("\\\\serveur\\partage\\cadesk")).isTrue();
        assertThat(AppConfig.isNetworkShare("C:\\Users\\jean\\cadesk")).isFalse();
        assertThat(AppConfig.isNetworkShare("/home/jean/cadesk")).isFalse();
    }

    @Test
    void dataLeftByTheApplicationUnderItsFormerNameStayWhereTheyAre(@TempDir Path local) throws Exception {
        assertThat(AppConfig.localFolder(local)).isEqualTo(local.resolve("CaDesk"));

        Files.createDirectory(local.resolve("ComptesCA"));
        assertThat(AppConfig.localFolder(local)).as("seul l'ancien dossier existe").isEqualTo(local.resolve("ComptesCA"));

        Files.createDirectory(local.resolve("CaDesk"));
        assertThat(AppConfig.localFolder(local)).as("le nouveau dossier existe").isEqualTo(local.resolve("CaDesk"));
    }
}
