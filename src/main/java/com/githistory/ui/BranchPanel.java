package com.githistory.ui;

import com.githistory.git.GitService;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * Panel lateral con secciones LOCAL, REMOTO, ETIQUETAS y STASH (con
 * contadores), más un filtro por nombre. Doble clic en una rama local hace
 * checkout; el menú contextual ofrece crear, eliminar, checkout y acciones
 * de stash (aplicar, pop, eliminar).
 */
public class BranchPanel {

    private record TagInfo(String name) {}

    private record BranchData(List<GitService.BranchInfo> locals,
                              List<GitService.BranchInfo> remotes,
                              List<String> tags,
                              List<GitService.StashEntry> stashes) {}

    private final RepoView repo;
    private final VBox root = new VBox(4);
    private final TextField filterField = new TextField();
    private final TreeView<Object> tree = new TreeView<>();
    private BranchData lastData;

    public BranchPanel(RepoView repo) {
        this.repo = repo;
        root.setPadding(new Insets(4, 0, 0, 0));
        filterField.setPromptText("Filtrar…");
        filterField.getStyleClass().add("search-field");
        filterField.textProperty().addListener((obs, o, q) -> rebuild());
        VBox.setMargin(filterField, new Insets(2, 6, 2, 6));
        tree.setShowRoot(false);
        tree.setCellFactory(tv -> new BranchCell());
        VBox.setVgrow(tree, Priority.ALWAYS);
        root.getChildren().addAll(filterField, tree);
    }

    public VBox getRoot() {
        return root;
    }

    public void refresh() {
        if (!repo.git().isOpen()) {
            tree.setRoot(null);
            lastData = null;
            return;
        }
        repo.runTask("Actualizando ramas", p -> new BranchData(
                repo.git().branches(false),
                repo.git().branches(true),
                repo.git().tags(),
                repo.git().stashList()), data -> {
            lastData = data;
            rebuild();
        });
    }

    private void rebuild() {
        if (lastData == null) {
            tree.setRoot(null);
            return;
        }
        String filter = filterField.getText() == null
                ? "" : filterField.getText().trim().toLowerCase();
        TreeItem<Object> rootItem = new TreeItem<>();
        TreeItem<Object> locals = section("LOCAL",
                lastData.locals().stream().filter(b -> matches(b.shortName(), filter)).toList());
        TreeItem<Object> remotes = section("REMOTO",
                lastData.remotes().stream().filter(b -> matches(b.shortName(), filter)).toList());
        TreeItem<Object> tags = section("ETIQUETAS",
                lastData.tags().stream().filter(t -> matches(t, filter)).map(TagInfo::new).toList());
        TreeItem<Object> stashes = section("STASH", lastData.stashes());
        locals.setExpanded(true);
        remotes.setExpanded(true);
        rootItem.getChildren().addAll(locals, remotes, tags, stashes);
        tree.setRoot(rootItem);
    }

    private static boolean matches(String name, String filter) {
        return filter.isEmpty() || name.toLowerCase().contains(filter);
    }

    private static TreeItem<Object> section(String title, List<?> items) {
        TreeItem<Object> section = new TreeItem<>(title + " (" + items.size() + ")");
        items.forEach(item -> section.getChildren().add(new TreeItem<>(item)));
        return section;
    }

    private void checkout(String name) {
        repo.runTask("Checkout " + name, p -> {
            repo.git().checkout(name);
            return null;
        }, v -> repo.refreshAll());
    }

    private void checkoutRemote(GitService.BranchInfo branch) {
        String local = branch.shortName().contains("/")
                ? branch.shortName().substring(branch.shortName().indexOf('/') + 1)
                : branch.shortName();
        repo.runTask("Checkout " + branch.shortName(), p -> {
            repo.git().checkoutRemote(branch.shortName(), local);
            return null;
        }, v -> repo.refreshAll());
    }

    private void createBranch() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("Nueva rama");
        dialog.setHeaderText("Crear una rama nueva desde HEAD y cambiar a ella");
        dialog.setContentText("Nombre:");
        dialog.showAndWait().map(String::trim).filter(n -> !n.isEmpty()).ifPresent(name -> {
            if (!repo.git().isValidBranchName(name)) {
                new Alert(Alert.AlertType.ERROR,
                        "Nombre de rama no válido: " + name).showAndWait();
                return;
            }
            repo.runTask("Creando rama " + name, p -> {
                repo.git().createBranch(name, true);
                return null;
            }, v -> repo.refreshAll());
        });
    }

    private void deleteBranch(GitService.BranchInfo branch) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "¿Eliminar la rama «" + branch.shortName() + "»? Esta acción no se puede deshacer.");
        confirm.setHeaderText("Eliminar rama");
        confirm.showAndWait().filter(bt -> bt == ButtonType.OK).ifPresent(bt ->
                repo.runTask("Eliminando rama", p -> {
                    repo.git().deleteBranch(branch.shortName());
                    return null;
                }, v -> repo.refreshAll()));
    }

    private void stashApply(GitService.StashEntry entry, boolean pop) {
        repo.runTask((pop ? "Pop" : "Aplicar") + " stash@{" + entry.index() + "}", p -> {
            if (pop) {
                repo.git().stashPop(entry.index());
            } else {
                repo.git().stashApply(entry.index());
            }
            return null;
        }, v -> repo.refreshAll());
    }

    private void stashDrop(GitService.StashEntry entry) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "¿Eliminar stash@{" + entry.index() + "} («" + entry.message() + "»)?");
        confirm.setHeaderText("Eliminar stash");
        confirm.showAndWait().filter(bt -> bt == ButtonType.OK).ifPresent(bt ->
                repo.runTask("Eliminando stash", p -> {
                    repo.git().stashDrop(entry.index());
                    return null;
                }, v -> repo.refreshAll()));
    }

    private ContextMenu menuForBranch(GitService.BranchInfo branch) {
        ContextMenu menu = new ContextMenu();
        if (branch.remote()) {
            MenuItem checkout = new MenuItem("Checkout (nueva rama local)");
            checkout.setOnAction(e -> checkoutRemote(branch));
            menu.getItems().add(checkout);
        } else {
            MenuItem checkout = new MenuItem("Checkout");
            checkout.setOnAction(e -> checkout(branch.shortName()));
            MenuItem merge = new MenuItem("Mergear en la rama actual");
            merge.setDisable(branch.current());
            merge.setOnAction(e -> repo.mergeBranchInto(branch.shortName()));
            MenuItem create = new MenuItem("Nueva rama…");
            create.setOnAction(e -> createBranch());
            MenuItem delete = new MenuItem("Eliminar rama");
            delete.setOnAction(e -> deleteBranch(branch));
            menu.getItems().addAll(checkout, merge, create, new SeparatorMenuItem(), delete);
        }
        return menu;
    }

    private ContextMenu menuForStash(GitService.StashEntry entry) {
        MenuItem apply = new MenuItem("Aplicar");
        apply.setOnAction(e -> stashApply(entry, false));
        MenuItem pop = new MenuItem("Pop");
        pop.setOnAction(e -> stashApply(entry, true));
        MenuItem drop = new MenuItem("Eliminar");
        drop.setOnAction(e -> stashDrop(entry));
        return new ContextMenu(apply, pop, new SeparatorMenuItem(), drop);
    }

    private class BranchCell extends TreeCell<Object> {
        @Override
        protected void updateItem(Object item, boolean empty) {
            super.updateItem(item, empty);
            setContextMenu(null);
            setOnMouseClicked(null);
            getStyleClass().removeAll("branch-header", "branch-tag");
            if (empty || item == null) {
                setText(null);
                return;
            }
            if (item instanceof String header) {
                setText(header);
                getStyleClass().add("branch-header");
            } else if (item instanceof TagInfo tag) {
                setText(tag.name());
                getStyleClass().add("branch-tag");
                MenuItem delete = new MenuItem("Eliminar etiqueta");
                delete.setOnAction(e -> repo.deleteTag(tag.name()));
                setContextMenu(new ContextMenu(delete));
            } else if (item instanceof GitService.StashEntry stash) {
                setText("stash@{" + stash.index() + "} — " + stash.message());
                setContextMenu(menuForStash(stash));
            } else if (item instanceof GitService.BranchInfo branch) {
                setText(branch.shortName() + (branch.current() ? " ✓" : ""));
                setContextMenu(menuForBranch(branch));
                setOnMouseClicked(e -> {
                    if (e.getClickCount() == 2 && !branch.remote()) {
                        checkout(branch.shortName());
                    }
                });
            }
        }
    }
}
