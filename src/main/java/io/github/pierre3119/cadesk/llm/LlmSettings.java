package io.github.pierre3119.cadesk.llm;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Réglages de l'assistant, propres à chaque profil et enregistrés dans ses préférences.
 *
 * @param provider service choisi, ou {@code null} tant que rien n'est réglé
 * @param url      adresse de base de l'API (« https://api.openai.com/v1 »), vide pour un service qui n'en a pas
 * @param apiKey   clé d'API (pour GitHub Copilot : jeton d'accès GitHub), vide pour un service qui n'en demande pas
 * @param model    nom du modèle
 * @param onImport vrai pour classer automatiquement les opérations nouvelles à chaque import
 */
public record LlmSettings(LlmProvider provider, String url, String apiKey, String model, boolean onImport) {

    public static final LlmSettings NONE = new LlmSettings(null, "", "", "", false);

    private static final String PROVIDER = "llm.provider";
    private static final String URL = "llm.url";
    private static final String KEY = "llm.key";
    private static final String MODEL = "llm.model";
    private static final String ON_IMPORT = "llm.import";

    public LlmSettings {
        url = url == null ? "" : url.strip();
        apiKey = apiKey == null ? "" : apiKey.strip();
        model = model == null ? "" : model.strip();
    }

    /** Vrai si le service peut être appelé : modèle et, s'il en faut, adresse et clé d'API. */
    public boolean isConfigured() {
        return provider != null && !model.isEmpty() && (!provider.urlRequired() || !url.isEmpty())
                && (!provider.keyRequired() || !apiKey.isEmpty());
    }

    /** Vrai si les opérations nouvelles doivent être classées à l'import. */
    public boolean classifiesImports() {
        return onImport && isConfigured();
    }

    public LlmSettings withOnImport(boolean enabled) {
        return new LlmSettings(provider, url, apiKey, model, enabled);
    }

    /** Les réglages sous la forme de préférences (nom, valeur). */
    public Map<String, String> toSettings() {
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put(PROVIDER, provider == null ? "" : provider.name());
        settings.put(URL, url);
        settings.put(KEY, apiKey);
        settings.put(MODEL, model);
        settings.put(ON_IMPORT, Boolean.toString(onImport));
        return settings;
    }

    /**
     * @param setting donne la valeur d'une préférence, ou {@code null} si elle n'est pas enregistrée
     */
    public static LlmSettings of(Function<String, String> setting) {
        String name = setting.apply(PROVIDER);
        LlmProvider provider = null;
        for (LlmProvider candidate : LlmProvider.values()) {
            if (candidate.name().equals(name)) {
                provider = candidate;
            }
        }
        return new LlmSettings(provider, setting.apply(URL), setting.apply(KEY), setting.apply(MODEL),
                Boolean.parseBoolean(setting.apply(ON_IMPORT)));
    }
}
