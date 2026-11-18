package io.github.pierre3119.cadesk.ui;

import atlantafx.base.theme.Styles;
import io.github.pierre3119.cadesk.analysis.Analysis;
import io.github.pierre3119.cadesk.analysis.Analysis.PosteStat;
import io.github.pierre3119.cadesk.domain.Category;
import io.github.pierre3119.cadesk.domain.ClassifiedOperation;
import io.github.pierre3119.cadesk.domain.Money;
import io.github.pierre3119.cadesk.domain.Nature;
import io.github.pierre3119.cadesk.ui.Sheet.Col;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import org.kordamp.ikonli.feather.Feather;
import org.kordamp.ikonli.javafx.FontIcon;

/** Dépenses variables : par catégorie, puis les plus gros postes (commerçants et bénéficiaires). */
final class VariableView extends AnalysisPage {

    private static final int TOP = 30;

    private final HBox stats = new HBox(14);
    private final VBox byCategory = Ui.card("Par catégorie");
    private final HBox pending = new HBox(12);
    private final VBox topPostes = Ui.card("Les " + TOP + " plus gros postes de dépenses variables");

    VariableView(AppModel model, Navigator navigator) {
        super(model, navigator);
        pending.getStyleClass().add("callout");
        pending.setAlignment(Pos.CENTER_LEFT);
        VBox body = new VBox(18, stats, byCategory, pending, topPostes);
        body.getStyleClass().add("page-body");
        getChildren().addAll(Ui.header("Dépenses variables", subtitle, Ui.periodChoice(model)), Ui.scroll(body));
    }

    @Override
    protected void rebuild(Analysis a) {
        long total = a.variables();
        int count = a.count(Nature.VARIABLES);
        String variables = Nature.VARIABLES.styleClass();
        stats.getChildren().setAll(
                Ui.stat("Total des dépenses variables", Money.format(total), overPeriod(a).toLowerCase(), "kpi", variables),
                Ui.stat("En moyenne", Money.format(a.perMonth(total)), "par mois", "kpi", variables),
                Ui.stat("Opérations", Fmt.count(count),
                        count == 0 ? "aucune dépense" : "montant moyen " + Money.format(total / count), "kpi-soft"),
                Ui.stat("Part des revenus", Fmt.percent(total, a.revenus()), "consacrée aux dépenses variables", "kpi-soft"));

        categories(a, total);
        pending(a);
        postes(a, total);
    }

    private void categories(Analysis a, long total) {
        Sheet sheet = new Sheet(Col.grow(230), Col.center(56), Col.number(118), Col.number(108), Col.text(90), Col.number(100),
                Col.wrap(196));
        sheet.header("Catégorie", "Nb", overPeriod(a), "Moyenne / mois", "Part", "Montant moyen", "Principaux postes");
        List<Category> categories = a.categories(Nature.VARIABLES).stream().filter(c -> a.count(c) > 0)
                .sorted(Comparator.comparingLong((Category c) -> a.total(c)).reversed()).toList();
        long highest = categories.isEmpty() ? 0 : a.total(categories.getFirst());
        for (Category category : categories) {
            long sum = a.total(category);
            List<PosteStat> postes = a.postes(category);
            String main = postes.stream().limit(3).map(p -> p.poste() + " (" + Fmt.euros(p.total()) + ")")
                    .collect(Collectors.joining("  ·  ")) + (postes.size() > 3 ? "  ·  + " + (postes.size() - 3) + " autres" : "");
            Region[] cells = sheet.row("item", category.name(), String.valueOf(a.count(category)), Money.format(sum),
                    Money.format(a.perMonth(sum)), Ui.bar(highest <= 0 ? 0 : (double) sum / highest, Fmt.percent(sum, total)),
                    Money.format(sum / a.count(category)), main);
            cells[0].getStyleClass().add("strong");
            cells[2].getStyleClass().add("strong");
            cells[4].getStyleClass().add(Nature.VARIABLES.styleClass());
            cells[5].getStyleClass().add("muted");
            cells[6].getStyleClass().add("muted");
            Sheet.onClick(cells, () -> navigator.showOperations(category, null));
        }
        if (categories.isEmpty()) {
            sheet.band("empty", "Aucune dépense variable sur cette période.");
        } else {
            int count = a.count(Nature.VARIABLES);
            sheet.row("grand " + Nature.VARIABLES.styleClass(), "TOTAL DES DÉPENSES VARIABLES", String.valueOf(count),
                    Money.format(total), Money.format(a.perMonth(total)), Fmt.percent(total, total), Money.format(total / count), "");
        }
        byCategory.getChildren().setAll(byCategory.getChildren().getFirst(), sheet);
    }

    /** Rappel des opérations que le classement automatique n'a pas reconnues. */
    private void pending(Analysis a) {
        List<ClassifiedOperation> unclassified = a.operations().stream().filter(ClassifiedOperation::isUnclassified).toList();
        pending.setVisible(!unclassified.isEmpty());
        pending.setManaged(!unclassified.isEmpty());
        if (unclassified.isEmpty()) {
            return;
        }
        long amount = unclassified.stream().filter(o -> !o.category().inflow()).mapToLong(ClassifiedOperation::amount).sum();
        FontIcon icon = new FontIcon(Feather.HELP_CIRCLE);
        Label text = Ui.wrapped(Fmt.plural(unclassified.size(), "opération attend", "opérations attendent")
                + " encore une catégorie sur cette période (" + Money.format(amount) + " de dépenses). Tant qu'elles ne sont pas "
                + "classées, elles sont comptées dans « à classer ».");
        HBox.setHgrow(text, Priority.ALWAYS);
        text.setMaxWidth(Double.MAX_VALUE);
        Button show = new Button("Les classer", new FontIcon(Feather.TAG));
        show.getStyleClass().add(Styles.ACCENT);
        show.setMinWidth(Region.USE_PREF_SIZE);
        show.setOnAction(_ -> navigator.showUnclassified());
        pending.getChildren().setAll(icon, text, show);
    }

    private void postes(Analysis a, long total) {
        Sheet sheet = new Sheet(Col.grow(220), Col.center(56), Col.number(118), Col.number(104), Col.text(90), Col.number(100),
                Col.grow(210));
        sheet.header("Poste", "Nb", overPeriod(a), "Moyenne / mois", "Part", "Montant moyen", "Catégorie");
        List<PosteStat> postes = a.postes(Nature.VARIABLES).stream().limit(TOP).toList();
        long highest = postes.isEmpty() ? 0 : postes.getFirst().total();
        for (PosteStat poste : postes) {
            Region[] cells = sheet.row("item", poste.poste(), String.valueOf(poste.count()), Money.format(poste.total()),
                    Money.format(a.perMonth(poste.total())),
                    Ui.bar(highest <= 0 ? 0 : (double) poste.total() / highest, Fmt.percent(poste.total(), total)),
                    Money.format(poste.total() / poste.count()), poste.category().name());
            cells[0].getStyleClass().add("strong");
            cells[2].getStyleClass().add("strong");
            cells[4].getStyleClass().add(Nature.VARIABLES.styleClass());
            cells[5].getStyleClass().add("muted");
            cells[6].getStyleClass().add("muted");
            Sheet.onClick(cells, () -> navigator.showOperations(poste.category(), poste.poste()));
        }
        if (postes.isEmpty()) {
            sheet.band("empty", "Aucune dépense variable sur cette période.");
        }
        topPostes.getChildren().setAll(topPostes.getChildren().getFirst(), sheet);
    }
}
