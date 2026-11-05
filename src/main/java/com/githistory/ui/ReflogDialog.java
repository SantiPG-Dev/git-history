package com.githistory.ui;

import com.githistory.git.GitService.ReflogEntryInfo;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Dialog;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.stage.Window;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Visor del reflog de HEAD: muestra cada movimiento (commits, checkouts,
 * resets…) y permite crear una rama o hacer checkout en cualquier punto,
 * para recuperar trabajo perdido.
 */
public class ReflogDialog extends Dialog<Void> {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    public interface ReflogActions {
        void createBranchAt(String sha);
        void checkout(String sha);
        void copyHash(String sha);
    }

    public ReflogDialog(Window owner, List<ReflogEntryInfo> entries, ReflogActions actions) {
        setTitle("Reflog de HEAD");
        setHeaderText("Movimientos recientes de HEAD, de más nuevo a más viejo");
        initOwner(owner);
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        ListView<ReflogEntryInfo> list = new ListView<>();
        list.getItems().setAll(entries);
        list.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(ReflogEntryInfo entry, boolean empty) {
                super.updateItem(entry, empty);
                if (empty || entry == null) {
                    setText(null);
                    return;
                }
                String when = entry.when() != null ? TIME_FORMAT.format(entry.when()) : "";
                setText(when + "   " + entry.shortNewId() + "   " + entry.message());
            }
        });
        list.setPrefSize(680, 440);

        MenuItem branch = new MenuItem("Crear rama aquí…");
        branch.setOnAction(e -> withSelected(list, sel -> actions.createBranchAt(sel.newId())));
        MenuItem checkout = new MenuItem("Checkout (HEAD separado)");
        checkout.setOnAction(e -> withSelected(list, sel -> actions.checkout(sel.newId())));
        MenuItem copy = new MenuItem("Copiar hash");
        copy.setOnAction(e -> withSelected(list, sel -> actions.copyHash(sel.newId())));
        list.setContextMenu(new ContextMenu(branch, checkout, new SeparatorMenuItem(), copy));

        getDialogPane().setContent(list);
    }

    private static void withSelected(ListView<ReflogEntryInfo> list,
                                     java.util.function.Consumer<ReflogEntryInfo> action) {
        ReflogEntryInfo selected = list.getSelectionModel().getSelectedItem();
        if (selected != null) {
            action.accept(selected);
        }
    }
}
