package io.github.pierre3119.cadesk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * L'application lancée comme le font start.sh et start.bat : son module, sur les bibliothèques de la
 * distribution, avec les mêmes options.
 *
 * <p>Les autres tests tournent dans une JVM lancée hors module : elle dispose d'office de modules du JDK
 * (jdk.unsupported…) et de bibliothèques (Commons Logging…) que le lanceur, lui, ne charge que si
 * l'application les déclare dans {@code module-info.java}. Une déclaration oubliée ne s'y voit donc pas ;
 * ici, elle fait échouer {@link LauncherCheck} ou lui fait journaliser une erreur.
 */
class LauncherTest {

    /** Les options que chaque lanceur donne à Java : celles avec lesquelles l'application est vérifiée ici. */
    private static final String ADDED_MODULE = "copilot.sdk.runtime";
    private static final String NATIVE_ACCESS = "--enable-native-access=javafx.graphics,org.xerial.sqlitejdbc";

    @TempDir
    Path dir;

    /** Les lanceurs du projet et ceux du dossier autonome : quatre fichiers qui doivent dire la même chose. */
    @Test
    void everyLauncherStartsTheApplicationWithTheOptionsCheckedHere() throws Exception {
        for (String launcher : List.of("start.sh", "start.bat", "scripts/standalone/start.sh", "scripts/standalone/start.bat")) {
            assertThat(Files.readString(Path.of(launcher))).as(launcher)
                    .contains("--add-modules " + ADDED_MODULE, NATIVE_ACCESS, "-Dcadesk.home=",
                            "-m cadesk/io.github.pierre3119.cadesk.ui.Main");
        }
    }

    @Test
    void theApplicationWorksWithTheModulesTheLauncherGivesIt() throws Exception {
        Path lib = Path.of(System.getProperty("cadesk.app.lib", "target/app/lib"));
        Path nativeLib = lib.resolve(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "windows" : "linux");
        assumeTrue(Files.isDirectory(nativeLib), "bibliothèques de la distribution absentes (" + lib + ") : lancez les tests par Maven");
        Path classes = location(AppConfig.class);
        Path testClasses = location(LauncherCheck.class);

        // les options de start.sh et de start.bat ; la classe de vérification est greffée dans le module
        List<String> command = List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--module-path", classes + File.pathSeparator + lib + File.pathSeparator + nativeLib,
                "--add-modules", ADDED_MODULE,
                NATIVE_ACCESS,
                "--patch-module", "cadesk=" + testClasses,
                "-m", "cadesk/" + LauncherCheck.class.getName(), dir.toString());
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(2, TimeUnit.MINUTES)).as("fin de la vérification").isTrue();

        assertThat(output).as("sortie de l'application lancée comme par start.sh").contains("IMPORT : ", "RAPPORT : %PDF, complet",
                "CONNEXION : octocat, jeton remis");
        assertThat(process.exitValue()).as("code de sortie ; sortie : " + output).isZero();
    }

    /** Le dossier d'où vient une classe : celui des classes de l'application, ou celui des classes de test. */
    private static Path location(Class<?> type) throws Exception {
        String resource = type.getName().replace('.', '/') + ".class";
        String url = type.getClassLoader().getResource(resource).toString();
        return Path.of(new java.net.URI(url.substring(0, url.length() - resource.length())));
    }
}
