package io.github.pierre3119.cadesk.domain;

import java.util.List;

/**
 * Une règle de classement : les opérations qu'elle reconnaît vont dans sa catégorie.
 *
 * <p>Une règle reconnaît une opération si le type d'opération est le sien (quand elle en fixe un) et si
 * l'un de ses mots-clés figure dans le libellé. Un mot-clé « A + B » exige A et B ; sans mot-clé, toutes
 * les opérations du type sont reconnues. Les règles sont essayées dans l'ordre de {@code position} :
 * la première qui correspond l'emporte.
 *
 * @param id            identifiant en base ; 0 pour une règle pas encore enregistrée
 * @param poste         nom donné aux opérations reconnues ; {@code null} pour reprendre le nom du commerçant
 *                      ou du tiers figurant dans le libellé
 * @param operationType type d'opération exigé (« Paiement par carte »…), ou {@code null} pour tous
 * @param keywords      mots-clés en majuscules sans accents
 */
public record Rule(long id, long categoryId, String poste, String operationType, List<String> keywords, int position) {

    public Rule {
        poste = poste == null || poste.isBlank() ? null : poste.strip();
        operationType = operationType == null || operationType.isBlank() ? null : operationType.strip();
        keywords = List.copyOf(keywords);
        if (operationType == null && keywords.isEmpty()) {
            throw new IllegalArgumentException("Une règle doit préciser un type d'opération ou au moins un mot-clé.");
        }
    }

    /** Nouvelle règle, pas encore enregistrée. */
    public static Rule create(long categoryId, String poste, String operationType, List<String> keywords) {
        return new Rule(0, categoryId, poste, operationType, keywords, 0);
    }
}
