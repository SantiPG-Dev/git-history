package com.githistory.ui;

import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/**
 * Notificaciones transitorias (toasts) apiladas en la esquina inferior
 * derecha, al estilo de GitKraken. Desaparecen solas a los pocos segundos
 * o al hacer clic sobre ellas.
 */
public class Toaster {

    private static final int MAX_TOASTS = 4;

    private final VBox box = new VBox(6);

    public Toaster(StackPane host) {
        box.setAlignment(Pos.BOTTOM_RIGHT);
        box.setPadding(new Insets(0, 14, 14, 0));
        box.setPickOnBounds(false);
        StackPane.setAlignment(box, Pos.BOTTOM_RIGHT);
        host.getChildren().add(box);
    }

    public void show(String message) {
        Label toast = new Label(message);
        toast.getStyleClass().add("toast");
        toast.setMaxWidth(420);
        toast.setWrapText(true);
        while (box.getChildren().size() >= MAX_TOASTS) {
            box.getChildren().remove(0);
        }
        box.getChildren().add(toast);
        toast.setOpacity(0);
        FadeTransition fadeIn = new FadeTransition(Duration.millis(180), toast);
        fadeIn.setToValue(1);
        fadeIn.play();
        PauseTransition pause = new PauseTransition(Duration.seconds(3.5));
        pause.setOnFinished(e -> dismiss(toast));
        pause.play();
        toast.setOnMouseClicked(e -> {
            pause.stop();
            dismiss(toast);
        });
    }

    private void dismiss(Label toast) {
        if (!box.getChildren().contains(toast)) {
            return;
        }
        FadeTransition fadeOut = new FadeTransition(Duration.millis(280), toast);
        fadeOut.setToValue(0);
        fadeOut.setOnFinished(e -> box.getChildren().remove(toast));
        fadeOut.play();
    }
}
