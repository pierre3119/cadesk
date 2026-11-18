package io.github.pierre3119.cadesk.ui;

import io.github.pierre3119.cadesk.analysis.Analysis;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import javafx.scene.Node;
import javafx.stage.FileChooser;

/** Export du rapport PDF de la période affichée : contenu, choix du fichier, écriture. */
final class ReportExport {

    private static final String FOLDER_SETTING = "export.folder";
    private static final String OPERATIONS = "operations";

    private ReportExport() {
    }

    /** Demande ce que doit contenir le rapport et où l'enregistrer, puis lance son écriture. */
    static void ask(AppModel model, Node owner) {
        Analysis analysis = model.analysis.get();
        if (analysis.isEmpty()) {
            Ui.error(owner, "Rien à exporter", "Aucune opération sur la période affichée.");
            return;
        }
        FormDialog.of(owner, "Exporter le rapport en PDF", "Le rapport reprend les chiffres et les graphiques des écrans de bilan — "
                + "synthèse, bilan mensuel, charges fixes, dépenses variables, économies — pour la période affichée : "
                + Fmt.period(model.period.get()) + ".\n" + Fmt.span(analysis) + ".")
                .check(OPERATIONS, "Terminer par la liste détaillée des opérations de la période", true)
                .button("Choisir le fichier…").ask().ifPresent(answers -> {
                    FileChooser chooser = new FileChooser();
                    chooser.setTitle("Enregistrer le rapport");
                    chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Document PDF", "*.pdf"));
                    chooser.setInitialFileName("rapport-comptes-" + analysis.months().getFirst() + "_" + analysis.months().getLast() + ".pdf");
                    model.setting(FOLDER_SETTING).map(Path::of).filter(Files::isDirectory)
                            .ifPresent(folder -> chooser.setInitialDirectory(folder.toFile()));
                    File chosen = chooser.showSaveDialog(owner.getScene().getWindow());
                    if (chosen != null) {
                        File file = chosen.getName().toLowerCase().endsWith(".pdf") ? chosen : new File(chosen.getPath() + ".pdf");
                        model.saveSetting(FOLDER_SETTING, file.getAbsoluteFile().getParent());
                        model.exportReport(file.toPath(), answers.checked(OPERATIONS));
                    }
                });
    }
}
