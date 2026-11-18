package io.github.pierre3119.cadesk.domain;

/** Un compte bancaire, identifié par son numéro tel qu'il figure dans l'en-tête des relevés. */
public record Account(long id, String number, String label, String holder) {

    /** Numéro masqué pour l'affichage : seuls les quatre derniers chiffres restent lisibles. */
    public String maskedNumber() {
        return number.length() <= 4 ? number : "••••\u00a0" + number.substring(number.length() - 4);
    }
}
