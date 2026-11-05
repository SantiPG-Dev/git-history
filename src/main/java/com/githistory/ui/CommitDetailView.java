package com.githistory.ui;

import com.githistory.git.CommitInfo;
import com.githistory.git.GitService;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.stream.Collectors;

/**
 * Panel con el detalle del commit seleccionado, estilo GitKraken: hash corto,
 * mensaje, avatar con iniciales del autor, fecha, padres, número de archivos
 * modificados y lista de archivos con el diff coloreado de cada uno.
 */
public class CommitDetailView {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy '@' HH:mm").withZone(ZoneId.systemDefault());
    private static final String[] AVATAR_COLORS = {
            "#89b4fa", "#f38ba8", "#a6e3a1", "#f9e2af",
            "#cba6f7", "#94e2d5", "#fab387", "#f5c2e7"
    };

    private final RepoView repo;
    private final VBox root = new VBox(6);
    private final Label hashLabel = new Label();
    private final Label title = new Label("Selecciona un commit para ver sus detalles");
    private final HBox authorRow = new HBox(10);
    private final Label dateLabel = new Label();
    private final Label parentsLabel = new Label();
    private final Label modifiedLabel = new Label();
    private final ListView<GitService.DiffFile> fileList = new ListView<>();
    private final DiffView diffView = new DiffView();
    private final SplitPane split;
    private String currentCommitId;

    public CommitDetailView(RepoView repo) {
        this.repo = repo;
        root.setPadding(new Insets(8));
        hashLabel.getStyleClass().add("detail-hash");
        title.setWrapText(true);
        title.getStyleClass().add("commit-message-title");
        authorRow.setAlignment(Pos.CENTER_LEFT);
        dateLabel.getStyleClass().add("detail-meta");
        parentsLabel.getStyleClass().add("detail-meta");
        modifiedLabel.getStyleClass().add("detail-meta");

        fileList.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(GitService.DiffFile file, boolean empty) {
                super.updateItem(file, empty);
                if (empty || file == null) {
                    setGraphic(null);
                    return;
                }
                Label badge = new Label(switch (file.changeType()) {
                    case "Nuevo" -> "A";
                    case "Eliminado" -> "D";
                    case "Renombrado", "Copiado" -> "R";
                    default -> "M";
                });
                badge.getStyleClass().addAll("badge", switch (file.changeType()) {
                    case "Nuevo" -> "badge-add";
                    case "Eliminado" -> "badge-del";
                    case "Renombrado", "Copiado" -> "badge-ren";
                    default -> "badge-mod";
                });
                setGraphic(new HBox(6, badge, new Label(file.path())));
            }
        });
        fileList.getSelectionModel().selectedItemProperty().addListener((obs, o, file) -> {
            if (file != null) {
                diffView.setDiff(file.diffText());
            }
        });

        split = new SplitPane(fileList, diffView);
        split.setOrientation(Orientation.HORIZONTAL);
        split.setDividerPositions(0.4);
        VBox.setVgrow(split, Priority.ALWAYS);
        split.setVisible(false);

        root.getChildren().addAll(hashLabel, title, authorRow, parentsLabel, modifiedLabel, split);
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
        modifiedLabel.setText("");
        fileList.getItems().clear();
        diffView.getItems().clear();
        repo.runTask("Cargando diff", p -> repo.git().commitDetail(info.id()), files -> {
            fileList.getItems().setAll(files);
            int n = files.size();
            modifiedLabel.setText(n + (n == 1 ? " archivo modificado" : " archivos modificados"));
            if (!files.isEmpty()) {
                fileList.getSelectionModel().select(0);
            }
        });
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
