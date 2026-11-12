package com.githistory.ui;

import com.githistory.git.CommitInfo;
import com.githistory.git.GitService;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Panel con el detalle del commit seleccionado, estilo GitKraken: hash corto,
 * mensaje, avatar con iniciales del autor, fecha, padres, número de archivos
 * modificados y lista de archivos (plana o en árbol, con opción de ver todos
 * los archivos del commit) con el diff coloreado de cada uno.
 */
public class CommitDetailView {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy '@' HH:mm").withZone(ZoneId.systemDefault());
    private static final String[] AVATAR_COLORS = {
            "#89b4fa", "#f38ba8", "#a6e3a1", "#f9e2af",
            "#cba6f7", "#94e2d5", "#fab387", "#f5c2e7"
    };

    /** Nodo del árbol de archivos: carpeta (path null) o archivo. */
    private record FileNode(String name, String path, GitService.DiffFile file) {}

    private final RepoView repo;
    private final VBox root = new VBox(6);
    private final Label hashLabel = new Label();
    private final Label title = new Label("Selecciona un commit para ver sus detalles");
    private final HBox authorRow = new HBox(10);
    private final Label parentsLabel = new Label();
    private final Label modifiedLabel = new Label();
    private final ToggleButton pathToggle = new ToggleButton("Path");
    private final ToggleButton treeToggle = new ToggleButton("Tree");
    private final CheckBox viewAllBox = new CheckBox("Todos los archivos");
    private final ListView<GitService.DiffFile> fileList = new ListView<>();
    private final TreeView<FileNode> treeView = new TreeView<>();
    private final DiffView diffView = new DiffView();
    private final StackPane filesPane;
    private final SplitPane split;

    private String currentCommitId;
    private List<GitService.DiffFile> currentFiles = List.of();
    private List<String> allFiles;

    public CommitDetailView(RepoView repo) {
        this.repo = repo;
        root.setPadding(new Insets(8));
        hashLabel.getStyleClass().add("detail-hash");
        title.setWrapText(true);
        title.getStyleClass().add("commit-message-title");
        authorRow.setAlignment(Pos.CENTER_LEFT);
        parentsLabel.getStyleClass().add("detail-meta");
        modifiedLabel.getStyleClass().add("detail-meta");

        ToggleGroup group = new ToggleGroup();
        pathToggle.setToggleGroup(group);
        treeToggle.setToggleGroup(group);
        pathToggle.setSelected(true);
        pathToggle.getStyleClass().add("view-toggle");
        treeToggle.getStyleClass().add("view-toggle");
        group.selectedToggleProperty().addListener((obs, o, t) -> {
            if (t != null) {
                applyViewMode();
            }
        });
        viewAllBox.setOnAction(e -> {
            if (viewAllBox.isSelected()) {
                ensureAllFiles(this::applyViewMode);
            } else {
                applyViewMode();
            }
        });
        HBox viewBar = new HBox(8, pathToggle, treeToggle, viewAllBox);
        viewBar.setAlignment(Pos.CENTER_LEFT);

        fileList.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(GitService.DiffFile file, boolean empty) {
                super.updateItem(file, empty);
                if (empty || file == null) {
                    setGraphic(null);
                    setContextMenu(null);
                    return;
                }
                setGraphic(new HBox(6, badge(file.changeType()), new Label(file.path())));
                MenuItem blame = new MenuItem("Ver blame de este archivo");
                blame.setOnAction(e -> repo.showBlame(file.path(), currentCommitId));
                MenuItem view = new MenuItem("Ver archivo en este commit");
                view.setOnAction(e -> showFileAtCommit(file.path()));
                MenuItem restore = new MenuItem("Restaurar archivo a esta versión");
                restore.setOnAction(e -> restoreFile(file.path()));
                MenuItem copyPath = new MenuItem("Copiar ruta");
                copyPath.setOnAction(e -> {
                    var content = new javafx.scene.input.ClipboardContent();
                    content.putString(file.path());
                    javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
                });
                setContextMenu(new ContextMenu(blame, view, restore,
                        new javafx.scene.control.SeparatorMenuItem(), copyPath));
            }
        });
        fileList.getSelectionModel().selectedItemProperty().addListener((obs, o, file) -> {
            if (file != null) {
                diffView.setDiff(file.diffText());
            }
        });

        treeView.setShowRoot(false);
        treeView.setCellFactory(tv -> new TreeCell<>() {
            @Override
            protected void updateItem(FileNode node, boolean empty) {
                super.updateItem(node, empty);
                if (empty || node == null) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                if (node.file() != null) {
                    setGraphic(new HBox(6, badge(node.file().changeType()), new Label(node.name())));
                    setText(null);
                } else {
                    setGraphic(null);
                    setText(node.path() == null ? node.name() + "/" : node.name());
                }
            }
        });
        treeView.getSelectionModel().selectedItemProperty().addListener((obs, o, item) -> {
            if (item != null && item.getValue().path() != null) {
                GitService.DiffFile file = item.getValue().file();
                diffView.setDiff(file != null ? file.diffText()
                        : "(archivo sin cambios en este commit)");
            }
        });

        filesPane = new StackPane(fileList, treeView);
        treeView.setVisible(false);

        split = new SplitPane(filesPane, diffView);
        split.setOrientation(Orientation.HORIZONTAL);
        split.setDividerPositions(0.4);
        VBox.setVgrow(split, Priority.ALWAYS);
        split.setVisible(false);

        root.getChildren().addAll(hashLabel, title, authorRow, parentsLabel,
                modifiedLabel, viewBar, split);
    }

    public VBox getRoot() {
        return root;
    }

    public void showCommit(CommitInfo info) {
        split.setVisible(true);
        currentCommitId = info.id();
        hashLabel.setText("commit: " + info.shortId());
        title.setText(info.fullMessage().trim());

        Label authorLabel = new Label(info.author());
        authorLabel.getStyleClass().add("commit-author");
        Label dateLabel = new Label("authored " + TIME_FORMAT.format(info.time()));
        dateLabel.getStyleClass().add("detail-meta");
        VBox authorInfo = new VBox(0, authorLabel, dateLabel);
        authorRow.getChildren().setAll(avatar(info.author(), info.email()), authorInfo);

        // Sustituye las iniciales por el avatar real de Gravatar si se puede
        // descargar; si el usuario cambia de commit mientras tanto, se ignora.
        String commitId = info.id();
        repo.runTask("Cargando avatar", p -> AvatarService.load(info.email(), 52), image -> {
            if (image != null && commitId.equals(currentCommitId)) {
                ImageView view = new ImageView(image);
                view.setFitWidth(26);
                view.setFitHeight(26);
                view.setClip(new Circle(13, 13, 13));
                authorRow.getChildren().set(0, view);
            }
        });

        parentsLabel.setText(info.parents().isEmpty()
                ? "Commit inicial (sin padres)"
                : "parent: " + info.parents().stream()
                        .map(p -> p.substring(0, 7))
                        .collect(Collectors.joining(", ")));
        allFiles = null;
        loadFiles(p -> repo.git().commitDetail(info.id()));
    }

    /** Muestra el diff combinado entre dos commits (Ctrl+clic en el grafo). */
    public void showComparison(CommitInfo older, CommitInfo newer) {
        split.setVisible(true);
        currentCommitId = newer.id();
        hashLabel.setText("comparando: " + older.shortId() + " … " + newer.shortId());
        title.setText("Diferencias entre los dos commits seleccionados");
        authorRow.getChildren().setAll(new Label("⇄ " + older.message()
                + "  →  " + newer.message()));
        parentsLabel.setText("");
        allFiles = null;
        loadFiles(p -> repo.git().diffBetween(older.id(), newer.id()));
    }

    private void loadFiles(TaskRunner.Work<List<GitService.DiffFile>> work) {
        modifiedLabel.setText("");
        currentFiles = List.of();
        fileList.getItems().clear();
        treeView.setRoot(null);
        diffView.getItems().clear();
        repo.runTask("Cargando diff", work, files -> {
            currentFiles = files;
            int n = files.size();
            modifiedLabel.setText("✎ " + n + (n == 1 ? " modificado" : " modificados"));
            applyViewMode();
        });
    }

    /** Rellena el control visible según Path/Tree y "todos los archivos". */
    private void applyViewMode() {
        boolean treeMode = treeToggle.isSelected();
        boolean viewAll = viewAllBox.isSelected();
        List<String> paths = viewAll && allFiles != null
                ? allFiles
                : currentFiles.stream().map(GitService.DiffFile::path).sorted().toList();

        if (treeMode) {
            Map<String, GitService.DiffFile> byPath = new HashMap<>();
            for (GitService.DiffFile f : currentFiles) {
                byPath.put(f.path(), f);
            }
            treeView.setRoot(buildTree(paths, byPath));
            treeView.setVisible(true);
            fileList.setVisible(false);
        } else {
            List<GitService.DiffFile> shown = viewAll && allFiles != null
                    ? paths.stream()
                            .map(p -> currentFiles.stream()
                                    .filter(f -> f.path().equals(p)).findFirst()
                                    .orElse(new GitService.DiffFile(p, "Sin cambios",
                                            "(archivo sin cambios en este commit)")))
                            .toList()
                    : currentFiles;
            fileList.getItems().setAll(shown);
            fileList.setVisible(true);
            treeView.setVisible(false);
            if (!shown.isEmpty()) {
                fileList.getSelectionModel().select(0);
            }
        }
    }

    /** Visor de solo lectura del archivo tal como estaba en el commit. */
    private void showFileAtCommit(String path) {
        String commitId = currentCommitId;
        if (commitId == null) {
            return;
        }
        repo.runTask("Cargando " + path, p -> repo.git().fileContent(commitId, path),
                content -> {
                    javafx.scene.control.Dialog<javafx.scene.control.ButtonType> dialog =
                            new javafx.scene.control.Dialog<>();
                    dialog.setTitle(path + " @ " + commitId.substring(0, 7));
                    dialog.getDialogPane().getButtonTypes().add(
                            javafx.scene.control.ButtonType.CLOSE);
                    javafx.scene.control.TextArea area =
                            new javafx.scene.control.TextArea(content == null
                                    ? "(archivo binario o inexistente en este commit)"
                                    : content);
                    area.setEditable(false);
                    area.getStyleClass().add("conflict-editor");
                    area.setPrefSize(700, 480);
                    dialog.getDialogPane().setContent(area);
                    dialog.showAndWait();
                });
    }

    /** Restaura el archivo del worktree a su versión en el commit mostrado. */
    private void restoreFile(String path) {
        String commitId = currentCommitId;
        if (commitId == null) {
            return;
        }
        javafx.scene.control.Alert confirm = new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.CONFIRMATION,
                "«" + path + "» pasará a tener el contenido del commit "
                        + commitId.substring(0, 7)
                        + ". El cambio aparecerá en Cambios y se puede descartar."
                        + " ¿Continuar?");
        confirm.setHeaderText("Restaurar archivo");
        confirm.showAndWait()
                .filter(bt -> bt == javafx.scene.control.ButtonType.OK)
                .ifPresent(bt -> repo.runTask("Restaurando " + path, p -> {
                    repo.git().checkoutFileFromCommit(commitId, path);
                    return null;
                }, v -> {
                    repo.toast("✓ «" + path + "» restaurado a "
                            + commitId.substring(0, 7));
                    repo.refreshAll();
                }));
    }

    private void ensureAllFiles(Runnable then) {
        if (allFiles != null) {
            then.run();
            return;
        }
        if (currentCommitId == null) {
            viewAllBox.setSelected(false);
            return;
        }
        String commitId = currentCommitId;
        repo.runTask("Listando archivos", p -> repo.git().treeFiles(commitId), files -> {
            allFiles = files;
            then.run();
        });
    }

    private static TreeItem<FileNode> buildTree(List<String> paths,
                                                Map<String, GitService.DiffFile> byPath) {
        TreeItem<FileNode> rootItem = new TreeItem<>(new FileNode("", null, null));
        for (String path : paths) {
            String[] parts = path.split("/");
            TreeItem<FileNode> node = rootItem;
            StringBuilder partial = new StringBuilder();
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) {
                    partial.append('/');
                }
                partial.append(parts[i]);
                boolean leaf = i == parts.length - 1;
                TreeItem<FileNode> child = null;
                for (TreeItem<FileNode> c : node.getChildren()) {
                    if (c.getValue().name().equals(parts[i])) {
                        child = c;
                        break;
                    }
                }
                if (child == null) {
                    String fullPath = partial.toString();
                    child = new TreeItem<>(new FileNode(parts[i],
                            leaf ? fullPath : null,
                            leaf ? byPath.get(fullPath) : null));
                    node.getChildren().add(child);
                }
                node = child;
            }
        }
        sortTree(rootItem);
        return rootItem;
    }

    private static void sortTree(TreeItem<FileNode> node) {
        node.getChildren().sort(Comparator
                .comparing((TreeItem<FileNode> t) -> t.getValue().path() != null)
                .thenComparing(t -> t.getValue().name()));
        for (TreeItem<FileNode> child : node.getChildren()) {
            sortTree(child);
        }
    }

    private static Label badge(String changeType) {
        Label badge = new Label(switch (changeType) {
            case "Nuevo" -> "A";
            case "Eliminado" -> "D";
            case "Renombrado", "Copiado" -> "R";
            case "Sin cambios" -> "·";
            default -> "M";
        });
        badge.getStyleClass().addAll("badge", switch (changeType) {
            case "Nuevo" -> "badge-add";
            case "Eliminado" -> "badge-del";
            case "Renombrado", "Copiado" -> "badge-ren";
            default -> "badge-mod";
        });
        return badge;
    }

    private static StackPane avatar(String name, String email) {
        String[] parts = name == null ? new String[0] : name.trim().split("\\s+");
        StringBuilder initials = new StringBuilder();
        for (int i = 0; i < Math.min(2, parts.length); i++) {
            if (!parts[i].isEmpty()) {
                initials.append(Character.toUpperCase(parts[i].charAt(0)));
            }
        }
        if (initials.isEmpty()) {
            initials.append('?');
        }
        int idx = Math.floorMod((email != null ? email : String.valueOf(name)).hashCode(),
                AVATAR_COLORS.length);
        Circle circle = new Circle(13, Color.web(AVATAR_COLORS[idx]));
        Label label = new Label(initials.toString());
        label.setStyle("-fx-text-fill: #11111b; -fx-font-weight: bold; -fx-font-size: 10px;");
        return new StackPane(circle, label);
    }
}
