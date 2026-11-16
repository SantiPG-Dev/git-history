package com.githistory.ui;

import com.githistory.git.CommitInfo;
import com.githistory.git.GitService;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.List;

/**
 * Historial de un archivo (estilo File History de GitKraken): lista de los
 * commits que lo tocaron y, debajo, el diff del archivo en el commit
 * seleccionado.
 */
public class FileHistoryDialog extends ThemedDialog<Void> {

    public FileHistoryDialog(Window owner, RepoView repo, String path) {
        setTitle("Historial: " + path);
        setHeaderText(null);
        initOwner(owner);
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        ListView<CommitInfo> list = new ListView<>();
        list.setPlaceholder(new Label("Este archivo no tiene commits aún"));
        list.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(CommitInfo info, boolean empty) {
                super.updateItem(info, empty);
                if (empty || info == null) {
                    setGraphic(null);
                    return;
                }
                Label hash = new Label(info.shortId());
                hash.getStyleClass().add("graph-hash");
                Label message = new Label(info.message());
                message.getStyleClass().add("file-name");
                Label meta = new Label(info.author() + " · "
                        + RelativeTime.format(info.time()));
                meta.getStyleClass().add("graph-meta");
                setGraphic(new HBox(10, hash, message, meta));
            }
        });

        DiffView diffView = new DiffView();
        SplitPane split = new SplitPane(list, diffView);
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(0.45);
        VBox.setVgrow(split, Priority.ALWAYS);
        VBox box = new VBox(split);
        box.setPadding(new Insets(8));
        box.setPrefSize(720, 480);
        getDialogPane().setContent(box);

        list.getSelectionModel().selectedItemProperty().addListener((obs, o, info) -> {
            if (info == null) {
                return;
            }
            diffView.setDiff("Cargando…");
            repo.runTask("Cargando diff", p -> {
                List<GitService.DiffFile> files = repo.git().commitDetail(info.id());
                return files.stream()
                        .filter(f -> f.path().equals(path))
                        .findFirst()
                        .map(GitService.DiffFile::diffText)
                        .orElse("(el archivo no cambió en este commit)");
            }, diffView::setDiff);
        });

        repo.runTask("Historial de " + path, p -> repo.git().fileHistory(path, 200),
                commits -> {
                    list.getItems().setAll(commits);
                    if (!commits.isEmpty()) {
                        list.getSelectionModel().select(0);
                    }
                });
    }
}
