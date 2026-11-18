package io.github.pierre3119.cadesk.ui;

import static org.assertj.core.api.Assertions.assertThat;

import atlantafx.base.theme.PrimerLight;
import io.github.pierre3119.cadesk.About;
import io.github.pierre3119.cadesk.Samples;
import io.github.pierre3119.cadesk.Samples.Row;
import io.github.pierre3119.cadesk.analysis.Period;
import io.github.pierre3119.cadesk.categorize.Classifier;
import io.github.pierre3119.cadesk.domain.Account;
import io.github.pierre3119.cadesk.domain.Category;
import io.github.pierre3119.cadesk.domain.Category.IncomeKind;
import io.github.pierre3119.cadesk.domain.ClassifiedOperation;
import io.github.pierre3119.cadesk.domain.ClassifiedOperation.Source;
import io.github.pierre3119.cadesk.domain.Nature;
import io.github.pierre3119.cadesk.domain.Operation;
import io.github.pierre3119.cadesk.domain.Rule;
import io.github.pierre3119.cadesk.llm.Assistant.Proposal;
import io.github.pierre3119.cadesk.llm.CopilotLogin.Identity;
import io.github.pierre3119.cadesk.llm.FakeCopilot;
import io.github.pierre3119.cadesk.llm.FakeCopilotRuntime;
import io.github.pierre3119.cadesk.llm.LlmClient;
import io.github.pierre3119.cadesk.llm.LlmProvider;
import io.github.pierre3119.cadesk.llm.LlmSettings;
import io.github.pierre3119.cadesk.profile.Profile;
import io.github.pierre3119.cadesk.profile.ProfileManager;
import io.github.pierre3119.cadesk.profile.Session;
import io.github.pierre3119.cadesk.ui.AppModel.Reach;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Parcourt l'application hors écran (aucune fenêtre n'est ouverte) : chaque écran et chaque boîte de
 * dialogue est rendu en PNG pour contrôle visuel, et les actions — classement à la main, par une règle
 * et par l'assistant (avec un modèle de langage simulé), période entre deux dates, rapport PDF, profils —
 * sont exécutées de bout en bout sur de vraies bases.
 *
 * <p>Demande un environnement graphique, donc exclu de la construction normale. Pour le lancer :
 * {@code ./build.sh -DexcludedGroups= -Dgroups=ui} ; les images vont dans {@code target/ui-snapshots}, et celles
 * qui illustrent le guide d'utilisation dans son sous-dossier {@code guide} (à copier dans {@code docs/images}).
 * Avec {@code -Dcadesk.sample.csv=/chemin/releve.csv}, le rendu utilise ce relevé au lieu des données fictives.
 */
@Tag("ui")
class UiSnapshotTest {

    private static final String[] SCREENS = {"synthese", "bilan-mensuel", "charges-fixes", "depenses-variables", "economies",
            "operations", "categories", "imports", "assistant", "profil"};

    @TempDir
    Path dir;

    @Test
    void rendersEveryScreenAndClassifiesOperations() throws Exception {
        Path out = Files.createDirectories(Path.of(System.getProperty("cadesk.snapshots", "target/ui-snapshots")));
        String sample = System.getProperty("cadesk.sample.csv", "");
        Path csv = sample.isBlank() ? Samples.write(dir, "releve-demo.csv", Samples.demoStatement()) : Path.of(sample);
        // comme dans un dossier installé : la base du profil est désignée par rapport au dossier de l'application
        System.setProperty("cadesk.home", dir.toString());
        try {
            Platform.startup(() -> { });
        } catch (IllegalStateException alreadyStarted) {
            // la plateforme JavaFX est déjà démarrée
        }
        Platform.setImplicitExit(false);
        onFx(() -> {
            Application.setUserAgentStylesheet(new PrimerLight().getUserAgentStylesheet());
            return null;
        });

        ProfileManager profiles = new ProfileManager(dir.resolve("data"), null);
        snapshot(chooser(profiles), out.resolve("00-choix-du-profil-premier-lancement.png"));
        Profile demo = profiles.create("Démo", null, false);
        Profile secret = profiles.create("Camille", "camille".toCharArray(), true);
        snapshot(chooser(profiles), out.resolve("00b-choix-du-profil.png"));

        Session session = profiles.open(demo, null);
        try {
            AppModel model = onFx(() -> new AppModel(profiles, session));
            // scène haute : les écrans qui défilent sont vus en entier
            Scene scene = onFx(() -> App.createScene(model, () -> { }, 1340, 1650));
            snapshot(scene, out.resolve("01-accueil.png"));
            // la fenêtre telle qu'elle s'ouvre : ses images illustrent le guide d'utilisation (docs/images)
            Path guide = Files.createDirectories(out.resolve("guide"));
            Scene window = onFx(() -> App.createScene(model, () -> { }, 1340, 860));
            snapshot(window, guide.resolve("accueil.png"));

            onFx(() -> {
                model.importFiles(List.of(csv));
                return null;
            });
            awaitIdle(model);
            assertThat(onFx(() -> model.operations.size())).isPositive();
            assertThat(onFx(() -> model.analysis.get().control())).as("le bilan retombe sur les opérations").isZero();

            List<ToggleButton> nav = onFx(() -> scene.getRoot().lookupAll(".nav-item").stream().map(ToggleButton.class::cast).toList());
            assertThat(nav).hasSize(SCREENS.length);
            for (int i = 0; i < SCREENS.length; i++) {
                show(model, nav.get(i));
                snapshot(scene, out.resolve("%02d-%s.png".formatted(i + 2, SCREENS[i])));
            }
            List<ToggleButton> windowNav = onFx(() -> window.getRoot().lookupAll(".nav-item").stream().map(ToggleButton.class::cast).toList());
            for (int i : new int[] {0, 1, 5, 6, 8, 9}) {
                show(model, windowNav.get(i));
                snapshot(window, guide.resolve(SCREENS[i] + ".png"));
            }
            show(model, nav.get(6));
            onFx(() -> {
                scene.getRoot().lookup(".list-row").getOnMouseClicked().handle(null); // « Toutes les règles, par priorité »
                return null;
            });
            snapshot(scene, out.resolve("08b-categories-toutes-les-regles.png"));

            classifiesByHandThenByRule(model, scene, out);
            managesCategoriesAndRules(model);

            // navigation depuis un écran de bilan vers les opérations d'une catégorie
            Category busiest = onFx(() -> model.analysis.get().spendingCategories().getFirst());
            onFx(() -> {
                ((AnalysisPage.Navigator) scene.getRoot()).showOperations(busiest, null);
                return null;
            });
            assertThat(onFx(() -> nav.get(5).isSelected())).isTrue();
            snapshot(scene, out.resolve("15-operations-d-une-categorie.png"));

            // fenêtre à sa taille minimale
            Scene small = onFx(() -> App.createScene(model, () -> { }, 1120, 640));
            List<ToggleButton> smallNav = onFx(() -> small.getRoot().lookupAll(".nav-item").stream().map(ToggleButton.class::cast).toList());
            snapshot(small, out.resolve("16-synthese-petite-fenetre.png"));
            show(model, smallNav.get(1));
            snapshot(small, out.resolve("17-bilan-petite-fenetre.png"));
            onFx(() -> {
                ScrollPane bilan = (ScrollPane) small.getRoot().lookup(".bilan-scroll");
                bilan.setHvalue(1);
                bilan.setVvalue(0.55);
                return null;
            });
            snapshot(small, out.resolve("17b-bilan-defile-titres-figes.png"));
            show(model, smallNav.get(6));
            snapshot(small, out.resolve("18-categories-petite-fenetre.png"));

            analysesBetweenTwoDates(model, scene, nav, out);
            exportsTheReport(model, out);
            letsTheAssistantClassify(model, scene, nav, out);
            protectsTheProfile(model, profiles, session, scene, nav, out);
            tellsWhoMadeIt(scene, out);

            // thème sombre
            onFx(() -> {
                ((Button) scene.getRoot().lookup(".sidebar .theme-button")).fire();
                return null;
            });
            for (int i : new int[] {0, 1, 4, 6, 9}) {
                show(model, nav.get(i));
                snapshot(scene, out.resolve("%02d-sombre-%s.png".formatted(30 + i, SCREENS[i])));
            }

            emptiesTheBase(model);
            onFx(() -> {
                model.close();
                return null;
            });

            // l'administrateur ouvre le profil chiffré d'une autre utilisatrice, sans son mot de passe ;
            // elle l'a rouvert une fois depuis qu'il y a un administrateur, ce qui lui a confié une clé de secours
            profiles.open(secret, "camille".toCharArray()).close();
            Session guest = profiles.openAs(session, secret);
            try {
                AppModel guestModel = onFx(() -> new AppModel(profiles, guest));
                Scene guestScene = onFx(() -> App.createScene(guestModel, () -> { }, 1340, 860));
                List<ToggleButton> guestNav = onFx(() -> guestScene.getRoot().lookupAll(".nav-item").stream().map(ToggleButton.class::cast).toList());
                show(guestModel, guestNav.get(9));
                snapshot(guestScene, out.resolve("41-profil-ouvert-par-un-administrateur.png"));
                assertThat(guest.isGuest()).isTrue();
                onFx(() -> {
                    guestModel.close();
                    return null;
                });
            } finally {
                guest.close();
            }
        } finally {
            session.close();
            System.clearProperty("cadesk.home");
        }
        assertThat(out.resolve("30-sombre-synthese.png")).isNotEmptyFile();
        // le guide reprend aussi le choix du profil et deux boîtes de dialogue
        Map<String, String> shared = Map.of("00b-choix-du-profil.png", "choix-du-profil.png",
                "12-dialogue-categoriser.png", "categoriser.png", "42-dialogue-a-propos.png", "a-propos.png");
        for (Map.Entry<String, String> image : shared.entrySet()) {
            Files.copy(out.resolve(image.getKey()), out.resolve("guide").resolve(image.getValue()), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Scene chooser(ProfileManager profiles) throws Exception {
        return onFx(() -> {
            Scene scene = new Scene(new ProfileChooser(profiles, _ -> { }, _ -> { }, null), 1340, 860);
            scene.getStylesheets().add(App.class.getResource("styles.css").toExternalForm());
            assertThat(scene.getRoot().lookup(".about-button")).as("accès à « À propos » dès le choix du profil").isNotNull();
            return scene;
        });
    }

    /** « À propos » : l'auteur de l'application, et ses pages, que le navigateur ouvre. */
    private static void tellsWhoMadeIt(Scene scene, Path out) throws Exception {
        assertThat(onFx(() -> scene.getRoot().lookup(".sidebar .about-button"))).as("accès à « À propos » dans la barre latérale").isNotNull();
        List<String> opened = new CopyOnWriteArrayList<>();
        AboutDialog dialog = onFx(() -> new AboutDialog(scene.getRoot(), opened::add));
        List<String> texts = onFx(() -> dialog.getDialogPane().getContent().lookupAll(".label").stream()
                .map(label -> ((Label) label).getText()).toList());
        assertThat(texts).contains(About.NAME, About.AUTHOR, "Auteur", "LinkedIn", "GitHub");
        About.version().ifPresent(version -> assertThat(texts).as("version du module de l'application").contains("Version " + version));

        List<Hyperlink> links = onFx(() -> dialog.getDialogPane().getContent().lookupAll(".hyperlink").stream().map(Hyperlink.class::cast).toList());
        assertThat(links).extracting(Hyperlink::getText).containsExactly("linkedin.com/in/pierre-duloy-2101929", "github.com/pierre3119");
        onFx(() -> {
            links.forEach(Hyperlink::fire);
            return null;
        });
        assertThat(opened).containsExactly(About.AUTHOR_LINKEDIN, About.AUTHOR_GITHUB);
        snapshotDialog(() -> new AboutDialog(scene.getRoot(), opened::add), out.resolve("42-dialogue-a-propos.png"));
    }

    /** La période « entre deux dates » : ses bornes se règlent, et le bilan les suit. */
    private static void analysesBetweenTwoDates(AppModel model, Scene scene, List<ToggleButton> nav, Path out) throws Exception {
        List<LocalDate> dates = onFx(() -> model.operations.stream().map(o -> o.operation().date()).sorted().toList());
        LocalDate from = dates.get(dates.size() / 3);
        LocalDate to = dates.get(2 * dates.size() / 3);
        Period before = onFx(model.period::get);
        onFx(() -> {
            model.period.set(model.periods.getLast());
            model.setCustomRange(to, from); // bornes inversées : remises dans l'ordre
            return null;
        });
        Period custom = onFx(model.period::get);
        assertThat(custom.custom()).isTrue();
        assertThat(List.of(custom.from(), custom.to())).containsExactly(from, to);
        assertThat(onFx(() -> model.analysis.get().operations())).isNotEmpty()
                .allSatisfy(o -> assertThat(o.operation().date()).isBetween(from, to));
        assertThat(onFx(() -> model.analysis.get().control())).isZero();
        show(model, nav.get(0));
        snapshot(scene, out.resolve("20-synthese-entre-deux-dates.png"));
        setsTheBoundsInTheirFields(model, scene, from, to);

        onFx(() -> {
            model.setCustomRange(null, to);
            return null;
        });
        assertThat(onFx(() -> model.analysis.get().operations().getFirst().operation().date())).as("sans borne de début").isEqualTo(dates.getFirst());
        onFx(() -> {
            model.period.set(before);
            return null;
        });
        assertThat(onFx(() -> model.periods.getLast().custom())).as("la période choisie reste proposée").isTrue();
    }

    /**
     * Les bornes se règlent dans leurs champs, sans rien avoir à valider : un jour choisi au calendrier
     * s'applique aussitôt, une date saisie au clavier dès la touche Entrée.
     */
    private static void setsTheBoundsInTheirFields(AppModel model, Scene scene, LocalDate from, LocalDate to) throws Exception {
        List<DatePicker> bounds = onFx(() -> scene.getRoot().lookupAll(".date-picker").stream().map(DatePicker.class::cast)
                .filter(Node::isVisible).toList());
        assertThat(bounds).as("champs « Du » et « Au »").hasSize(2);
        assertThat(onFx(() -> List.of(bounds.get(0).getValue(), bounds.get(1).getValue()))).containsExactly(from, to);

        LocalDate later = from.plusDays(2);
        onFx(() -> {
            bounds.get(0).setValue(later); // ce que fait un clic sur un jour du calendrier
            return null;
        });
        assertThat(onFx(model.period::get)).as("borne choisie au calendrier").satisfies(period -> assertThat(period.custom()).isTrue(),
                period -> assertThat(period.from()).isEqualTo(later), period -> assertThat(period.to()).isEqualTo(to));
        assertThat(onFx(() -> model.analysis.get().operations())).isNotEmpty()
                .allSatisfy(o -> assertThat(o.operation().date()).isBetween(later, to));
        assertThat(onFx(() -> bounds.get(0).isVisible() && bounds.get(1).isVisible())).as("les champs restent affichés").isTrue();

        LocalDate earlier = to.minusDays(2);
        DateTimeFormatter typing = DateTimeFormatter.ofPattern("dd/MM/yyyy");
        onFx(() -> {
            // la saisie au clavier : le texte, puis la touche Entrée, que reçoit le champ de date
            DatePicker typed = bounds.get(1);
            typed.getEditor().setText(typing.format(earlier));
            typed.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
            typed.fireEvent(new KeyEvent(KeyEvent.KEY_RELEASED, "", "", KeyCode.ENTER, false, false, false, false));
            return null;
        });
        assertThat(onFx(model.period::get)).as("borne saisie puis validée par Entrée").satisfies(
                period -> assertThat(period.from()).isEqualTo(later), period -> assertThat(period.to()).isEqualTo(earlier));
        assertThat(onFx(() -> model.analysis.get().control())).isZero();
        String confirmed = "Du " + typing.format(later) + " au " + typing.format(earlier);
        assertThat(onFx(() -> scene.getRoot().lookupAll(".label").stream().map(label -> ((Label) label).getText())
                .anyMatch(text -> text != null && text.startsWith(confirmed)))).as("le sous-titre confirme les dates retenues : " + confirmed).isTrue();

        onFx(() -> {
            model.setCustomRange(from, to);
            return null;
        });
        assertThat(onFx(() -> List.of(bounds.get(0).getValue(), bounds.get(1).getValue()))).as("les champs suivent la période").containsExactly(from, to);
    }

    /** Le rapport PDF de la période affichée est écrit là où on le demande. */
    private static void exportsTheReport(AppModel model, Path out) throws Exception {
        Path report = out.resolve("rapport.pdf");
        Files.deleteIfExists(report);
        onFx(() -> {
            model.exportReport(report, true);
            return null;
        });
        awaitIdle(model);
        assertThat(onFx(() -> model.notice.get().text())).contains("Rapport enregistré");
        assertThat(Files.readAllBytes(report)).startsWith("%PDF".getBytes(StandardCharsets.US_ASCII)).hasSizeGreaterThan(20_000);
    }

    /** L'assistant propose, on valide ; puis il classe de lui-même les opérations d'un nouvel import. */
    private void letsTheAssistantClassify(AppModel model, Scene scene, List<ToggleButton> nav, Path out) throws Exception {
        Category refunds = category(model, "Autres remboursements et avoirs");
        // modèle simulé : les crédits sont des remboursements, les débits vont dans une catégorie nouvelle
        LlmClient fake = (system, user) -> {
            JsonArray rows = new JsonArray();
            for (JsonElement element : JsonParser.parseString(user).getAsJsonObject().getAsJsonArray("activites")) {
                JsonObject activity = element.getAsJsonObject();
                rows.add(JsonParser.parseString("{\"id\":" + activity.get("id").getAsInt() + ","
                        + ("crédit".equals(activity.get("sens").getAsString()) ? "\"categorie\":" + refunds.id()
                                : "\"nouvelle\":{\"nom\":\"Animaux\",\"nature\":\"VARIABLES\"}") + "}"));
            }
            JsonObject answer = new JsonObject();
            answer.add("classements", rows);
            return answer.toString();
        };
        onFx(() -> {
            model.llmClients = _ -> fake;
            model.saveLlm(new LlmSettings(LlmProvider.OLLAMA, LlmProvider.OLLAMA.defaultUrl(), "", "modele-de-test", true));
            return null;
        });
        show(model, nav.get(8));
        snapshot(scene, out.resolve("21-assistant-regle.png"));
        usesGitHubCopilot(model, scene, fake, dir, out);

        // une passe sur les opérations à classer : rien ne change avant validation
        long pending = onFx(() -> model.operations.stream().filter(ClassifiedOperation::isUnclassified).count());
        assertThat(pending).isPositive();
        AtomicReference<Proposal> proposed = new AtomicReference<>();
        onFx(() -> {
            model.propose(Period.ALL, Reach.UNCLASSIFIED, proposed::set);
            return null;
        });
        awaitIdle(model);
        Proposal proposal = proposed.get();
        assertThat(proposal.failure()).isNull();
        assertThat(proposal.changes()).isNotEmpty();
        assertThat(onFx(() -> model.operations.stream().filter(ClassifiedOperation::isUnclassified).count())).isEqualTo(pending);
        snapshotDialog(() -> new RecategorizeDialog(proposal, scene.getRoot()), out.resolve("22-dialogue-propositions-de-l-assistant.png"));

        onFx(() -> {
            model.applySuggestions(proposal.changes());
            return null;
        });
        awaitIdle(model);
        assertThat(onFx(() -> model.notice.get().text())).contains("Assistant");
        assertThat(onFx(() -> model.operations.stream().filter(ClassifiedOperation::isUnclassified).count())).isZero();
        assertThat(onFx(() -> model.operations.stream().filter(o -> o.source() == Source.ASSISTANT).count())).isEqualTo(pending);
        assertThat(onFx(() -> model.analysis.get().control())).isZero();

        // à l'import suivant, une activité déjà classée par l'assistant est reconnue sans lui, une nouvelle lui est soumise
        ClassifiedOperation known = onFx(() -> model.operations.stream().filter(o -> o.source() == Source.ASSISTANT).findFirst().orElseThrow());
        Account account = onFx(model.account::get);
        Path next = Samples.write(dir, "releve-suivant.csv", Samples.statement(account.number(), List.of(
                Row.debit("09/11/2026", "Paiement par carte\nX1234 ZZ TOILETTEUR CANIN 08/11", "45,00"),
                new Row("10/11/2026", known.operation().type() + "\n" + known.operation().label(),
                        known.operation().isCredit() ? "" : "12,00", known.operation().isCredit() ? "12,00" : ""))));
        onFx(() -> {
            model.importFiles(List.of(next));
            return null;
        });
        awaitIdle(model);
        assertThat(onFx(() -> model.notice.get().text())).contains("2 opérations ajoutées").contains("Assistant : 2 opérations nouvelles classées");
        long petsId = category(model, "Animaux").id();
        assertThat(onFx(() -> model.operations.stream().filter(o -> o.operation().date().equals(LocalDate.of(2026, 11, 9))).findFirst().orElseThrow()))
                .satisfies(o -> assertThat(o.category().id()).isEqualTo(petsId), o -> assertThat(o.source()).isEqualTo(Source.ASSISTANT));
        assertThat(onFx(() -> model.operations.stream().filter(o -> o.operation().date().equals(LocalDate.of(2026, 11, 10))).findFirst().orElseThrow()))
                .satisfies(o -> assertThat(o.category().id()).as("comme l'activité semblable").isEqualTo(known.category().id()));

        // l'assistant coupé, l'import ne classe plus rien de lui-même
        onFx(() -> {
            model.saveLlm(model.llm.get().withOnImport(false));
            return null;
        });
        Path last = Samples.write(dir, "releve-dernier.csv", Samples.statement(account.number(), List.of(
                Row.debit("12/11/2026", "Paiement par carte\nX1234 ZZ SALON DU CHIOT 11/11", "30,00"))));
        onFx(() -> {
            model.importFiles(List.of(last));
            return null;
        });
        awaitIdle(model);
        assertThat(onFx(() -> model.notice.get().text())).doesNotContain("Assistant");
        assertThat(onFx(() -> model.operations.stream().filter(ClassifiedOperation::isUnclassified).count())).isEqualTo(1);
        onFx(() -> {
            ((AnalysisPage.Navigator) scene.getRoot()).showOperations(null, null);
            return null;
        });
        snapshot(scene, out.resolve("23-operations-classees-par-l-assistant.png"));
    }

    /**
     * GitHub Copilot se règle sans adresse, avec un jeton GitHub ; la liste de ses modèles et le test de la
     * connexion passent par le même client que le classement, refermé après usage (ici, un client simulé).
     */
    private static void usesGitHubCopilot(AppModel model, Scene scene, LlmClient other, Path dir, Path out) throws Exception {
        LlmSettings before = onFx(model.llm::get);
        AtomicInteger closed = new AtomicInteger();
        LlmClient copilot = new LlmClient() {
            @Override
            public String complete(String system, String user) {
                return "OK";
            }

            @Override
            public List<String> models() {
                return List.of("modele-a", "modele-b");
            }

            @Override
            public void close() {
                closed.incrementAndGet();
            }
        };
        LlmSettings settings = new LlmSettings(LlmProvider.COPILOT, "", "github_pat_de_test", "modele-a", false);
        assertThat(settings.isConfigured()).isTrue();
        AtomicReference<List<String>> listed = new AtomicReference<>();
        AtomicReference<String> answered = new AtomicReference<>();
        onFx(() -> {
            model.llmClients = chosen -> chosen.provider() == LlmProvider.COPILOT ? copilot : other;
            model.listModels(settings, listed::set);
            model.testLlm(settings, answered::set);
            return null;
        });
        awaitIdle(model);
        assertThat(listed.get()).containsExactly("modele-a", "modele-b");
        assertThat(answered.get()).isEqualTo("OK");
        assertThat(closed).hasValue(2);

        @SuppressWarnings("unchecked")
        ComboBox<LlmProvider> service = onFx(() -> (ComboBox<LlmProvider>) scene.getRoot().lookupAll(".combo-box").stream()
                .filter(box -> ((ComboBox<?>) box).getItems().contains(LlmProvider.COPILOT)).findFirst().orElseThrow());
        TextField address = onFx(() -> scene.getRoot().lookupAll(".text-field").stream().map(TextField.class::cast)
                .filter(field -> field.getText().equals(LlmProvider.OLLAMA.defaultUrl())).findFirst().orElseThrow());
        Predicate<String> labelled = text -> scene.getRoot().lookupAll(".label").stream().anyMatch(label -> text.equals(((Label) label).getText()));
        onFx(() -> {
            service.setValue(LlmProvider.COPILOT);
            return null;
        });
        assertThat(onFx(() -> address.isDisabled() && address.getText().isEmpty())).as("pas d'adresse pour Copilot").isTrue();
        assertThat(onFx(() -> labelled.test("Jeton GitHub") && !labelled.test("Clé d'API"))).isTrue();
        snapshot(scene, out.resolve("21b-assistant-github-copilot.png"));
        signsInToGitHub(model, scene, dir, out);
        onFx(() -> {
            model.saveLlm(before); // les réglages d'avant la connexion : le formulaire les reprend
            return null;
        });
        awaitIdle(model);
        assertThat(onFx(service::getValue)).isEqualTo(LlmProvider.OLLAMA);
        assertThat(onFx(() -> !address.isDisabled() && address.getText().equals(LlmProvider.OLLAMA.defaultUrl()))).as("adresse rétablie").isTrue();
        assertThat(onFx(() -> labelled.test("Clé d'API") && !labelled.test("Jeton GitHub"))).isTrue();
    }

    /** Navigateur simulé : il retient les pages qu'on lui demande d'ouvrir. */
    private static final class Browser implements Shell {

        final List<String> opened = new CopyOnWriteArrayList<>();

        @Override
        public void browse(String url) {
            opened.add(url);
        }

        @Override
        public void chooseFiles() {
        }

        @Override
        public void show(Session session, boolean onProfilePage) {
        }

        @Override
        public void leave(String message) {
        }
    }

    /**
     * La connexion à GitHub par le navigateur, menée par un faux moteur de Copilot : la page est confiée au
     * navigateur, l'utilisateur y autorise la connexion, et le jeton rapporté est enregistré dans le profil.
     * Puis la même par un code à saisir, abandonnée en cours de route.
     */
    private static void signsInToGitHub(AppModel model, Scene scene, Path dir, Path out) throws Exception {
        Path byPage = dir.resolve("connexion-par-la-page.jsonl");
        Path byCode = dir.resolve("connexion-par-un-code.jsonl");
        Browser browser = new Browser();
        GitHubLoginDialog dialog = onFx(() -> {
            model.githubLogins = typing -> FakeCopilot.login(typing ? byCode : byPage, typing);
            GitHubLoginDialog created = new GitHubLoginDialog(model, browser, scene.getRoot());
            created.start(false);
            return created;
        });
        await("page de GitHub confiée au navigateur", () -> !browser.opened.isEmpty());
        assertThat(browser.opened).singleElement().asString().startsWith("https://github.com/login/oauth/authorize?");
        assertThat(onFx(dialog::getResult)).as("en attente de l'autorisation").isNull();
        snapshotDialog(() -> dialog, out.resolve("21c-dialogue-connexion-a-github.png"));

        FakeCopilotRuntime.authorize(byPage); // l'utilisateur autorise la connexion dans son navigateur
        awaitIdle(model);
        Identity identity = onFx(dialog::getResult);
        assertThat(identity).isNotNull();
        assertThat(identity.login()).isEqualTo(FakeCopilotRuntime.LOGIN);

        AssistantView view = onFx(() -> scene.getRoot().lookupAll(".page").stream().filter(AssistantView.class::isInstance)
                .map(AssistantView.class::cast).findFirst().orElseThrow());
        onFx(() -> {
            view.connected(identity);
            return null;
        });
        awaitIdle(model);
        assertThat(onFx(model.llm::get)).satisfies(saved -> assertThat(saved.provider()).isEqualTo(LlmProvider.COPILOT),
                saved -> assertThat(saved.apiKey()).as("jeton enregistré dans le profil").isEqualTo(FakeCopilotRuntime.DELIVERED),
                saved -> assertThat(saved.isConfigured()).isTrue());
        assertThat(onFx(() -> scene.getRoot().lookupAll(".label").stream().map(label -> ((Label) label).getText())
                .anyMatch(text -> text != null && text.startsWith("Connecté à GitHub en tant que « " + FakeCopilotRuntime.LOGIN + " »")))).isTrue();
        assertThat(onFx(() -> ((PasswordField) scene.getRoot().lookup(".password-field")).getText())).isEqualTo(FakeCopilotRuntime.DELIVERED);
        snapshot(scene, out.resolve("21d-assistant-connecte-a-github.png"));

        // par un code à saisir, puis abandon : le moteur en est prévenu
        browser.opened.clear();
        GitHubLoginDialog typed = onFx(() -> {
            GitHubLoginDialog created = new GitHubLoginDialog(model, browser, scene.getRoot());
            created.start(true);
            return created;
        });
        await("page du code confiée au navigateur", () -> !browser.opened.isEmpty());
        assertThat(browser.opened).containsExactly("https://github.com/login/device");
        snapshotDialog(() -> typed, out.resolve("21e-dialogue-connexion-par-un-code.png"));
        onFx(() -> {
            typed.close();
            return null;
        });
        awaitIdle(model);
        assertThat(onFx(typed::getResult)).isNull();
        assertThat(FakeCopilotRuntime.noted(byCode)).as("connexion abandonnée auprès du moteur")
                .anyMatch(line -> line.has("method") && line.get("method").getAsString().equals("session.accounts.login.cancel"));
    }

    /** Mot de passe, rôle d'administrateur et boîtes de dialogue de l'écran « Profil et sécurité ». */
    private static void protectsTheProfile(AppModel model, ProfileManager profiles, Session session, Scene scene, List<ToggleButton> nav,
            Path out) throws Exception {
        snapshotDialog(() -> ProfileDialogs.newProfile(scene.getRoot()).dialog(), out.resolve("24-dialogue-nouveau-profil.png"));
        profiles.setPassword(session, null, "démo".toCharArray());
        profiles.grantAdmin(session, "démo".toCharArray(), null, null);
        onFx(() -> {
            model.refreshProfile();
            return null;
        });
        assertThat(session.isAdmin()).isTrue();
        assertThat(onFx(() -> model.profile.get().isAdmin())).isTrue();
        show(model, nav.get(9));
        snapshot(scene, out.resolve("25-profil-administrateur.png"));
        assertThat(onFx(() -> scene.getRoot().lookupAll(".setting-row").size())).as("réglages et liste des profils").isGreaterThanOrEqualTo(8);

        // sauvegarde du profil depuis l'application
        Path backup = out.resolve("sauvegarde-demo.zip");
        Files.deleteIfExists(backup);
        onFx(() -> {
            model.backup(backup);
            return null;
        });
        awaitIdle(model);
        assertThat(backup).isNotEmptyFile();
        assertThat(onFx(() -> model.notice.get().text())).contains("sauvegardé");
    }

    /** Vider la base ne garde que les catégories et les règles. */
    private static void emptiesTheBase(AppModel model) throws Exception {
        int categories = onFx(() -> model.categories.size());
        onFx(() -> {
            model.clearData(false);
            return null;
        });
        awaitIdle(model);
        assertThat(onFx(() -> model.operations.size())).isZero();
        assertThat(onFx(() -> model.accounts.size())).isZero();
        assertThat(onFx(() -> model.categories.size())).isEqualTo(categories);
        assertThat(onFx(() -> model.notice.get().text())).contains("supprimés");
    }

    /** Une opération non reconnue est rangée à la main, puis une règle classe toutes ses semblables. */
    private static void classifiesByHandThenByRule(AppModel model, Scene scene, Path out) throws Exception {
        ClassifiedOperation pending = onFx(() -> model.operations.stream()
                .filter(o -> o.isUnclassified() && !o.operation().isCredit()).findFirst().orElseThrow());
        long id = pending.operation().id();
        Category health = category(model, "Santé");
        Category transport = category(model, "Transports et voiture");
        snapshotDialog(() -> new CategorizeDialog(model, List.of(pending), scene.getRoot()), out.resolve("12-dialogue-categoriser.png"));

        onFx(() -> {
            model.categorize(List.of(id), health);
            return null;
        });
        awaitIdle(model);
        ClassifiedOperation manual = operation(model, id);
        assertThat(manual.category().id()).isEqualTo(health.id());
        assertThat(manual.source()).isEqualTo(Source.MANUAL);

        // règle créée depuis la même opération : elle reprend la main sur le choix manuel et classe ses semblables
        String keyword = Classifier.suggestKeyword(pending.operation());
        Rule rule = Rule.create(transport.id(), "Garage", pending.operation().type(), List.of(keyword));
        Predicate<Operation> matcher = Classifier.matcher(rule);
        long expected = onFx(() -> model.operations.stream().filter(o -> matcher.test(o.operation())).count());
        onFx(() -> {
            model.categorizeWithRule(List.of(id), rule);
            return null;
        });
        awaitIdle(model);
        ClassifiedOperation byRule = operation(model, id);
        assertThat(byRule.source()).isEqualTo(Source.RULE);
        assertThat(byRule.category().id()).isEqualTo(transport.id());
        assertThat(byRule.poste()).isEqualTo("Garage");
        assertThat(onFx(() -> model.rules.getFirst().keywords())).as("la nouvelle règle est prioritaire").containsExactly(keyword);
        assertThat(onFx(() -> model.operations.stream().filter(o -> o.rule() != null && o.rule().id() == model.rules.getFirst().id()).count()))
                .isEqualTo(expected).isPositive();
        assertThat(onFx(() -> model.notice.get().text())).contains("Règle créée");
        assertThat(onFx(() -> model.analysis.get().control())).isZero();
        snapshotDialog(() -> new RuleDialog(model, model.rules.getFirst(), null, scene.getRoot()), out.resolve("13-dialogue-regle.png"));
        snapshotDialog(() -> new CategoryDialog(null, Nature.REVENUS, List.copyOf(model.categories), scene.getRoot()),
                out.resolve("14-dialogue-categorie.png"));
    }

    /** Création, déplacement et suppression d'une catégorie et de sa règle ; objectif d'économie conservé. */
    private static void managesCategoriesAndRules(AppModel model) throws Exception {
        int before = onFx(() -> model.categories.size());
        onFx(() -> {
            model.saveCategory(Category.create("Animaux", Nature.VARIABLES, false, IncomeKind.PONCTUEL));
            return null;
        });
        awaitIdle(model);
        Category pets = category(model, "Animaux");
        assertThat(onFx(() -> model.categories.size())).isEqualTo(before + 1);

        onFx(() -> {
            model.saveRule(Rule.create(pets.id(), "Vétérinaire", null, List.of("VETERINAIRE")));
            return null;
        });
        awaitIdle(model);
        Rule created = onFx(() -> model.rules.getFirst());
        assertThat(created.categoryId()).isEqualTo(pets.id());
        Rule second = onFx(() -> model.rules.get(1));
        onFx(() -> {
            model.swapRules(created, second);
            return null;
        });
        awaitIdle(model);
        assertThat(onFx(() -> model.rules.get(1).id())).isEqualTo(created.id());

        onFx(() -> {
            model.setSavingPercent(pets, 35);
            return null;
        });
        Thread.sleep(400);
        onFx(() -> {
            model.reload(null);
            return null;
        });
        awaitIdle(model);
        assertThat(category(model, "Animaux").savingPercent()).isEqualTo(35);

        onFx(() -> {
            model.deleteCategory(pets);
            return null;
        });
        awaitIdle(model);
        assertThat(onFx(() -> model.categories.size())).isEqualTo(before);
        assertThat(onFx(() -> model.rules.stream().noneMatch(r -> r.id() == created.id()))).isTrue();
    }

    private static Category category(AppModel model, String name) throws Exception {
        return onFx(() -> model.categories.stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow());
    }

    private static ClassifiedOperation operation(AppModel model, long id) throws Exception {
        return onFx(() -> model.operations.stream().filter(o -> o.operation().id() == id).findFirst().orElseThrow());
    }

    private static void show(AppModel model, ToggleButton item) throws Exception {
        onFx(() -> {
            model.notice.set(null);
            item.setSelected(true);
            return null;
        });
        onFx(() -> null); // laisse passer les mises à jour différées de l'écran
    }

    // ------------------------------------------------------------------ outils

    private static <T> T onFx(Callable<T> action) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                result.complete(action.call());
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        return result.get(30, TimeUnit.SECONDS);
    }

    /** Attend la fin des tâches de fond, puis laisse passer les mises à jour d'affichage en attente. */
    private static void awaitIdle(AppModel model) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        Thread.sleep(150);
        while (onFx(model.busy::get)) {
            assertThat(System.nanoTime()).as("tâche de fond trop longue").isLessThan(deadline);
            Thread.sleep(50);
        }
        onFx(() -> null);
    }

    /** Attend qu'une condition, vérifiée hors du fil JavaFX, soit remplie. */
    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as("en attente : " + what).isLessThan(deadline);
            Thread.sleep(50);
        }
    }

    private static void snapshot(Scene scene, Path file) throws Exception {
        WritableImage image = onFx(() -> {
            // Les zones défilantes sont rendues via une image en cache, que seul le rendu d'une vraie fenêtre
            // tient à jour : sans fenêtre, il faut la désactiver pour ne pas photographier un état périmé.
            scene.getRoot().lookupAll(".viewport").forEach(viewport -> viewport.setCache(false));
            scene.getRoot().applyCss();
            scene.getRoot().layout();
            return scene.snapshot(null);
        });
        writePng(image, file);
        System.out.println("SNAPSHOT " + file.toAbsolutePath() + " " + (int) image.getWidth() + "x" + (int) image.getHeight());
    }

    /**
     * Rend le contenu d'une boîte de dialogue sans l'ouvrir, à sa taille naturelle. Seul le contenu est
     * rendu (sans la barre de boutons) : hors de sa fenêtre, le panneau complet ne se dimensionne pas.
     */
    private static void snapshotDialog(Callable<Dialog<?>> factory, Path file) throws Exception {
        Scene scene = onFx(() -> {
            DialogPane pane = factory.call().getDialogPane();
            // une fenêtre de dialogue prend la hauteur prefHeight(-1) de son panneau : elle doit suffire au contenu
            pane.applyCss();
            assertThat(pane.prefHeight(-1)).as("hauteur que prendra la fenêtre de " + file.getFileName())
                    .isGreaterThanOrEqualTo(pane.prefHeight(pane.prefWidth(-1)) - 1);
            Node content = pane.getContent();
            pane.setContent(null);
            StackPane holder = new StackPane(content);
            holder.setPadding(new Insets(20));
            holder.getStylesheets().addAll(pane.getStylesheets());
            holder.getStyleClass().addAll(pane.getStyleClass().filtered("dark"::equals));
            Scene probe = new Scene(holder, 900, 1200);
            holder.applyCss();
            holder.layout();
            double width = Math.ceil(holder.prefWidth(-1));
            double height = Math.ceil(holder.prefHeight(width));
            probe.setRoot(new Group());
            return new Scene(holder, width, height);
        });
        snapshot(scene, file);
    }

    /** Encodeur PNG minimal (RVB 8 bits), pour ne dépendre ni de Swing ni d'AWT. */
    private static void writePng(WritableImage image, Path file) throws IOException {
        int w = (int) image.getWidth();
        int h = (int) image.getHeight();
        PixelReader pixels = image.getPixelReader();
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DeflaterOutputStream z = new DeflaterOutputStream(raw)) {
            byte[] line = new byte[1 + 3 * w];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int argb = pixels.getArgb(x, y);
                    line[1 + 3 * x] = (byte) (argb >> 16);
                    line[2 + 3 * x] = (byte) (argb >> 8);
                    line[3 + 3 * x] = (byte) argb;
                }
                z.write(line);
            }
        }
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(png);
        out.write(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        DataOutputStream ihdr = new DataOutputStream(header);
        ihdr.writeInt(w);
        ihdr.writeInt(h);
        ihdr.write(new byte[] {8, 2, 0, 0, 0}); // 8 bits par canal, RVB
        chunk(out, "IHDR", header.toByteArray());
        chunk(out, "IDAT", raw.toByteArray());
        chunk(out, "IEND", new byte[0]);
        Files.write(file, png.toByteArray());
    }

    private static void chunk(DataOutputStream out, String type, byte[] data) throws IOException {
        byte[] name = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(name);
        crc.update(data);
        out.writeInt(data.length);
        out.write(name);
        out.write(data);
        out.writeInt((int) crc.getValue());
    }
}
