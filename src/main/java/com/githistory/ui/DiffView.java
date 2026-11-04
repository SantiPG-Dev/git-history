package com.githistory.ui;

import javafx.collections.FXCollections;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;

import java.util.ArrayList;
import java.util.List;

/**
 * Visor de diff con sintaxis coloreada: líneas añadidas en verde, eliminadas
 * en rojo, cabeceras de hunk en azul y metadatos en gris. Trunca diffs
 * gigantes para no bloquear la interfaz.
 */
public class DiffView extends ListView<String> {

    private static final int MAX_LINES = 20_000;

    public DiffView() {
        getStyleClass().add("diff-view");
        setPlaceholder(new Label("(sin cambios)"));
        setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(String line, boolean empty) {
                super.updateItem(line, empty);
                getStyleClass().removeAll(
                        "diff-line-add", "diff-line-del", "diff-line-hunk", "diff-line-meta");
                if (empty || line == null) {
                    setText(null);
                    return;
                }
                setText(line);
                if (line.startsWith("+") && !line.startsWith("+++")) {
                    getStyleClass().add("diff-line-add");
                } else if (line.startsWith("-") && !line.startsWith("---")) {
                    getStyleClass().add("diff-line-del");
                } else if (line.startsWith("@@")) {
                    getStyleClass().add("diff-line-hunk");
                } else if (line.startsWith("diff ") || line.startsWith("index ")
                        || line.startsWith("new file") || line.startsWith("deleted file")
                        || line.startsWith("rename ") || line.startsWith("similarity ")
                        || line.startsWith("Binary")) {
                    getStyleClass().add("diff-line-meta");
                }
            }
        });
    }

    public void setDiff(String text) {
        if (text == null || text.isEmpty()) {
            getItems().clear();
            return;
        }
        List<String> lines = text.lines().limit(MAX_LINES + 1L).toList();
        if (lines.size() > MAX_LINES) {
            lines = new ArrayList<>(lines.subList(0, MAX_LINES));
            lines.add("… diff truncado (máximo " + MAX_LINES + " líneas)");
        }
        setItems(FXCollections.observableArrayList(lines));
        scrollTo(0);
    }
}
