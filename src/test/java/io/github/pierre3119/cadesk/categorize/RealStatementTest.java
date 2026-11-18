package io.github.pierre3119.cadesk.categorize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.pierre3119.cadesk.analysis.Analysis;
import io.github.pierre3119.cadesk.analysis.Period;
import io.github.pierre3119.cadesk.db.CategoryRepository;
import io.github.pierre3119.cadesk.db.Database;
import io.github.pierre3119.cadesk.db.OperationRepository;
import io.github.pierre3119.cadesk.db.RuleRepository;
import io.github.pierre3119.cadesk.domain.Category;
import io.github.pierre3119.cadesk.domain.ClassifiedOperation;
import io.github.pierre3119.cadesk.domain.Money;
import io.github.pierre3119.cadesk.domain.Nature;
import io.github.pierre3119.cadesk.domain.Operation;
import io.github.pierre3119.cadesk.importer.ImportService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Contrôle facultatif du classement sur un vrai relevé : {@code -Dcadesk.sample.csv=/chemin/releve.csv}.
 * Ignoré si la propriété est absente. Les totaux sont affichés, rien n'est conservé dans le projet ;
 * {@code -Dcadesk.dump=/chemin/fichier.tsv} écrit en plus le classement de chaque opération, pour le comparer
 * à une autre source.
 */
class RealStatementTest {

    @TempDir
    Path dir;

    @Test
    void aRealStatementIsClassifiedAndTheBalanceSheetAddsUp() throws IOException {
        String sample = System.getProperty("cadesk.sample.csv", "");
        assumeTrue(!sample.isBlank() && Files.isRegularFile(Path.of(sample)), "pas de relevé réel fourni");

        try (Database db = new Database(dir.resolve("reel.db"))) {
            long account = new ImportService(db).importFile(Path.of(sample)).account().id();
            List<Operation> operations = db.inTransaction(c -> new OperationRepository().findByAccount(c, account));
            List<Category> categories = db.inTransaction(new CategoryRepository()::findAll);
            List<ClassifiedOperation> classified = new Classifier(categories, db.inTransaction(new RuleRepository()::findAll))
                    .classify(operations);

            Period period = Period.choices(operations.stream().map(Operation::date).toList()).getFirst();
            Analysis a = new Analysis(categories, classified.stream().filter(o -> period.contains(o.operation().date())).toList());
            List<ClassifiedOperation> pending = a.operations().stream().filter(ClassifiedOperation::isUnclassified).toList();
            System.out.printf("CLASSEMENT REEL (%s, %d mois, %d opérations) : revenus %s ; fixes %s ; variables %s ; "
                            + "exceptionnelles %s ; interne reçu %s, versé %s ; résultat %s ; solde %s ; à classer %d (%s)%n",
                    period.label(), a.monthCount(), a.operations().size(), Money.format(a.revenus()), Money.format(a.fixes()),
                    Money.format(a.variables()), Money.format(a.exceptionnelles()), Money.format(a.interneIn()),
                    Money.format(a.interneOut()), Money.formatSigned(a.resultat()), Money.formatSigned(a.solde()),
                    pending.size(), Money.format(pending.stream().mapToLong(ClassifiedOperation::amount).sum()));

            assertThat(classified).hasSameSizeAs(operations);
            assertThat(a.control()).as("le bilan retombe sur la somme des opérations").isZero();
            assertThat(a.total(Nature.REVENUS) + a.interneIn()).as("entrées = crédits")
                    .isEqualTo(a.operations().stream().mapToLong(o -> o.operation().amountCents()).filter(v -> v > 0).sum());

            String dump = System.getProperty("cadesk.dump", "");
            if (!dump.isBlank()) {
                Files.writeString(Path.of(dump), classified.stream()
                        .map(o -> String.join("\t", o.operation().date().toString(), String.valueOf(o.operation().amountCents()),
                                o.operation().title(), o.category().name(), o.poste()))
                        .sorted().collect(Collectors.joining("\n", "", "\n")));
            }
        }
    }
}
