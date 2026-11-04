package com.githistory.ui;

import com.githistory.git.GitService;
import javafx.beans.binding.Bindings;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Separator;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.ToolBar;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;

import java.io.File;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Ventana principal: barra de herramientas global y un TabPane con una
 * pestaña por repositorio abierto (cada una con su propia {@link RepoView}).
 * La barra de estado global se usa para operaciones sin repo abierto (clonar).
 */
public class MainView {

    private final Stage stage;
    private final BorderPane root = new BorderPane();
    private final CredentialsPrompt credentials = new CredentialsPrompt();
    private final TabPane repoTabs = new TabPane();
    private final Node welcome;

    private final Label statusLabel = new Label("Listo");
    private final ProgressBar progressBar = new ProgressBar(0);

    public MainView(Stage stage) {
        this.stage = stage;
        welcome = buildWelcome();
        root.setTop(buildToolbar());
        root.setCenter(welcome);

        progressBar.setVisible(false);
        progressBar.setPrefWidth(220);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox statusBar = new HBox(10, statusLabel, spacer, progressBar);
        statusBar.setAlignment(Pos.CENTER_LEFT);
        statusBar.getStyleClass().add("status-bar");
        statusBar.setManaged(false);
        statusBar.setVisible(false);
        root.setBottom(statusBar);
        this.globalStatusBar = statusBar;

        repoTabs.getSelectionModel().selectedItemProperty().addListener((obs, o, tab) -> {
            if (tab == null) {
                if (repoTabs.getTabs().isEmpty() && root.getCenter() != welcome) {
                    root.setCenter(welcome);
                    stage.setTitle("Git History");
                }
            } else {
                stage.setTitle("Git History — " + tab.getText());
            }
        });
    }

    private final HBox globalStatusBar;

    public BorderPane getRoot() {
        return root;
    }

    public <T> void runGlobalTask(String description, TaskRunner.Work<T> work, Consumer<T> onSuccess) {
        globalStatusBar.setManaged(true);
        globalStatusBar.setVisible(true);
        TaskRunner.run(description, statusLabel, progressBar, work, result -> {
            globalStatusBar.setManaged(false);
            globalStatusBar.setVisible(false);
            onSuccess.accept(result);
        });
    }

    public void openRepo(Path dir) {
        for (Tab tab : repoTabs.getTabs()) {
            RepoView view = (RepoView) tab.getUserData();
            if (dir.equals(view.getRepoPath())) {
                repoTabs.getSelectionModel().select(tab);
                return;
            }
        }
        RepoView view = new RepoView(credentials);
        Tab tab = new Tab(dir.getFileName().toString());
        tab.setUserData(view);
        tab.setContent(view.getRoot());
        tab.setOnClosed(e -> {
            view.close();
            if (repoTabs.getTabs().isEmpty()) {
                root.setCenter(welcome);
                stage.setTitle("Git History");
            }
        });
        repoTabs.getTabs().add(tab);
        repoTabs.getSelectionModel().select(tab);
        if (root.getCenter() != repoTabs) {
            root.setCenter(repoTabs);
        }
        stage.setTitle("Git History — " + tab.getText());
        view.openRepo(dir, () -> repoTabs.getTabs().remove(tab));
    }

    private RepoView activeRepo() {
        Tab tab = repoTabs.getSelectionModel().getSelectedItem();
        return tab != null ? (RepoView) tab.getUserData() : null;
    }

    private ToolBar buildToolbar() {
        Button open = new Button("Abrir…");
        open.setOnAction(e -> openRepoDialog());
        Button clone = new Button("Clonar…");
        clone.setOnAction(e -> cloneRepo());
        Button fetch = new Button("Fetch");
        fetch.setOnAction(e -> {
            RepoView repo = activeRepo();
            if (repo != null) {
                repo.fetch();
            }
        });
        Button pull = new Button("Pull");
        pull.setOnAction(e -> {
            RepoView repo = activeRepo();
            if (repo != null) {
                repo.pull();
            }
        });
        Button push = new Button("Push");
        push.setOnAction(e -> {
            RepoView repo = activeRepo();
            if (repo != null) {
                repo.push();
            }
        });
        Button refresh = new Button("Refrescar");
        refresh.setOnAction(e -> {
            RepoView repo = activeRepo();
            if (repo != null) {
                repo.refreshAll();
            }
        });
        var noRepo = Bindings.isEmpty(repoTabs.getTabs());
        fetch.disableProperty().bind(noRepo);
        pull.disableProperty().bind(noRepo);
        push.disableProperty().bind(noRepo);
        refresh.disableProperty().bind(noRepo);
        return new ToolBar(open, clone, new Separator(), fetch, pull, push,
                new Separator(), refresh);
    }

    private Node buildWelcome() {
        Label title = new Label("Git History");
        title.getStyleClass().add("welcome-title");
        Label sub = new Label("Un cliente Git visual, libre y sin IA. Abre o clona un repositorio para empezar.");
        sub.getStyleClass().add("welcome-sub");
        Button open = new Button("Abrir repositorio…");
        open.getStyleClass().add("accent-button");
        open.setOnAction(e -> openRepoDialog());
        Button clone = new Button("Clonar repositorio…");
        clone.getStyleClass().add("accent-button");
        clone.setOnAction(e -> cloneRepo());
        VBox box = new VBox(12, title, sub, open, clone);
        box.setAlignment(Pos.CENTER);
        return box;
    }

    private void openRepoDialog() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Abrir repositorio");
        File dir = chooser.showDialog(stage);
        if (dir != null) {
            openRepo(dir.toPath());
        }
    }

    private void cloneRepo() {
        new CloneDialog(stage).showAndWait().ifPresent(request ->
                runGlobalTask("Clonando " + request.url(), p -> {
                    GitService.cloneRepo(request.url(), request.directory(), credentials, p);
                    return null;
                }, v -> openRepo(request.directory())));
    }
}
