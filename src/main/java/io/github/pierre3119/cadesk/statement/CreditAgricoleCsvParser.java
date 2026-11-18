package io.github.pierre3119.cadesk.statement;

import io.github.pierre3119.cadesk.domain.Money;
import io.github.pierre3119.cadesk.domain.Statement;
import io.github.pierre3119.cadesk.domain.StatementLine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lit l'export CSV « Liste des opérations » du Crédit Agricole.
 *
 * <p>Le fichier commence par un en-tête libre (date de téléchargement, titulaire, compte, solde, période),
 * suivi d'un tableau {@code Date d'opération;Date valeur;Libellé;Débit euros;Crédit euros;} dont le libellé
 * s'étend sur plusieurs lignes entre guillemets. Il est encodé en Windows-1252.
 */
public final class CreditAgricoleCsvParser {

    // STRICT : une date impossible (31/02) est refusée au lieu d'être ramenée en silence au dernier jour du mois
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(ResolverStyle.STRICT);
    private static final String D = "(\\d{2}/\\d{2}/\\d{4})";

    private static final Pattern TABLE_HEADER = Pattern.compile("(?m)^Date d.op[ée]ration;Date valeur;.*$");
    private static final Pattern DOWNLOAD = Pattern.compile("T[ée]l[ée]chargement du " + D);
    private static final Pattern ACCOUNT = Pattern.compile("(?m)^(.*?)\\s+n°\\s*(\\d+)");
    private static final Pattern BALANCE = Pattern.compile("Solde au " + D + "\\s+(-?[\\d\\s\\u00a0\\u202f.,]+?)\\s*€");
    private static final Pattern PERIOD = Pattern.compile("entre le " + D + " et le " + D);

    public Statement parse(Path file) throws IOException {
        return parse(Files.readAllBytes(file));
    }

    public Statement parse(byte[] content) {
        return read(StatementReader.decode(content));
    }

    /** Vrai si ce texte a la ligne de titres du tableau des opérations. */
    static boolean accepts(String text) {
        return TABLE_HEADER.matcher(text).find();
    }

    static Statement read(String text) {
        Matcher table = TABLE_HEADER.matcher(text);
        if (!table.find()) {
            throw new StatementFormatException("Ce fichier n'est pas un relevé CSV du Crédit Agricole : "
                    + "la ligne de titres « Date d'opération;Date valeur;Libellé;… » est introuvable.");
        }
        String header = text.substring(0, table.start());
        int bodyStart = Math.min(text.length(), table.end() + 1);
        int firstBodyLine = (int) text.substring(0, bodyStart).chars().filter(c -> c == '\n').count() + 1;

        Matcher account = ACCOUNT.matcher(header);
        if (!account.find()) {
            throw new StatementFormatException("Numéro de compte introuvable dans l'en-tête du relevé.");
        }
        Matcher download = DOWNLOAD.matcher(header);
        Matcher balance = BALANCE.matcher(header);
        Matcher period = PERIOD.matcher(header);
        boolean hasBalance = balance.find();
        boolean hasPeriod = period.find();

        List<StatementLine> lines = new ArrayList<>();
        for (CsvReader.Row row : CsvReader.read(text.substring(bodyStart), ';', firstBodyLine)) {
            if (!row.isBlank()) {
                lines.add(toLine(row));
            }
        }
        return new Statement(
                account.group(2),
                squeeze(account.group(1)),
                holderBefore(header, account.start()),
                download.find() ? date(download.group(1), 0) : null,
                hasPeriod ? date(period.group(1), 0) : null,
                hasPeriod ? date(period.group(2), 0) : null,
                hasBalance ? Money.parseCents(balance.group(2)) : null,
                hasBalance ? date(balance.group(1), 0) : null,
                lines);
    }

    private static StatementLine toLine(CsvReader.Row row) {
        List<String> f = row.fields();
        if (f.size() < 5) {
            throw new StatementFormatException("Ligne " + row.line() + " : 5 colonnes attendues, " + f.size() + " trouvée(s).");
        }
        List<String> parts = f.get(2).lines().map(CreditAgricoleCsvParser::squeeze).filter(s -> !s.isEmpty()).toList();
        if (parts.isEmpty()) {
            throw new StatementFormatException("Ligne " + row.line() + " : libellé vide.");
        }
        String debit = f.get(3).strip();
        String credit = f.get(4).strip();
        if (debit.isEmpty() == credit.isEmpty()) {
            throw new StatementFormatException("Ligne " + row.line()
                    + " : une opération doit avoir soit un débit, soit un crédit.");
        }
        long cents;
        try {
            cents = debit.isEmpty() ? Money.parseCents(credit) : -Money.parseCents(debit);
        } catch (NumberFormatException e) {
            throw new StatementFormatException("Ligne " + row.line() + " : montant illisible (« "
                    + (debit.isEmpty() ? credit : debit) + " »).", e);
        }
        return new StatementLine(date(f.get(0), row.line()), date(f.get(1), row.line()),
                parts.getFirst(), String.join("\n", parts.subList(1, parts.size())), cents);
    }

    /** Le titulaire est la dernière ligne non vide qui précède celle du compte. */
    private static String holderBefore(String header, int accountLineStart) {
        return header.substring(0, accountLineStart).lines()
                .map(CreditAgricoleCsvParser::squeeze)
                .filter(s -> !s.isEmpty() && !DOWNLOAD.matcher(s).find())
                .reduce((_, last) -> last)
                .orElse(null);
    }

    private static LocalDate date(String text, int line) {
        try {
            return LocalDate.parse(text.strip(), DATE);
        } catch (DateTimeParseException e) {
            throw new StatementFormatException((line > 0 ? "Ligne " + line + " : " : "") + "date illisible (« " + text + " »).", e);
        }
    }

    private static String squeeze(String s) {
        return StatementReader.squeeze(s);
    }
}
