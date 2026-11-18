package io.github.pierre3119.cadesk.importer;

import io.github.pierre3119.cadesk.db.AccountRepository;
import io.github.pierre3119.cadesk.db.Database;
import io.github.pierre3119.cadesk.db.ImportRepository;
import io.github.pierre3119.cadesk.db.OperationRepository;
import io.github.pierre3119.cadesk.domain.Account;
import io.github.pierre3119.cadesk.domain.ImportRecord;
import io.github.pierre3119.cadesk.domain.Operation;
import io.github.pierre3119.cadesk.domain.Statement;
import io.github.pierre3119.cadesk.domain.StatementLine;
import io.github.pierre3119.cadesk.statement.StatementFormatException;
import io.github.pierre3119.cadesk.statement.StatementReader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Importe un relevé, CSV ou OFX, dans la base : lecture, rapprochement avec l'existant, écriture.
 * Tout l'import tient dans une transaction : un fichier est pris en compte entièrement ou pas du tout.
 */
public final class ImportService {

    /**
     * @param previousImportOfSameFile date à laquelle ce même fichier avait déjà été importé, le cas échéant
     */
    public record Outcome(Account account, ImportRecord record, Optional<LocalDateTime> previousImportOfSameFile) {
    }

    private final Database database;
    private final AccountRepository accounts = new AccountRepository();
    private final OperationRepository operations = new OperationRepository();
    private final ImportRepository imports = new ImportRepository();

    public ImportService(Database database) {
        this.database = database;
    }

    /**
     * @throws StatementFormatException si le fichier n'est pas un relevé exploitable
     * @throws UncheckedIOException     si le fichier ne peut pas être lu
     */
    public Outcome importFile(Path file) {
        byte[] content;
        try {
            content = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Lecture impossible : " + file, e);
        }
        Statement statement = StatementReader.read(content);
        List<StatementLine> lines = statement.lines();
        if (lines.isEmpty()) {
            throw new StatementFormatException("Le relevé ne contient aucune opération.");
        }
        LocalDate from = lines.stream().map(StatementLine::date).min(LocalDate::compareTo).orElseThrow();
        LocalDate to = lines.stream().map(StatementLine::date).max(LocalDate::compareTo).orElseThrow();
        String sha256 = sha256(content);
        String fileName = String.valueOf(file.getFileName());

        return database.inTransaction(c -> {
            Account account = accounts.findOrCreate(c, statement.accountNumber(), statement.accountLabel(), statement.holder());
            Optional<LocalDateTime> previous = imports.firstImportOf(c, account.id(), sha256);
            List<Operation> existing = operations.findBetween(c, account.id(), from, to);
            Reconciler.Plan plan = Reconciler.plan(lines, existing);

            ImportRecord record = imports.insert(c, new ImportRecord(0, account.id(), fileName,
                    LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS),
                    statement.periodStart() != null ? statement.periodStart() : from,
                    statement.periodEnd() != null ? statement.periodEnd() : to,
                    statement.balanceCents(), statement.balanceDate(),
                    lines.size(), plan.toInsert().size(), plan.known(), plan.toUpdate().size()), sha256);
            operations.insertAll(c, account.id(), record.id(), plan.toInsert());
            operations.updateAll(c, record.id(), plan.toUpdate());
            return new Outcome(account, record, previous);
        });
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
