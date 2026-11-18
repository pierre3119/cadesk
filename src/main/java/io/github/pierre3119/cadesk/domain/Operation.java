package io.github.pierre3119.cadesk.domain;

import java.time.LocalDate;
import java.util.List;

/**
 * Une opération enregistrée en base.
 *
 * @param type             nature de l'opération selon la banque (« Paiement par carte », « Prélèvement »…)
 * @param label            détail du libellé, une information par ligne
 * @param amountCents      montant en centimes, positif pour un crédit, négatif pour un débit
 * @param manualCategoryId catégorie enregistrée avec l'opération, ou {@code null} si elle est classée par les règles
 * @param assisted         vrai si cette catégorie a été attribuée par l'assistant plutôt que choisie à la main
 */
public record Operation(long id, LocalDate date, LocalDate valueDate, String type, String label, long amountCents,
        Long manualCategoryId, boolean assisted) {

    public Operation(long id, LocalDate date, LocalDate valueDate, String type, String label, long amountCents,
            Long manualCategoryId) {
        this(id, date, valueDate, type, label, amountCents, manualCategoryId, false);
    }

    public Operation(long id, LocalDate date, LocalDate valueDate, String type, String label, long amountCents) {
        this(id, date, valueDate, type, label, amountCents, null, false);
    }

    public boolean isCredit() {
        return amountCents > 0;
    }

    /** Lignes du libellé. */
    public List<String> lines() {
        return label.isBlank() ? List.of() : label.lines().toList();
    }

    /** Première ligne du libellé, ou le type d'opération quand la banque ne donne aucun détail. */
    public String title() {
        return label.isBlank() ? type : label.lines().findFirst().orElse(type);
    }
}
