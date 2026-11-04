package com.githistory.ui;

import com.githistory.git.CommitInfo;
import com.githistory.git.GitService;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.stream.Collectors;

/**
 * Panel con el detalle del commit seleccionado: mensaje, autor, padres,
 * lista de archivos tocados y el diff de cada archivo.
 */
public class CommitDetailView {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final RepoView repo;
    private final VBox root = new VBox(6);
    private final Label title = new Label("Selecciona un commit para ver sus detalles");
    private final Label meta = new Label();
    private final Label dateLabel = new Label();
    private final Label parentsLabel = new Label();
    private final ListView<GitService.DiffFile> fileList = new ListView<>();
    private final DiffView diffView = new DiffView();
    private final SplitPane split;

    public CommitDetailView(RepoView repo) {
        this.repo = repo;
        root.setPadding(new Insets(8));
        title.setWrapText(true);
        title.getStyleClass().add("commit-message-title");
        meta.getStyleClass().add("detail-meta");
        dateLabel.getStyleClass().add("detail-meta");
        parentsLabel.getStyleClass().add("detail-meta");
        meta.setWrapText(true);

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

        root.getChildren().addAll(title, meta, dateLabel, parentsLabel, split);
    }

    public VBox getRoot() {
        return root;
    }

    public void showCommit(CommitInfo info) {
        split.setVisible(true);
        title.setText(info.fullMessage().trim());
        meta.setText(info.id() + " · " + info.author() + " <" + info.email() + ">");
        dateLabel.setText(TIME_FORMAT.format(info.time()));
        parentsLabel.setText(info.parents().isEmpty()
                ? "Commit inicial (sin padres)"
                : "Padres: " + info.parents().stream()
                        .map(p -> p.substring(0, 7))
                        .collect(Collectors.joining(", ")));
        fileList.getItems().clear();
        diffView.getItems().clear();
        repo.runTask("Cargando diff", p -> repo.git().commitDetail(info.id()), files -> {
            fileList.getItems().setAll(files);
            if (!files.isEmpty()) {
                fileList.getSelectionModel().select(0);
            }
        });
    }
}
