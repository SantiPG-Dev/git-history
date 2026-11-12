package com.githistory.ui;

import javafx.scene.control.TextInputDialog;

/** TextInputDialog con el tema de la aplicación aplicado a su DialogPane. */
public class ThemedTextInputDialog extends TextInputDialog {

    public ThemedTextInputDialog() {
        super();
        Themes.style(getDialogPane());
    }

    public ThemedTextInputDialog(String defaultValue) {
        super(defaultValue);
        Themes.style(getDialogPane());
    }
}
