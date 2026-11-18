package io.github.pierre3119.cadesk.domain;

import java.time.LocalDate;
import java.util.List;

/**
 * Contenu d'un relevé (CSV ou OFX) : l'en-tête (compte, période, solde) et ses lignes, dans l'ordre du fichier.
 * Les champs d'en-tête facultatifs valent {@code null} quand le fichier ne les fournit pas.
 */
public record Statement(
        String accountNumber,
        String accountLabel,
        String holder,
        LocalDate downloadDate,
        LocalDate periodStart,
        LocalDate periodEnd,
        Long balanceCents,
        LocalDate balanceDate,
        List<StatementLine> lines) {

    public Statement {
        lines = List.copyOf(lines);
    }
}
