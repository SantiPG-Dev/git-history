package com.githistory.ui;

import com.githistory.git.GitService;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * Panel de staging: archivos sin preparar / preparados, con doble clic para
 * moverlos entre estados, el diff del archivo seleccionado (cambios actuales)
 * y la caja de mensaje para crear el commit.
 */
public class StagingView {

    private final RepoView repo;
    private final VBox root = new VBox();
    private final ListView<GitService.StatusEntry> unstagedList = new ListView<>();
    private final ListView<GitService.StatusEntry> stagedList = new ListView<>();
    private final TextArea messageArea = new TextArea();
    private final Label diffHeader = section("Cambios del archivo seleccionado");
    private final DiffView diffView = new DiffView();
    private final HBox mergeBar;

    public StagingView(RepoView repo) {
        this.repo = repo;

        VBox lists = new VBox(4);
        lists.setPadding(new Insets(4, 8, 8, 8));

        Label unstagedHeader = section("Cambios sin preparar");
        unstagedList.setCellFactory(lv -> new StatusCell(true));
        VBox.setVgrow(unstagedList, Priority.ALWAYS);
        unstagedList.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                var item = unstagedList.getSelectionModel().getSelectedItem();
                if (item != null) {
                    stage(item);
                }
            }
        });
        Button stageOne = new Button("Preparar");
        stageOne.setOnAction(e -> {
            var item = unstagedList.getSelectionModel().getSelectedItem();
            if (item != null) {
                stage(item);
            }
        });
        Button stageAll = new Button("Preparar todo");
        stageAll.setOnAction(e ->
                repo.runTask("Preparando todo", p -> {
                    repo.git().stageAll();
                    return null;
                }, v -> refresh()));

        Label stagedHeader = section("Cambios preparados");
        stagedList.setCellFactory(lv -> new StatusCell(false));
        VBox.setVgrow(stagedList, Priority.ALWAYS);
        stagedList.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                var item = stagedList.getSelectionModel().getSelectedItem();
                if (item != null) {
                    unstage(item);
                }
            }
        });
        Button unstageOne = new Button("Retirar");
        unstageOne.setOnAction(e -> {
            var item = stagedList.getSelectionModel().getSelectedItem();
            if (item != null) {
                unstage(item);
            }
        });
        Button unstageAll = new Button("Retirar todo");
        unstageAll.setOnAction(e ->
                repo.runTask("Retirando todo", p -> {
                    repo.git().unstageAll();
                    return null;
                }, v -> refresh()));

        Label messageHeader = section("Mensaje del commit");
        messageArea.setPromptText("Resumen de los cambios…");
        messageArea.setPrefRowCount(3);
        CheckBox amendBox = new CheckBox("Enmendar último commit (amend)");
        Button commit = new Button("Commit");
        commit.setMaxWidth(Double.MAX_VALUE);
        commit.getStyleClass().add("accent-button");
        commit.setOnAction(e -> doCommit(amendBox.isSelected()));

        Button discardAll = new Button("Descartar todo");
        discardAll.setMaxWidth(Double.MAX_VALUE);
        discardAll.setOnAction(e -> discardAll());

        lists.getChildren().addAll(
                unstagedHeader, unstagedList, new HBox(6, stageOne, stageAll),
                stagedHeader, stagedList, new HBox(6, unstageOne, unstageAll),
                messageHeader, messageArea, amendBox, commit, discardAll);

        unstagedList.getSelectionModel().selectedItemProperty().addListener((obs, o, item) -> {
            if (item != null) {
                stagedList.getSelectionModel().clearSelection();
                showDiff(item, false);
            }
        });
        stagedList.getSelectionModel().selectedItemProperty().addListener((obs, o, item) -> {
            if (item != null) {
                unstagedList.getSelectionModel().clearSelection();
                showDiff(item, true);
            }
        });

        VBox.setVgrow(diffView, Priority.ALWAYS);
        VBox diffBox = new VBox(4, diffHeader, diffView);
        diffBox.setPadding(new Insets(4, 8, 8, 8));

        SplitPane split = new SplitPane(lists, diffBox);
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(0.7);
        VBox.setVgrow(split, Priority.ALWAYS);
        mergeBar = buildMergeBar();
        root.getChildren().addAll(mergeBar, split);
    }

    private HBox buildMergeBar() {
        Label label = new Label("Merge o rebase en curso: resuelve los conflictos marcados con !");
        label.getStyleClass().add("merge-bar-label");
        Button abort = new Button("Abortar");
        abort.setOnAction(e -> abortOperation());
        Button complete = new Button("Continuar");
        complete.getStyleClass().add("accent-button");
        complete.setOnAction(e -> continueOperation());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(8, label, spacer, abort, complete);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("merge-bar");
        bar.setVisible(false);
        bar.setManaged(false);
        return bar;
    }

    private void abortOperation() {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Se descartará la operación en curso y se volverá al estado anterior. ¿Continuar?");
        confirm.setHeaderText("Abortar operación");
        confirm.showAndWait().filter(bt -> bt == ButtonType.OK).ifPresent(bt ->
                repo.runTask("Abortando operación", p -> {
                    repo.git().abortOperation();
                    return null;
                }, v -> repo.refreshAll()));
    }

    private void continueOperation() {
        boolean conflicts = unstagedList.getItems().stream()
                .anyMatch(en -> en.state().equals("Conflicto"));
        if (conflicts) {
            new Alert(Alert.AlertType.WARNING,
                    "Aún hay conflictos sin resolver.").showAndWait();
            return;
        }
        repo.runTask("Continuando operación", p -> {
            repo.git().continueOperation(null);
            return null;
        }, v -> repo.refreshAll());
    }

    private void showConflictEditor(GitService.StatusEntry entry) {
        repo.runTask("Cargando conflicto", p -> repo.git().workingFileContent(entry.path()),
                content -> {
                    Dialog<ButtonType> dialog = new Dialog<>();
                    dialog.setTitle("Resolver conflicto: " + entry.path());
                    dialog.setHeaderText("Edita el contenido o usa un lado; no deben quedar"
                            + " marcadores <<<<<<<, ======= ni >>>>>>>");
                    dialog.getDialogPane().getButtonTypes().addAll(
                            ButtonType.OK, ButtonType.CANCEL);
                    TextArea editor = new TextArea(content);
                    editor.getStyleClass().add("conflict-editor");
                    editor.setPrefSize(720, 420);
                    Button ours = new Button("Usar nuestra versión");
                    ours.setOnAction(e ->
                            editor.setText(GitService.resolveWithSide(editor.getText(), true)));
                    Button theirs = new Button("Usar su versión");
                    theirs.setOnAction(e ->
                            editor.setText(GitService.resolveWithSide(editor.getText(), false)));
                    HBox tools = new HBox(8, ours, theirs);
                    VBox box = new VBox(8, tools, editor);
                    VBox.setVgrow(editor, Priority.ALWAYS);
                    dialog.getDialogPane().setContent(box);
                    var result = dialog.showAndWait();
                    if (result.isEmpty() || result.get() != ButtonType.OK) {
                        return;
                    }
                    String resolvedText = editor.getText();
                    if (resolvedText.lines().anyMatch(l -> l.startsWith("<<<<<<<")
                            || l.startsWith(">>>>>>>"))) {
                        new Alert(Alert.AlertType.WARNING,
                                "Quedan marcadores de conflicto sin resolver.").showAndWait();
                        return;
                    }
                    repo.runTask("Resolviendo " + entry.path(), p -> {
                        repo.git().resolveConflict(entry.path(), resolvedText);
                        return null;
                    }, v -> repo.refreshAll());
                });
    }

    public VBox getRoot() {
        return root;
    }

    private static Label section(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("section-header");
        return label;
    }

    public void refresh() {
        if (!repo.git().isOpen()) {
            unstagedList.getItems().clear();
            stagedList.getItems().clear();
            diffView.getItems().clear();
            mergeBar.setVisible(false);
            mergeBar.setManaged(false);
            return;
        }
        repo.runTask("Analizando cambios", p -> {
            GitService.WorkingTreeStatus status = repo.git().status();
            boolean inProgress = repo.git().isOperationInProgress();
            return new Object[]{status, inProgress};
        }, result -> {
            GitService.WorkingTreeStatus status = (GitService.WorkingTreeStatus) result[0];
            boolean inProgress = (Boolean) result[1];
            unstagedList.getItems().setAll(status.unstaged());
            stagedList.getItems().setAll(status.staged());
            mergeBar.setVisible(inProgress);
            mergeBar.setManaged(inProgress);
            if (inProgress) {
                repo.showChangesTab();
            }
        });
    }

    private void showDiff(GitService.StatusEntry entry, boolean staged) {
        diffHeader.setText("Cambios: " + entry.path());
        diffView.setDiff("Cargando…");
        boolean hunkable = entry.state().equals("Modificado");
        repo.runTask("Cargando cambios", p -> {
            if (!staged && entry.state().equals("Nuevo")) {
                try {
                    return new Object[]{repo.git().untrackedContent(entry.path()), false};
                } catch (Exception e) {
                    return new Object[]{"(no se pudo leer el archivo)", false};
                }
            }
            String diff = repo.git().workingTreeDiff(entry.path(), staged);
            return new Object[]{diff.isBlank() ? "(sin diff disponible)" : diff, hunkable};
        }, result -> {
            String diff = (String) result[0];
            if ((Boolean) result[1] && !diff.startsWith("(")) {
                diffView.setDiff(diff, staged ? "Retirar hunk" : "Preparar hunk",
                        hunk -> applyHunk(entry, staged, hunk));
            } else {
                diffView.setDiff(diff);
            }
        });
    }

    private void applyHunk(GitService.StatusEntry entry, boolean staged, int hunkIndex) {
        repo.runTask((staged ? "Retirando" : "Preparando") + " hunk de " + entry.path(), p -> {
            if (staged) {
                repo.git().unstageHunks(entry.path(), java.util.List.of(hunkIndex));
            } else {
                repo.git().stageHunks(entry.path(), java.util.List.of(hunkIndex));
            }
            return null;
        }, v -> refresh());
    }

    private void stage(GitService.StatusEntry entry) {
        repo.runTask("Preparando " + entry.path(), p -> {
            repo.git().stage(entry.path(), entry.state().equals("Eliminado"));
            return null;
        }, v -> refresh());
    }

    private void unstage(GitService.StatusEntry entry) {
        repo.runTask("Retirando " + entry.path(), p -> {
            repo.git().unstage(entry.path());
            return null;
        }, v -> refresh());
    }

    private void doCommit(boolean amend) {
        String message = messageArea.getText().trim();
        if (message.isEmpty() && !amend) {
            new Alert(Alert.AlertType.WARNING, "Escribe un mensaje para el commit.").showAndWait();
            return;
        }
        if (stagedList.getItems().isEmpty() && !amend) {
            new Alert(Alert.AlertType.WARNING,
                    "No hay cambios preparados. Prepara algún archivo primero.").showAndWait();
            return;
        }
        if ((repo.git().identityName() == null || repo.git().identityEmail() == null)
                && !promptIdentity()) {
            return;
        }
        repo.runTask(amend ? "Enmendando commit" : "Creando commit", p -> {
            String preSha = repo.git().headSha();
            if (amend) {
                if (preSha == null) {
                    throw new IllegalStateException("No hay ningún commit que enmendar.");
                }
                repo.git().amend(message.isEmpty() ? null : message);
            } else {
                repo.git().commit(message);
            }
            return preSha;
        }, preSha -> {
            messageArea.clear();
            if (amend) {
                repo.recordAction("Amend: " + abbrev(message),
                        g -> g.resetSoft(preSha),
                        g -> g.amend(message.isEmpty() ? null : message));
            } else {
                repo.recordCommit(message, preSha);
            }
            repo.refreshAll();
        });
    }

    private void discardAll() {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Se descartarán TODOS los cambios sin commitear, incluidos los"
                        + " archivos nuevos. Esta acción no se puede deshacer. ¿Continuar?");
        confirm.setHeaderText("Descartar todos los cambios");
        confirm.showAndWait().filter(bt -> bt == ButtonType.OK).ifPresent(bt ->
                repo.runTask("Descartando todo", p -> {
                    repo.git().discardAll();
                    return null;
                }, v -> repo.refreshAll()));
    }

    private void discardFile(GitService.StatusEntry entry) {
        boolean untracked = entry.state().equals("Nuevo");
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                untracked
                        ? "«" + entry.path() + "» no tiene seguimiento: se eliminará del disco."
                        : "Se descartarán los cambios de «" + entry.path() + "».");
        confirm.setHeaderText("Descartar cambios (no deshacible)");
        confirm.showAndWait().filter(bt -> bt == ButtonType.OK).ifPresent(bt ->
                repo.runTask("Descartando " + entry.path(), p -> {
                    if (untracked) {
                        repo.git().deleteUntracked(entry.path());
                    } else {
                        repo.git().discardChanges(entry.path());
                    }
                    return null;
                }, v -> repo.refreshAll()));
    }

    private static String abbrev(String message) {
        if (message == null || message.isBlank()) {
            return "(mensaje original)";
        }
        String first = message.lines().findFirst().orElse(message);
        return first.length() > 40 ? first.substring(0, 40) + "…" : first;
    }

    private boolean promptIdentity() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Identidad de Git");
        dialog.setHeaderText("Configura tu nombre y email para este repositorio");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        TextField name = new TextField();
        name.setPromptText("Nombre");
        TextField email = new TextField();
        email.setPromptText("email@ejemplo.com");
        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.setPadding(new Insets(12));
        grid.addRow(0, new Label("Nombre:"), name);
        grid.addRow(1, new Label("Email:"), email);
        dialog.getDialogPane().setContent(grid);
        var result = dialog.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK
                || name.getText().isBlank() || email.getText().isBlank()) {
            return false;
        }
        try {
            repo.git().setIdentity(name.getText().trim(), email.getText().trim());
            return true;
        } catch (Exception e) {
            new Alert(Alert.AlertType.ERROR,
                    "No se pudo guardar la identidad: " + e.getMessage()).showAndWait();
            return false;
        }
    }

    private class StatusCell extends ListCell<GitService.StatusEntry> {

        private final boolean unstaged;

        StatusCell(boolean unstaged) {
            this.unstaged = unstaged;
        }

        @Override
        protected void updateItem(GitService.StatusEntry entry, boolean empty) {
            super.updateItem(entry, empty);
            setContextMenu(null);
            if (empty || entry == null) {
                setGraphic(null);
                return;
            }
            Label badge = new Label(switch (entry.state()) {
                case "Nuevo" -> "A";
                case "Eliminado" -> "D";
                case "Conflicto" -> "!";
                default -> "M";
            });
            badge.getStyleClass().addAll("badge", switch (entry.state()) {
                case "Nuevo" -> "badge-add";
                case "Eliminado", "Conflicto" -> "badge-del";
                default -> "badge-mod";
            });
            setGraphic(new HBox(6, badge, new Label(entry.path())));
            if (entry.state().equals("Conflicto")) {
                MenuItem resolve = new MenuItem("Resolver conflicto…");
                resolve.setOnAction(e -> showConflictEditor(entry));
                setContextMenu(new ContextMenu(resolve));
            } else if (unstaged) {
                MenuItem discard = new MenuItem("Descartar cambios");
                discard.setOnAction(e -> discardFile(entry));
                setContextMenu(new ContextMenu(discard));
            }
        }
    }
}
