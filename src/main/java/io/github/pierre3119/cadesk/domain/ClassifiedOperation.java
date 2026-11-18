package io.github.pierre3119.cadesk.domain;

import java.time.YearMonth;

/**
 * Une opération et son classement.
 *
 * @param poste  nom lisible du commerçant, du tiers ou de la charge (« Spotify », « Orange – salaire »)
 * @param source d'où vient la catégorie
 * @param rule   la règle qui a classé l'opération, ou {@code null}
 */
public record ClassifiedOperation(Operation operation, Category category, String poste, Source source, Rule rule) {

    /** Origine du classement d'une opération. */
    public enum Source {
        /** Catégorie choisie à la main pour cette opération. */
        MANUAL,
        /** Catégorie attribuée par l'assistant : activité semblable déjà classée, ou proposition du LLM. */
        ASSISTANT,
        /** Reconnue par une règle. */
        RULE,
        /** Reconnue par aucune règle : rangée dans la catégorie par défaut de son sens. */
        DEFAULT
    }

    public Nature nature() {
        return category.nature();
    }

    /** Vrai si la catégorie est enregistrée avec l'opération (à la main ou par l'assistant) et passe avant les règles. */
    public boolean isPinned() {
        return source == Source.MANUAL || source == Source.ASSISTANT;
    }

    /** Montant compté dans le sens naturel de la catégorie (voir {@link Category#signed}). */
    public long amount() {
        return category.signed(operation.amountCents());
    }

    public YearMonth month() {
        return YearMonth.from(operation.date());
    }

    /** Vrai si l'opération attend encore une vraie catégorie. */
    public boolean isUnclassified() {
        return category.isFallback();
    }
}
