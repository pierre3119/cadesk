/**
 * CaDesk : import des relevés du Crédit Agricole (CSV et OFX), classement par catégories et bilan.
 *
 * @author Pierre Duloy (<a href="https://github.com/pierre3119">GitHub</a>,
 *         <a href="https://www.linkedin.com/in/pierre-duloy-2101929/">LinkedIn</a>)
 */
module cadesk {
    requires javafx.controls;
    requires atlantafx.base;
    requires org.kordamp.ikonli.javafx;
    requires org.kordamp.ikonli.feather;
    requires java.sql;
    // le SDK de Copilot journalise par java.util.logging : l'application règle ce journal
    requires java.logging;
    requires org.xerial.sqlitejdbc;
    requires java.net.http;
    requires com.google.gson;
    requires com.microsoft.copilot.java;
    requires org.apache.pdfbox;
    // PDFBox n'a pas de descripteur de module : ce dont il a besoin ne fait partie de l'exécution que si
    // l'application le déclare pour lui. Sans Commons Logging, par lequel il journalise, le rapport PDF échoue ;
    // sans jdk.unsupported, il signale une erreur à la première écriture (libération de fichiers projetés
    // en mémoire). Les tests ordinaires ne le voient pas : voir LauncherTest.
    requires org.apache.commons.logging;
    requires jdk.unsupported;
    // PDFBox s'appuie sur AWT (couleurs, géométrie) : le module doit faire partie de l'exécution
    requires java.desktop;

    // JavaFX instancie la classe Application par réflexion
    exports io.github.pierre3119.cadesk.ui to javafx.graphics;
}
