package io.github.pierre3119.cadesk.ui;

import io.github.pierre3119.cadesk.profile.Session;

/** Ce que la fenêtre principale demande à l'application qui l'héberge. */
interface Shell {

    /** Ouvre le sélecteur de fichiers pour importer des relevés. */
    void chooseFiles();

    /**
     * Affiche cette session à la place de la session courante, déjà fermée par l'opération qui a produit
     * la nouvelle : profil rouvert après un changement de chiffrement, profil ouvert par un administrateur…
     *
     * @param onProfilePage vrai pour arriver sur l'écran « Profil et sécurité » plutôt que sur la synthèse
     */
    void show(Session session, boolean onProfilePage);

    /** Ferme le profil et revient au choix du profil ; {@code message} y est affiché s'il n'est pas {@code null}. */
    void leave(String message);

    /** Ouvre une page web dans le navigateur par défaut de l'utilisateur. */
    void browse(String url);
}
