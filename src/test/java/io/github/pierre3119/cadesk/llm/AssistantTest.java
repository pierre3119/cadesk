package io.github.pierre3119.cadesk.llm;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pierre3119.cadesk.Samples;
import io.github.pierre3119.cadesk.Samples.Row;
import io.github.pierre3119.cadesk.categorize.Classifier;
import io.github.pierre3119.cadesk.db.CategoryRepository;
import io.github.pierre3119.cadesk.db.Database;
import io.github.pierre3119.cadesk.db.OperationRepository;
import io.github.pierre3119.cadesk.db.RuleRepository;
import io.github.pierre3119.cadesk.domain.Category;
import io.github.pierre3119.cadesk.domain.ClassifiedOperation;
import io.github.pierre3119.cadesk.domain.ClassifiedOperation.Source;
import io.github.pierre3119.cadesk.domain.Nature;
import io.github.pierre3119.cadesk.domain.Rule;
import io.github.pierre3119.cadesk.importer.ImportService;
import io.github.pierre3119.cadesk.llm.Assistant.Applied;
import io.github.pierre3119.cadesk.llm.Assistant.Origin;
import io.github.pierre3119.cadesk.llm.Assistant.Proposal;
import io.github.pierre3119.cadesk.llm.Assistant.Suggestion;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Assistant de classement, avec un modèle de langage simulé et une vraie base. */
class AssistantTest {

    private static final Row GARAGE_JANUARY = Row.debit("12/01/2026", "Paiement par carte\nX1234 ZZ GARAGE DES ALPES 11/01", "340,00");
    private static final Row GARAGE_FEBRUARY = Row.debit("12/02/2026", "Paiement par carte\nX1234 ZZ GARAGE DES ALPES 11/02", "120,00");
    private static final Row VET = Row.debit("03/02/2026", "Paiement par carte\nX1234 ZZ VETO DU PARC 02/02", "58,00");
    private static final Row PET_SHOP = Row.debit("05/02/2026", "Paiement par carte\nX1234 ZZ ANIMALERIE 04/02", "23,90");
    private static final Row PODIATRIST = Row.debit("07/02/2026", "Paiement par carte\nX1234 ZZ PODOLOGUE 06/02", "55,00");

    @TempDir
    Path dir;
    private Database db;
    private long account;
    private final CategoryRepository categories = new CategoryRepository();
    private final RuleRepository rules = new RuleRepository();
    private final OperationRepository operations = new OperationRepository();

    @BeforeEach
    void openDatabase() {
        db = new Database(dir.resolve("test.db"));
    }

    @AfterEach
    void closeDatabase() {
        db.close();
    }

    private void importing(Row... rows) throws IOException {
        account = new ImportService(db).importFile(Samples.write(dir, "releve.csv", Samples.statement(rows))).account().id();
    }

    private List<Category> allCategories() {
        return db.inTransaction(categories::findAll);
    }

    private Category category(String name) {
        return allCategories().stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow();
    }

    private List<ClassifiedOperation> classified() {
        return db.inTransaction(c -> new Classifier(categories.findAll(c), rules.findAll(c)).classify(operations.findByAccount(c, account)));
    }

    private ClassifiedOperation operation(String needle) {
        return classified().stream().filter(o -> o.operation().label().contains(needle)).findFirst().orElseThrow();
    }

    private void pin(String needle, Category category, boolean assisted) {
        long id = operation(needle).operation().id();
        db.inTransaction(c -> {
            operations.setCategory(c, List.of(id), category.id(), assisted);
            return null;
        });
    }

    private Proposal propose(LlmClient client) {
        return new Assistant(client).propose(allCategories(), classified(), ClassifiedOperation::isUnclassified, (_, _) -> { });
    }

    private Applied apply(List<Suggestion> suggestions) {
        return db.inTransaction(c -> Assistant.apply(c, suggestions));
    }

    /** Modèle simulé : répond à chaque activité d'après son libellé, en entourant le JSON de texte comme le font certains modèles. */
    private static LlmClient answering(Function<String, String> verdictByLabel) {
        return (system, user) -> {
            JsonArray rows = new JsonArray();
            for (JsonElement element : JsonParser.parseString(user).getAsJsonObject().getAsJsonArray("activites")) {
                JsonObject activity = element.getAsJsonObject();
                rows.add(JsonParser.parseString("{\"id\":" + activity.get("id").getAsInt() + ","
                        + verdictByLabel.apply(activity.get("libelle").getAsString()) + "}"));
            }
            JsonObject answer = new JsonObject();
            answer.add("classements", rows);
            return "Voici le classement demandé :\n```json\n" + answer + "\n```\n";
        };
    }

    private static final LlmClient NEVER_ASKED = (system, user) -> {
        throw new AssertionError("le modèle ne devait pas être interrogé");
    };

    // ------------------------------------------------------------------ activités déjà connues

    @Test
    void anActivityAlreadyClassifiedKeepsItsCategoryWithoutAskingTheModel() throws IOException {
        importing(GARAGE_JANUARY, GARAGE_FEBRUARY);
        Category transport = category("Transports et voiture");
        pin("11/01", transport, false);

        Proposal proposal = propose(NEVER_ASKED);

        assertThat(proposal.failure()).isNull();
        assertThat(proposal.suggestions()).singleElement().satisfies(s -> {
            assertThat(s.origin()).isEqualTo(Origin.SIMILAR);
            assertThat(s.category()).isEqualTo(transport);
            assertThat(s.activity().operations()).hasSize(1);
        });
        assertThat(apply(proposal.suggestions())).isEqualTo(new Applied(1, 0));
        ClassifiedOperation february = operation("11/02");
        assertThat(february.category()).isEqualTo(transport);
        assertThat(february.source()).isEqualTo(Source.ASSISTANT);
        assertThat(operation("11/01").source()).as("le choix manuel n'est pas touché").isEqualTo(Source.MANUAL);
    }

    // ------------------------------------------------------------------ modèle de langage

    @Test
    void theModelReceivesCategoriesExamplesAndGroupedActivities() throws IOException {
        importing(GARAGE_JANUARY, GARAGE_FEBRUARY, VET, PODIATRIST);
        pin("ZZ PODOLOGUE", category("Santé"), false);
        AtomicReference<JsonObject> request = new AtomicReference<>();
        AtomicReference<String> system = new AtomicReference<>();

        Proposal proposal = propose((consigne, demande) -> {
            system.set(consigne);
            request.set(JsonParser.parseString(demande).getAsJsonObject());
            return "{\"classements\":[]}";
        });

        assertThat(system.get()).contains("classements").contains("catégorie existante");
        JsonObject sent = request.get();
        List<String> names = new ArrayList<>();
        sent.getAsJsonArray("categories").forEach(c -> names.add(c.getAsJsonObject().get("nom").getAsString()));
        assertThat(names).contains("Santé", "Salaire").doesNotContain("Divers / à classer", "Entrées à classer");
        assertThat(sent.getAsJsonArray("exemples").toString()).as("les activités déjà classées servent d'exemples")
                .contains("\"categorie\":" + category("Santé").id()).contains("Zz Podologue");
        assertThat(sent.getAsJsonArray("activites")).hasSize(2);
        JsonObject garage = sent.getAsJsonArray("activites").get(0).getAsJsonObject();
        assertThat(garage.get("libelle").getAsString()).contains("ZZ GARAGE DES ALPES").doesNotContain("X1234");
        assertThat(garage.get("occurrences").getAsInt()).as("les deux paiements au garage forment une seule activité").isEqualTo(2);
        assertThat(garage.get("sens").getAsString()).isEqualTo("débit");
        assertThat(garage.get("type").getAsString()).isEqualTo("Paiement par carte");
        assertThat(garage.has("categorie_actuelle")).isFalse();
        assertThat(proposal.suggestions()).isEmpty();
        assertThat(proposal.activities()).isEqualTo(2);
        assertThat(proposal.pending()).isEqualTo(2);
    }

    @Test
    void theModelPicksAnExistingCategoryOrCreatesOneOnlyOnce() throws IOException {
        importing(GARAGE_JANUARY, GARAGE_FEBRUARY, VET, PET_SHOP, PODIATRIST);
        int before = allCategories().size();
        long transport = category("Transports et voiture").id();

        Proposal proposal = propose(answering(label -> label.contains("GARAGE") ? "\"categorie\":" + transport
                : label.contains("PODOLOGUE") ? "\"nouvelle\":{\"nom\":\"sante\",\"nature\":\"VARIABLES\"}"
                        : "\"nouvelle\":{\"nom\":\" Animaux \",\"nature\":\"variables\"}"));

        assertThat(proposal.failure()).isNull();
        assertThat(proposal.pending()).isZero();
        assertThat(proposal.suggestions()).extracting(Suggestion::categoryName, Suggestion::origin).containsExactlyInAnyOrder(
                org.assertj.core.api.Assertions.tuple("Transports et voiture", Origin.LLM),
                org.assertj.core.api.Assertions.tuple("Animaux", Origin.LLM),
                org.assertj.core.api.Assertions.tuple("Animaux", Origin.LLM),
                org.assertj.core.api.Assertions.tuple("Santé", Origin.LLM));

        assertThat(apply(proposal.suggestions())).isEqualTo(new Applied(5, 1));

        assertThat(allCategories()).hasSize(before + 1);
        Category pets = category("Animaux");
        assertThat(pets.nature()).isEqualTo(Nature.VARIABLES);
        assertThat(pets.inflow()).isFalse();
        assertThat(classified()).extracting(o -> o.category().name(), ClassifiedOperation::source).containsExactlyInAnyOrder(
                org.assertj.core.api.Assertions.tuple("Transports et voiture", Source.ASSISTANT),
                org.assertj.core.api.Assertions.tuple("Transports et voiture", Source.ASSISTANT),
                org.assertj.core.api.Assertions.tuple("Animaux", Source.ASSISTANT),
                org.assertj.core.api.Assertions.tuple("Animaux", Source.ASSISTANT),
                org.assertj.core.api.Assertions.tuple("Santé", Source.ASSISTANT));
    }

    @Test
    void incoherentAnswersLeaveTheOperationsToClassify() throws IOException {
        importing(GARAGE_JANUARY, VET, PET_SHOP, PODIATRIST);
        long fallback = category("Divers / à classer").id();

        Proposal proposal = propose(answering(label -> label.contains("GARAGE") ? "\"categorie\":987654"
                : label.contains("VETO") ? "\"categorie\":" + fallback
                        : label.contains("ANIMALERIE") ? "\"nouvelle\":{\"nom\":\"Animaux\",\"nature\":\"INCONNUE\"}" : "\"categorie\":null"));

        assertThat(proposal.failure()).isNull();
        assertThat(proposal.suggestions()).isEmpty();
        assertThat(proposal.pending()).isEqualTo(4);

        Proposal unreadable = propose((system, user) -> "Je ne peux pas répondre à cette demande.");
        assertThat(unreadable.failure()).contains("format attendu");
        assertThat(unreadable.suggestions()).isEmpty();
    }

    @Test
    void aFailureOfTheModelKeepsWhatWasAlreadyObtained() throws IOException {
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < 90; i++) {
            rows.add(Row.debit("%02d/02/2026".formatted(1 + i % 28), "Paiement par carte\nX1234 ZZ COMMERCE NUMERO " + i + " 01/02", "10,00"));
        }
        importing(rows.toArray(Row[]::new));
        long food = category("Alimentation et courses").id();
        AtomicInteger calls = new AtomicInteger();
        List<Integer> progress = new ArrayList<>();

        Proposal complete = new Assistant((system, user) -> {
            calls.incrementAndGet();
            return answering(_ -> "\"categorie\":" + food).complete(system, user);
        }).propose(allCategories(), classified(), ClassifiedOperation::isUnclassified, (done, total) -> progress.add(done));
        assertThat(calls).as("90 activités, 40 par demande").hasValue(3);
        assertThat(complete.suggestions()).hasSize(90);
        assertThat(progress).containsExactly(0, 40, 80, 90);

        calls.set(0);
        Proposal interrupted = propose((system, user) -> {
            if (calls.incrementAndGet() == 2) {
                throw new LlmException("quota dépassé");
            }
            return answering(_ -> "\"categorie\":" + food).complete(system, user);
        });
        assertThat(calls).as("l'analyse s'arrête à la première erreur").hasValue(2);
        assertThat(interrupted.failure()).isEqualTo("quota dépassé");
        assertThat(interrupted.suggestions()).hasSize(40);
        assertThat(interrupted.pending()).isEqualTo(50);
    }

    // ------------------------------------------------------------------ nouvelle passe

    @Test
    void aFullPassTellsTheModelTheCurrentCategoryAndOnlyReportsChanges() throws IOException {
        importing(GARAGE_JANUARY, VET, PODIATRIST);
        Category health = category("Santé");
        Category transport = category("Transports et voiture");
        pin("ZZ VETO", health, true);
        pin("ZZ PODOLOGUE", health, true);
        AtomicReference<String> request = new AtomicReference<>();

        Proposal proposal = new Assistant((system, user) -> {
            request.set(user);
            return answering(label -> "\"categorie\":" + (label.contains("PODOLOGUE") ? health.id() : transport.id())).complete(system, user);
        }).propose(allCategories(), classified(), _ -> true, (_, _) -> { });

        assertThat(request.get()).contains("\"categorie_actuelle\":" + health.id());
        assertThat(proposal.suggestions()).hasSize(3);
        assertThat(proposal.changes()).as("le podologue garde sa catégorie").extracting(s -> s.activity().poste())
                .containsExactlyInAnyOrder("Zz Garage Des Alpes", "Zz Veto Du Parc");
        assertThat(apply(proposal.suggestions())).isEqualTo(new Applied(2, 0));
        assertThat(operation("ZZ VETO").category()).isEqualTo(transport);
    }

    @Test
    void anOperationIsHandedBackToTheRulesWhenTheyAlreadyGiveTheProposedCategory() throws IOException {
        importing(VET);
        Category health = category("Santé");
        Category transport = category("Transports et voiture");
        pin("ZZ VETO", health, true);
        db.inTransaction(c -> rules.insertFirst(c, Rule.create(transport.id(), "Vétérinaire", null, List.of("ZZ VETO"))));
        assertThat(operation("ZZ VETO").category()).as("la catégorie enregistrée passe avant la règle").isEqualTo(health);

        Proposal proposal = new Assistant(answering(_ -> "\"categorie\":" + transport.id()))
                .propose(allCategories(), classified(), _ -> true, (_, _) -> { });
        apply(proposal.suggestions());

        ClassifiedOperation vet = operation("ZZ VETO");
        assertThat(vet.category()).isEqualTo(transport);
        assertThat(vet.source()).isEqualTo(Source.RULE);
        assertThat(vet.operation().manualCategoryId()).isNull();
    }

    @Test
    void aCategoryDeletedSinceTheAnalysisStopsTheWholeUpdate() throws IOException {
        importing(GARAGE_JANUARY, VET);
        Category pets = db.inTransaction(c -> categories.insert(c,
                Category.create("Animaux", Nature.VARIABLES, false, Category.IncomeKind.PONCTUEL)));
        long transport = category("Transports et voiture").id();
        Proposal proposal = propose(answering(label -> "\"categorie\":" + (label.contains("VETO") ? pets.id() : transport)));
        db.inTransaction(c -> {
            categories.delete(c, pets.id());
            return null;
        });

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> apply(proposal.suggestions()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Animaux");

        assertThat(classified()).as("rien n'a été enregistré").allMatch(ClassifiedOperation::isUnclassified);
    }
}
