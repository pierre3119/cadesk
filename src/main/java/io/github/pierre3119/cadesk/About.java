package io.github.pierre3119.cadesk;

import java.lang.module.ModuleDescriptor;
import java.util.Optional;

/** L'application et son auteur : ce qu'affiche la boîte « À propos ». */
public final class About {

    /** Nom de l'application, tel qu'il s'affiche. */
    public static final String NAME = "CaDesk";

    public static final String AUTHOR = "Pierre Duloy";
    public static final String AUTHOR_LINKEDIN = "https://www.linkedin.com/in/pierre-duloy-2101929/";
    public static final String AUTHOR_GITHUB = "https://github.com/pierre3119";

    private About() {
    }

    /**
     * Version de l'application : celle de son module, que la construction y inscrit. Absente quand
     * l'application est lancée hors de son module (depuis un environnement de développement).
     */
    public static Optional<String> version() {
        ModuleDescriptor descriptor = About.class.getModule().getDescriptor();
        return descriptor == null ? Optional.empty() : descriptor.rawVersion();
    }
}
