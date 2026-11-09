package com.githistory.ui;

import javafx.scene.Scene;

/** Aplica el tema visual (oscuro por defecto; claro como hoja extra). */
public final class Themes {

    public static final String DARK = "dark";
    public static final String LIGHT = "light";

    private static final String LIGHT_URL = Themes.class
            .getResource("/com/githistory/app-light.css").toExternalForm();

    private Themes() {}

    /** El tema claro es una segunda hoja que sobrescribe la base oscura. */
    public static void apply(Scene scene, String theme) {
        scene.getStylesheets().remove(LIGHT_URL);
        if (LIGHT.equals(theme)) {
            scene.getStylesheets().add(LIGHT_URL);
        }
    }
}
