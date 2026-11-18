package io.github.pierre3119.cadesk.statement;

import io.github.pierre3119.cadesk.domain.Money;
import io.github.pierre3119.cadesk.domain.Statement;
import io.github.pierre3119.cadesk.domain.StatementLine;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lit l'export OFX du Crédit Agricole : OFX 1, dont les valeurs ne sont pas refermées par une balise.
 * La forme XML d'OFX 2, où elles le sont, est lue de la même façon.
 *
 * <p>Le fichier décrit un compte ({@code BANKACCTFROM}), son solde ({@code LEDGERBAL}) et ses opérations
 * ({@code STMTTRN}), de la plus récente à la plus ancienne. Une opération n'y est que résumée : sa date
 * ({@code DTPOSTED}), son montant signé ({@code TRNAMT}) et deux textes — {@code NAME}, le tiers, coupé à
 * 32 caractères, et {@code MEMO}, où se suivent le type de l'opération, le tiers et le motif. Il n'y a ni
 * date de valeur, ni nom du commerçant, ni références : le libellé reconstitué ici est la première ligne
 * de celui que donne un relevé CSV, ou son début.
 *
 * <p>Le fichier est lu comme une suite d'événements — balise ouverte, balise fermée, texte — sans supposer
 * que tout ce qui est ouvert sera refermé.
 */
public final class CreditAgricoleOfxParser {

    /** Type d'une opération dont le texte n'a pas la forme attendue : le relevé ne dit pas ce qu'elle est. */
    static final String UNKNOWN_TYPE = "Opération";
    /** Longueur à laquelle le format coupe {@code NAME}. */
    private static final int NAME_LENGTH = 32;
    private static final String SEPARATOR = " - ";

    private static final Pattern OFX = Pattern.compile("(?i)<OFX>");
    /** Une balise ouvrante, fermante ou vide, ou le texte qui sépare deux balises. */
    private static final Pattern TOKEN = Pattern.compile("<(/?)([A-Za-z0-9_.]+)\\s*(/?)>|([^<]+)");
    /** Les blocs dont les valeurs sont lues hors des opérations : une même balise (date, montant) figure dans plusieurs. */
    private static final Set<String> SECTIONS = Set.of("SONRS", "BANKACCTFROM", "BANKTRANLIST", "LEDGERBAL", "AVAILBAL");
    private static final String STATEMENT = "STMTRS";
    private static final String TRANSACTION = "STMTTRN";

    private static final Pattern DAY = Pattern.compile("^\\s*(\\d{8})");
    // STRICT : une date impossible (31/02) est refusée au lieu d'être ramenée en silence au dernier jour du mois
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT);

    public Statement parse(byte[] content) {
        return read(StatementReader.decode(content));
    }

    /** Vrai si ce texte est balisé comme un fichier OFX. */
    static boolean accepts(String text) {
        return OFX.matcher(text).find();
    }

    static Statement read(String text) {
        Reading reading = new Reading();
        Matcher token = TOKEN.matcher(text);
        while (token.find()) {
            if (token.group(4) != null) {
                reading.text(token.group(4));
            } else if (!token.group(1).isEmpty()) {
                reading.close(token.group(2).toUpperCase(Locale.ROOT));
            } else if (token.group(3).isEmpty()) {
                reading.open(token.group(2).toUpperCase(Locale.ROOT));
            }
        }
        return reading.statement();
    }

    /** Ce que la lecture a relevé : les valeurs de l'en-tête, rangées par bloc, et celles de chaque opération. */
    private static final class Reading {

        /** Balises ouvertes et pas encore refermées, la plus récente en tête. */
        private final Deque<String> open = new ArrayDeque<>();
        private final Map<String, String> header = new HashMap<>();
        private final List<Map<String, String>> transactions = new ArrayList<>();
        private Map<String, String> transaction;
        private int statements;

        void open(String tag) {
            if (tag.equals(STATEMENT)) {
                statements++;
            } else if (tag.equals(TRANSACTION)) {
                transaction = new HashMap<>();
                transactions.add(transaction);
            }
            open.push(tag);
        }

        /** Refermer une balise referme aussi celles, sans fin explicite, qui ont été ouvertes après elle. */
        void close(String tag) {
            if (open.contains(tag)) {
                String closed;
                do {
                    closed = open.pop();
                } while (!closed.equals(tag));
            }
            if (!open.contains(TRANSACTION)) {
                transaction = null;
            }
        }

        /** Un texte est la valeur de la dernière balise ouverte, qu'il referme : en OFX 1, rien d'autre ne le fait. */
        void text(String text) {
            if (text.isBlank() || open.isEmpty()) {
                return;
            }
            String tag = open.pop();
            String value = unescape(firstLine(text));
            if (transaction != null) {
                transaction.putIfAbsent(tag, value);
            } else {
                String section = open.stream().filter(SECTIONS::contains).findFirst().orElse("");
                header.putIfAbsent(section + "/" + tag, value);
            }
        }

        Statement statement() {
            if (statements == 0) {
                throw new StatementFormatException("Ce fichier OFX ne contient pas le relevé d'un compte bancaire.");
            }
            if (statements > 1) {
                throw new StatementFormatException("Ce fichier OFX réunit les relevés de " + statements
                        + " comptes : téléchargez un fichier par compte.");
            }
            String number = StatementReader.squeeze(header("BANKACCTFROM/ACCTID")).replace(" ", "");
            if (number.isEmpty()) {
                throw new StatementFormatException("Numéro de compte introuvable dans le fichier OFX.");
            }
            List<StatementLine> lines = new ArrayList<>();
            for (int i = 0; i < transactions.size(); i++) {
                StatementLine line = toLine(transactions.get(i), "Opération n° " + (i + 1));
                if (line != null) {
                    lines.add(line);
                }
            }
            boolean hasBalance = !header("LEDGERBAL/BALAMT").isBlank() && !header("LEDGERBAL/DTASOF").isBlank();
            return new Statement(
                    number,
                    accountLabel(header("BANKACCTFROM/ACCTTYPE")),
                    null,
                    optionalDate(header("SONRS/DTSERVER"), "Date du fichier"),
                    optionalDate(header("BANKTRANLIST/DTSTART"), "Début de la période"),
                    optionalDate(header("BANKTRANLIST/DTEND"), "Fin de la période"),
                    hasBalance ? amount(header("LEDGERBAL/BALAMT"), "Solde") : null,
                    hasBalance ? date(header("LEDGERBAL/DTASOF"), "Solde") : null,
                    lines);
        }

        private String header(String name) {
            return header.getOrDefault(name, "");
        }
    }

    /** L'opération d'un bloc {@code STMTTRN}, ou {@code null} si son montant est nul : ce n'est pas un mouvement. */
    private static StatementLine toLine(Map<String, String> transaction, String where) {
        long cents = amount(transaction.getOrDefault("TRNAMT", ""), where);
        if (cents == 0) {
            return null;
        }
        String available = transaction.getOrDefault("DTAVAIL", "");
        Text text = typeAndLabel(transaction.getOrDefault("NAME", ""), transaction.getOrDefault("MEMO", ""));
        return new StatementLine(date(transaction.getOrDefault("DTPOSTED", ""), where),
                available.isBlank() ? null : date(available, where), text.type(), text.label(), cents);
    }

    /** Le type d'une opération et son libellé, tirés de ses deux textes. */
    record Text(String type, String label) {
    }

    /**
     * Sépare le type de l'opération de son libellé.
     *
     * <p>{@code MEMO} vaut « type tiers motif » et {@code NAME} « tiers » ou « tiers - nom court » : le
     * type est ce qui précède le tiers dans {@code MEMO}, et le libellé « NAME - motif », comme dans un
     * relevé CSV. Quand {@code NAME} occupe toute sa longueur, le tiers a pu y être coupé : la suite de
     * {@code MEMO} est alors reprise telle quelle, sans y chercher la fin du tiers.
     */
    static Text typeAndLabel(String rawName, String rawMemo) {
        String name = StatementReader.squeeze(rawName);
        String memo = StatementReader.squeeze(rawMemo);
        for (String party = name; !party.isEmpty(); party = beforeLastSeparator(party)) {
            int at = memo.indexOf(" " + party);
            if (at > 0) {
                String afterType = memo.substring(at + 1);
                String reason = afterType.substring(party.length()).strip();
                if (rawName.stripLeading().length() >= NAME_LENGTH) {
                    return new Text(memo.substring(0, at), afterType);
                }
                return new Text(memo.substring(0, at), reason.isEmpty() ? name : name + SEPARATOR + reason);
            }
        }
        // forme inattendue : rien ne dit le type, et tout le texte devient le libellé
        if (memo.isEmpty() || name.contains(memo)) {
            return new Text(UNKNOWN_TYPE, name);
        }
        return new Text(UNKNOWN_TYPE, memo.contains(name) ? memo : name + SEPARATOR + memo);
    }

    private static String beforeLastSeparator(String text) {
        int at = text.lastIndexOf(SEPARATOR);
        return at < 0 ? "" : text.substring(0, at);
    }

    private static String accountLabel(String type) {
        return switch (type.strip().toUpperCase(Locale.ROOT)) {
            case "CHECKING" -> "Compte de dépôt";
            case "SAVINGS" -> "Compte d'épargne";
            default -> "Compte";
        };
    }

    // ------------------------------------------------------------------ valeurs

    /** La valeur telle qu'elle est écrite, espaces de fin compris : la première ligne du texte qui suit la balise. */
    private static String firstLine(String text) {
        return text.lines().filter(line -> !line.isBlank()).findFirst().orElse("");
    }

    private static String unescape(String value) {
        return value.indexOf('&') < 0 ? value : value.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&apos;", "'").replace("&nbsp;", " ").replace("&amp;", "&");
    }

    private static long amount(String text, String where) {
        try {
            return Money.parseCents(text);
        } catch (NumberFormatException e) {
            throw new StatementFormatException(where + " : montant illisible (« " + text.strip() + " »).", e);
        }
    }

    private static LocalDate optionalDate(String text, String where) {
        return text.isBlank() ? null : date(text, where);
    }

    /** Lit la date d'un horodatage OFX : {@code AAAAMMJJ}, que peuvent suivre l'heure et le fuseau. */
    private static LocalDate date(String text, String where) {
        Matcher day = DAY.matcher(text);
        if (day.find()) {
            try {
                return LocalDate.parse(day.group(1), DATE);
            } catch (DateTimeParseException e) {
                throw new StatementFormatException(where + " : date illisible (« " + text.strip() + " »).", e);
            }
        }
        throw new StatementFormatException(where + " : date illisible (« " + text.strip() + " »).");
    }
}
