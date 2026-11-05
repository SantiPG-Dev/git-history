package com.githistory.ui;

import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.util.SystemReader;

import java.io.IOException;

/**
 * Preferencias de la aplicación. De momento: identidad de autor global
 * (~/.gitconfig) y, si hay un repositorio abierto, una identidad propia
 * para ese repositorio (.git/config).
 */
public class PreferencesDialog extends Dialog<Void> {

    public PreferencesDialog(Window owner, RepoView activeRepo) {
        setTitle("Preferencias");
        setHeaderText(null);
        initOwner(owner);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        String globalName0 = "";
        String globalEmail0 = "";
        try {
            StoredConfig global = SystemReader.getInstance().getUserConfig();
            globalName0 = nvl(global.getString("user", null, "name"));
            globalEmail0 = nvl(global.getString("user", null, "email"));
        } catch (IOException | ConfigInvalidException ignored) {
            // ~/.gitconfig ilegible: se empieza con campos vacíos.
        }
        TextField globalName = new TextField(globalName0);
        TextField globalEmail = new TextField(globalEmail0);

        GridPane globalGrid = new GridPane();
        globalGrid.setHgap(8);
        globalGrid.setVgap(8);
        globalGrid.addRow(0, new Label("Nombre:"), globalName);
        globalGrid.addRow(1, new Label("Email:"), globalEmail);

        Label globalTitle = new Label("Identidad global");
        globalTitle.getStyleClass().add("prefs-section-title");
        VBox box = new VBox(10, globalTitle, globalGrid);
        box.setPadding(new Insets(12));

        CheckBox repoOverride = null;
        TextField repoName = null;
        TextField repoEmail = null;
        if (activeRepo != null) {
            repoOverride = new CheckBox("Usar identidad propia en este repositorio");
            repoName = new TextField();
            repoEmail = new TextField();
            repoName.setDisable(true);
            repoEmail.setDisable(true);

            String localName = activeRepo.git().localIdentityName();
            String localEmail = activeRepo.git().localIdentityEmail();
            if (localName != null || localEmail != null) {
                repoOverride.setSelected(true);
                repoName.setDisable(false);
                repoEmail.setDisable(false);
                repoName.setText(nvl(localName));
                repoEmail.setText(nvl(localEmail));
            }

            TextField rn = repoName;
            TextField re = repoEmail;
            CheckBox cb = repoOverride;
            repoOverride.setOnAction(e -> {
                rn.setDisable(!cb.isSelected());
                re.setDisable(!cb.isSelected());
                if (cb.isSelected() && rn.getText().isBlank()) {
                    rn.setText(globalName.getText());
                    re.setText(globalEmail.getText());
                }
            });

            GridPane repoGrid = new GridPane();
            repoGrid.setHgap(8);
            repoGrid.setVgap(8);
            repoGrid.addRow(0, new Label("Nombre:"), repoName);
            repoGrid.addRow(1, new Label("Email:"), repoEmail);

            Label repoTitle = new Label("Repositorio: " + activeRepo.getRepoPath().getFileName());
            repoTitle.getStyleClass().add("prefs-section-title");
            box.getChildren().addAll(repoTitle, repoOverride, repoGrid);
        }

        getDialogPane().setContent(box);
        CheckBox override = repoOverride;
        TextField rn = repoName;
        TextField re = repoEmail;

        setResultConverter(button -> {
            if (button == ButtonType.OK) {
                try {
                    StoredConfig global = SystemReader.getInstance().getUserConfig();
                    global.setString("user", null, "name", emptyToNull(globalName.getText()));
                    global.setString("user", null, "email", emptyToNull(globalEmail.getText()));
                    global.save();
                    if (activeRepo != null) {
                        if (override.isSelected()) {
                            activeRepo.git().setIdentity(rn.getText().trim(), re.getText().trim());
                        } else {
                            activeRepo.git().clearIdentity();
                        }
                    }
                } catch (IOException | ConfigInvalidException ex) {
                    new Alert(Alert.AlertType.ERROR,
                            "No se pudieron guardar las preferencias: " + ex.getMessage())
                            .showAndWait();
                }
            }
            return null;
        });
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    private static String emptyToNull(String s) {
        String trimmed = s == null ? "" : s.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
