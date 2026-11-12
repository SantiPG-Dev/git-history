package com.githistory.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Control;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import org.eclipse.jgit.transport.CredentialItem;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.URIish;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * CredentialsProvider interactivo: cuando JGit necesita credenciales (HTTPS)
 * o confirmaciones SSH (aceptar una clave de host desconocida o modificada,
 * passphrase de una clave privada), muestra un diálogo en el hilo de JavaFX
 * y bloquea el hilo de fondo hasta que el usuario responde.
 */
public class CredentialsPrompt extends CredentialsProvider {

    @Override
    public boolean isInteractive() {
        return true;
    }

    @Override
    public boolean supports(CredentialItem... items) {
        for (CredentialItem item : items) {
            if (item instanceof CredentialItem.Username
                    || item instanceof CredentialItem.Password
                    || item instanceof CredentialItem.StringType
                    || item instanceof CredentialItem.CharArrayType
                    || item instanceof CredentialItem.YesNoType
                    || item instanceof CredentialItem.InformationalMessage) {
                continue;
            }
            return false;
        }
        return true;
    }

    @Override
    public boolean get(URIish uri, CredentialItem... items) {
        if (Platform.isFxApplicationThread()) {
            return prompt(uri, items);
        }
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        Platform.runLater(() -> future.complete(prompt(uri, items)));
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException e) {
            return false;
        }
    }

    private boolean prompt(URIish uri, CredentialItem... items) {
        boolean hasYesNo = false;
        boolean hasSecrets = false;
        for (CredentialItem item : items) {
            if (item instanceof CredentialItem.YesNoType) {
                hasYesNo = true;
            } else if (!(item instanceof CredentialItem.InformationalMessage)) {
                hasSecrets = true;
            }
        }
        if (hasYesNo && !hasSecrets) {
            return promptConfirmation(uri, items);
        }
        return promptForm(uri, items);
    }

    /** Confirmación Sí/No: p. ej. aceptar la clave de un host SSH desconocido. */
    private boolean promptConfirmation(URIish uri, CredentialItem... items) {
        StringBuilder message = new StringBuilder();
        for (CredentialItem item : items) {
            message.append(item.getPromptText()).append('\n');
        }
        Alert alert = new ThemedAlert(Alert.AlertType.CONFIRMATION,
                message.toString().trim(), ButtonType.YES, ButtonType.NO);
        alert.setTitle("Confirmación de seguridad");
        alert.setHeaderText("Conexión a " + uri);
        boolean yes = alert.showAndWait().filter(ButtonType.YES::equals).isPresent();
        for (CredentialItem item : items) {
            if (item instanceof CredentialItem.YesNoType yesNo) {
                yesNo.setValue(yes);
            }
        }
        return yes;
    }

    /** Formulario de credenciales: usuario/contraseña, passphrases, etc. */
    private boolean promptForm(URIish uri, CredentialItem... items) {
        Dialog<ButtonType> dialog = new ThemedDialog<>();
        dialog.setTitle("Credenciales");
        dialog.setHeaderText("Introduce las credenciales para\n" + uri);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.setPadding(new Insets(12));
        List<Control> controls = new ArrayList<>();
        int row = 0;
        for (CredentialItem item : items) {
            if (item instanceof CredentialItem.InformationalMessage) {
                Label info = new Label(item.getPromptText());
                info.setWrapText(true);
                grid.add(info, 0, row++, 2, 1);
                controls.add(null);
            } else if (item instanceof CredentialItem.YesNoType) {
                CheckBox check = new CheckBox(item.getPromptText());
                grid.add(check, 0, row++, 2, 1);
                controls.add(check);
            } else {
                TextField field = item instanceof CredentialItem.Password
                        || item instanceof CredentialItem.CharArrayType
                        ? new PasswordField()
                        : new TextField();
                grid.addRow(row++, new Label(item.getPromptText() + ":"), field);
                controls.add(field);
            }
        }
        dialog.getDialogPane().setContent(grid);

        var result = dialog.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK) {
            return false;
        }
        for (int i = 0; i < items.length; i++) {
            CredentialItem item = items[i];
            Control control = controls.get(i);
            if (item instanceof CredentialItem.YesNoType yesNo && control instanceof CheckBox check) {
                yesNo.setValue(check.isSelected());
            } else if (control instanceof TextField field) {
                String value = field.getText();
                if (item instanceof CredentialItem.Username username) {
                    username.setValue(value);
                } else if (item instanceof CredentialItem.Password password) {
                    password.setValue(value.toCharArray());
                } else if (item instanceof CredentialItem.StringType stringType) {
                    stringType.setValue(value);
                } else if (item instanceof CredentialItem.CharArrayType charArray) {
                    charArray.setValueNoCopy(value.toCharArray());
                }
                field.clear();
            }
        }
        return true;
    }
}
