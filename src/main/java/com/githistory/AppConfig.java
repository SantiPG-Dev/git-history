package com.githistory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * Configuración persistente de la app en
 * {@code $XDG_CONFIG_HOME/git-history/git-history.properties}
 * (por defecto {@code ~/.config/git-history/}). Guarda las pestañas de
 * repositorios abiertas para restaurarlas al arrancar y el tema visual.
 */
public final class AppConfig {

    private final Path file;
    private final Properties props = new Properties();

    private AppConfig(Path file) {
        this.file = file;
    }

    public static AppConfig load() {
        return load(defaultFile());
    }

    public static AppConfig load(Path file) {
        AppConfig config = new AppConfig(file);
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                config.props.load(in);
            } catch (IOException ignored) {
                // Config corrupta o ilegible: se empieza con valores vacíos.
            }
        }
        return config;
    }

    static Path defaultFile() {
        String xdg = System.getenv("XDG_CONFIG_HOME");
        Path base = xdg != null && !xdg.isBlank()
                ? Path.of(xdg)
                : Path.of(System.getProperty("user.home"), ".config");
        return base.resolve("git-history").resolve("git-history.properties");
    }

    /** Rutas de los repos abiertos la última sesión (solo las que existen). */
    public List<Path> openRepos() {
        String raw = props.getProperty("repos", "");
        return Arrays.stream(raw.split(java.io.File.pathSeparator))
                .filter(s -> !s.isBlank())
                .map(Path::of)
                .filter(Files::exists)
                .toList();
    }

    public void setOpenRepos(List<Path> repos) {
        props.setProperty("repos", repos.stream()
                .map(Path::toString)
                .collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator)));
    }

    /** Tema visual: "dark" (por defecto) o "light". */
    public String theme() {
        return props.getProperty("theme", "dark");
    }

    public void setTheme(String theme) {
        props.setProperty("theme", theme);
    }

    public void save() throws IOException {
        Files.createDirectories(file.getParent());
        try (OutputStream out = Files.newOutputStream(file)) {
            props.store(out, "git-history");
        }
    }
}
