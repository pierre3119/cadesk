package io.github.pierre3119.cadesk.llm;

import io.github.pierre3119.cadesk.categorize.Classifier;
import io.github.pierre3119.cadesk.categorize.Texts;
import io.github.pierre3119.cadesk.db.CategoryRepository;
import io.github.pierre3119.cadesk.db.OperationRepository;
import io.github.pierre3119.cadesk.db.RuleRepository;
import io.github.pierre3119.cadesk.domain.Category;
import io.github.pierre3119.cadesk.domain.Category.IncomeKind;
import io.github.pierre3119.cadesk.domain.ClassifiedOperation;
import io.github.pierre3119.cadesk.domain.Nature;
import io.github.pierre3119.cadesk.domain.Operation;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/**
 * Assistant de classement : propose une catégorie aux opérations qu'on lui désigne.
 *
 * <p>Les opérations sont regroupées en <em>activités</em> — même commerçant ou tiers, même type, même
 * sens — de sorte qu'une activité reçoit une seule catégorie. Pour chacune :
 * <ol>
 *   <li>si la même activité existe déjà parmi les opérations hors de la demande, avec une vraie
 *       catégorie, elle prend cette catégorie, sans interroger le modèle ;</li>
 *   <li>sinon le modèle de langage choisit parmi les catégories existantes, dont il reçoit des exemples
 *       d'activités ; il ne propose une catégorie nouvelle que si aucune ne convient.</li>
 * </ol>
 * L'assistant ne modifie rien : il rend une {@link Proposal}, que {@link #apply} enregistre.
 */
public final class Assistant {

    /** Nombre d'activités soumises au modèle par demande. */
    private static final int BATCH = 40;
    private static final int EXAMPLES_PER_CATEGORY = 8;
    private static final int MAX_LABEL = 160;
    private static final int MAX_NAME = 60;

    private static final String SYSTEM = """
            Tu classes les opérations d'un compte bancaire français dans les catégories du budget d'un particulier.

            Tu reçois un objet JSON :
            - « categories » : les catégories existantes, avec leur identifiant, leur nature et leur sens ;
            - « exemples » : pour certaines catégories, des activités (commerçants, tiers) qui y sont déjà classées ;
            - « activites » : les activités à classer. Chacune regroupe les opérations d'un même commerçant ou tiers : \
            type d'opération, libellé de la banque, sens (débit ou crédit), montant d'une opération en euros et nombre \
            d'opérations ; « categorie_actuelle » est donnée quand elles ont déjà une catégorie.

            Natures : REVENUS (salaires, remboursements, aides) ; FIXES (charges régulières : loyer ou prêt, impôts, \
            énergie, assurances, abonnements) ; VARIABLES (courses, restaurants, achats, loisirs, santé) ; \
            EXCEPTIONNELLES (travaux, grosses réparations, dépenses hors du commun) ; INTERNE (virements entre les \
            comptes du titulaire, épargne).

            Règles :
            1. Choisis en priorité une catégorie existante. Une activité semblable à un exemple va dans la même \
            catégorie que lui.
            2. Ne propose une catégorie nouvelle que si aucune catégorie existante ne convient ; donne-lui un nom court \
            en français et une nature. Réutilise exactement le même nom pour toutes les activités qui en relèvent.
            3. Un débit est une dépense ou un virement émis ; un crédit est un revenu, un remboursement ou un virement \
            reçu. Le crédit qui rembourse une dépense précise peut aller dans la catégorie de cette dépense.
            4. Si « categorie_actuelle » convient, garde-la.
            5. Si le libellé ne permet pas de décider, réponds null plutôt que de deviner.

            Réponds uniquement par un objet JSON, sans texte autour, de cette forme :
            {"classements":[{"id":1,"categorie":12},{"id":2,"nouvelle":{"nom":"Animaux","nature":"VARIABLES"}},{"id":3,"categorie":null}]}
            Chaque activité reçue doit figurer une fois dans « classements ».
            """;

    /**
     * Une activité : les opérations d'un même commerçant ou tiers, de même type et de même sens.
     *
     * @param operations ses opérations, dans l'ordre où elles ont été fournies ; jamais vide
     */
    public record Activity(String key, List<ClassifiedOperation> operations) {

        public Activity {
            operations = List.copyOf(operations);
        }

        public ClassifiedOperation sample() {
            return operations.getFirst();
        }

        public String poste() {
            return sample().poste();
        }

        public boolean credit() {
            return sample().operation().isCredit();
        }

        /** Somme des opérations, en centimes signés. */
        public long total() {
            return operations.stream().mapToLong(o -> o.operation().amountCents()).sum();
        }

        /** La catégorie de ses opérations si elles ont toutes la même, sinon {@code null}. */
        public Category category() {
            Category first = sample().category();
            return operations.stream().allMatch(o -> o.category().id() == first.id()) ? first : null;
        }
    }

    /** Une catégorie que le modèle propose de créer. */
    public record NewCategory(String name, Nature nature, boolean inflow) {
    }

    /** D'où vient une proposition. */
    public enum Origin {
        /** La même activité est déjà classée ainsi dans la base. */
        SIMILAR,
        /** Choix du modèle de langage. */
        LLM
    }

    /**
     * Proposition pour une activité : une catégorie existante, ou une catégorie à créer.
     *
     * @param category catégorie existante proposée, ou {@code null} si {@code created} est renseignée
     * @param created  catégorie à créer, ou {@code null}
     */
    public record Suggestion(Activity activity, Category category, NewCategory created, Origin origin) {

        public String categoryName() {
            return category != null ? category.name() : created.name();
        }

        public Nature nature() {
            return category != null ? category.nature() : created.nature();
        }

        /** Les opérations de l'activité dont la catégorie changerait. */
        public List<ClassifiedOperation> changed() {
            return created != null ? activity.operations()
                    : activity.operations().stream().filter(o -> o.category().id() != category.id()).toList();
        }
    }

    /**
     * Résultat d'une analyse.
     *
     * @param suggestions une proposition par activité qui en a reçu une
     * @param activities  nombre d'activités examinées
     * @param failure     message de l'erreur qui a interrompu l'analyse avant la fin, ou {@code null}
     */
    public record Proposal(List<Suggestion> suggestions, int activities, String failure) {

        public Proposal {
            suggestions = List.copyOf(suggestions);
        }

        /** Les propositions qui changeraient la catégorie d'au moins une opération. */
        public List<Suggestion> changes() {
            return suggestions.stream().filter(s -> !s.changed().isEmpty()).toList();
        }

        /** Nombre d'activités restées sans proposition. */
        public int pending() {
            return activities - suggestions.size();
        }
    }

    /** Ce que {@link #apply} a enregistré. */
    public record Applied(int operations, int categories) {
    }

    /** Suivi de l'avancement, en nombre d'activités traitées. */
    @FunctionalInterface
    public interface Progress {
        void report(int done, int total);
    }

    private final LlmClient client;

    public Assistant(LlmClient client) {
        this.client = client;
    }

    // ------------------------------------------------------------------ analyse

    /**
     * Propose une catégorie à chaque activité des opérations retenues par {@code scope}. Les autres
     * opérations servent de référence : ce sont elles qui disent ce qui est « déjà classé ».
     *
     * <p>Si le modèle fait défaut en cours de route, les propositions déjà obtenues sont rendues, avec le
     * message de l'erreur.
     *
     * @param operations toutes les opérations du compte, classées
     */
    public Proposal propose(List<Category> categories, List<ClassifiedOperation> operations,
            Predicate<ClassifiedOperation> scope, Progress progress) {
        Map<String, List<ClassifiedOperation>> grouped = new LinkedHashMap<>();
        Map<String, Map<Long, Integer>> known = new HashMap<>();
        Map<Long, Category> byId = new HashMap<>();
        categories.forEach(c -> byId.put(c.id(), c));
        for (ClassifiedOperation o : operations) {
            String key = key(o.operation());
            if (scope.test(o)) {
                grouped.computeIfAbsent(key, _ -> new ArrayList<>()).add(o);
            } else if (!o.isUnclassified()) {
                known.computeIfAbsent(key, _ -> new HashMap<>()).merge(o.category().id(), 1, Integer::sum);
            }
        }
        List<Activity> activities = new ArrayList<>();
        grouped.forEach((key, list) -> activities.add(new Activity(key, list)));

        Map<Activity, Suggestion> suggestions = new LinkedHashMap<>();
        List<Activity> unknown = new ArrayList<>();
        for (Activity activity : activities) {
            // la catégorie la plus fréquente pour cette activité ; à égalité, la première du bilan par identifiant
            Category similar = known.getOrDefault(activity.key(), Map.of()).entrySet().stream()
                    .max(Map.Entry.<Long, Integer>comparingByValue()
                            .thenComparing(Map.Entry.<Long, Integer>comparingByKey(Comparator.reverseOrder())))
                    .map(e -> byId.get(e.getKey())).orElse(null);
            if (similar != null) {
                suggestions.put(activity, new Suggestion(activity, similar, null, Origin.SIMILAR));
            } else {
                unknown.add(activity);
            }
        }
        progress.report(activities.size() - unknown.size(), activities.size());

        String failure = null;
        JsonArray reference = categories(categories);
        JsonArray examples = examples(operations);
        Map<String, NewCategory> created = new HashMap<>();
        for (int start = 0; start < unknown.size() && failure == null; start += BATCH) {
            List<Activity> batch = unknown.subList(start, Math.min(start + BATCH, unknown.size()));
            try {
                JsonObject request = new JsonObject();
                request.add("categories", reference);
                request.add("exemples", examples);
                request.add("activites", activities(batch));
                read(client.complete(SYSTEM, request.toString()), batch, categories, created)
                        .forEach(s -> suggestions.put(s.activity(), s));
                progress.report(activities.size() - unknown.size() + start + batch.size(), activities.size());
            } catch (LlmException e) {
                failure = e.getMessage();
            }
        }
        List<Suggestion> ordered = activities.stream().map(suggestions::get).filter(s -> s != null).toList();
        return new Proposal(ordered, activities.size(), failure);
    }

    /** Ce qui fait que deux opérations relèvent de la même activité. */
    static String key(Operation o) {
        return Texts.fold(o.type()) + " | " + Classifier.suggestKeyword(o) + " | " + (o.isCredit() ? "+" : "-");
    }

    // ------------------------------------------------------------------ demande au modèle

    private static JsonArray categories(List<Category> categories) {
        JsonArray array = new JsonArray();
        for (Category category : categories) {
            if (!category.isFallback()) {
                JsonObject o = new JsonObject();
                o.addProperty("id", category.id());
                o.addProperty("nom", category.name());
                o.addProperty("nature", category.nature().name());
                o.addProperty("sens", category.inflow() ? "entrée" : "sortie");
                array.add(o);
            }
        }
        return array;
    }

    /** Pour chaque catégorie, les activités qui y sont le plus souvent classées. */
    private static JsonArray examples(List<ClassifiedOperation> operations) {
        Map<Long, Map<String, Integer>> postes = new LinkedHashMap<>();
        for (ClassifiedOperation o : operations) {
            if (!o.isUnclassified() && !o.poste().isBlank()) {
                postes.computeIfAbsent(o.category().id(), _ -> new LinkedHashMap<>()).merge(o.poste(), 1, Integer::sum);
            }
        }
        JsonArray array = new JsonArray();
        postes.forEach((categoryId, counts) -> {
            JsonArray names = new JsonArray();
            counts.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(EXAMPLES_PER_CATEGORY).forEach(e -> names.add(e.getKey()));
            JsonObject o = new JsonObject();
            o.addProperty("categorie", categoryId);
            o.add("activites", names);
            array.add(o);
        });
        return array;
    }

    private static JsonArray activities(List<Activity> batch) {
        JsonArray array = new JsonArray();
        for (int i = 0; i < batch.size(); i++) {
            Activity activity = batch.get(i);
            Operation sample = activity.sample().operation();
            String label = Classifier.matchText(sample).strip();
            JsonObject o = new JsonObject();
            o.addProperty("id", i + 1);
            o.addProperty("type", sample.type());
            o.addProperty("libelle", label.length() > MAX_LABEL ? label.substring(0, MAX_LABEL) : label);
            o.addProperty("sens", activity.credit() ? "crédit" : "débit");
            o.addProperty("montant", BigDecimal.valueOf(sample.amountCents(), 2));
            o.addProperty("occurrences", activity.operations().size());
            Category current = activity.category();
            if (current != null && !current.isFallback()) {
                o.addProperty("categorie_actuelle", current.id());
            }
            array.add(o);
        }
        return array;
    }

    // ------------------------------------------------------------------ réponse du modèle

    /**
     * Lit la réponse du modèle pour un lot d'activités. Une ligne incohérente (activité inconnue,
     * catégorie inexistante, nature invalide) est ignorée : l'activité reste sans proposition.
     *
     * @param created catégories nouvelles déjà proposées, par nom normalisé : un même nom désigne la même catégorie
     * @throws LlmException si la réponse n'est pas l'objet JSON attendu
     */
    private static List<Suggestion> read(String answer, List<Activity> batch, List<Category> categories,
            Map<String, NewCategory> created) {
        JsonArray rows;
        try {
            int start = answer.indexOf('{');
            int end = answer.lastIndexOf('}');
            rows = JsonParser.parseString(answer.substring(start, end + 1)).getAsJsonObject().getAsJsonArray("classements");
            if (rows == null) {
                throw new LlmException("La réponse du modèle ne contient pas les classements attendus.");
            }
        } catch (JsonParseException | IllegalStateException | IndexOutOfBoundsException | ClassCastException e) {
            throw new LlmException("La réponse du modèle n'est pas au format attendu.", e);
        }
        Map<Activity, Suggestion> suggestions = new LinkedHashMap<>();
        for (JsonElement element : rows) {
            try {
                JsonObject row = element.getAsJsonObject();
                int index = row.get("id").getAsInt() - 1;
                if (index < 0 || index >= batch.size()) {
                    continue;
                }
                Activity activity = batch.get(index);
                JsonElement existing = row.get("categorie");
                JsonElement proposed = row.get("nouvelle");
                if (existing != null && !existing.isJsonNull()) {
                    long id = existing.getAsLong();
                    categories.stream().filter(c -> c.id() == id && !c.isFallback()).findFirst()
                            .ifPresent(c -> suggestions.putIfAbsent(activity, new Suggestion(activity, c, null, Origin.LLM)));
                } else if (proposed != null && proposed.isJsonObject()) {
                    Suggestion suggestion = created(activity, proposed.getAsJsonObject(), categories, created);
                    if (suggestion != null) {
                        suggestions.putIfAbsent(activity, suggestion);
                    }
                }
            } catch (RuntimeException e) {
                // ligne mal formée : l'activité reste sans proposition
            }
        }
        return List.copyOf(suggestions.values());
    }

    /** Proposition d'une catégorie nouvelle — ou de la catégorie existante qui porte déjà ce nom. */
    private static Suggestion created(Activity activity, JsonObject proposed, List<Category> categories,
            Map<String, NewCategory> created) {
        String name = proposed.get("nom").getAsString().strip().replaceAll("\\s+", " ");
        if (name.isEmpty()) {
            return null;
        }
        if (name.length() > MAX_NAME) {
            name = name.substring(0, MAX_NAME).strip();
        }
        String folded = Texts.fold(name);
        Category existing = categories.stream().filter(c -> Texts.fold(c.name()).equals(folded)).findFirst().orElse(null);
        if (existing != null) {
            return existing.isFallback() ? null : new Suggestion(activity, existing, null, Origin.LLM);
        }
        NewCategory category = created.get(folded);
        if (category == null) {
            Nature chosen = Nature.valueOf(proposed.get("nature").getAsString().strip().toUpperCase(Locale.ROOT));
            category = new NewCategory(name, chosen, Category.inflowFor(chosen, activity.credit()));
            created.put(folded, category);
        }
        return new Suggestion(activity, null, category, Origin.LLM);
    }

    // ------------------------------------------------------------------ enregistrement

    /**
     * Enregistre des propositions : crée les catégories nouvelles, puis range chaque opération dans la
     * catégorie proposée. La catégorie est enregistrée avec l'opération, au titre de l'assistant — sauf
     * si les règles donnent déjà cette catégorie : l'opération leur est alors simplement rendue.
     *
     * @throws IllegalArgumentException si une catégorie proposée a été supprimée entre-temps
     */
    public static Applied apply(Connection c, Collection<Suggestion> accepted) throws SQLException {
        CategoryRepository categoryRepository = new CategoryRepository();
        OperationRepository operationRepository = new OperationRepository();
        List<Category> categories = new ArrayList<>(categoryRepository.findAll(c));
        Map<Suggestion, Category> targets = new LinkedHashMap<>();
        int createdCount = 0;
        for (Suggestion suggestion : accepted) {
            Category target;
            if (suggestion.created() == null) {
                long id = suggestion.category().id();
                target = categories.stream().filter(k -> k.id() == id).findFirst().orElseThrow(() -> new IllegalArgumentException(
                        "La catégorie « " + suggestion.category().name() + " » n'existe plus : relancez l'analyse."));
            } else {
                NewCategory wanted = suggestion.created();
                String folded = Texts.fold(wanted.name());
                target = categories.stream().filter(k -> Texts.fold(k.name()).equals(folded)).findFirst().orElse(null);
                if (target == null) {
                    target = categoryRepository.insert(c,
                            Category.create(wanted.name(), wanted.nature(), wanted.inflow(), IncomeKind.PONCTUEL));
                    categories.add(target);
                    createdCount++;
                }
            }
            if (!target.isFallback()) {
                targets.put(suggestion, target);
            }
        }
        Classifier rules = new Classifier(categories, new RuleRepository().findAll(c));
        int changed = 0;
        for (Map.Entry<Suggestion, Category> entry : targets.entrySet()) {
            Category target = entry.getValue();
            List<ClassifiedOperation> current = entry.getKey().activity().operations();
            List<ClassifiedOperation> byRules = rules.classify(current.stream().map(o -> unpinned(o.operation())).toList());
            List<Long> pinned = new ArrayList<>();
            List<Long> released = new ArrayList<>();
            for (int i = 0; i < current.size(); i++) {
                if (current.get(i).category().id() == target.id()) {
                    continue;
                }
                changed++;
                (byRules.get(i).category().id() == target.id() ? released : pinned).add(current.get(i).operation().id());
            }
            operationRepository.setCategory(c, pinned, target.id(), true);
            operationRepository.setCategory(c, released, null);
        }
        return new Applied(changed, createdCount);
    }

    private static Operation unpinned(Operation o) {
        return new Operation(o.id(), o.date(), o.valueDate(), o.type(), o.label(), o.amountCents());
    }
}
