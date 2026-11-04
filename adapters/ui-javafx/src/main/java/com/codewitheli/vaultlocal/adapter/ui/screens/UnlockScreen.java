package com.codewitheli.vaultlocal.adapter.ui.screens;

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
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;

/** S5: master key, Unlock, Hint (length only), "Forgot master key?", persisted lockout. */
public final class UnlockScreen implements Navigator.Screen {

    private final StackPane root = new StackPane();
    private final SecretField key = Ui.secret("unlockKey", "Master key");

    public UnlockScreen(Navigator nav) {
        var ctx = nav.context();
        root.getStyleClass().add("screen-center");
        Label error = Ui.error("unlockError");
        Label hint = new Label();
        hint.setId("unlockHintText");
        hint.getStyleClass().add("muted");
        LockoutCountdown countdown = new LockoutCountdown();
        countdown.setId("unlockLockout");
        BooleanProperty busy = new SimpleBooleanProperty(false);
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setPrefSize(18, 18);
        spinner.visibleProperty().bind(busy);

        Button unlock = Ui.primary("Unlock", "unlockButton");
        Button hintButton = Ui.link("Hint", "unlockHint");
        Button forgot = Ui.link("Forgot master key?", "unlockForgot");
        forgot.setVisible(ctx.services().vault().recoveryEnabled());
        forgot.managedProperty().bind(forgot.visibleProperty());

        key.disableProperty().bind(busy.or(countdown.lockedProperty()));
        unlock.disableProperty().bind(busy.or(countdown.lockedProperty()).or(key.charCountProperty().isEqualTo(0)));

        hintButton.setOnAction(e ->
                hint.setText("Your master key has " + ctx.services().vault().masterKeyLength() + " characters."));
        forgot.setOnAction(e -> nav.showRecovery());

        Runnable submit = () -> {
            if (busy.get() || countdown.lockedProperty().get() || key.isEmpty()) {
                return;
            }
            error.setText("");
            busy.set(true);
            Secret entered = key.takeSecret();
            ctx.worker().run(() -> {
                try (entered) {
                    ctx.services().vault().unlock(entered);
                }
            }, nav::showVault, failure -> {
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
        unlock.setOnAction(e -> submit.run());
        key.setOnAction(e -> submit.run());
        countdown.lockedProperty().addListener((o, was, is) -> {
            if (!is) {
                key.requestFocus();
            }
        });

        root.getChildren().add(Ui.card(440,
                Ui.title("VaultLocal is locked"),
                Ui.muted("Enter your master key to open the vault."),
                Ui.field("Master key", key),
                new HBox(8, hintButton, hint),
                countdown,
                error,
                Ui.row(forgot, Ui.spacer(), spinner, unlock)));

        countdown.start(ctx.services().vault().lockoutRemaining(LockoutChannel.MASTER));
        Platform.runLater(key::requestFocus);
    }

    @Override
    public Parent root() {
        return root;
    }

    @Override
    public void dispose() {
        key.clear();
    }
}
