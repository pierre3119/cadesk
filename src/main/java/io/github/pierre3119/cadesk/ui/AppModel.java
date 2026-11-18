package io.github.pierre3119.cadesk.ui;

import io.github.pierre3119.cadesk.analysis.Analysis;
import io.github.pierre3119.cadesk.analysis.Period;
import io.github.pierre3119.cadesk.categorize.Classifier;
import io.github.pierre3119.cadesk.db.AccountRepository;
import io.github.pierre3119.cadesk.db.CategoryRepository;
import io.github.pierre3119.cadesk.db.Database;
import io.github.pierre3119.cadesk.db.ImportRepository;
import io.github.pierre3119.cadesk.db.OperationRepository;
import io.github.pierre3119.cadesk.db.RuleRepository;
import io.github.pierre3119.cadesk.db.SettingsRepository;
import io.github.pierre3119.cadesk.domain.Account;
import io.github.pierre3119.cadesk.domain.Category;
import io.github.pierre3119.cadesk.domain.ClassifiedOperation;
import io.github.pierre3119.cadesk.domain.ClassifiedOperation.Source;
import io.github.pierre3119.cadesk.domain.ImportRecord;
import io.github.pierre3119.cadesk.domain.Operation;
import io.github.pierre3119.cadesk.domain.Rule;
import io.github.pierre3119.cadesk.importer.ImportService;
import io.github.pierre3119.cadesk.importer.ImportService.Outcome;
import io.github.pierre3119.cadesk.llm.Assistant;
import io.github.pierre3119.cadesk.llm.Assistant.Applied;
import io.github.pierre3119.cadesk.llm.Assistant.Proposal;
import io.github.pierre3119.cadesk.llm.Assistant.Suggestion;
import io.github.pierre3119.cadesk.llm.CopilotLogin;
import io.github.pierre3119.cadesk.llm.LlmClient;
import io.github.pierre3119.cadesk.llm.LlmException;
import io.github.pierre3119.cadesk.llm.LlmSettings;
import io.github.pierre3119.cadesk.profile.Profile;
import io.github.pierre3119.cadesk.profile.ProfileException;
import io.github.pierre3119.cadesk.profile.ProfileManager;
import io.github.pierre3119.cadesk.profile.Session;
import io.github.pierre3119.cadesk.report.PdfReport;
import io.github.pierre3119.cadesk.security.WrongPasswordException;
import io.github.pierre3119.cadesk.statement.StatementFormatException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

/**
 * État du profil ouvert, partagé par les écrans. Les accès à la base se font sur un fil d'exécution
 * dédié ; les propriétés observables ne sont modifiées que sur le fil JavaFX.
 *
 * <p>Après chaque chargement, {@link #analysis} est remplacée en dernier : c'est le signal que les
 * écrans écoutent pour se redessiner, toutes les listes étant alors à jour.
 */
final class AppModel {

    enum NoticeKind { SUCCESS, INFO, ERROR }

    /** Message affiché en bandeau (résultat d'un import, erreur). */
    record Notice(NoticeKind kind, String text) {
    }

    /** Opérations qu'une passe de l'assistant réexamine. */
    enum Reach {
        UNCLASSIFIED("Seulement les opérations à classer"),
        AUTOMATIC("Toutes, sauf celles classées à la main"),
        ALL("Toutes, y compris celles classées à la main");

        private final String label;

        Reach(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** Modification de la base, exécutée dans une transaction ; peut renvoyer un message à afficher. */
    @FunctionalInterface
    private interface Change {
        String apply(Connection c) throws SQLException;
    }

    private record Snapshot(List<Account> accounts, Account current, List<Category> categories, List<Rule> rules,
            List<ClassifiedOperation> operations, List<ImportRecord> imports) {
    }

    final ObservableList<Account> accounts = FXCollections.observableArrayList();
    final ObjectProperty<Account> account = new SimpleObjectProperty<>();
    final ObservableList<Category> categories = FXCollections.observableArrayList();
    final ObservableList<Rule> rules = FXCollections.observableArrayList();
    /** Toutes les opérations du compte, classées, de la plus récente à la plus ancienne. */
    final ObservableList<ClassifiedOperation> operations = FXCollections.observableArrayList();
    final ObservableList<ImportRecord> imports = FXCollections.observableArrayList();
    /** Périodes proposées ; la dernière, quand il y a des opérations, est la période choisie entre deux dates. */
    final ObservableList<Period> periods = FXCollections.observableArrayList(Period.ALL);
    /** Période d'analyse commune aux écrans de bilan. */
    final ObjectProperty<Period> period = new SimpleObjectProperty<>(Period.ALL);
    /** Bilan de la période choisie. */
    final ObjectProperty<Analysis> analysis = new SimpleObjectProperty<>(new Analysis(List.of(), List.of()));
    final BooleanProperty busy = new SimpleBooleanProperty();
    final ObjectProperty<Notice> notice = new SimpleObjectProperty<>();
    /** Le profil ouvert, dans son état courant (nom, rôle, protection). */
    final ObjectProperty<Profile> profile = new SimpleObjectProperty<>();
    /** Réglages de l'assistant pour ce profil. */
    final ObjectProperty<LlmSettings> llm = new SimpleObjectProperty<>(LlmSettings.NONE);
    /** Avancement de l'assistant (« 80 activités sur 240 »), vide quand il ne travaille pas. */
    final StringProperty progress = new SimpleStringProperty("");

    final ProfileManager profiles;
    final Session session;
    /** Fabrique du client du modèle de langage ; remplaçable dans les tests. */
    Function<LlmSettings, LlmClient> llmClients = LlmClient::of;
    /** Fabrique de la connexion à GitHub, par un code à saisir ou non ; remplaçable dans les tests. */
    Function<Boolean, CopilotLogin> githubLogins = CopilotLogin::new;

    private final Database database;
    private final ImportService importService;
    private final AccountRepository accountRepository = new AccountRepository();
    private final OperationRepository operationRepository = new OperationRepository();
    private final CategoryRepository categoryRepository = new CategoryRepository();
    private final RuleRepository ruleRepository = new RuleRepository();
    private final ImportRepository importRepository = new ImportRepository();
    private final SettingsRepository settings = new SettingsRepository();
    private final ExecutorService worker = executor("cadesk-base");
    /** Tâches longues qui ne touchent pas à la base (appels au modèle de langage, clés d'un profil) : la base reste disponible. */
    private final ExecutorService background = executor("cadesk-fond");
    private Period customRange;
    private int running;
    private boolean applying;

    AppModel(ProfileManager profiles, Session session) {
        this.profiles = profiles;
        this.session = session;
        this.database = session.database();
        this.importService = new ImportService(database);
        profile.set(session.profile());
        llm.set(database.inTransaction(this::readLlm));
        account.addListener((_, _, selected) -> {
            if (!applying && selected != null) {
                reload(selected.id());
            }
        });
        period.addListener((_, _, _) -> {
            if (!applying) {
                analyse();
            }
        });
    }

    private static ExecutorService executor(String name) {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        });
    }

    Path databaseFile() {
        return database.file();
    }

    /** Recharge comptes, catégories, règles, opérations et imports ; {@code accountId} désigne le compte à afficher (ou {@code null}). */
    void reload(Long accountId) {
        run(() -> apply(load(accountId)));
    }

    /**
     * Importe les fichiers l'un après l'autre, fait classer les opérations nouvelles par l'assistant s'il
     * est activé, puis recharge l'affichage sur le compte du dernier import réussi.
     */
    void importFiles(List<Path> files) {
        Long accountBefore = currentAccountId();
        LlmSettings assistant = llm.get();
        run(() -> {
            List<String> messages = new ArrayList<>();
            NoticeKind kind = NoticeKind.INFO;
            Long accountId = null;
            Map<Long, Set<Long>> added = new LinkedHashMap<>();
            for (Path file : files) {
                try {
                    Outcome outcome = importService.importFile(file);
                    accountId = outcome.account().id();
                    messages.add(describe(outcome));
                    if (outcome.record().rowsNew() + outcome.record().rowsUpdated() > 0 && kind != NoticeKind.ERROR) {
                        kind = NoticeKind.SUCCESS;
                    }
                    if (outcome.record().rowsNew() > 0) {
                        List<Long> ids = database.inTransaction(c -> operationRepository.findIdsByImport(c, outcome.record().id()));
                        added.computeIfAbsent(outcome.account().id(), _ -> new HashSet<>()).addAll(ids);
                    }
                } catch (StatementFormatException e) {
                    kind = NoticeKind.ERROR;
                    messages.add("« " + file.getFileName() + " » n'a pas été importé. " + e.getMessage());
                } catch (RuntimeException e) {
                    kind = NoticeKind.ERROR;
                    messages.add("« " + file.getFileName() + " » n'a pas été importé : " + e.getMessage());
                }
            }
            if (assistant.classifiesImports() && !added.isEmpty()) {
                try {
                    classifyImported(assistant, added).ifPresent(messages::add);
                } catch (RuntimeException e) {
                    messages.add("Assistant : les opérations nouvelles n'ont pas pu être classées. " + message(e));
                } finally {
                    Platform.runLater(() -> progress.set(""));
                }
            }
            Snapshot snapshot = load(accountId != null ? accountId : accountBefore);
            Notice result = new Notice(kind, String.join("\n", messages));
            apply(snapshot);
            Platform.runLater(() -> notice.set(result));
        });
    }

    // ------------------------------------------------------------------ classement

    /** Range ces opérations dans une catégorie choisie à la main ; {@code null} rend la main au classement automatique. */
    void categorize(Collection<Long> operationIds, Category category) {
        List<Long> ids = List.copyOf(operationIds);
        change(c -> {
            operationRepository.setCategory(c, ids, category == null ? null : category.id());
            return null;
        });
    }

    /**
     * Crée une règle prioritaire, pour que les opérations semblables — présentes et à venir — soient classées
     * comme celles-ci. Les opérations désignées que la règle ne reconnaîtrait pas sont rangées à la main.
     */
    void categorizeWithRule(Collection<Long> operationIds, Rule rule) {
        List<Long> ids = List.copyOf(operationIds);
        Long accountId = currentAccountId();
        change(c -> {
            Rule created = ruleRepository.insertFirst(c, rule);
            if (accountId == null) {
                return null;
            }
            List<Category> all = categoryRepository.findAll(c);
            List<ClassifiedOperation> classified = new Classifier(all, ruleRepository.findAll(c))
                    .classify(operationRepository.findByAccount(c, accountId).stream()
                            .map(o -> ids.contains(o.id()) ? withoutManualCategory(o) : o).toList());
            List<Long> recognised = new ArrayList<>();
            List<Long> leftovers = new ArrayList<>();
            int reach = 0;
            for (ClassifiedOperation o : classified) {
                boolean byRule = o.rule() != null && o.rule().id() == created.id();
                reach += byRule ? 1 : 0;
                if (ids.contains(o.operation().id())) {
                    (byRule ? recognised : leftovers).add(o.operation().id());
                }
            }
            operationRepository.setCategory(c, recognised, null);
            operationRepository.setCategory(c, leftovers, rule.categoryId());
            String name = all.stream().filter(k -> k.id() == rule.categoryId()).map(Category::name).findFirst().orElse("");
            return "Règle créée : elle range " + Fmt.plural(reach, "opération", "opérations") + " dans « " + name + " »"
                    + (leftovers.isEmpty() ? "." : " ; " + Fmt.plural(leftovers.size(), "opération choisie ne lui correspond pas et a été rangée",
                            "opérations choisies ne lui correspondent pas et ont été rangées") + " à la main.");
        });
    }

    private static Operation withoutManualCategory(Operation o) {
        return new Operation(o.id(), o.date(), o.valueDate(), o.type(), o.label(), o.amountCents());
    }

    // ------------------------------------------------------------------ catégories

    /** Crée la catégorie (identifiant 0) ou enregistre ses modifications. */
    void saveCategory(Category category) {
        change(c -> {
            if (category.id() == 0) {
                categoryRepository.insert(c, category);
            } else {
                categoryRepository.update(c, category);
            }
            return null;
        });
    }

    void deleteCategory(Category category) {
        change(c -> {
            categoryRepository.delete(c, category.id());
            return null;
        });
    }

    /** Échange le rang de deux catégories dans le bilan. */
    void swapCategories(Category first, Category second) {
        change(c -> {
            categoryRepository.swapPositions(c, first.id(), second.id());
            return null;
        });
    }

    /**
     * Enregistre l'objectif de réduction d'une catégorie sans recharger l'affichage : l'écran qui le modifie
     * se met à jour lui-même.
     */
    void setSavingPercent(Category category, int percent) {
        for (int i = 0; i < categories.size(); i++) {
            if (categories.get(i).id() == category.id()) {
                categories.set(i, categories.get(i).withSavingPercent(percent));
            }
        }
        worker.execute(() -> {
            try {
                database.inTransaction(c -> {
                    categoryRepository.setSavingPercent(c, category.id(), percent);
                    return null;
                });
            } catch (RuntimeException e) {
                Platform.runLater(() -> notice.set(new Notice(NoticeKind.ERROR, "Objectif non enregistré : " + e.getMessage())));
            }
        });
    }

    // ------------------------------------------------------------------ règles

    /** Crée la règle (identifiant 0) en tête de liste, ou enregistre ses modifications. */
    void saveRule(Rule rule) {
        change(c -> {
            if (rule.id() == 0) {
                ruleRepository.insertFirst(c, rule);
            } else {
                ruleRepository.update(c, rule);
            }
            return null;
        });
    }

    void deleteRule(Rule rule) {
        change(c -> {
            ruleRepository.delete(c, rule.id());
            return null;
        });
    }

    /** Échange la priorité de deux règles. */
    void swapRules(Rule first, Rule second) {
        change(c -> {
            ruleRepository.swapPositions(c, first.id(), second.id());
            return null;
        });
    }

    void moveRuleFirst(Rule rule) {
        change(c -> {
            ruleRepository.moveFirst(c, rule.id());
            return null;
        });
    }

    // ------------------------------------------------------------------ période

    /**
     * Fixe les bornes de la période « entre deux dates » ; une borne {@code null} est sans limite. Si
     * cette période est celle des écrans de bilan, ils sont recalculés.
     */
    void setCustomRange(LocalDate from, LocalDate to) {
        Period updated = Period.between(from, to);
        int index = customRange == null ? -1 : periods.indexOf(customRange);
        if (updated.equals(customRange)) {
            return;
        }
        customRange = updated;
        if (index < 0) {
            return;
        }
        boolean selected = period.get() != null && period.get().custom();
        applying = true;
        try {
            periods.set(index, updated);
            if (selected) {
                period.set(updated);
            }
        } finally {
            applying = false;
        }
        if (selected) {
            analyse();
        }
    }

    // ------------------------------------------------------------------ assistant

    /** Enregistre les réglages de l'assistant. */
    void saveLlm(LlmSettings updated) {
        llm.set(updated);
        worker.execute(() -> {
            try {
                database.inTransaction(c -> {
                    for (Map.Entry<String, String> setting : updated.toSettings().entrySet()) {
                        settings.put(c, setting.getKey(), setting.getValue());
                    }
                    return null;
                });
            } catch (RuntimeException e) {
                Platform.runLater(() -> notice.set(new Notice(NoticeKind.ERROR, "Réglages de l'assistant non enregistrés : " + e.getMessage())));
            }
        });
    }

    private LlmSettings readLlm(Connection c) throws SQLException {
        Map<String, String> stored = new HashMap<>();
        for (String name : LlmSettings.NONE.toSettings().keySet()) {
            settings.get(c, name).ifPresent(value -> stored.put(name, value));
        }
        return LlmSettings.of(stored::get);
    }

    /**
     * Fait examiner par l'assistant les opérations du compte affiché, sur une période, sans rien
     * modifier : {@code done} reçoit ses propositions sur le fil JavaFX.
     */
    void propose(Period range, Reach reach, Consumer<Proposal> done) {
        List<Category> allCategories = List.copyOf(categories);
        List<ClassifiedOperation> all = List.copyOf(operations);
        LlmSettings assistant = llm.get();
        Predicate<ClassifiedOperation> scope = o -> range.contains(o.operation().date()) && switch (reach) {
            case UNCLASSIFIED -> o.isUnclassified();
            case AUTOMATIC -> o.source() != Source.MANUAL;
            case ALL -> true;
        };
        background(() -> {
            try (LlmClient client = llmClients.apply(assistant)) {
                return new Assistant(client).propose(allCategories, all, scope, this::report);
            } finally {
                Platform.runLater(() -> progress.set(""));
            }
        }, done);
    }

    /** Enregistre les propositions retenues, puis recharge l'affichage. */
    void applySuggestions(List<Suggestion> accepted) {
        List<Suggestion> chosen = List.copyOf(accepted);
        change(c -> {
            Applied applied = Assistant.apply(c, chosen);
            return "Assistant : " + Fmt.plural(applied.operations(), "opération reclassée", "opérations reclassées")
                    + (applied.categories() == 0 ? "" : ", " + Fmt.plural(applied.categories(), "catégorie créée", "catégories créées")) + ".";
        });
    }

    /** Classe, sur chaque compte touché par un import, les opérations nouvelles que les règles n'ont pas reconnues. */
    private Optional<String> classifyImported(LlmSettings assistantSettings, Map<Long, Set<Long>> added) {
        int examined = 0;
        int classified = 0;
        int created = 0;
        String failure = null;
        try (LlmClient client = llmClients.apply(assistantSettings)) {
            Assistant assistant = new Assistant(client);
            for (Map.Entry<Long, Set<Long>> entry : added.entrySet()) {
                Snapshot data = load(entry.getKey());
                Set<Long> ids = entry.getValue();
                Predicate<ClassifiedOperation> scope = o -> ids.contains(o.operation().id()) && o.isUnclassified();
                examined += (int) data.operations().stream().filter(scope).count();
                Proposal proposal = assistant.propose(data.categories(), data.operations(), scope, this::report);
                Applied applied = database.inTransaction(c -> Assistant.apply(c, proposal.suggestions()));
                classified += applied.operations();
                created += applied.categories();
                failure = proposal.failure() == null ? failure : proposal.failure();
            }
        }
        if (examined == 0) {
            return Optional.empty();
        }
        return Optional.of("Assistant : " + Fmt.plural(classified, "opération nouvelle classée", "opérations nouvelles classées")
                + (created == 0 ? "" : ", " + Fmt.plural(created, "catégorie créée", "catégories créées"))
                + (examined == classified ? "" : " ; " + Fmt.plural(examined - classified, "reste à classer", "restent à classer")) + "."
                + (failure == null ? "" : " Le modèle a fait défaut : " + failure));
    }

    private void report(int done, int total) {
        Platform.runLater(() -> progress.set(total == 0 ? "" : "Assistant : " + done + " activités examinées sur " + total + "…"));
    }

    /** Envoie une question sans importance au modèle pour vérifier les réglages ; {@code done} reçoit sa réponse. */
    void testLlm(LlmSettings candidate, Consumer<String> done) {
        background(() -> {
            try (LlmClient client = llmClients.apply(candidate)) {
                return client.complete("Tu es un service de test.", "Réponds uniquement par le mot : OK");
            }
        }, done);
    }

    /** Demande au service la liste de ses modèles. */
    void listModels(LlmSettings candidate, Consumer<List<String>> done) {
        background(() -> {
            try (LlmClient client = llmClients.apply(candidate)) {
                return client.models();
            }
        }, done);
    }

    /**
     * Lance une connexion à GitHub par le navigateur, pour obtenir le jeton de GitHub Copilot. Sur le fil
     * JavaFX, {@code asked} reçoit la page à ouvrir, puis {@code done} le compte connecté, ou {@code failed}
     * l'erreur — une {@link java.util.concurrent.CancellationException} si la connexion a été abandonnée.
     *
     * @param byCode vrai pour autoriser par un code à saisir sur la page
     * @return la connexion lancée, pour pouvoir l'abandonner
     */
    CopilotLogin signIn(boolean byCode, Consumer<CopilotLogin.Prompt> asked, Consumer<CopilotLogin.Identity> done, Consumer<Exception> failed) {
        CopilotLogin login = githubLogins.apply(byCode);
        background(() -> login.signIn(prompt -> Platform.runLater(() -> asked.accept(prompt))), done, failed);
        return login;
    }

    // ------------------------------------------------------------------ données du profil

    /**
     * Vide la base du profil.
     *
     * @param everything faux pour ne supprimer que les opérations, les comptes et les imports ; vrai pour
     *                   supprimer aussi les règles et remettre les catégories dans leur état de départ
     */
    void clearData(boolean everything) {
        run(() -> {
            if (everything) {
                database.reset();
            } else {
                database.clearOperations();
            }
            apply(load(null));
            String done = everything ? "La base a été réinitialisée : plus aucune opération ni règle, catégories de départ."
                    : "Les opérations, les comptes et l'historique des imports ont été supprimés ; les catégories et les règles sont conservées.";
            Platform.runLater(() -> notice.set(new Notice(NoticeKind.SUCCESS, done)));
        });
    }

    /** Sauvegarde le profil ouvert dans une archive. */
    void backup(Path zip) {
        run(() -> {
            profiles.backup(session, zip);
            Platform.runLater(() -> notice.set(new Notice(NoticeKind.SUCCESS, "Profil sauvegardé dans " + zip)));
        });
    }

    /** Sauvegarde tous les profils dans une archive (administrateur). */
    void backupAll(Path zip) {
        int count = profiles.profiles().size();
        run(() -> {
            profiles.backupAll(session, zip);
            Platform.runLater(() -> notice.set(new Notice(NoticeKind.SUCCESS,
                    Fmt.plural(count, "profil sauvegardé", "profils sauvegardés") + " dans " + zip)));
        });
    }

    /** Écrit le rapport PDF de la période affichée. */
    void exportReport(Path file, boolean withOperations) {
        Period current = period.get() == null ? Period.ALL : period.get();
        // bilan refait à neuf : les objectifs d'économie ont pu changer depuis le dernier calcul
        Analysis report = new Analysis(List.copyOf(categories),
                operations.stream().filter(o -> current.contains(o.operation().date())).toList());
        PdfReport.Content content = new PdfReport.Content(profile.get().name(), account.get(), current, report, withOperations,
                LocalDateTime.now());
        background(() -> {
            try {
                PdfReport.write(file, content);
                return file;
            } catch (IOException e) {
                throw new UncheckedIOException("Le rapport n'a pas pu être écrit dans " + file + " (" + e.getMessage() + ")", e);
            }
        }, written -> notice.set(new Notice(NoticeKind.SUCCESS, "Rapport enregistré : " + written)));
    }

    /** Relit l'état du profil après un changement de nom, de rôle ou de protection. */
    void refreshProfile() {
        profile.set(session.profile());
    }

    // ------------------------------------------------------------------ préférences

    Optional<String> setting(String name) {
        return database.inTransaction(c -> settings.get(c, name));
    }

    void saveSetting(String name, String value) {
        worker.execute(() -> database.inTransaction(c -> {
            settings.put(c, name, value);
            return null;
        }));
    }

    void close() {
        worker.shutdownNow();
        background.shutdownNow();
    }

    static String describe(Outcome outcome) {
        ImportRecord r = outcome.record();
        String file = "« " + r.fileName() + " »";
        if (r.rowsNew() == 0 && r.rowsUpdated() == 0) {
            return file + " : rien de nouveau, les " + Fmt.plural(r.rowsRead(), "opération", "opérations")
                    + " du fichier sont déjà dans la base"
                    + outcome.previousImportOfSameFile().map(at -> " (fichier déjà importé le " + Fmt.dateTime(at) + ")").orElse("")
                    + ".";
        }
        StringBuilder sb = new StringBuilder(file).append(" : ")
                .append(Fmt.plural(r.rowsNew(), "opération ajoutée", "opérations ajoutées"));
        if (r.rowsKnown() > 0) {
            sb.append(", ").append(Fmt.plural(r.rowsKnown(), "déjà présente ignorée", "déjà présentes ignorées"));
        }
        if (r.rowsUpdated() > 0) {
            sb.append(", ").append(Fmt.plural(r.rowsUpdated(), "libellé mis à jour", "libellés mis à jour"));
        }
        return sb.append(" (").append(Fmt.plural(r.rowsRead(), "ligne lue", "lignes lues")).append(").").toString();
    }

    // ------------------------------------------------------------------ chargement

    private Long currentAccountId() {
        Account current = account.get();
        return current == null ? null : current.id();
    }

    /** Applique une modification, puis recharge tout : le classement est recalculé avec les nouvelles règles. */
    private void change(Change change) {
        Long accountId = currentAccountId();
        run(() -> {
            String message = database.inTransaction(change::apply);
            apply(load(accountId));
            if (message != null) {
                Platform.runLater(() -> notice.set(new Notice(NoticeKind.SUCCESS, message)));
            }
        });
    }

    private Snapshot load(Long accountId) {
        return database.inTransaction(c -> {
            List<Account> all = accountRepository.findAll(c);
            Account current = all.stream().filter(a -> accountId != null && a.id() == accountId).findFirst()
                    .orElse(all.isEmpty() ? null : all.getFirst());
            List<Category> categoryList = categoryRepository.findAll(c);
            List<Rule> ruleList = ruleRepository.findAll(c);
            if (current == null) {
                return new Snapshot(all, null, categoryList, ruleList, List.of(), List.of());
            }
            List<ClassifiedOperation> classified = new Classifier(categoryList, ruleList)
                    .classify(operationRepository.findByAccount(c, current.id()));
            return new Snapshot(all, current, categoryList, ruleList, classified, importRepository.findByAccount(c, current.id()));
        });
    }

    private void apply(Snapshot s) {
        Platform.runLater(() -> {
            applying = true;
            try {
                // premières opérations affichées : on part de la période la plus parlante, les 12 derniers mois
                Period wanted = operations.isEmpty() ? null : period.get();
                accounts.setAll(s.accounts());
                account.set(s.current());
                categories.setAll(s.categories());
                rules.setAll(s.rules());
                imports.setAll(s.imports());
                operations.setAll(s.operations());
                List<LocalDate> dates = s.operations().stream().map(o -> o.operation().date()).toList();
                List<Period> choices = new ArrayList<>(Period.choices(dates));
                if (!dates.isEmpty()) {
                    if (customRange == null) {
                        // proposée d'abord sur tout ce que contient la base
                        customRange = Period.between(dates.stream().min(Comparator.naturalOrder()).orElseThrow(),
                                dates.stream().max(Comparator.naturalOrder()).orElseThrow());
                    }
                    choices.add(customRange);
                }
                if (!periods.equals(choices)) {
                    periods.setAll(choices);
                }
                period.set(choices.stream().filter(p -> wanted != null && p.label().equals(wanted.label())).findFirst()
                        .orElse(choices.getFirst()));
            } finally {
                applying = false;
            }
            analyse();
        });
    }

    private void analyse() {
        Period current = period.get() == null ? Period.ALL : period.get();
        analysis.set(new Analysis(List.copyOf(categories),
                operations.stream().filter(o -> current.contains(o.operation().date())).toList()));
    }

    // ------------------------------------------------------------------ tâches de fond

    /** Exécute une tâche sur le fil de la base ; une erreur est affichée en bandeau. */
    private void run(Runnable task) {
        submit(worker, () -> {
            task.run();
            return null;
        }, _ -> { }, this::showError);
    }

    /**
     * Exécute hors du fil de la base une tâche qui ne la modifie pas ; {@code done} reçoit son résultat sur
     * le fil JavaFX, et une erreur est affichée en bandeau.
     */
    <T> void background(Callable<T> task, Consumer<T> done) {
        submit(background, task, done, this::showError);
    }

    /** Comme {@link #background(Callable, Consumer)}, mais une erreur est remise à {@code failed}, sur le fil JavaFX. */
    <T> void background(Callable<T> task, Consumer<T> done, Consumer<Exception> failed) {
        submit(background, task, done, failed);
    }

    private void showError(Exception e) {
        notice.set(new Notice(NoticeKind.ERROR, message(e)));
    }

    private <T> void submit(ExecutorService executor, Callable<T> task, Consumer<T> done, Consumer<Exception> failed) {
        Platform.runLater(() -> {
            running++;
            busy.set(true);
        });
        executor.execute(() -> {
            try {
                T result = task.call();
                Platform.runLater(() -> done.accept(result));
            } catch (Exception e) {
                Platform.runLater(() -> failed.accept(e));
            } catch (LinkageError e) {
                // un composant de l'application manque ou ne se charge pas : à dire, plutôt que de ne rien faire
                Platform.runLater(() -> failed.accept(new IllegalStateException("un composant de l'application ne se charge pas ("
                        + e + ")", e)));
            } finally {
                Platform.runLater(() -> {
                    running--;
                    busy.set(running > 0);
                });
            }
        });
    }

    /** Le message d'une erreur : tel quel s'il s'adresse déjà à l'utilisateur, sinon présenté comme inattendu. */
    static String message(Exception e) {
        boolean spoken = e instanceof IllegalArgumentException || e instanceof ProfileException || e instanceof WrongPasswordException
                || e instanceof LlmException || e instanceof UncheckedIOException;
        return spoken ? e.getMessage() : "Erreur inattendue : " + e.getMessage();
    }
}
