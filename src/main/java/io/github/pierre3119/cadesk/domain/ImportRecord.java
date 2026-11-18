package io.github.pierre3119.cadesk.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Trace d'un import de relevé.
 *
 * @param rowsRead    lignes lues dans le fichier
 * @param rowsNew     opérations ajoutées à la base
 * @param rowsKnown   opérations déjà présentes à l'identique (ignorées)
 * @param rowsUpdated opérations déjà présentes dont la banque a modifié le libellé (mises à jour, non dupliquées)
 */
public record ImportRecord(
        long id,
        long accountId,
        String fileName,
        LocalDateTime importedAt,
        LocalDate periodStart,
        LocalDate periodEnd,
        Long balanceCents,
        LocalDate balanceDate,
        int rowsRead,
        int rowsNew,
        int rowsKnown,
        int rowsUpdated) {
}
