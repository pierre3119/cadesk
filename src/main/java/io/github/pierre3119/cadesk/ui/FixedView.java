package io.github.pierre3119.cadesk.ui;

import io.github.pierre3119.cadesk.analysis.Analysis;
import io.github.pierre3119.cadesk.analysis.Analysis.PosteStat;
import io.github.pierre3119.cadesk.analysis.Insights;
import io.github.pierre3119.cadesk.domain.Category;
import io.github.pierre3119.cadesk.domain.Money;
import io.github.pierre3119.cadesk.domain.Nature;
import io.github.pierre3119.cadesk.ui.Sheet.Col;

import java.util.List;

import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * Charges fixes : ce qui part du compte de façon régulière, poste par poste, avec le nombre de
 * paiements, la période observée, l'évolution des montants et le dernier montant payé.
 */
final class FixedView extends AnalysisPage {

    private final HBox stats = new HBox(14);
    private final VBox table = Ui.card(null);

    FixedView(AppModel model, Navigator navigator) {
        super(model, navigator);
        table.getStyleClass().add("card-flush");
        VBox body = new VBox(18, stats, table, Ui.wrapped("Les montants sont comptés dans le sens de la dépense : un "
                + "remboursement rangé dans une charge vient en déduction. Cliquez sur un poste pour voir ses opérations.", "hint"));
        body.getStyleClass().add("page-body");
        getChildren().addAll(Ui.header("Charges fixes", subtitle, Ui.periodChoice(model)), Ui.scroll(body));
    }

    @Override
    protected void rebuild(Analysis a) {
        long total = a.fixes();
        long transfers = Insights.fixedTransfers(a);
        String fixes = Nature.FIXES.styleClass();
        stats.getChildren().setAll(
                Ui.stat("Total des charges fixes", Money.format(total), overPeriod(a).toLowerCase(), "kpi", fixes),
                Ui.stat("En moyenne", Money.format(a.perMonth(total)), "par mois", "kpi", fixes),
                Ui.stat("dont virements", Money.format(a.perMonth(transfers)), "par mois : prêt, budgets…", "kpi-soft"),
                Ui.stat("dont factures", Money.format(a.perMonth(total - transfers)), "par mois : prélèvements, carte", "kpi-soft"),
                Ui.stat("Part des revenus", Fmt.percent(total, a.revenus()), "va aux charges fixes", "kpi-soft"));

        Sheet sheet = new Sheet(Col.grow(236), Col.center(56), Col.center(144), Col.center(150), Col.number(94),
                Col.number(116), Col.number(98), Col.text(84));
        sheet.header("Poste", "Nb", "Période observée", "Montants (évolution)", "Dernier montant", overPeriod(a),
                "Moyenne / mois", "Part");
        List<Category> categories = a.categories(Nature.FIXES).stream().filter(c -> a.count(c) > 0).toList();
        long highest = categories.stream().flatMap(c -> a.postes(c).stream()).mapToLong(PosteStat::total).max().orElse(0);
        for (Category category : categories) {
            long sum = a.total(category);
            Region[] head = sheet.row("group " + fixes, category.name(), String.valueOf(a.count(category)), "", "", "",
                    Money.format(sum), Money.format(a.perMonth(sum)), Fmt.percent(sum, total));
            Sheet.onClick(head, () -> navigator.showOperations(category, null));
            for (PosteStat poste : a.postes(category)) {
                Region[] cells = sheet.row("item", poste.poste(), String.valueOf(poste.count()),
                        Fmt.months(poste.first(), poste.last()), Fmt.steps(poste.amountSteps()), Money.format(poste.lastAmount()),
                        Money.format(poste.total()), Money.format(a.perMonth(poste.total())),
                        Ui.bar(highest <= 0 ? 0 : (double) poste.total() / highest, Fmt.percent(poste.total(), total)));
                cells[0].getStyleClass().add("indent");
                cells[2].getStyleClass().add("muted");
                cells[3].getStyleClass().add("muted");
                cells[5].getStyleClass().add("strong");
                cells[7].getStyleClass().add(fixes);
                Sheet.onClick(cells, () -> navigator.showOperations(category, poste.poste()));
            }
        }
        if (categories.isEmpty()) {
            sheet.band("empty", "Aucune charge fixe sur cette période.");
        } else {
            sheet.row("grand " + fixes, "TOTAL DES CHARGES FIXES", String.valueOf(a.count(Nature.FIXES)), "", "", "",
                    Money.format(total), Money.format(a.perMonth(total)), Fmt.percent(total, total));
        }
        table.getChildren().setAll(sheet);
    }
}
