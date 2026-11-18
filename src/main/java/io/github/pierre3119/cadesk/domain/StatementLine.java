package io.github.pierre3119.cadesk.domain;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Une ligne d'un relevé bancaire, telle que lue dans le fichier (pas encore enregistrée).
 *
 * <p>Un relevé CSV décrit chaque opération en entier. Un relevé OFX en donne un <em>résumé</em> : pas de
 * date de valeur, et un libellé réduit à sa première ligne, sans le nom du commerçant ni les références.
 *
 * @param date        date d'opération
 * @param valueDate   date de valeur, ou {@code null} pour la ligne d'un relevé qui ne la donne pas (OFX)
 * @param type        nature de l'opération (première ligne du libellé : « Paiement par carte », « Prélèvement »…)
 * @param label       détail du libellé, une information par ligne, espaces normalisés
 * @param amountCents montant en centimes, positif pour un crédit, négatif pour un débit
 */
public record StatementLine(LocalDate date, LocalDate valueDate, String type, String label, long amountCents) {

    public StatementLine {
        Objects.requireNonNull(date);
        Objects.requireNonNull(type);
        Objects.requireNonNull(label);
    }

    /** Vrai pour la ligne d'un relevé qui ne fait que résumer l'opération (OFX). */
    public boolean summary() {
        return valueDate == null;
    }
}
