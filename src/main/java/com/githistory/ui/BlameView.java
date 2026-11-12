package com.githistory.ui;

import com.githistory.git.GitService;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.List;
import java.util.function.Consumer;

/**
 * Diálogo de blame: cada línea del archivo con el commit y autor que la
 * escribió, coloreado por commit. Doble clic en una línea salta al commit
 * correspondiente en el historial.
 */
public class BlameView {

    private static final String[] COMMIT_COLORS = {
            "#89b4fa", "#f38ba8", "#a6e3a1", "#f9e2af",
            "#cba6f7", "#94e2d5", "#fab387", "#f5c2e7"
    };

    private BlameView() {}

    public static void show(Window owner, String path, List<GitService.BlameLine> lines,
                            Consumer<String> onJumpToCommit) {
        Dialog<Void> dialog = new ThemedDialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Blame: " + path);
        dialog.setHeaderText(null);

        ListView<GitService.BlameLine> list = new ListView<>();
        list.getItems().setAll(lines);
        list.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(GitService.BlameLine line, boolean empty) {
                super.updateItem(line, empty);
                if (empty || line == null) {
                    setGraphic(null);
                    setOnMouseClicked(null);
                    return;
                }
                Label hash = new Label(line.shortId());
                hash.getStyleClass().add("blame-hash");
                int idx = Math.floorMod(line.commitId().hashCode(), COMMIT_COLORS.length);
                hash.setStyle("-fx-text-fill: " + COMMIT_COLORS[idx] + ";");
                Label author = new Label(line.author());
                author.getStyleClass().add("blame-author");
                Label number = new Label(String.format("%4d", line.lineNumber()));
                number.getStyleClass().add("blame-number");
                Label text = new Label(line.text());
                text.getStyleClass().add("blame-text");
                setGraphic(new HBox(10, hash, author, number, text));
                setOnMouseClicked(e -> {
                    if (e.getClickCount() == 2 && !line.commitId().isEmpty()) {
                        dialog.close();
                        onJumpToCommit.accept(line.commitId());
                    }
                });
            }
        });
        list.setPrefSize(760, 480);

        VBox box = new VBox(6,
                new Label("Doble clic en una línea para ir a su commit"), list);
        box.getStyleClass().add("blame-box");
        VBox.setVgrow(list, Priority.ALWAYS);
        dialog.getDialogPane().setContent(box);
        dialog.getDialogPane().getButtonTypes().add(javafx.scene.control.ButtonType.CLOSE);
        dialog.show();
    }
}
