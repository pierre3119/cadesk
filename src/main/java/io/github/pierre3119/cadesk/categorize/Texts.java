package io.github.pierre3119.cadesk.categorize;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Normalisation des libellés et des mots-clés pour le classement. */
public final class Texts {

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern SPACES = Pattern.compile("[\\s\\u00a0]+");

    private Texts() {
    }

    /** Majuscules sans accents, espaces réduits : la forme sous laquelle libellés et mots-clés sont comparés. */
    public static String fold(String s) {
        if (s == null) {
            return "";
        }
        String plain = MARKS.matcher(Normalizer.normalize(s, Normalizer.Form.NFKD)).replaceAll("");
        return SPACES.matcher(plain).replaceAll(" ").toUpperCase(Locale.ROOT).strip();
    }

    /** « DUPONT JEAN-MARC » devient « Dupont Jean-Marc ». */
    public static String titleCase(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        boolean inWord = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            boolean letter = Character.isLetter(ch);
            sb.append(!letter ? ch : inWord ? Character.toLowerCase(ch) : Character.toUpperCase(ch));
            inWord = letter;
        }
        return sb.toString();
    }

    /**
     * Lit des mots-clés saisis à raison d'un par ligne. Ils sont mis en majuscules sans accents ; des
     * guillemets conservent une espace en début ou en fin (« "DR " » ne reconnaît pas « DRIVE »).
     */
    public static List<String> parseKeywords(String text) {
        Set<String> keywords = new LinkedHashSet<>();
        for (String line : text == null ? List.<String>of() : text.lines().toList()) {
            String raw = line.strip();
            if (raw.length() >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
                raw = raw.substring(1, raw.length() - 1);
            }
            String keyword = normalizeKeyword(raw);
            if (!keyword.isEmpty()) {
                keywords.add(keyword);
            }
        }
        return new ArrayList<>(keywords);
    }

    /** Forme affichée d'un mot-clé : entre guillemets s'il commence ou finit par une espace. */
    public static String displayKeyword(String keyword) {
        return keyword.startsWith(" ") || keyword.endsWith(" ") ? "\"" + keyword + "\"" : keyword;
    }

    /** Forme comparée d'un mot-clé, en conservant au plus une espace à chaque extrémité. */
    static String normalizeKeyword(String raw) {
        String core = fold(raw);
        if (core.isEmpty()) {
            return "";
        }
        boolean lead = !raw.isEmpty() && Character.isWhitespace(raw.charAt(0));
        boolean trail = !raw.isEmpty() && Character.isWhitespace(raw.charAt(raw.length() - 1));
        return (lead ? " " : "") + core + (trail ? " " : "");
    }
}
