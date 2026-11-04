package com.codewitheli.vaultlocal.adapter.ui.components;

import com.codewitheli.vaultlocal.adapter.ui.Navigator;
import com.codewitheli.vaultlocal.adapter.ui.Ui;
import com.codewitheli.vaultlocal.adapter.ui.UiErrors;
import com.codewitheli.vaultlocal.adapter.ui.controls.LockoutCountdown;
import com.codewitheli.vaultlocal.adapter.ui.controls.SecretField;
import com.codewitheli.vaultlocal.domain.error.LockedOutException;
import com.codewitheli.vaultlocal.domain.error.WrongSecretException;
import com.codewitheli.vaultlocal.domain.policy.LockoutChannel;
import com.codewitheli.vaultlocal.domain.secret.Secret;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * D3: asks for the master key before a reveal, copy, history reveal or sensitive setting.
 * The key is verified inside {@code action} (by the use case) on the worker thread; wrong keys
 * start the persisted MASTER lockout. Offers the Hint (character count only).
 */
public final class MasterKeyPrompt {

    private MasterKeyPrompt() {
    }

    public static <T> void ask(Navigator nav, String title, String purpose, Function<Secret, T> action,
                               Consumer<T> onSuccess) {
        var ctx = nav.context();
        SecretField key = Ui.secret("promptKey", "Master key");
        Label error = Ui.error("promptError");
        Label hint = new Label();
        hint.setId("promptHintText");
        hint.getStyleClass().add("muted");
        hint.managedProperty().bind(hint.visibleProperty());
        hint.setVisible(false);
        LockoutCountdown countdown = new LockoutCountdown();
        countdown.setId("promptLockout");
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setPrefSize(18, 18);
        spinner.setVisible(false);
        BooleanProperty busy = new SimpleBooleanProperty(false);
        spinner.visibleProperty().bind(busy);

        Button hintButton = Ui.link("Hint", "promptHint");
        Button cancel = Ui.button("Cancel", "promptCancel");
        Button confirm = Ui.primary("Confirm", "promptConfirm");
        confirm.setDefaultButton(true);
        key.disableProperty().bind(busy.or(countdown.lockedProperty()));
        confirm.disableProperty().bind(busy.or(countdown.lockedProperty()).or(key.charCountProperty().isEqualTo(0)));

        VBox content = new VBox(12,
                Ui.heading(title),
                Ui.muted(purpose),
                Ui.field("Master key", key),
                new HBox(8, hintButton, hint),
                countdown,
                error,
                Ui.buttons(spinner, cancel, confirm));
        content.setPadding(new Insets(22));
        content.setPrefWidth(420);

        Stage stage = nav.dialog(title, content);
        stage.setOnHidden(e -> key.clear());

        hintButton.setOnAction(e -> {
            hint.setText("Your master key has " + ctx.services().vault().masterKeyLength() + " characters.");
            hint.setVisible(true);
        });
        cancel.setOnAction(e -> stage.close());

        Runnable submit = () -> {
            if (busy.get() || countdown.lockedProperty().get() || key.isEmpty()) {
                return;
            }
            error.setText("");
            busy.set(true);
            Secret entered = key.takeSecret();
            ctx.worker().run(() -> {
                try (entered) {
                    return action.apply(entered);
                }
            }, result -> {
                busy.set(false);
                stage.close();
                onSuccess.accept(result);
            }, failure -> {
                busy.set(false);
                if (failure instanceof WrongSecretException wrong) {
                    error.setText(wrong.getMessage());
                    countdown.start(wrong.lockout());
                } else if (failure instanceof LockedOutException locked) {
                    countdown.start(locked.remaining());
                } else {
                    error.setText(UiErrors.message(failure));
                }
                Platform.runLater(key::requestFocus);
            });
        };
        confirm.setOnAction(e -> submit.run());
        key.setOnAction(e -> submit.run());

        countdown.lockedProperty().addListener((obs, was, is) -> {
            if (!is) {
                key.requestFocus();
            }
        });
        countdown.start(ctx.services().vault().lockoutRemaining(LockoutChannel.MASTER));
        stage.show();
        key.requestFocus();
    }
}
