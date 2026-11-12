package com.githistory.ui;

import com.githistory.git.GitService.RemoteInfo;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.List;

/**
 * Gestión de remotos del repositorio: lista los configurados y permite
 * añadir nuevos (nombre + URL) o eliminar el seleccionado.
 */
public class RemotesDialog extends Dialog<Void> {

    public interface RemoteActions {
        List<RemoteInfo> remotes();
        void addRemote(String name, String url) throws Exception;
        void removeRemote(String name) throws Exception;
    }

    public RemotesDialog(Window owner, RemoteActions actions) {
        Themes.style(getDialogPane());
        setTitle("Remotos");
        setHeaderText(null);
        initOwner(owner);
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        ListView<RemoteInfo> list = new ListView<>();
        list.setCellFactory(lv -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(RemoteInfo remote, boolean empty) {
                super.updateItem(remote, empty);
                setText(empty || remote == null ? null
                        : remote.name() + "  →  " + (remote.url() == null ? "" : remote.url()));
            }
        });
        list.setPrefSize(560, 220);
        Runnable reload = () -> list.getItems().setAll(actions.remotes());
        reload.run();

        TextField nameField = new TextField();
        nameField.setPromptText("origin");
        TextField urlField = new TextField();
        urlField.setPromptText("git@github.com:usuario/repo.git");
        HBox.setHgrow(urlField, Priority.ALWAYS);
        Button add = new Button("Añadir");
        add.disableProperty().bind(nameField.textProperty().isEmpty()
                .or(urlField.textProperty().isEmpty()));
        add.setOnAction(e -> {
            try {
                actions.addRemote(nameField.getText().trim(), urlField.getText().trim());
                nameField.clear();
                urlField.clear();
                reload.run();
            } catch (Exception ex) {
                showError("No se pudo añadir el remoto", ex);
            }
        });

        Button remove = new Button("Eliminar");
        remove.disableProperty().bind(list.getSelectionModel().selectedItemProperty().isNull());
        remove.setOnAction(e -> {
            RemoteInfo selected = list.getSelectionModel().getSelectedItem();
            if (selected == null) {
                return;
            }
            Alert confirm = new ThemedAlert(Alert.AlertType.CONFIRMATION,
                    "Se eliminará el remoto «" + selected.name()
                            + "» y sus ramas de seguimiento. ¿Continuar?");
            confirm.setHeaderText("Eliminar remoto");
            confirm.showAndWait().filter(bt -> bt == ButtonType.OK).ifPresent(bt -> {
                try {
                    actions.removeRemote(selected.name());
                    reload.run();
                } catch (Exception ex) {
                    showError("No se pudo eliminar el remoto", ex);
                }
            });
        });

        GridPane addRow = new GridPane();
        addRow.setHgap(8);
        addRow.addRow(0, new Label("Nombre:"), nameField, new Label("URL:"), urlField, add);

        HBox buttons = new HBox(8, remove);
        VBox box = new VBox(10, list, addRow, buttons);
        box.setPadding(new Insets(12));
        getDialogPane().setContent(box);
    }

    private static void showError(String header, Exception ex) {
        Alert alert = new ThemedAlert(Alert.AlertType.ERROR, ex.getMessage());
        alert.setHeaderText(header);
        alert.showAndWait();
    }
}
