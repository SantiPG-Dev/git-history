package com.githistory.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Terminal integrada en la parte inferior de la vista del repositorio:
 * una sesión de bash en su directorio. Tras cada comando que empiece por
 * "git" se refresca la vista, por si cambió el estado del repo.
 */
public class TerminalView extends VBox {

    private final TextArea output = new TextArea();
    private final TextField input = new TextField();
    private TerminalSession session;

    /**
     * @param onGitCommand se invoca tras enviar un comando "git …" (para refrescar)
     * @param onClose      se invoca al pulsar el botón de cerrar el panel
     */
    public TerminalView(Path directory, Runnable onGitCommand, Runnable onClose) {
        Label title = new Label("Terminal — " + directory);
        title.getStyleClass().add("section-header");
        Button close = new Button("✕");
        close.getStyleClass().add("hunk-button");
        close.setOnAction(e -> onClose.run());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(8, title, spacer, close);
        header.setAlignment(Pos.CENTER_LEFT);

        output.setEditable(false);
        output.getStyleClass().add("terminal-output");
        output.setPrefRowCount(9);
        VBox.setVgrow(output, Priority.ALWAYS);

        input.setPromptText("comando… (Enter para ejecutar, clear para limpiar)");
        input.getStyleClass().add("terminal-input");
        input.setOnAction(e -> runCommand(input.getText().trim(), onGitCommand));

        getChildren().addAll(header, output, input);
        setPadding(new Insets(4, 8, 8, 8));
        setSpacing(4);
        getStyleClass().add("terminal-panel");

        try {
            session = new TerminalSession(directory,
                    text -> Platform.runLater(() -> append(text)));
        } catch (IOException e) {
            append("No se pudo iniciar bash: " + e.getMessage() + "\n");
        }
    }

    private void runCommand(String command, Runnable onGitCommand) {
        if (command.isEmpty()) {
            return;
        }
        input.clear();
        if (command.equals("clear")) {
            output.clear();
            return;
        }
        if (session == null || !session.isAlive()) {
            append("(la sesión de bash no está activa)\n");
            return;
        }
        append("$ " + command + "\n");
        try {
            session.send(command);
        } catch (IOException e) {
            append("Error al enviar el comando: " + e.getMessage() + "\n");
            return;
        }
        if (command.equals("git") || command.startsWith("git ")) {
            onGitCommand.run();
        }
    }

    private void append(String text) {
        output.appendText(text);
        output.setScrollTop(Double.MAX_VALUE);
    }

    public void dispose() {
        if (session != null) {
            session.close();
        }
    }
}
