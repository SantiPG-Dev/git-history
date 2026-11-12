package com.githistory.ui;

import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;

/** Alert con el tema de la aplicación aplicado a su DialogPane. */
public class ThemedAlert extends Alert {

    public ThemedAlert(AlertType type) {
        super(type);
        Themes.style(getDialogPane());
    }

    public ThemedAlert(AlertType type, String contentText) {
        super(type, contentText);
        Themes.style(getDialogPane());
    }

    public ThemedAlert(AlertType type, String contentText, ButtonType... buttons) {
        super(type, contentText, buttons);
        Themes.style(getDialogPane());
    }
}
