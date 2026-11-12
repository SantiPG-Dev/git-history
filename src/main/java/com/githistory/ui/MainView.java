package com.githistory.ui;

import com.githistory.AppConfig;
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
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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

        root.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (new KeyCodeCombination(KeyCode.K, KeyCombination.SHORTCUT_DOWN).match(e)) {
                showCommandPalette();
                e.consume();
            } else if (new KeyCodeCombination(KeyCode.PLUS, KeyCombination.SHORTCUT_DOWN).match(e)
                    || new KeyCodeCombination(KeyCode.ADD, KeyCombination.SHORTCUT_DOWN).match(e)
                    || new KeyCodeCombination(KeyCode.EQUALS, KeyCombination.SHORTCUT_DOWN).match(e)) {
                adjustFontSize(1);
                e.consume();
            } else if (new KeyCodeCombination(KeyCode.MINUS, KeyCombination.SHORTCUT_DOWN).match(e)
                    || new KeyCodeCombination(KeyCode.SUBTRACT, KeyCombination.SHORTCUT_DOWN).match(e)) {
                adjustFontSize(-1);
                e.consume();
            } else if (new KeyCodeCombination(KeyCode.DIGIT0, KeyCombination.SHORTCUT_DOWN).match(e)
                    || new KeyCodeCombination(KeyCode.NUMPAD0, KeyCombination.SHORTCUT_DOWN).match(e)) {
                resetFontSize();
                e.consume();
            }
        });
        applyFontSize(AppConfig.load().fontSize());

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
        restartAutoFetch();
    }

    private java.util.concurrent.ScheduledFuture<?> autoFetchTask;
    private final java.util.concurrent.ScheduledExecutorService autoFetchExecutor =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "auto-fetch");
                thread.setDaemon(true);
                return thread;
            });

    /** (Re)programa el fetch automático según AppConfig (0 = desactivado). */
    public void restartAutoFetch() {
        if (autoFetchTask != null) {
            autoFetchTask.cancel(false);
            autoFetchTask = null;
        }
        int minutes = AppConfig.load().autoFetchMinutes();
        if (minutes <= 0) {
            return;
        }
        autoFetchTask = autoFetchExecutor.scheduleWithFixedDelay(
                () -> Platform.runLater(() -> {
                    for (Tab tab : new ArrayList<>(repoTabs.getTabs())) {
                        if (tab.getUserData() instanceof RepoView view) {
                            view.fetch();
                        }
                    }
                }), minutes, minutes, java.util.concurrent.TimeUnit.MINUTES);
    }

    public BorderPane getRoot() {
        return root;
    }

    /** Zoom de la interfaz: escala la fuente base (Ctrl +/-/0). */
    private void adjustFontSize(double delta) {
        double size = Math.min(20, Math.max(8, currentFontSize() + delta));
        applyFontSize(size, true);
        persistFontSize(size);
    }

    private void resetFontSize() {
        applyFontSize(AppConfig.DEFAULT_FONT_SIZE, true);
        persistFontSize(AppConfig.DEFAULT_FONT_SIZE);
    }

    private double currentFontSize() {
        String style = root.getStyle();
        if (style != null && style.contains("-fx-font-size:")) {
            String value = style.split("-fx-font-size:")[1].replace("px;", "").trim();
            try {
                return Double.parseDouble(value);
            } catch (NumberFormatException ignored) {
                // Estilo corrupto: se usa el valor por defecto.
            }
        }
        return AppConfig.DEFAULT_FONT_SIZE;
    }

    private void applyFontSize(double size) {
        applyFontSize(size, false);
    }

    private void applyFontSize(double size, boolean feedback) {
        root.setStyle("-fx-font-size: " + size + "px;");
        RepoView active = feedback ? activeRepo() : null;
        if (active != null) {
            active.toast("Zoom: "
                    + Math.round(size / AppConfig.DEFAULT_FONT_SIZE * 100) + "%");
        }
    }

    private void persistFontSize(double size) {
        try {
            AppConfig config = AppConfig.load();
            config.setFontSize(size);
            config.save();
        } catch (java.io.IOException ignored) {
            // Sin persistencia: el zoom se mantiene solo para esta sesión.
        }
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
        view.setOpenInTab(this::openRepo);
        Tab tab = new Tab(dir.getFileName().toString());
        tab.setUserData(view);
        tab.setContent(view.getRoot());
        tab.setOnClosed(e -> {
            view.close();
            persistOpenRepos();
        });
        repoTabs.getTabs().add(repoTabs.getTabs().size() - 1, tab);
        repoTabs.getSelectionModel().select(tab);
        stage.setTitle("Git History — " + tab.getText());
        view.openRepo(dir, () -> repoTabs.getTabs().remove(tab), this::persistOpenRepos);
    }

    /** Guarda las pestañas de repos abiertas para restaurarlas al arrancar. */
    private void persistOpenRepos() {
        List<Path> paths = new ArrayList<>();
        for (Tab tab : repoTabs.getTabs()) {
            if (tab.getUserData() instanceof RepoView view && view.getRepoPath() != null) {
                paths.add(view.getRepoPath());
            }
        }
        AppConfig config = AppConfig.load();
        config.setOpenRepos(paths);
        try {
            config.save();
        } catch (IOException ignored) {
            // Sin persistencia: las pestañas no se restaurarán al arrancar.
        }
    }

    private RepoView activeRepo() {
        Tab tab = repoTabs.getSelectionModel().getSelectedItem();
        return tab != null && tab.getUserData() instanceof RepoView view ? view : null;
    }

    private void showCommandPalette() {
        List<CommandPalette.Command> commands = new ArrayList<>();
        commands.add(new CommandPalette.Command("Abrir repositorio…", this::openRepoDialog));
        commands.add(new CommandPalette.Command("Clonar repositorio…", this::cloneRepo));
        commands.add(new CommandPalette.Command("Cerrar pestaña", this::closeActiveTab));
        RepoView repo = activeRepo();
        if (repo != null) {
            commands.add(new CommandPalette.Command("Fetch", repo::fetch));
            commands.add(new CommandPalette.Command("Pull", repo::pull));
            commands.add(new CommandPalette.Command("Push", repo::push));
            commands.add(new CommandPalette.Command("Push forzado…", repo::pushForce));
            commands.add(new CommandPalette.Command("Push de etiquetas", repo::pushTags));
            commands.add(new CommandPalette.Command("Refrescar", repo::refreshAll));
            commands.add(new CommandPalette.Command("Deshacer", repo::undoLast));
            commands.add(new CommandPalette.Command("Rehacer", repo::redoLast));
            commands.add(new CommandPalette.Command("Nueva rama…", repo::createBranch));
            commands.add(new CommandPalette.Command("Guardar cambios en stash", repo::stash));
            commands.add(new CommandPalette.Command("Pop de stash", repo::stashPop));
            commands.add(new CommandPalette.Command("Terminal integrada", repo::toggleTerminal));
            commands.add(new CommandPalette.Command("Terminal externa", repo::openTerminal));
            commands.add(new CommandPalette.Command("Reflog de HEAD…", repo::openReflog));
            commands.add(new CommandPalette.Command("Gestionar remotos…", repo::openRemotes));
            for (GitService.BranchInfo b : repo.knownBranches()) {
                if (!b.current()) {
                    commands.add(new CommandPalette.Command(
                            "Cambiar a rama: " + b.shortName(),
                            () -> repo.checkoutBranch(b.shortName())));
                }
            }
        }
        CommandPalette.show(stage, commands);
    }

    private MenuBar buildMenuBar() {
        Menu archivo = new Menu("Archivo");
        MenuItem abrir = new MenuItem("Abrir repositorio…");
        abrir.setAccelerator(KeyCombination.keyCombination("Ctrl+O"));
        abrir.setOnAction(e -> openRepoDialog());
        MenuItem clonar = new MenuItem("Clonar repositorio…");
        clonar.setAccelerator(KeyCombination.keyCombination("Ctrl+Shift+O"));
        clonar.setOnAction(e -> cloneRepo());
        MenuItem inicializar = new MenuItem("Inicializar repositorio…");
        inicializar.setOnAction(e -> initRepoDialog());
        MenuItem cerrar = new MenuItem("Cerrar pestaña");
        cerrar.setAccelerator(KeyCombination.keyCombination("Ctrl+W"));
        cerrar.setOnAction(e -> closeActiveTab());
        MenuItem prefs = new MenuItem("Preferencias…");
        prefs.setAccelerator(KeyCombination.keyCombination("Ctrl+,"));
        prefs.setOnAction(e -> {
            new PreferencesDialog(stage, activeRepo()).showAndWait();
            restartAutoFetch();
        });
        MenuItem salir = new MenuItem("Salir");
        salir.setAccelerator(KeyCombination.keyCombination("Ctrl+Q"));
        salir.setOnAction(e -> Platform.exit());
        archivo.getItems().addAll(abrir, clonar, inicializar, new SeparatorMenuItem(), cerrar,
                new SeparatorMenuItem(), prefs, new SeparatorMenuItem(), salir);

        Menu acciones = new Menu("Acciones");
        MenuItem deshacer = new MenuItem("Deshacer");
        deshacer.setAccelerator(KeyCombination.keyCombination("Ctrl+Z"));
        deshacer.setOnAction(e -> {
            RepoView repo = activeRepo();
            if (repo != null) {
                repo.undoLast();
            }
        });
        MenuItem rehacer = new MenuItem("Rehacer");
        rehacer.setAccelerator(KeyCombination.keyCombination("Ctrl+Y"));
        rehacer.setOnAction(e -> {
            RepoView repo = activeRepo();
            if (repo != null) {
                repo.redoLast();
            }
        });
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
        MenuItem pushTags = new MenuItem("Push de etiquetas");
        pushTags.setOnAction(e -> {
            RepoView repo = activeRepo();
            if (repo != null) {
                repo.pushTags();
            }
        });
        MenuItem pushForzado = new MenuItem("Push forzado…");
        pushForzado.setOnAction(e -> {
            RepoView repo = activeRepo();
            if (repo != null) {
                repo.pushForce();
            }
        });
        MenuItem remotos = new MenuItem("Gestionar remotos…");
        remotos.setOnAction(e -> {
            RepoView repo = activeRepo();
            if (repo != null) {
                repo.openRemotes();
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
        acciones.getItems().addAll(deshacer, rehacer, new SeparatorMenuItem(),
                fetch, pull, push, pushTags, pushForzado, new SeparatorMenuItem(), remotos, refrescar);
        acciones.setOnShowing(e -> {
            boolean noRepo = activeRepo() == null;
            deshacer.setDisable(noRepo);
            rehacer.setDisable(noRepo);
            fetch.setDisable(noRepo);
            pull.setDisable(noRepo);
            push.setDisable(noRepo);
            pushTags.setDisable(noRepo);
            pushForzado.setDisable(noRepo);
            remotos.setDisable(noRepo);
            refrescar.setDisable(noRepo);
        });

        Menu ver = new Menu("Ver");
        MenuItem paleta = new MenuItem("Paleta de comandos…");
        paleta.setAccelerator(KeyCombination.keyCombination("Ctrl+K"));
        paleta.setOnAction(e -> showCommandPalette());
        MenuItem reflog = new MenuItem("Reflog de HEAD…");
        reflog.setOnAction(e -> {
            RepoView repo = activeRepo();
            if (repo != null) {
                repo.openReflog();
            }
        });
        CheckMenuItem panelRamas = new CheckMenuItem("Panel de ramas");
        panelRamas.setSelected(true);
        CheckMenuItem panelDerecho = new CheckMenuItem("Panel de detalle");
        panelDerecho.setSelected(true);
        panelRamas.setOnAction(e -> togglePanels(panelRamas.isSelected(), panelDerecho.isSelected()));
        panelDerecho.setOnAction(e -> togglePanels(panelRamas.isSelected(), panelDerecho.isSelected()));
        ver.getItems().addAll(paleta, reflog, new SeparatorMenuItem(), panelRamas, panelDerecho);
        ver.setOnShowing(e -> {
            boolean noRepo = activeRepo() == null;
            reflog.setDisable(noRepo);
            panelRamas.setDisable(noRepo);
            panelDerecho.setDisable(noRepo);
        });

        Menu ayuda = new Menu("Ayuda");
        MenuItem acerca = new MenuItem("Acerca de Git History");
        acerca.setOnAction(e -> new ThemedAlert(Alert.AlertType.INFORMATION,
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
            persistOpenRepos();
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
        Button init = new Button("Inicializar repositorio…");
        init.getStyleClass().add("accent-button");
        init.setOnAction(e -> initRepoDialog());
        VBox box = new VBox(12, title, sub, open, clone, init);
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

    private void initRepoDialog() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Carpeta donde inicializar el repositorio");
        File dir = chooser.showDialog(stage);
        if (dir == null) {
            return;
        }
        Path path = dir.toPath();
        runGlobalTask("Inicializando " + path, p -> {
            GitService.initRepo(path);
            return null;
        }, v -> openRepo(path));
    }
}
