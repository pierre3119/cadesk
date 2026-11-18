package io.github.pierre3119.cadesk.ui;

import io.github.pierre3119.cadesk.analysis.Analysis;
import io.github.pierre3119.cadesk.analysis.Analysis.PosteStat;
import io.github.pierre3119.cadesk.analysis.Insights;
import io.github.pierre3119.cadesk.analysis.Insights.Capacity;
import io.github.pierre3119.cadesk.domain.Category;
import io.github.pierre3119.cadesk.domain.Category.IncomeKind;
import io.github.pierre3119.cadesk.domain.ClassifiedOperation;
import io.github.pierre3119.cadesk.domain.Money;
import io.github.pierre3119.cadesk.domain.Nature;
import io.github.pierre3119.cadesk.ui.Sheet.Col;

import java.util.ArrayList;
import java.util.List;

import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory.IntegerSpinnerValueFactory;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

/**
 * Économies : la capacité d'épargne constatée sur la période, un simulateur dont les objectifs de
 * réduction sont enregistrés avec chaque catégorie, et des pistes tirées des opérations.
 */
final class SavingsView extends AnalysisPage {

    /** Une ligne du simulateur : sa dépense et les cellules à recalculer quand l'objectif change. */
    private record SimulatorLine(Category category, long spending, int[] percent, Label saving, Label savingPerMonth) {
    }

    private final VBox capacity = Ui.card("1  ·  Capacité d'épargne constatée sur la période");
    private final VBox simulator = Ui.card("2  ·  Simulateur : et si certaines dépenses baissaient ?");
    private final VBox leads = Ui.card("3  ·  Pistes repérées dans vos opérations");

    SavingsView(AppModel model, Navigator navigator) {
        super(model, navigator);
        VBox body = new VBox(18, capacity, simulator, leads);
        body.getStyleClass().add("page-body");
        getChildren().addAll(Ui.header("Économies", subtitle, Ui.periodChoice(model)), Ui.scroll(body));
    }

    @Override
    protected void rebuild(Analysis a) {
        Capacity capacity = Insights.capacity(a);
        capacity(a, capacity.regular(), capacity.refunds(), capacity.oneOff(), capacity.current(), capacity.beforeExceptional());
        simulator(a, capacity.current(), capacity.beforeExceptional());
        leads(a);
    }

    // ------------------------------------------------------------------ 1. capacité d'épargne

    private void capacity(Analysis a, long regular, long refunds, long oneOff, long current, long beforeExceptional) {
        Sheet sheet = new Sheet(Col.grow(300), Col.number(138), Col.number(122), Col.wrap(300));
        sheet.header("", overPeriod(a), "Par mois", "Comment lire cette ligne");
        for (Category category : a.categories(Nature.REVENUS)) {
            if (category.incomeKind() == IncomeKind.REGULIER && a.total(category) != 0) {
                step(sheet, a, "item", category.name(), a.total(category), "");
            }
        }
        step(sheet, a, "sub", "→  Revenus réguliers", regular, "Ce qui rentre de façon prévisible.");
        step(sheet, a, "item", "Remboursements reçus", refunds, "Ils compensent des dépenses déjà comptées plus bas.");
        step(sheet, a, "item", Nature.FIXES.label(), -a.fixes(), "Détail dans l'écran « Charges fixes ».");
        step(sheet, a, "item", Nature.VARIABLES.label(), -a.variables(), "Détail dans l'écran « Dépenses variables ».");
        step(sheet, a, "key", "→  Reste sur le train de vie courant", current,
                "L'équilibre « normal » du compte : revenus réguliers et remboursements, moins les dépenses courantes.");
        step(sheet, a, "item", "Autres entrées ponctuelles", oneOff, "Des rentrées réelles, mais pas garanties d'une période à l'autre.");
        step(sheet, a, "key", "→  Capacité d'épargne hors exceptionnel", beforeExceptional,
                "Ce qui pouvait être mis de côté, hors dépenses exceptionnelles.");
        step(sheet, a, "item", Nature.EXCEPTIONNELLES.label(), -a.exceptionnelles(), "Travaux, réparations, dépenses hors du commun.");
        step(sheet, a, "key", "→  Résultat de la période", a.resultat(),
                "S'il est négatif, la période a été financée en puisant dans les autres comptes (ligne suivante).");
        step(sheet, a, "info", "Virements nets reçus de mes autres comptes", a.interneIn() - a.interneOut(),
                "Ce qui a été repris sur l'épargne, net de ce qui y a été versé.");
        step(sheet, a, "info", "Variation du solde du compte sur la période", a.solde(),
                "Résultat + virements nets : identique à l'écart crédits − débits des opérations.");
        capacity.getChildren().setAll(capacity.getChildren().getFirst(), sheet,
                Ui.wrapped("La régularité de chaque revenu (régulier, remboursement, ponctuel) se règle dans l'écran « Catégories ».", "hint"));
    }

    private static void step(Sheet sheet, Analysis a, String classes, String label, long amount, String how) {
        Region[] cells = sheet.row("step " + classes, label, Money.formatSigned(amount), Money.formatSigned(a.perMonth(amount)), how);
        String color = amount < 0 ? "amount-out" : "amount-in";
        cells[1].getStyleClass().add(color);
        cells[2].getStyleClass().add(color);
        cells[3].getStyleClass().add("muted");
    }

    // ------------------------------------------------------------------ 2. simulateur

    private void simulator(Analysis a, long current, long beforeExceptional) {
        Sheet sheet = new Sheet(Col.grow(228), Col.number(112), Col.number(108), Col.center(140), Col.number(112), Col.number(100),
                Col.text(158));
        sheet.header("Poste", "Dépense " + overPeriod(a).toLowerCase(), "Dépense par mois", "Objectif de réduction",
                "Économie " + overPeriod(a).toLowerCase(), "Économie par mois", "Nature");
        List<SimulatorLine> lines = new ArrayList<>();
        Label totalPercent = new Label();
        Label totalSaving = new Label();
        Label totalPerMonth = new Label();
        VBox today = Ui.stat("Train de vie courant aujourd'hui", Money.formatSigned(a.perMonth(current)), "reste par mois", "kpi-soft");
        Label withSavings = new Label();
        Label capacityWithSavings = new Label();
        Runnable refresh = () -> {
            long spending = lines.stream().mapToLong(SimulatorLine::spending).sum();
            long saving = 0;
            for (SimulatorLine line : lines) {
                long lineSaving = Math.round(line.spending() * line.percent()[0] / 100.0);
                saving += lineSaving;
                line.saving().setText(Money.format(lineSaving));
                line.savingPerMonth().setText(Money.format(a.perMonth(lineSaving)));
            }
            totalPercent.setText(Fmt.percent(saving, spending));
            totalSaving.setText(Money.format(saving));
            totalPerMonth.setText(Money.format(a.perMonth(saving)));
            withSavings.setText(Money.formatSigned(a.perMonth(current + saving)));
            capacityWithSavings.setText(Money.formatSigned(a.perMonth(beforeExceptional + saving)));
        };

        for (Category category : Insights.simulated(a)) {
            Nature nature = category.nature();
            long spending = a.total(category);
            int[] percent = {savedPercent(category)};
            Spinner<Integer> objective = objective(percent[0]);
            Region[] cells = sheet.row("tint " + nature.styleClass(), category.name(), Money.format(spending),
                    Money.format(a.perMonth(spending)), objective, "", "", Ui.label(nature.label(), "nature-text"));
            cells[0].getStyleClass().add("strong");
            cells[4].getStyleClass().add("strong");
            cells[5].getStyleClass().add("strong");
            SimulatorLine line = new SimulatorLine(category, spending, percent, (Label) cells[4], (Label) cells[5]);
            lines.add(line);
            objective.valueProperty().addListener((_, _, value) -> {
                percent[0] = value;
                model.setSavingPercent(category, value);
                refresh.run();
            });
        }
        long spending = lines.stream().mapToLong(SimulatorLine::spending).sum();
        Region[] total = sheet.row("grand " + Nature.VARIABLES.styleClass(), "TOTAL SIMULÉ", Money.format(spending),
                Money.format(a.perMonth(spending)), totalPercent, totalSaving, totalPerMonth, "");
        total[3].getStyleClass().add("grand-node");
        total[4].getStyleClass().add("grand-node");
        total[5].getStyleClass().add("grand-node");

        VBox after = Ui.stat("Train de vie courant avec ces économies", "", "reste par mois", "kpi", Nature.REVENUS.styleClass());
        VBox capacityAfter = Ui.stat("Capacité d'épargne avec ces économies", "", "par mois, hors exceptionnel", "kpi",
                Nature.REVENUS.styleClass());
        ((Label) after.getChildren().get(1)).textProperty().bind(withSavings.textProperty());
        ((Label) capacityAfter.getChildren().get(1)).textProperty().bind(capacityWithSavings.textProperty());
        refresh.run();

        simulator.getChildren().setAll(simulator.getChildren().getFirst(),
                Ui.wrapped("Réglez pour chaque poste l'objectif de réduction qui vous paraît tenable : les économies se recalculent, "
                        + "et vos objectifs sont conservés.", "hint"),
                sheet, new HBox(14, today, after, capacityAfter));
    }

    /** L'objectif enregistré, lu dans la liste vivante des catégories (le bilan affiché peut être plus ancien). */
    private int savedPercent(Category category) {
        return model.categories.stream().filter(c -> c.id() == category.id()).mapToInt(Category::savingPercent).findFirst()
                .orElse(category.savingPercent());
    }

    private static Spinner<Integer> objective(int percent) {
        IntegerSpinnerValueFactory values = new IntegerSpinnerValueFactory(0, 100, percent, 5);
        values.setConverter(new StringConverter<>() {
            @Override
            public String toString(Integer value) {
                return value == null ? "" : value + "\u00a0%";
            }

            @Override
            public Integer fromString(String text) {
                try {
                    return Integer.valueOf(text.replaceAll("[^0-9]", ""));
                } catch (NumberFormatException e) {
                    return 0;
                }
            }
        });
        Spinner<Integer> spinner = new Spinner<>(values);
        spinner.getStyleClass().add(Spinner.STYLE_CLASS_SPLIT_ARROWS_HORIZONTAL);
        spinner.setPrefWidth(118);
        return spinner;
    }

    // ------------------------------------------------------------------ 3. pistes

    private void leads(Analysis a) {
        Sheet sheet = new Sheet(Col.grow(240), Col.number(120), Col.wrap(360));
        sheet.header("Piste", "Montant " + overPeriod(a).toLowerCase(), "Constat");
        long variables = a.variables();

        List<PosteStat> biggest = Insights.biggestVariable(a);
        if (!biggest.isEmpty()) {
            sheet.band("group " + Nature.VARIABLES.styleClass(), "Les plus gros postes de dépenses variables");
            for (PosteStat poste : biggest) {
                lead(sheet, poste, Fmt.plural(poste.count(), "opération", "opérations") + ", soit " + Money.format(a.perMonth(poste.total()))
                        + " par mois et " + Fmt.percent(poste.total(), variables) + " des dépenses variables  ·  " + poste.category().name());
            }
        }
        List<PosteStat> frequent = Insights.frequentVariable(a);
        if (!frequent.isEmpty()) {
            sheet.band("group " + Nature.VARIABLES.styleClass(), "Les petites dépenses qui reviennent souvent");
            for (PosteStat poste : frequent) {
                lead(sheet, poste, Fmt.plural(poste.count(), "paiement", "paiements") + " sur la période, montant moyen "
                        + Money.format(poste.total() / poste.count()) + "  ·  " + poste.category().name());
            }
        }
        List<PosteStat> recurring = Insights.recurringFixed(a);
        if (!recurring.isEmpty()) {
            sheet.band("group " + Nature.FIXES.styleClass(), "Les factures et abonnements à comparer ou à renégocier");
            for (PosteStat poste : recurring) {
                lead(sheet, poste, Fmt.plural(poste.count(), "paiement", "paiements") + " ; dernier montant "
                        + Money.format(poste.lastAmount()) + " le " + Fmt.date(poste.last()) + "  ·  " + poste.category().name());
            }
        }
        List<ClassifiedOperation> pending = Insights.pendingSpending(a);
        if (!pending.isEmpty()) {
            sheet.band("group " + Nature.INTERNE.styleClass(), "Les dépenses qui attendent une catégorie");
            Region[] cells = sheet.row("item", "Opérations à classer", Money.format(pending.stream().mapToLong(ClassifiedOperation::amount).sum()),
                    Fmt.plural(pending.size(), "opération n'a", "opérations n'ont") + " pas été reconnue" + (pending.size() > 1 ? "s" : "")
                            + " : les classer rend ce bilan plus juste.");
            cells[0].getStyleClass().add("strong");
            Sheet.onClick(cells, navigator::showUnclassified);
        }
        if (sheet.getChildren().size() <= sheet.columnCount()) {
            sheet.band("empty", "Aucune dépense sur cette période.");
        }
        leads.getChildren().setAll(leads.getChildren().getFirst(), sheet,
                Ui.wrapped("Ces pistes sont des constats tirés de vos opérations, pas des recommandations personnalisées : à vous de "
                        + "juger ce qui en vaut la peine. Cliquez sur une ligne pour voir les opérations.", "hint"));
    }

    private void lead(Sheet sheet, PosteStat poste, String text) {
        Region[] cells = sheet.row("item", poste.poste(), Money.format(poste.total()), text);
        cells[0].getStyleClass().add("strong");
        cells[1].getStyleClass().add("strong");
        cells[2].getStyleClass().add("muted");
        Sheet.onClick(cells, () -> navigator.showOperations(poste.category(), poste.poste()));
    }
}
