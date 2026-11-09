package com.githistory.ui;

import com.githistory.git.CommitInfo;
import com.githistory.git.GitService;
import com.githistory.git.GraphLayout;
import com.githistory.git.GraphRow;
import com.githistory.git.UndoManager;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.collections.ListChangeListener;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Separator;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Vista de un repositorio abierto: barra de acciones estilo GitKraken arriba,
 * grafo de historial con cabecera de columnas en el centro, ramas a la
 * izquierda, detalle/staging a la derecha y barra de estado propia abajo.
 * Cada pestaña de la ventana contiene una instancia.
 */
public class RepoView {

    private static final AtomicInteger COUNT = new AtomicInteger();

    private final CredentialsPrompt credentials;
    private final GitService git = new GitService();
    private final UndoManager undoManager = new UndoManager();
    private final ExecutorService executor =
            TaskRunner.newSerialExecutor("repo-" + COUNT.incrementAndGet());
    private final BorderPane root = new BorderPane();
    private final Label statusLabel = new Label("Listo");
    private final ProgressBar progressBar = new ProgressBar(0);
    private final Button undoButton = new Button("Deshacer");
    private final Button redoButton = new Button("Rehacer");

    private final ListView<GraphRow> historyList;
    private final BranchPanel branchPanel;
    private final CommitDetailView detailView;
    private final StagingView stagingView;
    private final MenuButton branchMenu = new MenuButton("(sin rama)");
    private final TextField searchField = new TextField();
    private final IntegerProperty laneWidth = new SimpleIntegerProperty(26);
    private final SplitPane split;
    private final Node branchNode;
    private final Node centerNode;
    private final Node rightNode;
    private TabPane rightTabs;

    private int historyMaxLanes = 1;
    private Path repoPath;
    private TerminalView terminalView;
    private Toaster toaster;
    private VBox centerWrap;
    private List<GraphRow> allRows = List.of();
    private List<GitService.BranchInfo> lastBranches = List.of();
    private List<Integer> searchHits = List.of();
    private int searchCursor = -1;
    private Consumer<Path> openInTab;
    private final Map<String, javafx.scene.image.Image> avatars =
            new java.util.concurrent.ConcurrentHashMap<>();

    public RepoView(CredentialsPrompt credentials) {
        this.credentials = credentials;

        branchPanel = new BranchPanel(this);
        detailView = new CommitDetailView(this);
        stagingView = new StagingView(this);

        historyList = new ListView<>();
        historyList.getStyleClass().add("graph-list");
        historyList.setFixedCellSize(HistoryCell.ROW_HEIGHT);
        historyList.setCellFactory(lv -> new HistoryCell(() -> historyMaxLanes, avatars::get,
                new HistoryCell.CommitActions() {
                    @Override
                    public void createBranchAt(CommitInfo commit) {
                        RepoView.this.createBranchAt(commit);
                    }

                    @Override
                    public void createTagAt(CommitInfo commit) {
                        RepoView.this.createTagAt(commit);
                    }

                    @Override
                    public void checkoutCommit(CommitInfo commit) {
                        RepoView.this.checkoutCommit(commit);
                    }

                    @Override
                    public void cherryPick(CommitInfo commit) {
                        RepoView.this.cherryPickCommit(commit);
                    }

                    @Override
                    public void revert(CommitInfo commit) {
                        RepoView.this.revertCommit(commit);
                    }

                    @Override
                    public void resetToHere(CommitInfo commit) {
                        RepoView.this.resetToCommit(commit);
                    }

                    @Override
                    public void squashWithParent(CommitInfo commit) {
                        RepoView.this.squashWithParent(commit);
                    }

                    @Override
                    public void rewordCommit(CommitInfo commit) {
                        RepoView.this.rewordCommit(commit);
                    }

                    @Override
                    public void interactiveRebase(CommitInfo commit) {
                        RepoView.this.interactiveRebase(commit);
                    }

                    @Override
                    public void copyHash(CommitInfo commit) {
                        RepoView.this.copyHash(commit);
                    }
                }));
        historyList.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        historyList.getSelectionModel().getSelectedItems().addListener(
                (ListChangeListener<GraphRow>) change -> {
                    List<GraphRow> selected = new ArrayList<>(
                            historyList.getSelectionModel().getSelectedItems());
                    if (selected.size() == 1) {
                        detailView.showCommit(selected.get(0).commit());
                    } else if (selected.size() == 2) {
                        // allRows está ordenado de más nuevo a más antiguo.
                        selected.sort(Comparator.comparingInt(allRows::indexOf));
                        detailView.showComparison(selected.get(1).commit(),
                                selected.get(0).commit());
                    }
                });
        VBox.setVgrow(historyList, Priority.ALWAYS);

        Tab commitTab = new Tab("Commit", detailView.getRoot());
        Tab changesTab = new Tab("Cambios", stagingView.getRoot());
        commitTab.setClosable(false);
        changesTab.setClosable(false);
        rightTabs = new TabPane(commitTab, changesTab);
        rightTabs.setMinWidth(340);

        branchNode = branchPanel.getRoot();
        centerNode = new VBox(buildColumnHeader(), historyList);
        rightNode = rightTabs;
        split = new SplitPane(branchNode, centerNode, rightNode);
        split.setDividerPositions(0.16, 0.58);

        root.setTop(buildActionBar());
        centerWrap = new VBox(split);
        VBox.setVgrow(split, Priority.ALWAYS);
        javafx.scene.layout.StackPane centerStack =
                new javafx.scene.layout.StackPane(centerWrap);
        toaster = new Toaster(centerStack);
        root.setCenter(centerStack);
        root.setBottom(buildStatusBar());

        root.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (new KeyCodeCombination(KeyCode.Z, KeyCombination.SHORTCUT_DOWN,
                    KeyCombination.SHIFT_DOWN).match(e)
                    || new KeyCodeCombination(KeyCode.Y,
                    KeyCombination.SHORTCUT_DOWN).match(e)) {
                redoLast();
                e.consume();
            } else if (new KeyCodeCombination(KeyCode.Z,
                    KeyCombination.SHORTCUT_DOWN).match(e)) {
                undoLast();
                e.consume();
            }
        });
    }

    /** Muestra u oculta los paneles laterales (menú Ver). */
    public void setPanelsVisible(boolean showBranches, boolean showDetail) {
        var items = split.getItems();
        items.clear();
        if (showBranches) {
            items.add(branchNode);
        }
        items.add(centerNode);
        if (showDetail) {
            items.add(rightNode);
        }
    }

    public BorderPane getRoot() {
        return root;
    }

    public GitService git() {
        return git;
    }

    public Path getRepoPath() {
        return repoPath;
    }

    /** Callback para abrir una ruta (p. ej. un worktree) en otra pestaña. */
    public void setOpenInTab(Consumer<Path> openInTab) {
        this.openInTab = openInTab;
    }

    public Consumer<Path> openInTab() {
        return openInTab;
    }

    /** Ramas locales conocidas (caché del último refresco, para la paleta). */
    public List<GitService.BranchInfo> knownBranches() {
        return lastBranches;
    }

    public void close() {
        if (terminalView != null) {
            terminalView.dispose();
        }
        executor.shutdownNow();
        git.close();
    }

    public <T> void runTask(String description, TaskRunner.Work<T> work, Consumer<T> onSuccess) {
        TaskRunner.run(executor, description, statusLabel, progressBar, work, onSuccess);
    }

    /** Muestra una notificación transitoria en la esquina inferior derecha. */
    public void toast(String message) {
        toaster.show(message);
    }

    public org.eclipse.jgit.transport.CredentialsProvider credentialsProvider() {
        return credentials;
    }

    public void openRepo(Path dir, Runnable onFailure) {
        openRepo(dir, onFailure, () -> {});
    }

    public void openRepo(Path dir, Runnable onFailure, Runnable onSuccess) {
        TaskRunner.run(executor, "Abriendo " + dir, statusLabel, progressBar, p -> {
            git.open(dir);
            return null;
        }, v -> {
            repoPath = dir;
            undoManager.clear();
            updateUndoButtons();
            refreshAll();
            onSuccess.run();
        }, e -> onFailure.run());
    }

    public void refreshAll() {
        if (!git.isOpen()) {
            return;
        }
        runTask("Actualizando ramas", p -> git.branches(false), this::updateBranchMenu);
        runTask("Cargando historial",
                p -> GraphLayout.layout(git.loadHistory(2000)),
                graph -> {
                    historyMaxLanes = graph.maxLanes();
                    laneWidth.set(historyMaxLanes * (int) HistoryCell.LANE_WIDTH + 10);
                    allRows = graph.rows();
                    historyList.getItems().setAll(allRows);
                    historyList.refresh();
                    if (historyList.getSelectionModel().getSelectedItem() == null
                            && !allRows.isEmpty()) {
                        historyList.getSelectionModel().select(0);
                    }
                    preloadAvatars();
                });
        branchPanel.refresh();
        stagingView.refresh();
    }

    /**
     * Precarga en segundo plano los avatares de los autores visibles en el
     * historial (máx. 60 emails distintos) y repinta el grafo al terminar.
     */
    private void preloadAvatars() {
        List<String> emails = allRows.stream()
                .map(r -> r.commit().email())
                .filter(java.util.Objects::nonNull)
                .distinct()
                .limit(60)
                .filter(e -> !avatars.containsKey(e))
                .toList();
        if (emails.isEmpty()) {
            return;
        }
        runTask("Cargando avatares", p -> {
            int loaded = 0;
            for (String email : emails) {
                var image = AvatarService.load(email, 24);
                if (image != null) {
                    avatars.put(email, image);
                    loaded++;
                }
            }
            return loaded;
        }, loaded -> {
            if (loaded > 0) {
                historyList.refresh();
            }
        });
    }

    public void fetch() {
        if (git.isOpen()) {
            runTask("Fetch en curso…", p -> {
                git.fetch(credentials, p);
                return null;
            }, v -> {
                toast("✓ Fetch completado");
                refreshAll();
            });
        }
    }

    public void pull() {
        if (git.isOpen()) {
            runTask("Pull en curso…", p -> {
                git.pull(credentials, p);
                return null;
            }, v -> {
                toast("✓ Pull completado");
                refreshAll();
            });
        }
    }

    public void push() {
        if (!git.isOpen()) {
            return;
        }
        String branch = null;
        try {
            branch = git.currentBranch();
        } catch (IOException ignored) {
            // Sin rama activa.
        }
        if (branch != null && !git.hasUpstream(branch) && git.remoteUrl("origin") != null) {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "La rama «" + branch + "» no tiene upstream. ¿Publicarla en origin?");
            confirm.setHeaderText("Publicar rama");
            String current = branch;
            confirm.showAndWait().filter(bt -> bt == ButtonType.OK).ifPresent(bt ->
                    runTask("Publicando " + current, p -> {
                        git.pushSetUpstream(current, "origin", credentials, p);
                        return null;
                    }, v -> {
                        toast("✓ Rama «" + current + "» publicada en origin");
                        refreshAll();
                    }));
            return;
        }
        runTask("Push en curso…", p -> {
            git.push(credentials, p);
            return null;
        }, v -> {
            toast("✓ Push completado");
            refreshAll();
        });
    }

    /** Push forzado con confirmación (tras squash/amend/rebase). */
    public void pushForce() {
        if (!git.isOpen()) {
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.WARNING,
                "El push forzado sobrescribe la rama remota con la local. Si otros"
                        + " han empujado commits que no tienes, se perderán. ¿Continuar?");
        confirm.setHeaderText("Push forzado");
        confirm.showAndWait().filter(bt -> bt == ButtonType.OK).ifPresent(bt ->
                runTask("Push forzado en curso…", p -> {
                    git.pushForce(credentials, p);
                    return null;
                }, v -> {
                    toast("✓ Push forzado completado");
                    refreshAll();
                }));
    }

    // ── Barra de acciones ────────────────────────────────────────────────

    private Node buildActionBar() {
        branchMenu.getStyleClass().add("branch-menu");

        Button undo = undoButton;
        undo.setTooltip(new Tooltip("Deshacer la última acción"));
        undo.setOnAction(e -> undoLast());
        Button redo = redoButton;
        redo.setTooltip(new Tooltip("Rehacer la última acción deshecha"));
        redo.setOnAction(e -> redoLast());
        updateUndoButtons();

        Button pull = new Button("Pull");
        pull.setOnAction(e -> pull());
        Button push = new Button("Push");
        push.setOnAction(e -> push());

        Button branch = new Button("Rama");
        branch.setTooltip(new Tooltip("Crear una rama nueva desde HEAD"));
        branch.setOnAction(e -> createBranch());
        Button stash = new Button("Stash");
        stash.setTooltip(new Tooltip("Guardar los cambios actuales en el stash"));
        stash.setOnAction(e -> stash());
        Button pop = new Button("Pop");
        pop.setTooltip(new Tooltip("Aplicar y eliminar el último stash"));
        pop.setOnAction(e -> stashPop());

        Button terminal = new Button("Terminal");
        terminal.setTooltip(new Tooltip("Terminal integrada en este repositorio"));
        terminal.setOnAction(e -> toggleTerminal());

        searchField.setPromptText("Buscar commits…");
        searchField.getStyleClass().add("search-field");
        searchField.textProperty().addListener((obs, o, q) -> applySearch(q));
        searchField.setOnAction(e -> nextSearchHit());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox bar = new HBox(6, branchMenu,
                new Separator(Orientation.VERTICAL), undo, redo,
                new Separator(Orientation.VERTICAL), pull, push,
                new Separator(Orientation.VERTICAL), branch, stash, pop,
                new Separator(Orientation.VERTICAL), terminal,
                spacer, searchField);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("action-bar");
        return bar;
    }

    private Node buildColumnHeader() {
        Label graphHeader = new Label("GRAFO");
        graphHeader.prefWidthProperty().bind(laneWidth);
        Label refsHeader = new Label("RAMA / TAG");
        refsHeader.setPrefWidth(190);
        Label messageHeader = new Label("MENSAJE DEL COMMIT");
        HBox header = new HBox(8, graphHeader, refsHeader, messageHeader);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("column-header");
        return header;
    }

    private void updateBranchMenu(List<GitService.BranchInfo> branches) {
        lastBranches = branches;
        String current = branches.stream()
                .filter(GitService.BranchInfo::current)
                .map(GitService.BranchInfo::shortName)
                .findFirst()
                .orElse("HEAD separado");
        branchMenu.setText("⎇ " + current);
        ToggleGroup group = new ToggleGroup();
        branchMenu.getItems().clear();
        for (GitService.BranchInfo b : branches) {
            RadioMenuItem item = new RadioMenuItem(b.shortName());
            item.setToggleGroup(group);
            item.setSelected(b.current());
            item.setOnAction(e -> {
                if (!b.current()) {
                    checkoutBranch(b.shortName());
                }
            });
            branchMenu.getItems().add(item);
        }
        branchMenu.getItems().add(new SeparatorMenuItem());
        MenuItem create = new MenuItem("Nueva rama…");
        create.setOnAction(e -> createBranch());
        branchMenu.getItems().add(create);
    }

    // ── Búsqueda de commits ──────────────────────────────────────────────

    private void applySearch(String query) {
        searchHits = new ArrayList<>();
        searchCursor = -1;
        if (query == null || query.isBlank()) {
            statusLabel.setText("Listo");
            return;
        }
        String q = query.toLowerCase();
        for (int i = 0; i < allRows.size(); i++) {
            CommitInfo c = allRows.get(i).commit();
            if (c.message().toLowerCase().contains(q)
                    || c.author().toLowerCase().contains(q)
                    || c.id().startsWith(q)) {
                searchHits.add(i);
            }
        }
        if (searchHits.isEmpty()) {
            statusLabel.setText("Sin coincidencias para «" + query + "»");
        } else {
            jumpToHit(0);
        }
    }

    private void nextSearchHit() {
        if (!searchHits.isEmpty()) {
            jumpToHit((searchCursor + 1) % searchHits.size());
        }
    }

    private void jumpToHit(int cursor) {
        searchCursor = cursor;
        int index = searchHits.get(cursor);
        historyList.getSelectionModel().select(index);
        historyList.scrollTo(Math.max(0, index - 5));
        statusLabel.setText("Coincidencia " + (cursor + 1) + " de " + searchHits.size());
    }

    // ── Deshacer / Rehacer ───────────────────────────────────────────────

    /** Registra un commit recién creado como acción deshacible. */
    public void recordCommit(String message, String preSha) {
        if (preSha == null) {
            // Primer commit del repo: no hay padre al que volver con reset.
            return;
        }
        undoManager.push(new UndoManager.Action("Commit: " + abbrev(message),
                g -> g.resetSoft(preSha),
                g -> g.commit(message)));
        updateUndoButtons();
        toast("✓ Commit: " + abbrev(message) + " · Ctrl+Z para deshacer");
    }

    private void recordCheckout(String target, String previousRef) {
        if (previousRef == null) {
            return;
        }
        undoManager.push(new UndoManager.Action("Checkout: " + target,
                g -> g.checkout(previousRef),
                g -> g.checkout(target)));
        updateUndoButtons();
        toast("✓ Checkout: " + target + " · Ctrl+Z para deshacer");
    }

    private void recordCreateBranch(String name, String sha, String previousRef) {
        if (sha == null || previousRef == null) {
            return;
        }
        undoManager.push(new UndoManager.Action("Rama: " + name,
                g -> {
                    g.checkout(previousRef);
                    g.deleteBranch(name);
                },
                g -> {
                    g.createBranchAt(name, sha);
                    g.checkout(name);
                }));
        updateUndoButtons();
    }

    private void updateUndoButtons() {
        undoButton.setDisable(!undoManager.canUndo());
        redoButton.setDisable(!undoManager.canRedo());
        String undoLabel = undoManager.undoLabel();
        String redoLabel = undoManager.redoLabel();
        undoButton.setTooltip(new Tooltip(undoLabel == null
                ? "Nada que deshacer" : "Deshacer: " + undoLabel + " (Ctrl+Z)"));
        redoButton.setTooltip(new Tooltip(redoLabel == null
                ? "Nada que rehacer" : "Rehacer: " + redoLabel + " (Ctrl+Y)"));
    }

    public void undoLast() {
        UndoManager.Action action = undoManager.peekUndo();
        if (action == null) {
            return;
        }
        runTask("Deshacer: " + action.label(), p -> {
            action.undo().run(git);
            return null;
        }, v -> {
            undoManager.confirmUndo();
            updateUndoButtons();
            toast("↩ Deshecho: " + action.label());
            refreshAll();
        });
    }

    public void redoLast() {
        UndoManager.Action action = undoManager.peekRedo();
        if (action == null) {
            return;
        }
        runTask("Rehacer: " + action.label(), p -> {
            action.redo().run(git);
            return null;
        }, v -> {
            undoManager.confirmRedo();
            updateUndoButtons();
            toast("↪ Rehecho: " + action.label());
            refreshAll();
        });
    }

    private static String abbrev(String message) {
        String first = message.lines().findFirst().orElse(message);
        return first.length() > 40 ? first.substring(0, 40) + "…" : first;
    }

    void recordAction(String label, UndoManager.GitAction undo,
                      UndoManager.GitAction redo) {
        undoManager.push(new UndoManager.Action(label, undo, redo));
        updateUndoButtons();
        toast("✓ " + label + " · Ctrl+Z para deshacer");
    }

    // ── Cherry-pick / Revert / Reset / Merge ─────────────────────────────

    private void cherryPickCommit(CommitInfo commit) {
        runTask("Cherry-pick " + commit.shortId(), p -> {
            if (!git.isClean()) {
                throw new IllegalStateException(
                        "Hay cambios sin commitear. Haz commit o stash antes del cherry-pick.");
            }
            String preSha = git.headSha();
            git.cherryPick(commit.id());
            return preSha;
        }, preSha -> {
            recordAction("Cherry-pick: " + commit.shortId(),
                    g -> g.resetHard(preSha),
                    g -> g.cherryPick(commit.id()));
            refreshAll();
        });
    }

    private void revertCommit(CommitInfo commit) {
        runTask("Revertir " + commit.shortId(), p -> {
            if (!git.isClean()) {
                throw new IllegalStateException(
                        "Hay cambios sin commitear. Haz commit o stash antes de revertir.");
            }
            String preSha = git.headSha();
            git.revert(commit.id());
            return preSha;
        }, preSha -> {
            recordAction("Revert: " + commit.shortId(),
                    g -> g.resetHard(preSha),
                    g -> g.revert(commit.id()));
            refreshAll();
        });
    }

    private void resetToCommit(CommitInfo commit) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "La rama actual pasará a apuntar a " + commit.shortId()
                        + " (reset --hard). Los commits posteriores se podrán"
                        + " recuperar con Deshacer. ¿Continuar?");
        confirm.setHeaderText("Reset de rama");
        confirm.showAndWait().filter(bt -> bt == ButtonType.OK).ifPresent(bt ->
                runTask("Reset a " + commit.shortId(), p -> {
                    if (!git.isClean()) {
                        throw new IllegalStateException(
                                "Hay cambios sin commitear. Haz commit o stash antes del reset.");
                    }
                    String preSha = git.headSha();
                    git.resetHard(commit.id());
                    return preSha;
                }, preSha -> {
                    recordAction("Reset: " + commit.shortId(),
                            g -> g.resetHard(preSha),
                            g -> g.resetHard(commit.id()));
                    refreshAll();
                }));
    }

    /** Fusiona HEAD con su padre (squash), con mensaje editable. */
    private void squashWithParent(CommitInfo commit) {
        runTask("Comprobando squash", p -> {
            String head = git.headSha();
            String preview = git.squashPreview();
            return new String[]{head, preview};
        }, result -> {
            String head = result[0];
            String preview = result[1];
            if (preview == null) {
                new Alert(Alert.AlertType.WARNING,
                        "Solo se puede fusionar HEAD (con exactamente un padre)"
                                + " estando en una rama.").showAndWait();
                return;
            }
            if (!commit.id().equals(head)) {
                new Alert(Alert.AlertType.WARNING,
                        "Solo se puede fusionar el último commit (HEAD) con su padre.")
                        .showAndWait();
                return;
            }
            Dialog<ButtonType> dialog = new Dialog<>();
            dialog.setTitle("Squash");
            dialog.setHeaderText("Fusionar " + commit.shortId()
                    + " con su padre. Mensaje del commit resultante:");
            dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
            TextArea messageArea = new TextArea(preview);
            messageArea.getStyleClass().add("conflict-editor");
            messageArea.setPrefSize(520, 200);
            dialog.getDialogPane().setContent(messageArea);
            var result2 = dialog.showAndWait();
            if (result2.isEmpty() || result2.get() != ButtonType.OK
                    || messageArea.getText().isBlank()) {
                return;
            }
            String message = messageArea.getText().trim();
            runTask("Fusionando commits", p -> {
                if (!git.isClean()) {
                    throw new IllegalStateException(
                            "Hay cambios sin commitear. Haz commit o stash antes del squash.");
                }
                return git.squashHead(message);
            }, oldSha -> {
                recordAction("Squash: " + commit.shortId(),
                        g -> g.resetHard(oldSha),
                        g -> g.squashHead(message));
                refreshAll();
            });
        });
    }

    /**
     * Edita el mensaje de un commit (reword), reescribiendo la historia
     * desde él hasta HEAD.
     */
    private void rewordCommit(CommitInfo commit) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Editar mensaje");
        dialog.setHeaderText("Nuevo mensaje para " + commit.shortId()
                + " (reescribe la historia desde este commit hasta HEAD):");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        TextArea messageArea = new TextArea(commit.message());
        messageArea.getStyleClass().add("conflict-editor");
        messageArea.setPrefSize(520, 160);
        dialog.getDialogPane().setContent(messageArea);
        var result = dialog.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK
                || messageArea.getText().isBlank()) {
            return;
        }
        String message = messageArea.getText().trim();
        runTask("Reescribiendo mensaje", p -> {
            if (!git.isClean()) {
                throw new IllegalStateException(
                        "Hay cambios sin commitear. Haz commit o stash antes.");
            }
            return git.rewordCommit(commit.id(), message);
        }, oldSha -> {
            recordAction("Reword: " + commit.shortId(),
                    g -> g.resetHard(oldSha),
                    g -> g.rewordCommit(commit.id(), message));
            refreshAll();
        });
    }

    /** Rebase interactivo sobre los commits posteriores al seleccionado. */
    private void interactiveRebase(CommitInfo commit) {
        runTask("Cargando rango de rebase", p -> git.rebaseRange(commit.id()),
                range -> {
                    if (range.isEmpty()) {
                        new Alert(Alert.AlertType.WARNING,
                                "No hay commits posteriores a " + commit.shortId()
                                        + " en la rama actual.").showAndWait();
                        return;
                    }
                    if (range.stream().anyMatch(c -> c.parents().size() > 1)) {
                        new Alert(Alert.AlertType.WARNING,
                                "El rango contiene commits de merge: el rebase"
                                        + " interactivo solo admite historia lineal.")
                                .showAndWait();
                        return;
                    }
                    showInteractiveRebaseDialog(commit, range);
                });
    }

    /** Estado editable de una fila del diálogo de rebase interactivo. */
    private static final class RebaseRow {
        final CommitInfo commit;
        final javafx.scene.control.ComboBox<String> action =
                new javafx.scene.control.ComboBox<>();
        final TextField message = new TextField();
        final Button up = new Button("↑");
        final Button down = new Button("↓");
        final HBox node = new HBox(6);

        RebaseRow(CommitInfo commit) {
            this.commit = commit;
            action.getItems().addAll("pick", "reword", "squash", "drop");
            action.getSelectionModel().select("pick");
            action.setPrefWidth(100);
            message.setText(commit.message());
            message.setPromptText("Mensaje (solo reword)");
            message.setDisable(true);
            HBox.setHgrow(message, Priority.ALWAYS);
            action.valueProperty().addListener((obs, o, v) ->
                    message.setDisable(!"reword".equals(v)));
            Label text = new Label(commit.shortId() + " — " + commit.message());
            text.setMinWidth(260);
            node.setAlignment(Pos.CENTER_LEFT);
            node.getChildren().addAll(up, down, action, text, message);
        }
    }

    private void showInteractiveRebaseDialog(CommitInfo base, List<CommitInfo> range) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Rebase interactivo");
        dialog.setHeaderText("Reescribir " + range.size() + " commit(s) sobre "
                + base.shortId() + " (el más nuevo arriba)");
        List<RebaseRow> rows = new ArrayList<>();
        VBox rowsBox = new VBox(4);
        for (CommitInfo c : range) {
            RebaseRow row = new RebaseRow(c);
            row.up.setOnAction(e -> moveRow(rows, rowsBox, rows.indexOf(row), -1));
            row.down.setOnAction(e -> moveRow(rows, rowsBox, rows.indexOf(row), 1));
            rows.add(row);
        }
        rowsBox.getChildren().setAll(rows.stream().map(r -> r.node).toList());
        javafx.scene.control.ScrollPane scroll =
                new javafx.scene.control.ScrollPane(rowsBox);
        scroll.setFitToWidth(true);
        scroll.setPrefSize(760, Math.min(420, 70 + range.size() * 40));
        dialog.getDialogPane().setContent(scroll);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        var result = dialog.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK) {
            return;
        }
        // El plan se aplica del más antiguo al más nuevo.
        List<RebaseRow> applied = new ArrayList<>(rows);
        java.util.Collections.reverse(applied);
        List<GitService.RebasePlanEntry> plan = new ArrayList<>();
        boolean keptSeen = false;
        for (RebaseRow row : applied) {
            String action = row.action.getValue();
            if (!"drop".equals(action)) {
                if (!keptSeen && "squash".equals(action)) {
                    new Alert(Alert.AlertType.WARNING,
                            "El commit más antiguo no puede ser «squash»: no hay"
                                    + " un commit anterior sobre el que fusionar.")
                            .showAndWait();
                    return;
                }
                keptSeen = true;
            }
            if ("reword".equals(action) && row.message.getText().isBlank()) {
                new Alert(Alert.AlertType.WARNING,
                        "El mensaje de «reword» no puede estar vacío ("
                                + row.commit.shortId() + ").").showAndWait();
                return;
            }
            plan.add(new GitService.RebasePlanEntry(row.commit.id(), action,
                    row.message.getText().trim()));
        }
        if (!keptSeen) {
            new Alert(Alert.AlertType.WARNING,
                    "No puedes eliminar (drop) todos los commits del rango.")
                    .showAndWait();
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Esto reescribirá " + range.size() + " commit(s) de la rama actual."
                        + " ¿Continuar? (Se puede deshacer con Ctrl+Z)");
        confirm.setHeaderText("Rebase interactivo");
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        runTask("Rebase interactivo", p -> {
            if (!git.isClean()) {
                throw new IllegalStateException(
                        "Hay cambios sin commitear. Haz commit o stash antes.");
            }
            return git.interactiveRebase(base.id(), plan);
        }, oldSha -> {
            if (oldSha == null) {
                new Alert(Alert.AlertType.INFORMATION,
                        "El rebase se ha detenido por conflictos. Resuélvelos en"
                                + " la pestaña Cambios y continúa, o aborta el rebase.")
                        .showAndWait();
            } else {
                recordAction("Rebase interactivo sobre " + base.shortId(),
                        g -> g.resetHard(oldSha),
                        g -> g.interactiveRebase(base.id(), plan));
            }
            refreshAll();
        });
    }

    private static void moveRow(List<RebaseRow> rows, VBox box, int index, int delta) {
        int target = index + delta;
        if (index < 0 || target < 0 || target >= rows.size()) {
            return;
        }
        RebaseRow row = rows.remove(index);
        rows.add(target, row);
        box.getChildren().setAll(rows.stream().map(r -> r.node).toList());
    }

    /** Crea una etiqueta en un commit (menú contextual del grafo). */
    private void createTagAt(CommitInfo commit) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Nueva etiqueta");
        dialog.setHeaderText("Crear una etiqueta en el commit " + commit.shortId());
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        TextField name = new TextField();
        name.setPromptText("v1.0.0");
        TextField message = new TextField();
        message.setPromptText("Mensaje (opcional, la hace anotada)");
        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.addRow(0, new Label("Nombre:"), name);
        grid.addRow(1, new Label("Mensaje:"), message);
        dialog.getDialogPane().setContent(grid);
        var result = dialog.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK || name.getText().isBlank()) {
            return;
        }
        String tagName = name.getText().trim();
        if (!git.isValidTagName(tagName)) {
            new Alert(Alert.AlertType.ERROR,
                    "Nombre de etiqueta no válido: " + tagName).showAndWait();
            return;
        }
        String tagMessage = message.getText().trim();
        runTask("Creando etiqueta " + tagName, p -> {
            git.createTag(tagName, commit.id(), tagMessage.isEmpty() ? null : tagMessage);
            return null;
        }, v -> {
            recordAction("Etiqueta: " + tagName,
                    g -> g.deleteTag(tagName),
                    g -> g.createTag(tagName, commit.id(),
                            tagMessage.isEmpty() ? null : tagMessage));
            refreshAll();
        });
    }

    /** Elimina una etiqueta (menú contextual del panel de ramas). */
    public void deleteTag(String name) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "¿Eliminar la etiqueta «" + name + "»? Se puede recuperar con Deshacer.");
        confirm.setHeaderText("Eliminar etiqueta");
        confirm.showAndWait().filter(bt -> bt == ButtonType.OK).ifPresent(bt ->
                runTask("Eliminando etiqueta " + name, p -> {
                    String sha = git.tagCommitSha(name);
                    git.deleteTag(name);
                    return sha;
                }, sha -> {
                    recordAction("Eliminar etiqueta: " + name,
                            g -> g.createTag(name, sha, null),
                            g -> g.deleteTag(name));
                    refreshAll();
                }));
    }

    /** Sube todas las etiquetas al remoto (menú Acciones). */
    public void pushTags() {
        if (git.isOpen()) {
            runTask("Push de etiquetas…", p -> {
                git.pushTags(credentials, p);
                return null;
            }, v -> {
                toast("✓ Etiquetas enviadas al remoto");
                refreshAll();
            });
        }
    }

    /** Selecciona la pestaña Cambios del panel derecho (p. ej. tras un merge). */
    public void showChangesTab() {
        rightTabs.getSelectionModel().select(1);
    }

    /** Rebasa la rama actual sobre otra (menú contextual del panel). */
    public void rebaseOnto(String branchName) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "La rama actual se rebasará sobre «" + branchName
                        + "» (reescribe los commits locales). ¿Continuar?");
        confirm.setHeaderText("Rebase");
        confirm.showAndWait().filter(bt -> bt == ButtonType.OK).ifPresent(bt ->
                runTask("Rebase sobre " + branchName, p -> {
                    if (!git.isClean()) {
                        throw new IllegalStateException(
                                "Hay cambios sin commitear. Haz commit o stash antes del rebase.");
                    }
                    String preSha = git.headSha();
                    String status = git.rebase(branchName);
                    return new String[]{preSha, status};
                }, result -> {
                    String status = result[1];
                    if (status.startsWith("STOPPED") || status.startsWith("CONFLICTS")
                            || status.startsWith("FAILED")) {
                        new Alert(Alert.AlertType.WARNING,
                                "El rebase se detuvo por conflictos (" + status + ")."
                                        + " Resuélvelos en la pestaña Cambios y pulsa"
                                        + " Continuar, o Abortar para volver atrás.").showAndWait();
                    } else {
                        String preSha = result[0];
                        recordAction("Rebase sobre: " + branchName,
                                g -> g.resetHard(preSha),
                                g -> g.rebase(branchName));
                        statusLabel.setText("Rebase " + status);
                    }
                    refreshAll();
                }));
    }

    /** Muestra el blame de un archivo en un commit (menú contextual del detalle). */
    public void showBlame(String path, String commitId) {
        runTask("Blame de " + path, p -> git.blame(path, commitId),
                lines -> BlameView.show(root.getScene().getWindow(), path, lines,
                        this::selectCommit));
    }

    /** Selecciona un commit en el historial por su SHA (si está cargado). */
    public void selectCommit(String sha) {
        for (int i = 0; i < allRows.size(); i++) {
            if (allRows.get(i).commit().id().equals(sha)) {
                historyList.getSelectionModel().select(i);
                historyList.scrollTo(Math.max(0, i - 5));
                return;
            }
        }
        statusLabel.setText("El commit " + sha.substring(0, 7)
                + " no está en el historial cargado");
    }

    /** Mergea una rama local en la rama actual (menú contextual del panel). */
    public void mergeBranchInto(String branchName) {
        runTask("Merge " + branchName, p -> {
            if (!git.isClean()) {
                throw new IllegalStateException(
                        "Hay cambios sin commitear. Haz commit o stash antes del merge.");
            }
            String preSha = git.headSha();
            String status = git.merge(branchName);
            return new String[]{preSha, status};
        }, result -> {
            String status = result[1];
            if (status.startsWith("CONFLICTING") || status.startsWith("FAILED")) {
                new Alert(Alert.AlertType.WARNING,
                        "El merge produjo conflictos (" + status + "). Resuélvelos"
                                + " en la pestaña Cambios, o aborta con «git merge --abort»"
                                + " desde la terminal.").showAndWait();
            } else {
                String preSha = result[0];
                recordAction("Merge: " + branchName,
                        g -> g.resetHard(preSha),
                        g -> g.merge(branchName));
            }
            refreshAll();
        });
    }

    // ── Acciones ─────────────────────────────────────────────────────────

    public void checkoutBranch(String name) {
        runTask("Checkout " + name, p -> {
            String previousRef = git.headRef();
            git.checkout(name);
            return previousRef;
        }, previousRef -> {
            recordCheckout(name, previousRef);
            refreshAll();
        });
    }

    public void createBranch() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("Nueva rama");
        dialog.setHeaderText("Crear una rama nueva desde HEAD y cambiar a ella");
        dialog.setContentText("Nombre:");
        dialog.showAndWait().map(String::trim).filter(n -> !n.isEmpty()).ifPresent(name -> {
            if (!git.isValidBranchName(name)) {
                new Alert(Alert.AlertType.ERROR,
                        "Nombre de rama no válido: " + name).showAndWait();
                return;
            }
            runTask("Creando rama " + name, p -> {
                String previousRef = git.headRef();
                String sha = git.headSha();
                git.createBranch(name, true);
                return new String[]{previousRef, sha};
            }, ctx -> {
                recordCreateBranch(name, ctx[1], ctx[0]);
                refreshAll();
            });
        });
    }

    public void stash() {
        runTask("Creando stash", p -> git.stashCreate(null), created -> {
            if (!created) {
                new Alert(Alert.AlertType.INFORMATION,
                        "No hay cambios para guardar en el stash.").showAndWait();
            }
            refreshAll();
        });
    }

    public void stashPop() {
        runTask("Pop de stash", p -> {
            if (git.stashList().isEmpty()) {
                return false;
            }
            git.stashPop(0);
            return true;
        }, popped -> {
            if (!popped) {
                new Alert(Alert.AlertType.INFORMATION,
                        "No hay entradas en el stash.").showAndWait();
            }
            refreshAll();
        });
    }

    /** Muestra u oculta la terminal integrada en la parte inferior. */
    public void toggleTerminal() {
        if (repoPath == null) {
            return;
        }
        if (terminalView == null) {
            terminalView = new TerminalView(repoPath, this::refreshAll, this::toggleTerminal);
            terminalView.setVisible(false);
            terminalView.setManaged(false);
            centerWrap.getChildren().add(terminalView);
        }
        boolean show = !terminalView.isVisible();
        terminalView.setVisible(show);
        terminalView.setManaged(show);
    }

    public void openTerminal() {
        if (repoPath == null) {
            return;
        }
        String dir = repoPath.toString();
        String[][] candidates = {
                {"konsole", "--workdir", dir},
                {"kgx", "--working-directory", dir},
                {"gnome-terminal", "--working-directory", dir},
                {"alacritty", "--working-directory", dir},
                {"xterm"}
        };
        for (String[] cmd : candidates) {
            try {
                new ProcessBuilder(cmd).directory(repoPath.toFile()).start();
                return;
            } catch (IOException ignored) {
                // Probar con el siguiente emulador.
            }
        }
        statusLabel.setText("No se encontró un emulador de terminal");
    }

    // ── Barra de estado ──────────────────────────────────────────────────

    private HBox buildStatusBar() {
        progressBar.setVisible(false);
        progressBar.setPrefWidth(220);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(10, statusLabel, spacer, progressBar);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("status-bar");
        return bar;
    }

    // ── Acciones del menú contextual de commits ──────────────────────────

    private void createBranchAt(CommitInfo commit) {
        createBranchAtSha(commit.id(), commit.shortId());
    }

    private void createBranchAtSha(String sha, String shortSha) {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("Nueva rama");
        dialog.setHeaderText("Crear una rama en el commit " + shortSha);
        dialog.setContentText("Nombre:");
        dialog.showAndWait().map(String::trim).filter(n -> !n.isEmpty()).ifPresent(name -> {
            if (!git.isValidBranchName(name)) {
                new Alert(Alert.AlertType.ERROR,
                        "Nombre de rama no válido: " + name).showAndWait();
                return;
            }
            runTask("Creando rama " + name, p -> {
                git.createBranchAt(name, sha);
                return null;
            }, v -> {
                undoManager.push(new UndoManager.Action("Rama: " + name,
                        g -> g.deleteBranch(name),
                        g -> g.createBranchAt(name, sha)));
                updateUndoButtons();
                refreshAll();
            });
        });
    }

    private void checkoutCommit(CommitInfo commit) {
        checkoutSha(commit.id(), commit.shortId());
    }

    private void checkoutSha(String sha, String shortSha) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Esto dejará HEAD separado en el commit " + shortSha + ". ¿Continuar?");
        confirm.setHeaderText("Checkout de commit");
        confirm.showAndWait().filter(bt -> bt == ButtonType.OK).ifPresent(bt ->
                runTask("Checkout " + shortSha, p -> {
                    String previousRef = git.headRef();
                    git.checkout(sha);
                    return previousRef;
                }, previousRef -> {
                    recordCheckout(shortSha, previousRef);
                    refreshAll();
                }));
    }

    /** Abre el visor de reflog de HEAD. */
    public void openReflog() {
        runTask("Cargando reflog", p -> git.reflog(), entries ->
                new ReflogDialog(getRoot().getScene().getWindow(), entries,
                        new ReflogDialog.ReflogActions() {
                            @Override
                            public void createBranchAt(String sha) {
                                createBranchAtSha(sha, sha.substring(0, 7));
                            }

                            @Override
                            public void checkout(String sha) {
                                checkoutSha(sha, sha.substring(0, 7));
                            }

                            @Override
                            public void copyHash(String sha) {
                                copySha(sha);
                            }
                        }).showAndWait());
    }

    private void copyHash(CommitInfo commit) {
        copySha(commit.id());
    }

    private void copySha(String sha) {
        ClipboardContent content = new ClipboardContent();
        content.putString(sha);
        Clipboard.getSystemClipboard().setContent(content);
        statusLabel.setText("Hash copiado: " + sha.substring(0, 7));
    }

    /** Abre el diálogo de gestión de remotos. */
    public void openRemotes() {
        new RemotesDialog(getRoot().getScene().getWindow(), new RemotesDialog.RemoteActions() {
            @Override
            public List<GitService.RemoteInfo> remotes() {
                return git.remotes();
            }

            @Override
            public void addRemote(String name, String url) throws Exception {
                git.addRemote(name, url);
                refreshAll();
            }

            @Override
            public void removeRemote(String name) throws Exception {
                git.removeRemote(name);
                refreshAll();
            }
        }).showAndWait();
    }
}
