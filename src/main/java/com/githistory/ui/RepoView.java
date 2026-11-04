package com.githistory.ui;

import com.githistory.git.CommitInfo;
import com.githistory.git.GitService;
import com.githistory.git.GraphLayout;
import com.githistory.git.GraphRow;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextInputDialog;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Vista de un repositorio abierto: grafo de historial en el centro, ramas a la
 * izquierda, detalle/staging a la derecha y barra de estado propia abajo.
 * Cada pestaña de la ventana contiene una instancia.
 */
public class RepoView {

    private static final AtomicInteger COUNT = new AtomicInteger();

    private final CredentialsPrompt credentials;
    private final GitService git = new GitService();
    private final ExecutorService executor =
            TaskRunner.newSerialExecutor("repo-" + COUNT.incrementAndGet());
    private final BorderPane root = new BorderPane();
    private final Label statusLabel = new Label("Listo");
    private final Label branchLabel = new Label();
    private final ProgressBar progressBar = new ProgressBar(0);

    private final ListView<GraphRow> historyList;
    private final BranchPanel branchPanel;
    private final CommitDetailView detailView;
    private final StagingView stagingView;
    private int historyMaxLanes = 1;
    private Path repoPath;

    public RepoView(CredentialsPrompt credentials) {
        this.credentials = credentials;

        branchPanel = new BranchPanel(this);
        detailView = new CommitDetailView(this);
        stagingView = new StagingView(this);

        historyList = new ListView<>();
        historyList.setFixedCellSize(HistoryCell.ROW_HEIGHT);
        historyList.setCellFactory(lv -> new HistoryCell(() -> historyMaxLanes,
                new HistoryCell.CommitActions() {
                    @Override
                    public void createBranchAt(CommitInfo commit) {
                        RepoView.this.createBranchAt(commit);
                    }

                    @Override
                    public void checkoutCommit(CommitInfo commit) {
                        RepoView.this.checkoutCommit(commit);
                    }

                    @Override
                    public void copyHash(CommitInfo commit) {
                        RepoView.this.copyHash(commit);
                    }
                }));
        historyList.getSelectionModel().selectedItemProperty().addListener((obs, o, row) -> {
            if (row != null) {
                detailView.showCommit(row.commit());
            }
        });

        Tab commitTab = new Tab("Commit", detailView.getRoot());
        Tab changesTab = new Tab("Cambios", stagingView.getRoot());
        commitTab.setClosable(false);
        changesTab.setClosable(false);
        TabPane rightTabs = new TabPane(commitTab, changesTab);
        rightTabs.setMinWidth(340);

        SplitPane split = new SplitPane(branchPanel.getRoot(), historyList, rightTabs);
        split.setDividerPositions(0.16, 0.58);

        root.setCenter(split);
        root.setBottom(buildStatusBar());
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

    public void close() {
        executor.shutdownNow();
        git.close();
    }

    public <T> void runTask(String description, TaskRunner.Work<T> work, Consumer<T> onSuccess) {
        TaskRunner.run(executor, description, statusLabel, progressBar, work, onSuccess);
    }

    public void openRepo(Path dir, Runnable onFailure) {
        TaskRunner.run(executor, "Abriendo " + dir, statusLabel, progressBar, p -> {
            git.open(dir);
            return null;
        }, v -> {
            repoPath = dir;
            refreshAll();
        }, e -> onFailure.run());
    }

    public void refreshAll() {
        if (!git.isOpen()) {
            return;
        }
        runTask("Actualizando", p -> git.currentBranch(),
                branch -> branchLabel.setText("⎇ " + branch));
        runTask("Cargando historial",
                p -> GraphLayout.layout(git.loadHistory(2000)),
                graph -> {
                    historyMaxLanes = graph.maxLanes();
                    historyList.getItems().setAll(graph.rows());
                    historyList.refresh();
                    if (historyList.getSelectionModel().getSelectedItem() == null
                            && !graph.rows().isEmpty()) {
                        historyList.getSelectionModel().select(0);
                    }
                });
        branchPanel.refresh();
        stagingView.refresh();
    }

    public void fetch() {
        if (git.isOpen()) {
            runTask("Fetch en curso…", p -> {
                git.fetch(credentials, p);
                return null;
            }, v -> refreshAll());
        }
    }

    public void pull() {
        if (git.isOpen()) {
            runTask("Pull en curso…", p -> {
                git.pull(credentials, p);
                return null;
            }, v -> refreshAll());
        }
    }

    public void push() {
        if (git.isOpen()) {
            runTask("Push en curso…", p -> {
                git.push(credentials, p);
                return null;
            }, v -> refreshAll());
        }
    }

    private HBox buildStatusBar() {
        progressBar.setVisible(false);
        progressBar.setPrefWidth(220);
        branchLabel.getStyleClass().add("branch-label");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(10, branchLabel, statusLabel, spacer, progressBar);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("status-bar");
        return bar;
    }

    private void createBranchAt(CommitInfo commit) {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("Nueva rama");
        dialog.setHeaderText("Crear una rama en el commit " + commit.shortId());
        dialog.setContentText("Nombre:");
        dialog.showAndWait().map(String::trim).filter(n -> !n.isEmpty()).ifPresent(name -> {
            if (!git.isValidBranchName(name)) {
                new Alert(Alert.AlertType.ERROR,
                        "Nombre de rama no válido: " + name).showAndWait();
                return;
            }
            runTask("Creando rama " + name, p -> {
                git.createBranchAt(name, commit.id());
                return null;
            }, v -> refreshAll());
        });
    }

    private void checkoutCommit(CommitInfo commit) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Esto dejará HEAD separado en el commit " + commit.shortId() + ". ¿Continuar?");
        confirm.setHeaderText("Checkout de commit");
        confirm.showAndWait().filter(bt -> bt == ButtonType.OK).ifPresent(bt ->
                runTask("Checkout " + commit.shortId(), p -> {
                    git.checkout(commit.id());
                    return null;
                }, v -> refreshAll()));
    }

    private void copyHash(CommitInfo commit) {
        ClipboardContent content = new ClipboardContent();
        content.putString(commit.id());
        Clipboard.getSystemClipboard().setContent(content);
        statusLabel.setText("Hash copiado: " + commit.shortId());
    }
}
