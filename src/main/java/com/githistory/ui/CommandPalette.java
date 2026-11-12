package com.githistory.ui;

import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Paleta de comandos estilo GitKraken (Ctrl+K): un cuadro con buscador y
 * lista filtrable de acciones; Enter o doble clic ejecuta, Esc cierra.
 * Las coincidencias que empiezan por el texto escrito van primero.
 */
public class CommandPalette {

    public record Command(String label, Runnable action) {}

    private CommandPalette() {}

    public static void show(Window owner, List<Command> commands) {
        Dialog<Void> dialog = new ThemedDialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Paleta de comandos");
        dialog.setHeaderText(null);
        dialog.getDialogPane().getStyleClass().add("command-palette");

        TextField filter = new TextField();
        filter.setPromptText("Escribe un comando…");
        filter.getStyleClass().add("search-field");

        ListView<Command> list = new ListView<>();
        list.setCellFactory(lv -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(Command item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.label());
            }
        });
        list.setPrefHeight(320);

        filter.textProperty().addListener((obs, o, q) -> {
            list.getItems().setAll(filter(commands, q));
            if (!list.getItems().isEmpty()) {
                list.getSelectionModel().select(0);
            }
        });
        list.getItems().setAll(commands);
        if (!commands.isEmpty()) {
            list.getSelectionModel().select(0);
        }

        filter.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.DOWN) {
                list.getSelectionModel().selectNext();
                e.consume();
            } else if (e.getCode() == KeyCode.UP) {
                list.getSelectionModel().selectPrevious();
                e.consume();
            } else if (e.getCode() == KeyCode.ENTER) {
                execute(dialog, list.getSelectionModel().getSelectedItem());
                e.consume();
            }
        });
        list.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                execute(dialog, list.getSelectionModel().getSelectedItem());
            }
        });

        VBox box = new VBox(6, filter, list, new Label("↑↓ para navegar · Enter para ejecutar · Esc para cerrar"));
        box.getStyleClass().add("command-palette-box");
        VBox.setVgrow(list, Priority.ALWAYS);
        dialog.getDialogPane().setContent(box);
        dialog.getDialogPane().getButtonTypes().add(javafx.scene.control.ButtonType.CLOSE);
        // Oculta el botón de cierre: basta Esc; la acción se ejecuta y cierra.
        dialog.getDialogPane().lookupButton(javafx.scene.control.ButtonType.CLOSE)
                .setVisible(false);
        dialog.show();
    }

    private static void execute(Dialog<Void> dialog, Command command) {
        if (command == null) {
            return;
        }
        dialog.close();
        command.action().run();
    }

    static List<Command> filter(List<Command> commands, String query) {
        if (query == null || query.isBlank()) {
            return new ArrayList<>(commands);
        }
        String q = query.toLowerCase();
        return commands.stream()
                .filter(c -> c.label().toLowerCase().contains(q))
                .sorted(Comparator.comparingInt(c ->
                        c.label().toLowerCase().startsWith(q) ? 0 : 1))
                .toList();
    }
}
