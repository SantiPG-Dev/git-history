package com.githistory.ui;

import com.githistory.git.GitService;
import com.githistory.net.GitHubService;
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
                              List<GitService.StashEntry> stashes,
                              List<GitService.SubmoduleInfo> submodules,
                              List<GitHubService.PullRequest> pullRequests,
                              List<GitHubService.Issue> issues) {}

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
        repo.runTask("Actualizando ramas", p -> {
            List<GitHubService.PullRequest> prs = null;
            List<GitHubService.Issue> issues = null;
            String[] ownerRepo = GitHubService.ownerRepo(repo.git().remoteUrl("origin"));
            if (ownerRepo != null) {
                try {
                    GitHubService github = new GitHubService();
                    prs = github.listOpenPullRequests(ownerRepo[0], ownerRepo[1]);
                    issues = github.listOpenIssues(ownerRepo[0], ownerRepo[1]);
                } catch (Exception ignored) {
                    // Sin red o API no disponible: las secciones se ocultan.
                }
            }
            return new BranchData(
                    repo.git().branches(false),
                    repo.git().branches(true),
                    repo.git().tags(),
                    repo.git().stashList(),
                    repo.git().submodules(),
                    prs, issues);
        }, data -> {
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
        TreeItem<Object> submodules = section("SUBMÓDULOS", lastData.submodules());
        locals.setExpanded(true);
        remotes.setExpanded(true);
        rootItem.getChildren().addAll(locals, remotes, tags, stashes, submodules);
        if (lastData.pullRequests() != null) {
            rootItem.getChildren().add(section("PULL REQUESTS", lastData.pullRequests()));
        }
        if (lastData.issues() != null) {
            rootItem.getChildren().add(section("ISSUES", lastData.issues()));
        }
        tree.setRoot(rootItem);
    }

    /** Abre una URL en el navegador del sistema (xdg-open en Linux). */
    private static void openInBrowser(String url) {
        try {
            new ProcessBuilder("xdg-open", url).start();
        } catch (Exception ignored) {
            // Sin navegador disponible: no hay nada que hacer.
        }
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

    private void renameBranch(GitService.BranchInfo branch) {
        TextInputDialog dialog = new TextInputDialog(branch.shortName());
        dialog.setTitle("Renombrar rama");
        dialog.setHeaderText("Renombrar «" + branch.shortName() + "»");
        dialog.setContentText("Nuevo nombre:");
        dialog.showAndWait().map(String::trim)
                .filter(n -> !n.isEmpty() && !n.equals(branch.shortName()))
                .ifPresent(name -> {
                    if (!repo.git().isValidBranchName(name)) {
                        new Alert(Alert.AlertType.ERROR,
                                "Nombre de rama no válido: " + name).showAndWait();
                        return;
                    }
                    repo.runTask("Renombrando rama", p -> {
                        repo.git().renameBranch(branch.shortName(), name);
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
            MenuItem rebase = new MenuItem("Rebasar la rama actual sobre «" + branch.shortName() + "»");
            rebase.setDisable(branch.current());
            rebase.setOnAction(e -> repo.rebaseOnto(branch.shortName()));
            MenuItem create = new MenuItem("Nueva rama…");
            create.setOnAction(e -> createBranch());
            MenuItem rename = new MenuItem("Renombrar rama…");
            rename.setOnAction(e -> renameBranch(branch));
            menu.getItems().addAll(checkout, merge, rebase, create, rename);
            if (!repo.git().hasUpstream(branch.shortName())
                    && repo.git().remoteUrl("origin") != null) {
                MenuItem publish = new MenuItem("Publicar en origin");
                publish.setOnAction(e -> publishBranch(branch.shortName()));
                menu.getItems().add(publish);
            }
            MenuItem delete = new MenuItem("Eliminar rama");
            delete.setOnAction(e -> deleteBranch(branch));
            menu.getItems().addAll(new SeparatorMenuItem(), delete);
        }
        return menu;
    }

    private void publishBranch(String name) {
        repo.runTask("Publicando " + name, p -> {
            repo.git().pushSetUpstream(name, "origin", repo.credentialsProvider(), p);
            return null;
        }, v -> repo.refreshAll());
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

    private ContextMenu menuForSubmodule(GitService.SubmoduleInfo submodule) {
        MenuItem update = new MenuItem(submodule.initialized()
                ? "Actualizar al commit fijado" : "Inicializar y clonar");
        update.setOnAction(e ->
                repo.runTask("Actualizando submódulo " + submodule.path(), p -> {
                    repo.git().updateSubmodule(submodule.path());
                    return null;
                }, v -> repo.refreshAll()));
        MenuItem copyUrl = new MenuItem("Copiar URL");
        copyUrl.setOnAction(e -> {
            var content = new javafx.scene.input.ClipboardContent();
            content.putString(submodule.url());
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
        });
        return new ContextMenu(update, new SeparatorMenuItem(), copyUrl);
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
            } else if (item instanceof GitHubService.PullRequest pr) {
                setText("#" + pr.number() + " — " + pr.title() + " (" + pr.author() + ")");
                getStyleClass().add("branch-tag");
                setOnMouseClicked(e -> {
                    if (e.getClickCount() == 2) {
                        openInBrowser(pr.url());
                    }
                });
            } else if (item instanceof GitHubService.Issue issue) {
                setText("#" + issue.number() + " — " + issue.title() + " (" + issue.author() + ")");
                getStyleClass().add("branch-tag");
                setOnMouseClicked(e -> {
                    if (e.getClickCount() == 2) {
                        openInBrowser(issue.url());
                    }
                });
            } else if (item instanceof TagInfo tag) {
                setText(tag.name());
                getStyleClass().add("branch-tag");
                MenuItem delete = new MenuItem("Eliminar etiqueta");
                delete.setOnAction(e -> repo.deleteTag(tag.name()));
                setContextMenu(new ContextMenu(delete));
            } else if (item instanceof GitService.StashEntry stash) {
                setText("stash@{" + stash.index() + "} — " + stash.message());
                setContextMenu(menuForStash(stash));
            } else if (item instanceof GitService.SubmoduleInfo submodule) {
                setText(submodule.path() + (submodule.initialized() ? "" : " (sin inicializar)"));
                getStyleClass().add("branch-tag");
                setContextMenu(menuForSubmodule(submodule));
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
