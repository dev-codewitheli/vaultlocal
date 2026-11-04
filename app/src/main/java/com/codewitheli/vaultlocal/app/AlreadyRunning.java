package com.codewitheli.vaultlocal.app;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.stage.Stage;

/** Shown when a second copy is started: only one instance may open the vault file. */
public final class AlreadyRunning extends Application {

    static void show(String[] args) {
        Application.launch(AlreadyRunning.class, args);
    }

    @Override
    public void start(Stage stage) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION, "VaultLocal is already running. Switch to the open window.");
        alert.setTitle("VaultLocal");
        alert.setHeaderText(null);
        alert.showAndWait();
        Platform.exit();
    }
}
