package io.github.pierre3119.cadesk;

import io.github.pierre3119.cadesk.analysis.Analysis;
import io.github.pierre3119.cadesk.analysis.Period;
import io.github.pierre3119.cadesk.categorize.Classifier;
import io.github.pierre3119.cadesk.db.CategoryRepository;
import io.github.pierre3119.cadesk.db.Database;
import io.github.pierre3119.cadesk.db.OperationRepository;
import io.github.pierre3119.cadesk.db.RuleRepository;
import io.github.pierre3119.cadesk.domain.Category;
import io.github.pierre3119.cadesk.importer.ImportService;
import io.github.pierre3119.cadesk.importer.ImportService.Outcome;
import io.github.pierre3119.cadesk.llm.CopilotLogin.Identity;
import io.github.pierre3119.cadesk.llm.FakeCopilot;
import io.github.pierre3119.cadesk.llm.FakeCopilotRuntime;
import io.github.pierre3119.cadesk.profile.ProfileManager;
import io.github.pierre3119.cadesk.profile.Session;
import io.github.pierre3119.cadesk.report.PdfReport;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Ce que fait l'application de bout en bout, sans son interface : un profil, l'import d'un relevé, le
 * rapport PDF, une connexion à GitHub (menée par le faux moteur de Copilot). {@link LauncherTest} le lance
 * comme start.sh lance l'application ; tout avertissement journalisé par une bibliothèque est une erreur.
 *
 * <p>Sortie : une ligne par étape réussie, et le code 0 ; sinon l'erreur, et le code 1.
 */
public final class LauncherCheck {

    private LauncherCheck() {
    }

    public static void main(String[] args) {
        List<String> complaints = new CopyOnWriteArrayList<>();
        Logger.getLogger("").addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    complaints.add(record.getLevel().getName() + " " + record.getLoggerName() + " : " + record.getMessage());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        try {
            run(Path.of(args[0]));
        } catch (Throwable e) {
            e.printStackTrace(System.out);
            System.exit(1);
        }
        if (!complaints.isEmpty()) {
            System.out.println("JOURNAL : " + complaints);
            System.exit(1);
        }
        System.exit(0);
    }

    private static void run(Path dir) throws Exception {
        ProfileManager profiles = new ProfileManager(dir.resolve("data"), null);
        Path report = dir.resolve("rapport.pdf");
        try (Session session = profiles.open(profiles.create("Essai", null, false), null)) {
            Database database = session.database();
            Outcome imported = new ImportService(database).importFile(Samples.write(dir, "releve.csv", Samples.demoStatement()));
            Analysis analysis = database.inTransaction(c -> {
                List<Category> categories = new CategoryRepository().findAll(c);
                return new Analysis(categories, new Classifier(categories, new RuleRepository().findAll(c))
                        .classify(new OperationRepository().findByAccount(c, imported.account().id())));
            });
            System.out.println("IMPORT : " + analysis.operations().size() + " opérations");
            PdfReport.write(report, new PdfReport.Content("Essai", imported.account(), Period.ALL, analysis, true, LocalDateTime.now()));
        }
        byte[] written = Files.readAllBytes(report);
        System.out.println("RAPPORT : " + new String(written, 0, 4, StandardCharsets.US_ASCII) + ", " + (written.length > 20_000 ? "complet" : "TROP COURT"));

        Path journal = dir.resolve("connexion.jsonl");
        Identity identity = FakeCopilot.login(journal, false).signIn(_ -> FakeCopilotRuntime.authorize(journal));
        System.out.println("CONNEXION : " + identity.login() + ", " + (FakeCopilotRuntime.DELIVERED.equals(identity.token()) ? "jeton remis" : "SANS JETON"));
    }
}
