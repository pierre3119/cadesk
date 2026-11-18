package io.github.pierre3119.cadesk.categorize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pierre3119.cadesk.domain.Category;
import io.github.pierre3119.cadesk.domain.Category.IncomeKind;
import io.github.pierre3119.cadesk.domain.Category.Role;
import io.github.pierre3119.cadesk.domain.ClassifiedOperation;
import io.github.pierre3119.cadesk.domain.ClassifiedOperation.Source;
import io.github.pierre3119.cadesk.domain.Nature;
import io.github.pierre3119.cadesk.domain.Operation;
import io.github.pierre3119.cadesk.domain.Rule;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class ClassifierTest {

    private static final LocalDate DAY = LocalDate.of(2026, 2, 27);
    private static final String CARD = Classifier.CARD_PAYMENT;
    private static final String TRANSFER_IN = "Virement en votre faveur";
    private static final String TRANSFER_OUT = "Virement émis";

    private static final Category SALARY = category(1, "Salaire", Nature.REVENUS, true, Role.STANDARD);
    private static final Category IN_DEFAULT = category(2, "Entrées à classer", Nature.REVENUS, true, Role.DEFAULT_IN);
    private static final Category FOOD = category(3, "Alimentation", Nature.VARIABLES, false, Role.STANDARD);
    private static final Category HEALTH = category(4, "Santé", Nature.VARIABLES, false, Role.STANDARD);
    private static final Category ENERGY = category(5, "Énergie", Nature.FIXES, false, Role.STANDARD);
    private static final Category OUT_DEFAULT = category(6, "Divers / à classer", Nature.VARIABLES, false, Role.DEFAULT_OUT);
    private static final List<Category> CATEGORIES = List.of(SALARY, IN_DEFAULT, FOOD, HEALTH, ENERGY, OUT_DEFAULT);

    private final List<Rule> rules = new ArrayList<>();
    private long nextId = 1;

    private static Category category(long id, String name, Nature nature, boolean inflow, Role role) {
        return new Category(id, name, nature, inflow, (int) id, role, IncomeKind.PONCTUEL, 0);
    }

    private void rule(Category category, String poste, String type, String... keywords) {
        rules.add(new Rule(nextId, category.id(), poste, type, List.of(keywords), (int) nextId * 10));
        nextId++;
    }

    private Operation op(String type, String label, long cents) {
        return new Operation(nextId++, DAY, DAY, type, label, cents);
    }

    private ClassifiedOperation classify(Operation operation) {
        return new Classifier(CATEGORIES, rules).classify(List.of(operation)).getFirst();
    }

    @Test
    void theFirstMatchingRuleWins() {
        rule(HEALTH, "Pharmacies", CARD, "PHARM");
        rule(FOOD, "Supermarché", CARD, "PHARMACIE DU MARCHE", "MARCHE");

        ClassifiedOperation result = classify(op(CARD, "X1234 PHARMACIE DU MARCHE 26/02", -1250));

        assertThat(result.category()).isEqualTo(HEALTH);
        assertThat(result.poste()).isEqualTo("Pharmacies");
        assertThat(result.source()).isEqualTo(Source.RULE);
        assertThat(result.rule().id()).isEqualTo(1);
        assertThat(result.amount()).as("une dépense est positive dans sa catégorie").isEqualTo(1250);
    }

    @Test
    void aRuleOnlyAppliesToItsOperationType() {
        rule(ENERGY, "Fournisseur", "Prélèvement", "ENERGIE SA");

        assertThat(classify(op("Prélèvement", "ENERGIE SA - Mandat 123", -12_000)).category()).isEqualTo(ENERGY);
        assertThat(classify(op("PRELEVEMENT", "ENERGIE SA", -12_000)).category())
                .as("type comparé sans tenir compte des accents ni de la casse").isEqualTo(ENERGY);
        assertThat(classify(op(TRANSFER_OUT, "WEB ENERGIE SA", -12_000)).category()).isEqualTo(OUT_DEFAULT);
    }

    @Test
    void aRuleWithoutKeywordTakesEveryOperationOfItsType() {
        rule(ENERGY, "Cotisation du compte", "Cotisation");

        ClassifiedOperation result = classify(op("Cotisation", "OFFRE COMPTE", -450));

        assertThat(result.category()).isEqualTo(ENERGY);
        assertThat(result.poste()).isEqualTo("Cotisation du compte");
    }

    @Test
    void aRuleWithoutTypeAppliesToEveryType() {
        rule(ENERGY, "Fournisseur", null, "ENERGIE SA");

        assertThat(classify(op("Prélèvement", "ENERGIE SA", -100)).category()).isEqualTo(ENERGY);
        assertThat(classify(op(TRANSFER_IN, "ENERGIE SA - Remboursement", 100)).amount())
                .as("un remboursement rangé dans une catégorie de dépense vient en déduction").isEqualTo(-100);
    }

    @Test
    void aCompoundKeywordNeedsAllItsParts() {
        rule(ENERGY, "Contrat A", "Prélèvement", "ENERGIE + CONTRAT 111");
        rule(ENERGY, "Contrat B", "Prélèvement", "ENERGIE");

        assertThat(classify(op("Prélèvement", "ENERGIE SA\nContrat 111", -100)).poste()).isEqualTo("Contrat A");
        assertThat(classify(op("Prélèvement", "ENERGIE SA\nContrat 222", -100)).poste()).isEqualTo("Contrat B");
    }

    @Test
    void keywordsIgnoreCaseAndAccents() {
        rule(SALARY, "Employeur", TRANSFER_IN, "PAIE DU MOIS");

        assertThat(classify(op(TRANSFER_IN, "ACME SA R000123 - Paie du mois de Février 2026", 250_000)).category())
                .isEqualTo(SALARY);
    }

    @Test
    void aTrailingSpaceInAKeywordAsksForTheEndOfAWord() {
        rule(HEALTH, "Médecins", CARD, "DR ");

        assertThat(classify(op(CARD, "X1234 CABINET DR 26/02", -2500)).category()).isEqualTo(HEALTH);
        assertThat(classify(op(CARD, "X1234 DR MARTIN 26/02 - Dr Martin", -2500)).category()).isEqualTo(HEALTH);
        assertThat(classify(op(CARD, "X1234 DRIVE CENTRE 26/02", -2500)).category()).isEqualTo(OUT_DEFAULT);
    }

    @Test
    void aCardPaymentIsMatchedOnTheMerchantNotOnTheCardNumberOrTheDate() {
        rule(FOOD, "Piège", CARD, "X1234", "26/02", "12H30");

        ClassifiedOperation result = classify(op(CARD, "X1234 BOULANGERIE DUPAIN 26/02 12H30 - Boulangerie Dupain", -450));

        assertThat(result.category()).isEqualTo(OUT_DEFAULT);
        assertThat(Classifier.matchText(result.operation())).isEqualTo("BOULANGERIE DUPAIN | BOULANGERIE DUPAIN ");
        assertThat(Classifier.suggestKeyword(result.operation())).isEqualTo("BOULANGERIE DUPAIN");
    }

    @Test
    void anUnrecognisedOperationGoesToTheDefaultCategoryOfItsDirection() {
        ClassifiedOperation debit = classify(op(CARD, "X1234 GARAGE DES ALPES SAS 26/02", -124_000));
        ClassifiedOperation credit = classify(op(TRANSFER_IN, "VIR INST DE MR JEAN MARTIN - merci", 5_000));

        assertThat(debit.category()).isEqualTo(OUT_DEFAULT);
        assertThat(debit.source()).isEqualTo(Source.DEFAULT);
        assertThat(debit.isUnclassified()).isTrue();
        assertThat(debit.poste()).as("nom du commerçant remis en forme").isEqualTo("Garage Des Alpes SAS");
        assertThat(credit.category()).isEqualTo(IN_DEFAULT);
        assertThat(credit.poste()).as("nom du tiers, sans civilité").isEqualTo("Jean Martin");
    }

    @Test
    void theMerchantNameGivenByTheBankOnOneOperationNamesTheOthers() {
        rule(FOOD, null, CARD, "DUPAIN");
        Operation plain = op(CARD, "X1234 BOUL DUPAIN 20/02", -300);
        Operation enriched = op(CARD, "X1234 BOUL DUPAIN 26/02 - Boulangerie Dupain", -450);

        List<ClassifiedOperation> result = new Classifier(CATEGORIES, rules).classify(List.of(plain, enriched));

        assertThat(result).extracting(ClassifiedOperation::poste).containsExactly("Boulangerie Dupain", "Boulangerie Dupain");
        assertThat(result).extracting(ClassifiedOperation::category).containsOnly(FOOD);
    }

    @Test
    void postesOfOtherOperationsComeFromTheLabel() {
        assertThat(classify(op(TRANSFER_OUT, "WERO VERS DAVID DURAND - Cadeau\n019D9CCD", -2_000)).poste()).isEqualTo("David Durand");
        assertThat(classify(op(TRANSFER_IN, "VIR INST WERO DE M.OU MME PETIT - Repas", 2_000)).poste()).isEqualTo("Petit");
        assertThat(classify(op("Chèque emis", "3695493", -5_000)).poste()).isEqualTo("Chèque émis n° 3695493");
        assertThat(classify(op("Prélèvement", "ASSUREUR D'OC - Mandat 77", -5_000)).poste()).isEqualTo("Assureur D'Oc");
        assertThat(classify(op("Cotisation", "", -450)).poste()).as("pas de détail : le type").isEqualTo("Cotisation");
    }

    @Test
    void aManualCategoryOverridesTheRules() {
        rule(FOOD, "Supermarché", CARD, "SUPERMARCHE");
        Operation manual = new Operation(99, DAY, DAY, CARD, "X1234 SUPERMARCHE DU CENTRE 26/02", -8_437, HEALTH.id());

        ClassifiedOperation result = classify(manual);

        assertThat(result.category()).isEqualTo(HEALTH);
        assertThat(result.source()).isEqualTo(Source.MANUAL);
        assertThat(result.rule()).isNull();
        assertThat(result.poste()).isEqualTo("Supermarche Du Centre");
    }

    @Test
    void aManualCategoryThatNoLongerExistsIsIgnored() {
        rule(FOOD, "Supermarché", CARD, "SUPERMARCHE");
        Operation orphan = new Operation(99, DAY, DAY, CARD, "X1234 SUPERMARCHE DU CENTRE 26/02", -8_437, 12_345L);

        assertThat(classify(orphan).category()).isEqualTo(FOOD);
    }

    @Test
    void rulesOfAnUnknownCategoryAreIgnoredAndBothDefaultCategoriesAreRequired() {
        rules.add(new Rule(1, 777, "Fantôme", CARD, List.of("DUPAIN"), 1));

        assertThat(classify(op(CARD, "X1234 BOUL DUPAIN 26/02", -450)).category()).isEqualTo(OUT_DEFAULT);
        assertThatThrownBy(() -> new Classifier(List.of(SALARY, FOOD, OUT_DEFAULT), rules))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("DEFAULT_IN");
    }

    @Test
    void aSingleRuleCanBeTestedAgainstOperations() {
        Rule rule = Rule.create(FOOD.id(), null, CARD, List.of("DUPAIN"));

        assertThat(Classifier.matcher(rule).test(op(CARD, "X1234 BOUL DUPAIN 26/02", -450))).isTrue();
        assertThat(Classifier.matcher(rule).test(op(TRANSFER_OUT, "WEB DUPAIN", -450))).isFalse();
    }

    @Test
    void keywordsAreReadOnePerLineAndNormalised() {
        assertThat(Texts.parseKeywords("spotify\n  Café de la Paix  \n\n\"dr \"\nSPOTIFY\nengie   +  000123"))
                .containsExactly("SPOTIFY", "CAFE DE LA PAIX", "DR ", "ENGIE + 000123");
        assertThat(Texts.parseKeywords(null)).isEmpty();
        assertThat(Texts.displayKeyword("DR ")).isEqualTo("\"DR \"");
        assertThat(Texts.displayKeyword("SPOTIFY")).isEqualTo("SPOTIFY");
        assertThat(Texts.titleCase("DUPONT JEAN-MARC")).isEqualTo("Dupont Jean-Marc");
    }

    @Test
    void aRuleNeedsATypeOrAKeyword() {
        assertThatThrownBy(() -> Rule.create(FOOD.id(), "Tout", " ", List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThat(Rule.create(FOOD.id(), "  ", CARD, List.of()).poste()).isNull();
    }
}
