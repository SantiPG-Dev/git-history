package com.githistory.ui;

import com.githistory.AppConfig;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
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
 * Preferencias de la aplicación: identidad de autor global (~/.gitconfig),
 * firma GPG de commits (commit.gpgsign + user.signingkey) y, si hay un
 * repositorio abierto, una identidad propia para ese repositorio
 * (.git/config).
 */
public class PreferencesDialog extends Dialog<Void> {

    public PreferencesDialog(Window owner, RepoView activeRepo) {
        Themes.style(getDialogPane());
        setTitle("Preferencias");
        setHeaderText(null);
        initOwner(owner);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        String globalName0 = "";
        String globalEmail0 = "";
        boolean sign0 = false;
        String key0 = "";
        try {
            StoredConfig global = SystemReader.getInstance().getUserConfig();
            globalName0 = nvl(global.getString("user", null, "name"));
            globalEmail0 = nvl(global.getString("user", null, "email"));
            sign0 = global.getBoolean("commit", "gpgsign", false);
            key0 = nvl(global.getString("user", null, "signingkey"));
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

        CheckBox signBox = new CheckBox("Firmar commits con GPG");
        signBox.setSelected(sign0);
        TextField keyField = new TextField(key0);
        keyField.setPromptText("ID o huella de la clave (vacío = clave por defecto)");
        keyField.setPrefColumnCount(24);
        GridPane signGrid = new GridPane();
        signGrid.setHgap(8);
        signGrid.setVgap(8);
        signGrid.addRow(0, new Label("Clave:"), keyField);
        Label signTitle = new Label("Firma");
        signTitle.getStyleClass().add("prefs-section-title");

        VBox box = new VBox(10, globalTitle, globalGrid, signTitle, signBox, signGrid);
        box.setPadding(new Insets(12));

        String theme0 = AppConfig.load().theme();
        ComboBox<String> themeBox = new ComboBox<>();
        themeBox.getItems().addAll("Oscuro", "Claro");
        themeBox.getSelectionModel().select(Themes.LIGHT.equals(theme0) ? 1 : 0);
        // Vista previa en vivo sobre la ventana principal.
        themeBox.getSelectionModel().selectedIndexProperty().addListener(
                (obs, o, index) -> Themes.apply(owner.getScene(),
                        index.intValue() == 1 ? Themes.LIGHT : Themes.DARK));
        Label appearanceTitle = new Label("Apariencia");
        appearanceTitle.getStyleClass().add("prefs-section-title");
        GridPane appearanceGrid = new GridPane();
        appearanceGrid.setHgap(8);
        appearanceGrid.setVgap(8);
        appearanceGrid.addRow(0, new Label("Tema:"), themeBox);

        CheckBox autoFetchBox = new CheckBox("Fetch automático de los repos abiertos");
        TextField autoFetchMinutes = new TextField(
                String.valueOf(Math.max(1, AppConfig.load().autoFetchMinutes())));
        autoFetchMinutes.setPrefColumnCount(4);
        int autoFetch0 = AppConfig.load().autoFetchMinutes();
        autoFetchBox.setSelected(autoFetch0 > 0);
        autoFetchMinutes.setDisable(autoFetch0 <= 0);
        autoFetchBox.setOnAction(e ->
                autoFetchMinutes.setDisable(!autoFetchBox.isSelected()));
        GridPane fetchGrid = new GridPane();
        fetchGrid.setHgap(8);
        fetchGrid.setVgap(8);
        fetchGrid.addRow(0, new Label("Cada (minutos):"), autoFetchMinutes);
        box.getChildren().addAll(appearanceTitle, appearanceGrid,
                autoFetchBox, fetchGrid);

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
                    global.setBoolean("commit", null, "gpgsign", signBox.isSelected());
                    global.setString("user", null, "signingkey", emptyToNull(keyField.getText()));
                    global.save();
                    AppConfig config = AppConfig.load();
                    config.setTheme(themeBox.getSelectionModel().getSelectedIndex() == 1
                            ? Themes.LIGHT : Themes.DARK);
                    int minutes = 0;
                    if (autoFetchBox.isSelected()) {
                        try {
                            minutes = Math.max(1,
                                    Integer.parseInt(autoFetchMinutes.getText().trim()));
                        } catch (NumberFormatException ex) {
                            minutes = 5;
                        }
                    }
                    config.setAutoFetchMinutes(minutes);
                    config.save();
                    if (activeRepo != null) {
                        if (override.isSelected()) {
                            activeRepo.git().setIdentity(rn.getText().trim(), re.getText().trim());
                        } else {
                            activeRepo.git().clearIdentity();
                        }
                    }
                } catch (IOException | ConfigInvalidException ex) {
                    new ThemedAlert(Alert.AlertType.ERROR,
                            "No se pudieron guardar las preferencias: " + ex.getMessage())
                            .showAndWait();
                }
            } else {
                // Cancelar: revierte la vista previa del tema.
                Themes.apply(owner.getScene(), theme0);
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
