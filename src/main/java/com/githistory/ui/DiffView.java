package com.githistory.ui;

import javafx.collections.FXCollections;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Visor de diff con sintaxis coloreada: líneas añadidas en verde, eliminadas
 * en rojo, cabeceras de hunk en azul y metadatos en gris. Trunca diffs
 * gigantes para no bloquear la interfaz. Opcionalmente muestra un botón de
 * acción (preparar/retirar) en la cabecera de cada hunk.
 */
public class DiffView extends ListView<String> {

    private static final int MAX_LINES = 20_000;

    private List<Integer> hunkLines = List.of();
    private String hunkActionLabel;
    private IntConsumer hunkAction;

    public DiffView() {
        getStyleClass().add("diff-view");
        setPlaceholder(new Label("(sin cambios)"));
        setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(String line, boolean empty) {
                super.updateItem(line, empty);
                setGraphic(null);
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
                    int hunkIndex = hunkLines.indexOf(getIndex());
                    if (hunkIndex >= 0 && hunkAction != null) {
                        Label text = new Label(line);
                        text.getStyleClass().add("diff-line-hunk");
                        Button button = new Button(hunkActionLabel);
                        button.getStyleClass().add("hunk-button");
                        int index = hunkIndex;
                        button.setOnAction(e -> hunkAction.accept(index));
                        Region spacer = new Region();
                        HBox.setHgrow(spacer, Priority.ALWAYS);
                        HBox box = new HBox(8, text, spacer, button);
                        setText(null);
                        setGraphic(box);
                    }
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
        setDiff(text, null, null);
    }

    /**
     * Muestra el diff; si {@code actionLabel} no es null, cada cabecera de
     * hunk (línea @@) lleva un botón que invoca la acción con el índice del
     * hunk (0-based, en orden de aparición).
     */
    public void setDiff(String text, String actionLabel, IntConsumer action) {
        this.hunkActionLabel = actionLabel;
        this.hunkAction = action;
        this.hunkLines = List.of();
        if (text == null || text.isEmpty()) {
            getItems().clear();
            return;
        }
        List<String> lines = text.lines().limit(MAX_LINES + 1L).collect(java.util.stream.Collectors
                .toCollection(ArrayList::new));
        if (lines.size() > MAX_LINES) {
            lines = new ArrayList<>(lines.subList(0, MAX_LINES));
            lines.add("… diff truncado (máximo " + MAX_LINES + " líneas)");
        }
        if (action != null) {
            List<Integer> hunks = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).startsWith("@@")) {
                    hunks.add(i);
                }
            }
            this.hunkLines = hunks;
        }
        setItems(FXCollections.observableArrayList(lines));
        scrollTo(0);
    }
}
