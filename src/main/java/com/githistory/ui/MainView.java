package com.githistory.ui;

import com.githistory.git.GitService;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.input.KeyCombination;
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
 * Ventana principal: barra de menús superior (Archivo/Acciones/Ver/Ayuda),
 * TabPane con una pestaña por repositorio más una pestaña "+" final que
 * muestra la pantalla de bienvenida para abrir o clonar.
 */
public class MainView {

    private final Stage stage;
    private final BorderPane root = new BorderPane();
    private final CredentialsPrompt credentials = new CredentialsPrompt();
    private final TabPane repoTabs = new TabPane();
    private final Tab plusTab;

    private final Label statusLabel = new Label("Listo");
    private final ProgressBar progressBar = new ProgressBar(0);
    private final HBox globalStatusBar;

    public MainView(Stage stage) {
        this.stage = stage;

        plusTab = new Tab("+");
        plusTab.setClosable(false);
        plusTab.setTooltip(new javafx.scene.control.Tooltip("Abrir o clonar un repositorio"));
        plusTab.setContent(buildWelcome());
        repoTabs.getTabs().add(plusTab);
        repoTabs.getSelectionModel().selectedItemProperty().addListener((obs, o, tab) -> {
            if (tab == null || tab == plusTab) {
                stage.setTitle("Git History");
            } else {
                stage.setTitle("Git History — " + tab.getText());
            }
        });

        root.setTop(buildMenuBar());
        root.setCenter(repoTabs);

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
    }

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
            if (tab.getUserData() instanceof RepoView view && dir.equals(view.getRepoPath())) {
                repoTabs.getSelectionModel().select(tab);
                return;
            }
        }
        RepoView view = new RepoView(credentials);
        Tab tab = new Tab(dir.getFileName().toString());
        tab.setUserData(view);
        tab.setContent(view.getRoot());
        tab.setOnClosed(e -> view.close());
        repoTabs.getTabs().add(repoTabs.getTabs().size() - 1, tab);
        repoTabs.getSelectionModel().select(tab);
        stage.setTitle("Git History — " + tab.getText());
        view.openRepo(dir, () -> repoTabs.getTabs().remove(tab));
    }

    private RepoView activeRepo() {
        Tab tab = repoTabs.getSelectionModel().getSelectedItem();
        return tab != null && tab.getUserData() instanceof RepoView view ? view : null;
    }

    private MenuBar buildMenuBar() {
        Menu archivo = new Menu("Archivo");
        MenuItem abrir = new MenuItem("Abrir repositorio…");
        abrir.setAccelerator(KeyCombination.keyCombination("Ctrl+O"));
        abrir.setOnAction(e -> openRepoDialog());
        MenuItem clonar = new MenuItem("Clonar repositorio…");
        clonar.setAccelerator(KeyCombination.keyCombination("Ctrl+Shift+O"));
        clonar.setOnAction(e -> cloneRepo());
        MenuItem cerrar = new MenuItem("Cerrar pestaña");
        cerrar.setAccelerator(KeyCombination.keyCombination("Ctrl+W"));
        cerrar.setOnAction(e -> closeActiveTab());
        MenuItem salir = new MenuItem("Salir");
        salir.setAccelerator(KeyCombination.keyCombination("Ctrl+Q"));
        salir.setOnAction(e -> Platform.exit());
        archivo.getItems().addAll(abrir, clonar, new SeparatorMenuItem(), cerrar,
                new SeparatorMenuItem(), salir);

        Menu acciones = new Menu("Acciones");
        MenuItem fetch = new MenuItem("Fetch");
        fetch.setOnAction(e -> {
            RepoView repo = activeRepo();
            if (repo != null) {
                repo.fetch();
            }
        });
        MenuItem pull = new MenuItem("Pull");
        pull.setAccelerator(KeyCombination.keyCombination("Ctrl+L"));
        pull.setOnAction(e -> {
            RepoView repo = activeRepo();
            if (repo != null) {
                repo.pull();
            }
        });
        MenuItem push = new MenuItem("Push");
        push.setAccelerator(KeyCombination.keyCombination("Ctrl+P"));
        push.setOnAction(e -> {
            RepoView repo = activeRepo();
            if (repo != null) {
                repo.push();
            }
        });
        MenuItem refrescar = new MenuItem("Refrescar");
        refrescar.setAccelerator(KeyCombination.keyCombination("F5"));
        refrescar.setOnAction(e -> {
            RepoView repo = activeRepo();
            if (repo != null) {
                repo.refreshAll();
            }
        });
        acciones.getItems().addAll(fetch, pull, push, new SeparatorMenuItem(), refrescar);
        acciones.setOnShowing(e -> {
            boolean noRepo = activeRepo() == null;
            fetch.setDisable(noRepo);
            pull.setDisable(noRepo);
            push.setDisable(noRepo);
            refrescar.setDisable(noRepo);
        });

        Menu ver = new Menu("Ver");
        CheckMenuItem panelRamas = new CheckMenuItem("Panel de ramas");
        panelRamas.setSelected(true);
        CheckMenuItem panelDerecho = new CheckMenuItem("Panel de detalle");
        panelDerecho.setSelected(true);
        panelRamas.setOnAction(e -> togglePanels(panelRamas.isSelected(), panelDerecho.isSelected()));
        panelDerecho.setOnAction(e -> togglePanels(panelRamas.isSelected(), panelDerecho.isSelected()));
        ver.getItems().addAll(panelRamas, panelDerecho);
        ver.setOnShowing(e -> {
            boolean noRepo = activeRepo() == null;
            panelRamas.setDisable(noRepo);
            panelDerecho.setDisable(noRepo);
        });

        Menu ayuda = new Menu("Ayuda");
        MenuItem acerca = new MenuItem("Acerca de Git History");
        acerca.setOnAction(e -> new Alert(Alert.AlertType.INFORMATION,
                "Git History 0.1.0\n\nUn cliente Git visual, libre y sin IA.\nConstruido con JavaFX y JGit.")
                .showAndWait());
        ayuda.getItems().add(acerca);

        MenuBar menuBar = new MenuBar(archivo, acciones, ver, ayuda);
        menuBar.setUseSystemMenuBar(false);
        return menuBar;
    }

    /** Muestra u oculta los paneles laterales de la vista del repo activo. */
    private void togglePanels(boolean showBranches, boolean showDetail) {
        RepoView repo = activeRepo();
        if (repo != null) {
            repo.setPanelsVisible(showBranches, showDetail);
        }
    }

    private void closeActiveTab() {
        Tab tab = repoTabs.getSelectionModel().getSelectedItem();
        if (tab != null && tab.getUserData() instanceof RepoView view) {
            view.close();
            repoTabs.getTabs().remove(tab);
        }
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
