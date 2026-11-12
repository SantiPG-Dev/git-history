package com.githistory.ui;

import javafx.scene.control.Dialog;

/** Dialog con el tema de la aplicación aplicado a su DialogPane. */
public class ThemedDialog<R> extends Dialog<R> {

    public ThemedDialog() {
        super();
        Themes.style(getDialogPane());
    }
}
