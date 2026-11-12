package com.githistory.ui;

import com.githistory.AppConfig;
import javafx.scene.Scene;
import javafx.scene.control.DialogPane;

/** Aplica el tema visual (oscuro por defecto; claro como hoja extra). */
public final class Themes {

    public static final String DARK = "dark";
    public static final String LIGHT = "light";

    private static final String APP_URL = Themes.class
            .getResource("/com/githistory/app.css").toExternalForm();
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

    /**
     * Aplica el tema a un diálogo. Los diálogos crean su propia escena, así
     * que no heredan las hojas de la ventana principal: hay que añadirlas al
     * DialogPane (la base oscura y, si toca, la hoja clara encima).
     */
    public static void style(DialogPane pane) {
        if (!pane.getStylesheets().contains(APP_URL)) {
            pane.getStylesheets().add(APP_URL);
        }
        pane.getStylesheets().remove(LIGHT_URL);
        if (LIGHT.equals(AppConfig.load().theme())) {
            pane.getStylesheets().add(LIGHT_URL);
        }
    }
}
