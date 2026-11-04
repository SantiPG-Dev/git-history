package com.githistory.ui;

import com.githistory.git.GitService;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;

import java.util.List;

/**
 * Panel lateral con ramas locales, ramas remotas y etiquetas.
 * Doble clic en una rama local hace checkout; el menú contextual ofrece
 * crear, eliminar y hacer checkout de ramas.
 */
public class BranchPanel {

    private record TagInfo(String name) {}

    private record BranchData(List<GitService.BranchInfo> locals,
                              List<GitService.BranchInfo> remotes,
                              List<String> tags) {}

    private final RepoView repo;
    private final TreeView<Object> tree = new TreeView<>();

    public BranchPanel(RepoView repo) {
        this.repo = repo;
        tree.setShowRoot(false);
        tree.setCellFactory(tv -> new BranchCell());
    }

    public TreeView<Object> getRoot() {
        return tree;
    }

    public void refresh() {
        if (!repo.git().isOpen()) {
            tree.setRoot(null);
            return;
        }
        repo.runTask("Actualizando ramas", p -> new BranchData(
                repo.git().branches(false),
                repo.git().branches(true),
                repo.git().tags()), data -> {
            TreeItem<Object> root = new TreeItem<>();
            TreeItem<Object> locals = new TreeItem<>("Ramas locales");
            data.locals().forEach(b -> locals.getChildren().add(new TreeItem<>(b)));
            TreeItem<Object> remotes = new TreeItem<>("Ramas remotas");
            data.remotes().forEach(b -> remotes.getChildren().add(new TreeItem<>(b)));
            TreeItem<Object> tags = new TreeItem<>("Etiquetas");
            data.tags().forEach(t -> tags.getChildren().add(new TreeItem<>(new TagInfo(t))));
            locals.setExpanded(true);
            root.getChildren().addAll(locals, remotes, tags);
            tree.setRoot(root);
        });
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

    private ContextMenu menuFor(GitService.BranchInfo branch) {
        ContextMenu menu = new ContextMenu();
        if (branch.remote()) {
            MenuItem checkout = new MenuItem("Checkout (nueva rama local)");
            checkout.setOnAction(e -> checkoutRemote(branch));
            menu.getItems().add(checkout);
        } else {
            MenuItem checkout = new MenuItem("Checkout");
            checkout.setOnAction(e -> checkout(branch.shortName()));
            MenuItem create = new MenuItem("Nueva rama…");
            create.setOnAction(e -> createBranch());
            MenuItem delete = new MenuItem("Eliminar rama");
            delete.setOnAction(e -> deleteBranch(branch));
            menu.getItems().addAll(checkout, create, new SeparatorMenuItem(), delete);
        }
        return menu;
    }

    private class BranchCell extends TreeCell<Object> {
        @Override
        protected void updateItem(Object item, boolean empty) {
            super.updateItem(item, empty);
            setContextMenu(null);
            setOnMouseClicked(null);
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
            } else if (item instanceof GitService.BranchInfo branch) {
                setText(branch.shortName() + (branch.current() ? " ✓" : ""));
                setContextMenu(menuFor(branch));
                setOnMouseClicked(e -> {
                    if (e.getClickCount() == 2 && !branch.remote()) {
                        checkout(branch.shortName());
                    }
                });
            }
        }
    }
}
