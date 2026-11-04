package com.githistory.ui;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Ejecuta trabajo bloqueante (JGit) en hilos de fondo sin congelar la UI,
 * reportando progreso a la barra de estado y errores en un diálogo.
 * Permite usar un ejecutor serializado por repositorio para que dos
 * operaciones Git nunca corran a la vez sobre el mismo repo.
 */
public final class TaskRunner {

    @FunctionalInterface
    public interface Work<T> {
        T call(BiConsumer<String, Double> progress) throws Exception;
    }

    private static final ExecutorService SHARED = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "git-task-shared");
        thread.setDaemon(true);
        return thread;
    });

    private TaskRunner() {}

    /** Executor de un solo hilo: las tareas encoladas corren una detrás de otra. */
    public static ExecutorService newSerialExecutor(String name) {
        return Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "git-task-" + name);
            thread.setDaemon(true);
            return thread;
        });
    }

    public static <T> void run(String description, Label status, ProgressBar bar,
                               Work<T> work, Consumer<T> onSuccess) {
        run(SHARED, description, status, bar, work, onSuccess, null);
    }

    public static <T> void run(ExecutorService executor, String description, Label status,
                               ProgressBar bar, Work<T> work, Consumer<T> onSuccess) {
        run(executor, description, status, bar, work, onSuccess, null);
    }

    public static <T> void run(ExecutorService executor, String description, Label status,
                               ProgressBar bar, Work<T> work, Consumer<T> onSuccess,
                               Consumer<Throwable> onError) {
        BiConsumer<String, Double> progress = (text, fraction) -> Platform.runLater(() -> {
            if (text != null && !text.isBlank()) {
                status.setText(text);
            }
            bar.setVisible(true);
            bar.setProgress(fraction == null || fraction < 0
                    ? ProgressBar.INDETERMINATE_PROGRESS
                    : Math.min(1.0, fraction));
        });
        executor.execute(() -> {
            progress.accept(description, -1.0);
            try {
                T result = work.call(progress);
                Platform.runLater(() -> {
                    bar.setVisible(false);
                    status.setText("Listo");
                    if (onSuccess != null) {
                        onSuccess.accept(result);
                    }
                });
            } catch (Throwable e) {
                Platform.runLater(() -> {
                    bar.setVisible(false);
                    status.setText("Error");
                    showError(description, e);
                    if (onError != null) {
                        onError.accept(e);
                    }
                });
            }
        });
    }

    private static void showError(String description, Throwable e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            message = e.getClass().getSimpleName();
        }
        Alert alert = new Alert(Alert.AlertType.ERROR, message);
        alert.setTitle("Error");
        alert.setHeaderText(description);
        alert.showAndWait();
    }
}
