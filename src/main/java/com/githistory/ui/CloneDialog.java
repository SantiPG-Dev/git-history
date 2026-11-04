package com.githistory.ui;

import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;

import java.io.File;
import java.nio.file.Path;

public class CloneDialog extends Dialog<CloneDialog.Request> {

    public record Request(String url, Path directory) {}

    public CloneDialog(Window owner) {
        setTitle("Clonar repositorio");
        setHeaderText("Clona un repositorio remoto en tu equipo");
        initOwner(owner);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        TextField urlField = new TextField();
        urlField.setPromptText("https://github.com/usuario/repositorio.git");
        urlField.setPrefColumnCount(28);
        TextField dirField = new TextField();
        dirField.setPromptText("/home/usuario/proyectos/repositorio");
        dirField.setPrefColumnCount(28);
        Button browse = new Button("Examinar…");
        browse.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Carpeta donde crear el clon");
            File dir = chooser.showDialog(getOwner());
            if (dir != null) {
                dirField.setText(dir.toPath().resolve(repoName(urlField.getText())).toString());
            }
        });
        urlField.focusedProperty().addListener((obs, was, focused) -> {
            if (!focused && dirField.getText().isBlank() && !urlField.getText().isBlank()) {
                dirField.setText(System.getProperty("user.home")
                        + File.separator + repoName(urlField.getText()));
            }
        });

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.setPadding(new Insets(12));
        grid.addRow(0, new Label("URL:"), urlField);
        grid.addRow(1, new Label("Destino:"), dirField, browse);
        getDialogPane().setContent(grid);

        Button ok = (Button) getDialogPane().lookupButton(ButtonType.OK);
        ok.disableProperty().bind(
                urlField.textProperty().isEmpty().or(dirField.textProperty().isEmpty()));

        setResultConverter(button -> button == ButtonType.OK
                ? new Request(urlField.getText().trim(), Path.of(dirField.getText().trim()))
                : null);
    }

    private static String repoName(String url) {
        String name = url.replaceAll("[/\\\\]+$", "");
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf(':'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        if (name.endsWith(".git")) {
            name = name.substring(0, name.length() - 4);
        }
        return name.isBlank() ? "repositorio" : name;
    }
}
