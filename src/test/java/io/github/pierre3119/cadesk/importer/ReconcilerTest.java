package io.github.pierre3119.cadesk.importer;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pierre3119.cadesk.domain.Operation;
import io.github.pierre3119.cadesk.domain.StatementLine;
import io.github.pierre3119.cadesk.importer.Reconciler.Plan;
import io.github.pierre3119.cadesk.importer.Reconciler.Update;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class ReconcilerTest {

    private static final LocalDate D1 = LocalDate.of(2026, 2, 1);
    private static final LocalDate D2 = LocalDate.of(2026, 2, 2);

    private static StatementLine line(LocalDate date, String label, long cents) {
        return new StatementLine(date, date, "Paiement par carte", label, cents);
    }

    /** Simule la base après insertion des lignes : identifiants croissants. */
    private static List<Operation> stored(StatementLine... lines) {
        List<Operation> ops = new ArrayList<>();
        for (StatementLine l : lines) {
            ops.add(new Operation(ops.size() + 1, l.date(), l.valueDate(), l.type(), l.label(), l.amountCents()));
        }
        return ops;
    }

    @Test
    void everythingIsNewOnAnEmptyBase() {
        StatementLine recent = line(D2, "CAFE", -300);
        StatementLine old = line(D1, "LIVRE", -1500);

        Plan plan = Reconciler.plan(List.of(recent, old), List.of());

        assertThat(plan.toInsert()).as("ordre chronologique").containsExactly(old, recent);
        assertThat(plan.toUpdate()).isEmpty();
        assertThat(plan.known()).isZero();
    }

    @Test
    void importingTheSameStatementTwiceAddsNothing() {
        StatementLine a = line(D2, "CAFE", -300);
        StatementLine b = line(D1, "LIVRE", -1500);

        Plan plan = Reconciler.plan(List.of(a, b), stored(b, a));

        assertThat(plan.toInsert()).isEmpty();
        assertThat(plan.toUpdate()).isEmpty();
        assertThat(plan.known()).isEqualTo(2);
    }

    @Test
    void anOverlappingStatementAddsOnlyWhatIsNew() {
        StatementLine known = line(D1, "LIVRE", -1500);
        StatementLine fresh = line(D2, "CAFE", -300);

        Plan plan = Reconciler.plan(List.of(fresh, known), stored(known));

        assertThat(plan.toInsert()).containsExactly(fresh);
        assertThat(plan.known()).isEqualTo(1);
    }

    @Test
    void twoIdenticalOperationsInOneStatementAreBothKept() {
        StatementLine gift = line(D1, "Bon anniversaire", 50_000);

        Plan first = Reconciler.plan(List.of(gift, gift), List.of());
        Plan second = Reconciler.plan(List.of(gift, gift), stored(gift, gift));

        assertThat(first.toInsert()).containsExactly(gift, gift);
        assertThat(second.toInsert()).isEmpty();
        assertThat(second.known()).isEqualTo(2);
    }

    @Test
    void aSecondIdenticalOperationAppearingLaterIsAdded() {
        // premier export fait en cours de journée : une seule des deux opérations identiques y figurait
        StatementLine coffee = line(D1, "CAFE", -300);

        Plan plan = Reconciler.plan(List.of(coffee, coffee), stored(coffee));

        assertThat(plan.toInsert()).containsExactly(coffee);
        assertThat(plan.known()).isEqualTo(1);
    }

    @Test
    void aLabelRewordedByTheBankUpdatesTheOperationInsteadOfDuplicatingIt() {
        StatementLine before = line(D1, "X1234 BOULANGERIE 31/01", -450);
        StatementLine after = line(D1, "X1234 BOULANGERIE 31/01 - Boulangerie Dupain", -450);

        Plan plan = Reconciler.plan(List.of(after), stored(before));

        assertThat(plan.toInsert()).isEmpty();
        assertThat(plan.toUpdate()).containsExactly(new Update(1, after.label(), D1));
        assertThat(plan.known()).isZero();
    }

    @Test
    void aValueDateChangedByTheBankUpdatesTheOperationInsteadOfDuplicatingIt() {
        StatementLine before = new StatementLine(D2, D2, "Cotisation", "Offre Compte", -500);
        StatementLine after = new StatementLine(D2, D1, "Cotisation", "Offre Compte", -500);

        Plan plan = Reconciler.plan(List.of(after), stored(before));

        assertThat(plan.toInsert()).isEmpty();
        assertThat(plan.toUpdate()).containsExactly(new Update(1, "Offre Compte", D1));
        assertThat(plan.known()).isZero();
    }

    @Test
    void amongOperationsOfTheSameDayAndAmountTheOneWithTheSameValueDateIsRecognisedFirst() {
        StatementLine early = new StatementLine(D2, D1, "Cotisation", "Offre Compte", -500);
        StatementLine late = new StatementLine(D2, D2, "Cotisation", "Offre Compte", -500);

        Plan plan = Reconciler.plan(List.of(late, early), stored(early, late));

        assertThat(plan.toUpdate()).as("aucune date de valeur n'est échangée").isEmpty();
        assertThat(plan.known()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ relevé résumé (OFX) et relevé complet (CSV)

    /** La ligne d'un relevé OFX : pas de date de valeur, et un libellé réduit au début de celui du relevé CSV. */
    private static StatementLine summary(LocalDate date, String label, long cents) {
        return new StatementLine(date, null, "Paiement par carte", label, cents);
    }

    @Test
    void aSummaryLineRecognisesTheOperationACompleteStatementBroughtAndLeavesItUntouched() {
        StatementLine complete = new StatementLine(D2, D1, "Paiement par carte", "X1234 BOULANGERIE 31/01 - Boulangerie Dupain\nREF 42", -450);
        StatementLine reworded = new StatementLine(D1, D1, "Paiement par carte", "Fourniture d'une carte\nInternationale", -4500);

        Plan plan = Reconciler.plan(List.of(summary(D2, "X1234 BOULANGERIE 31/01", -450),
                summary(D1, "Fourniture d une carte Internationale", -4500)), stored(complete, reworded));

        assertThat(plan.toInsert()).as("ni la date de valeur ni le libellé ne font une opération nouvelle").isEmpty();
        assertThat(plan.toUpdate()).as("le résumé en sait moins que la base").isEmpty();
        assertThat(plan.known()).isEqualTo(2);
    }

    @Test
    void aCompleteLineCompletesTheOperationASummaryBrought() {
        // en base : ce qu'a laissé un relevé OFX — la date d'opération tient lieu de date de valeur
        StatementLine brought = new StatementLine(D2, D2, "Paiement par carte", "X1234 BOULANGERIE 31/01", -450);
        StatementLine complete = new StatementLine(D2, D1, "Paiement par carte", "X1234 BOULANGERIE 31/01 - Boulangerie Dupain\nREF 42", -450);

        Plan plan = Reconciler.plan(List.of(complete), stored(brought));

        assertThat(plan.toInsert()).isEmpty();
        assertThat(plan.toUpdate()).containsExactly(new Update(1, complete.label(), D1));
    }

    @Test
    void importingTheSameSummaryAgainAddsNothing() {
        StatementLine a = summary(D2, "X1234 CAFE 01/02", -300);
        StatementLine b = summary(D1, "X1234 LIVRE 31/01", -1500);
        List<Operation> base = List.of(new Operation(1, D1, D1, b.type(), b.label(), b.amountCents()),
                new Operation(2, D2, D2, a.type(), a.label(), a.amountCents()));

        Plan plan = Reconciler.plan(List.of(a, b), base);

        assertThat(plan.toInsert()).isEmpty();
        assertThat(plan.toUpdate()).isEmpty();
        assertThat(plan.known()).isEqualTo(2);
    }

    @Test
    void amongOperationsOfTheSameDayAndAmountEachSummaryLineTakesTheOneItResembles() {
        StatementLine bakery = line(D1, "X1234 BOULANGERIE 31/01 - Boulangerie Dupain", -450);
        StatementLine florist = line(D1, "X1234 FLEURISTE 31/01 - Aux Fleurs", -450);
        StatementLine coffee = summary(D1, "X1234 CAFE 31/01", -450);

        Plan plan = Reconciler.plan(List.of(coffee, summary(D1, "X1234 FLEURISTE 31/01", -450),
                summary(D1, "X1234 BOULANGERIE 31/01", -450)), stored(bakery, florist));

        assertThat(plan.toInsert()).as("le café, et non le fleuriste qu'il aurait pu supplanter").containsExactly(coffee);
        assertThat(plan.known()).isEqualTo(2);
    }

    @Test
    void sameDayAndAmountButOneMoreOperationIsNewNotAnUpdate() {
        StatementLine bakery = line(D1, "BOULANGERIE", -450);
        StatementLine florist = line(D1, "FLEURISTE", -450);

        Plan plan = Reconciler.plan(List.of(florist, bakery), stored(bakery));

        assertThat(plan.toInsert()).containsExactly(florist);
        assertThat(plan.toUpdate()).isEmpty();
        assertThat(plan.known()).isEqualTo(1);
    }

    @Test
    void operationsOfAnotherTypeDateOrAmountAreNeverConfused() {
        StatementLine card = line(D1, "ACME", -1000);
        StatementLine otherType = new StatementLine(D1, D1, "Prélèvement", "ACME", -1000);
        StatementLine otherDate = line(D2, "ACME", -1000);
        StatementLine otherAmount = line(D1, "ACME", -1001);
        StatementLine refund = line(D1, "ACME", 1000);

        Plan plan = Reconciler.plan(List.of(otherType, otherDate, otherAmount, refund), stored(card));

        assertThat(plan.toInsert()).containsExactlyInAnyOrder(otherType, otherDate, otherAmount, refund);
        assertThat(plan.toUpdate()).isEmpty();
    }
}
