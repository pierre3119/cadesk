package io.github.pierre3119.cadesk.categorize;

import io.github.pierre3119.cadesk.domain.Category;
import io.github.pierre3119.cadesk.domain.ClassifiedOperation;
import io.github.pierre3119.cadesk.domain.ClassifiedOperation.Source;
import io.github.pierre3119.cadesk.domain.Operation;
import io.github.pierre3119.cadesk.domain.Rule;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Classe les opérations d'après les catégories et les règles enregistrées en base.
 *
 * <p>Pour chaque opération, dans l'ordre : la catégorie enregistrée avec elle (choisie à la main, ou
 * attribuée par l'assistant) ; sinon la première règle qui la reconnaît ; sinon la catégorie par défaut
 * de son sens (« à classer »). Le classement par les règles n'est pas enregistré : il est recalculé à
 * chaque chargement, de sorte qu'une règle modifiée s'applique aussitôt à tout l'historique.
 *
 * <p>Les mots-clés sont cherchés dans le libellé mis en majuscules sans accents. Pour un paiement par
 * carte, ce texte est réduit au nom du commerçant (« X1234 BOULANGERIE DUPAIN 26/02 - Boulangerie
 * Dupain » devient « BOULANGERIE DUPAIN | BOULANGERIE DUPAIN ») ; il se termine toujours par une espace,
 * ce qui permet à un mot-clé comme « DR␣ » de n'accepter que le mot entier.
 */
public final class Classifier {

    public static final String CARD_PAYMENT = "Paiement par carte";

    private static final String FOLDED_CARD_PAYMENT = Texts.fold(CARD_PAYMENT);
    private static final Pattern CARD_LABEL =
            Pattern.compile("^X\\d{4}\\s+(.*?)\\s+\\d{2}/\\d{2}(?:\\s+\\d{2}H\\d{2})?(?:\\s+-\\s+(.*))?$");
    private static final Pattern AND = Pattern.compile("\\s\\+\\s");
    /** Ce qui précède le nom du tiers dans un libellé de virement. */
    private static final Pattern TRANSFER_PREFIX = Pattern.compile(
            "^(?:WEB )?(?:VIR INST )?(?:WERO )?(?:(?:DE|VERS) )?(?:M\\.OU MME |MADAME |MONSIEUR |MME |MR |M\\. )?",
            Pattern.CASE_INSENSITIVE);
    private static final Set<String> ACRONYMS = Set.of("SAS", "FDJ", "CGR", "DR");
    private static final int MAX_POSTE = 60;

    /** Nom du commerçant d'un paiement par carte : tel qu'abrégé sur le ticket, et tel qu'enrichi par la banque. */
    private record Card(String raw, String name) {
    }

    private record Compiled(Rule rule, Category category, String type, List<String[]> alternatives) {

        boolean matches(String foldedType, String text) {
            if (type != null && !type.equals(foldedType)) {
                return false;
            }
            if (alternatives.isEmpty()) {
                return true;
            }
            for (String[] fragments : alternatives) {
                boolean all = true;
                for (String fragment : fragments) {
                    all &= text.contains(fragment);
                }
                if (all) {
                    return true;
                }
            }
            return false;
        }
    }

    private final Map<Long, Category> categories = new HashMap<>();
    private final Category defaultOut;
    private final Category defaultIn;
    private final List<Compiled> rules = new ArrayList<>();

    /**
     * @throws IllegalArgumentException si l'une des deux catégories par défaut manque
     */
    public Classifier(Collection<Category> categories, Collection<Rule> rules) {
        categories.forEach(c -> this.categories.put(c.id(), c));
        defaultOut = fallback(categories, Category.Role.DEFAULT_OUT);
        defaultIn = fallback(categories, Category.Role.DEFAULT_IN);
        rules.stream().sorted(Comparator.comparingInt(Rule::position).thenComparingLong(Rule::id)).forEach(r -> {
            Category category = this.categories.get(r.categoryId());
            if (category != null) {
                this.rules.add(compile(r, category));
            }
        });
    }

    private static Category fallback(Collection<Category> categories, Category.Role role) {
        return categories.stream().filter(c -> c.role() == role).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Catégorie par défaut absente : " + role));
    }

    private static Compiled compile(Rule rule, Category category) {
        List<String[]> alternatives = new ArrayList<>();
        for (String keyword : rule.keywords()) {
            String[] fragments = AND.split(keyword);
            if (fragments.length > 1) {
                for (int i = 0; i < fragments.length; i++) {
                    fragments[i] = fragments[i].strip();
                }
            }
            alternatives.add(fragments);
        }
        return new Compiled(rule, category, rule.operationType() == null ? null : Texts.fold(rule.operationType()),
                alternatives);
    }

    /** Classe les opérations ; l'ordre de la liste est conservé. */
    public List<ClassifiedOperation> classify(List<Operation> operations) {
        // nom lisible d'un commerçant : celui que la banque ajoute au libellé, dès qu'une opération le donne
        Map<String, String> merchantNames = new HashMap<>();
        Map<Long, Card> cards = new HashMap<>();
        for (Operation o : operations) {
            Card card = card(o);
            if (card != null) {
                cards.put(o.id(), card);
                if (card.name() != null) {
                    merchantNames.putIfAbsent(card.raw(), card.name());
                }
            }
        }
        List<ClassifiedOperation> result = new ArrayList<>(operations.size());
        for (Operation o : operations) {
            Card card = cards.get(o.id());
            String text = matchText(o, card);
            String type = Texts.fold(o.type());
            Compiled hit = null;
            for (Compiled rule : rules) {
                if (rule.matches(type, text)) {
                    hit = rule;
                    break;
                }
            }
            String own = card != null
                    ? clean(merchantNames.getOrDefault(card.raw(), pretty(card.raw())))
                    : counterparty(o);
            Category manual = o.manualCategoryId() == null ? null : categories.get(o.manualCategoryId());
            if (manual != null) {
                boolean sameCategory = hit != null && hit.category().id() == manual.id() && hit.rule().poste() != null;
                result.add(new ClassifiedOperation(o, manual, sameCategory ? hit.rule().poste() : own,
                        o.assisted() ? Source.ASSISTANT : Source.MANUAL, null));
            } else if (hit != null) {
                String poste = hit.rule().poste() != null ? hit.rule().poste() : own;
                result.add(new ClassifiedOperation(o, hit.category(), poste, Source.RULE, hit.rule()));
            } else {
                result.add(new ClassifiedOperation(o, o.isCredit() ? defaultIn : defaultOut, own, Source.DEFAULT, null));
            }
        }
        return result;
    }

    /** Vrai pour les opérations que cette règle reconnaîtrait si elle était seule. */
    public static Predicate<Operation> matcher(Rule rule) {
        Compiled compiled = compile(rule, null);
        return o -> compiled.matches(Texts.fold(o.type()), matchText(o));
    }

    /** Texte dans lequel les mots-clés des règles sont cherchés. */
    public static String matchText(Operation o) {
        return matchText(o, card(o));
    }

    /** Mot-clé proposé pour créer une règle à partir d'une opération : le commerçant ou le tiers. */
    public static String suggestKeyword(Operation o) {
        Card card = card(o);
        if (card != null) {
            return Texts.fold(card.raw());
        }
        List<String> lines = o.lines();
        return lines.isEmpty() ? "" : Texts.fold(stripPrefix(lines.getFirst().split(" - ")[0]));
    }

    private static String matchText(Operation o, Card card) {
        String source = card != null ? card.raw() + " | " + (card.name() == null ? "" : card.name())
                : String.join(" | ", o.lines());
        return Texts.fold(source) + " ";
    }

    private static Card card(Operation o) {
        List<String> lines = o.lines();
        if (lines.isEmpty() || !FOLDED_CARD_PAYMENT.equals(Texts.fold(o.type()))) {
            return null;
        }
        Matcher m = CARD_LABEL.matcher(lines.getFirst());
        if (!m.matches()) {
            return new Card(lines.getFirst(), null);
        }
        String name = m.group(2);
        return new Card(m.group(1).strip(), name == null || name.isBlank() ? null : name.strip());
    }

    /** « BOULANGERIE DUPAIN SAS » devient « Boulangerie Dupain SAS ». */
    private static String pretty(String raw) {
        StringBuilder sb = new StringBuilder();
        for (String word : raw.split(" ")) {
            if (word.isEmpty()) {
                continue;
            }
            sb.append(sb.isEmpty() ? "" : " ").append(ACRONYMS.contains(word) ? word
                    : word.substring(0, 1).toUpperCase(Locale.ROOT) + word.substring(1).toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }

    /** Nom du tiers d'une opération qui n'est pas un paiement par carte, d'après la première ligne du libellé. */
    private static String counterparty(Operation o) {
        List<String> lines = o.lines();
        if (lines.isEmpty()) {
            return o.type();
        }
        String first = lines.getFirst();
        if (Texts.fold(o.type()).startsWith("CHEQUE EMIS")) {
            return "Chèque émis n° " + first;
        }
        String name = clean(Texts.titleCase(stripPrefix(first.split(" - ")[0])));
        return name.length() <= MAX_POSTE ? name : name.substring(0, MAX_POSTE).strip() + "…";
    }

    private static String stripPrefix(String head) {
        String stripped = TRANSFER_PREFIX.matcher(head).replaceFirst("").strip();
        return stripped.isEmpty() ? head.strip() : stripped;
    }

    /** L'apostrophe arrive parfois de la banque sous la forme « &£39; » ; « * » et « ? » n'apportent rien à un nom. */
    private static String clean(String name) {
        return name.replace("&£39;", "'").replace('*', ' ').replace("?", "").replaceAll("\\s+", " ").strip();
    }
}
