package com.codewitheli.vaultlocal.adapter.ui.components;

import com.codewitheli.vaultlocal.adapter.ui.Navigator;
import com.codewitheli.vaultlocal.adapter.ui.Ui;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/** Small confirmation dialogs (D2). */
public final class Dialogs {

    private Dialogs() {
    }

    public static void confirm(Navigator nav, String title, String message, String confirmText, boolean danger,
                               Runnable onConfirm) {
        Button cancel = Ui.button("Cancel", "confirmCancel");
        Button ok = Ui.primary(confirmText, "confirmOk");
        if (danger) {
            ok.getStyleClass().add("danger");
        }
        VBox content = new VBox(14, Ui.heading(title), Ui.body(message), Ui.buttons(cancel, ok));
        content.setPadding(new Insets(22));
        content.setPrefWidth(420);
        Stage stage = nav.dialog(title, content);
        cancel.setCancelButton(true);
        cancel.setOnAction(e -> stage.close());
        ok.setOnAction(e -> {
            stage.close();
            onConfirm.run();
        });
        stage.show();
        cancel.requestFocus();
    }

    public static void info(Navigator nav, String title, String message) {
        Button ok = Ui.primary("OK", "infoOk");
        ok.setDefaultButton(true);
        VBox content = new VBox(14, Ui.heading(title), Ui.body(message), Ui.buttons(ok));
        content.setPadding(new Insets(22));
        content.setPrefWidth(420);
        Stage stage = nav.dialog(title, content);
        ok.setOnAction(e -> stage.close());
        stage.show();
    }
}
