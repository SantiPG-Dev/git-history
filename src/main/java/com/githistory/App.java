package com.githistory;

import com.githistory.ui.MainView;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.nio.file.Path;
import java.util.Objects;

public class App extends Application {

    @Override
    public void start(Stage stage) {
        MainView mainView = new MainView(stage);
        Scene scene = new Scene(mainView.getRoot(), 1280, 800);
        scene.getStylesheets().add(Objects.requireNonNull(
                getClass().getResource("/com/githistory/app.css")).toExternalForm());
        stage.setTitle("Git History");
        stage.setScene(scene);
        stage.show();

        String repo = System.getenv("GITHISTORY_REPO");
        if (repo != null && !repo.isBlank()) {
            Platform.runLater(() -> {
                for (String path : repo.split(java.io.File.pathSeparator)) {
                    if (!path.isBlank()) {
                        mainView.openRepo(Path.of(path));
                    }
                }
            });
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
