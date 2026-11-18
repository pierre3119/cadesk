package io.github.pierre3119.cadesk.importer;

import io.github.pierre3119.cadesk.domain.Operation;
import io.github.pierre3119.cadesk.domain.StatementLine;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;

/**
 * Rapproche les lignes d'un relevé des opérations déjà en base, pour qu'un relevé qui en recouvre un
 * autre n'ajoute que ce qui est réellement nouveau.
 *
 * <p>Les relevés n'ont pas d'identifiant d'opération commun à tous les formats ; deux opérations distinctes
 * peuvent même être strictement identiques (deux virements de 500 € le même jour avec le même motif). Le
 * rapprochement raisonne donc en <em>quantités</em>, pour chaque groupe d'opérations de même date
 * d'opération, montant et type : chaque ligne du relevé y prend une opération de la base, la plus
 * ressemblante d'abord — même libellé et même date de valeur, puis même libellé, puis libellé dont l'un
 * commence par l'autre, puis n'importe laquelle.
 * <ol>
 *   <li>une ligne qui trouve une opération identique est <b>connue</b> (appariée une seule fois : si le
 *       fichier en contient deux et la base une, la seconde est nouvelle) ;</li>
 *   <li>une ligne qui trouve une opération différente par le libellé ou par la date de valeur est la même
 *       opération, que la banque a retouchée entre deux exports ou qu'un relevé résumé (OFX) avait apportée :
 *       elle est <b>mise à jour</b> et non dupliquée ;</li>
 *   <li>une ligne qui ne trouve plus d'opération dans son groupe est <b>nouvelle</b>.</li>
 * </ol>
 * La ligne d'un relevé résumé (OFX, voir {@link StatementLine#summary()}) en sait moins que la base : elle
 * reconnaît une opération, mais ne la modifie jamais.
 *
 * <p>Seules les opérations comprises dans la période du fichier doivent être fournies en entrée.
 */
public final class Reconciler {

    /** Opération existante dont le libellé et la date de valeur doivent prendre ceux du relevé. */
    public record Update(long operationId, String newLabel, LocalDate newValueDate) {
    }

    /**
     * @param toInsert lignes nouvelles, de la plus ancienne à la plus récente
     * @param known    nombre de lignes déjà présentes dans la base, sans rien à y changer
     */
    public record Plan(List<StatementLine> toInsert, List<Update> toUpdate, int known) {

        public Plan {
            toInsert = List.copyOf(toInsert);
            toUpdate = List.copyOf(toUpdate);
        }
    }

    private record Key(LocalDate date, long amountCents, String type) {
    }

    /** Ce qui fait qu'une opération de la base ressemble à une ligne du relevé, du plus probant au moins probant. */
    private static final List<BiPredicate<StatementLine, Operation>> RESEMBLANCES = List.of(
            (line, stored) -> sameLabel(line, stored) && sameValueDate(line, stored),
            Reconciler::sameLabel,
            (line, stored) -> relatedLabels(line, stored) && sameValueDate(line, stored),
            Reconciler::relatedLabels,
            Reconciler::sameValueDate,
            (_, _) -> true);

    private Reconciler() {
    }

    public static Plan plan(List<StatementLine> lines, List<Operation> existing) {
        Map<Key, List<Operation>> stored = new LinkedHashMap<>();
        existing.stream()
                .sorted(Comparator.comparingLong(Operation::id))
                .forEach(o -> stored.computeIfAbsent(new Key(o.date(), o.amountCents(), o.type()), _ -> new ArrayList<>()).add(o));
        Map<Key, List<StatementLine>> incoming = new LinkedHashMap<>();
        for (StatementLine l : lines) {
            incoming.computeIfAbsent(new Key(l.date(), l.amountCents(), l.type()), _ -> new ArrayList<>()).add(l);
        }

        List<StatementLine> toInsert = new ArrayList<>();
        List<Update> toUpdate = new ArrayList<>();
        int known = 0;
        for (Map.Entry<Key, List<StatementLine>> group : incoming.entrySet()) {
            List<Operation> candidates = stored.getOrDefault(group.getKey(), new ArrayList<>());
            List<StatementLine> unmatched = new ArrayList<>(group.getValue());
            for (BiPredicate<StatementLine, Operation> resemblance : RESEMBLANCES) {
                for (Iterator<StatementLine> pending = unmatched.iterator(); pending.hasNext() && !candidates.isEmpty();) {
                    StatementLine line = pending.next();
                    Operation match = candidates.stream().filter(o -> resemblance.test(line, o)).findFirst().orElse(null);
                    if (match == null) {
                        continue;
                    }
                    candidates.remove(match);
                    pending.remove();
                    if (line.summary() || sameLabel(line, match) && sameValueDate(line, match)) {
                        known++;
                    } else {
                        toUpdate.add(new Update(match.id(), line.label(), line.valueDate()));
                    }
                }
            }
            toInsert.addAll(unmatched);
        }
        // les relevés vont du plus récent au plus ancien : on insère dans l'ordre chronologique
        toInsert = toInsert.reversed().stream().sorted(Comparator.comparing(StatementLine::date)).toList();
        return new Plan(toInsert, toUpdate, known);
    }

    private static boolean sameLabel(StatementLine line, Operation stored) {
        return stored.label().equals(line.label());
    }

    /** Vrai aussi pour une ligne sans date de valeur : elle ne contredit celle d'aucune opération. */
    private static boolean sameValueDate(StatementLine line, Operation stored) {
        return line.valueDate() == null || line.valueDate().equals(stored.valueDate());
    }

    /** Vrai si l'un des libellés commence par l'autre : le résumé d'un relevé OFX et le libellé complet d'un relevé CSV. */
    private static boolean relatedLabels(StatementLine line, Operation stored) {
        return !line.label().isEmpty() && !stored.label().isEmpty()
                && (stored.label().startsWith(line.label()) || line.label().startsWith(stored.label()));
    }
}
