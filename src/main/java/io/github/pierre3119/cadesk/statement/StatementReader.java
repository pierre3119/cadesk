package io.github.pierre3119.cadesk.statement;

import io.github.pierre3119.cadesk.domain.Statement;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Lit un relevé téléchargé depuis le Crédit Agricole, quel que soit son format : CSV (« Liste des
 * opérations ») ou OFX. Le format est reconnu au contenu du fichier, pas à son extension.
 */
public final class StatementReader {

    /** Extensions des fichiers que la banque propose au téléchargement et que l'application sait lire. */
    public static final List<String> EXTENSIONS = List.of("csv", "ofx");

    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");
    private static final Pattern SPACES = Pattern.compile("[\\s\\u00a0]+");

    private StatementReader() {
    }

    /** Vrai pour un nom de fichier qui a l'extension d'un relevé ; le format, lui, est reconnu au contenu. */
    public static boolean hasStatementExtension(String fileName) {
        String name = fileName.toLowerCase(Locale.ROOT);
        return EXTENSIONS.stream().anyMatch(extension -> name.endsWith("." + extension));
    }

    /**
     * @throws StatementFormatException si le contenu n'est pas un relevé exploitable
     */
    public static Statement read(byte[] content) {
        String text = decode(content);
        if (CreditAgricoleOfxParser.accepts(text)) {
            return CreditAgricoleOfxParser.read(text);
        }
        if (!CreditAgricoleCsvParser.accepts(text)) {
            throw new StatementFormatException("Ce fichier n'est pas un relevé du Crédit Agricole : ni la ligne de titres "
                    + "« Date d'opération;Date valeur;Libellé;… » d'un relevé CSV, ni la balise <OFX> d'un relevé OFX n'y figurent.");
        }
        return CreditAgricoleCsvParser.read(text);
    }

    /** UTF-8 si le contenu en est strictement, sinon Windows-1252 (l'encodage des exports de la banque). */
    static String decode(byte[] content) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content)).toString();
        } catch (CharacterCodingException e) {
            text = new String(content, WINDOWS_1252);
        }
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
    }

    /** Ramène toute suite d'espaces à une seule, et retire celles des extrémités. */
    static String squeeze(String s) {
        return SPACES.matcher(s).replaceAll(" ").strip();
    }
}
