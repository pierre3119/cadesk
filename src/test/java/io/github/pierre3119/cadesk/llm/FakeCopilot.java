package io.github.pierre3119.cadesk.llm;

import java.nio.file.Path;
import java.time.Duration;

/** Pour les tests des autres paquets : ce que l'application obtient du {@linkplain FakeCopilotRuntime faux moteur}. */
public final class FakeCopilot {

    private FakeCopilot() {
    }

    /**
     * Une connexion à GitHub menée par le faux moteur ; elle aboutit quand le test
     * {@linkplain FakeCopilotRuntime#authorize autorise} la connexion.
     *
     * @param scenario scénario du faux moteur, s'il ne doit pas simplement réussir
     */
    public static CopilotLogin login(Path journal, boolean byCode, String... scenario) {
        return new CopilotLogin(byCode, FakeCopilotRuntime.command(journal, scenario), Duration.ofSeconds(30));
    }
}
