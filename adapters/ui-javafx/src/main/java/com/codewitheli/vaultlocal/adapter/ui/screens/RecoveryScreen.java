package com.codewitheli.vaultlocal.adapter.ui.screens;

import com.codewitheli.vaultlocal.adapter.ui.Navigator;
import com.codewitheli.vaultlocal.adapter.ui.Ui;
import com.codewitheli.vaultlocal.adapter.ui.UiErrors;
import com.codewitheli.vaultlocal.adapter.ui.components.MasterKeyForm;
import com.codewitheli.vaultlocal.adapter.ui.controls.LockoutCountdown;
import com.codewitheli.vaultlocal.adapter.ui.controls.SecretField;
import com.codewitheli.vaultlocal.application.port.in.VaultUseCases.RecoveryTicket;
import com.codewitheli.vaultlocal.domain.error.LockedOutException;
import com.codewitheli.vaultlocal.domain.error.WrongSecretException;
import com.codewitheli.vaultlocal.domain.policy.LockoutChannel;
import com.codewitheli.vaultlocal.domain.recovery.AnswerNormalizer;
import com.codewitheli.vaultlocal.domain.recovery.SecurityQuestion;
import com.codewitheli.vaultlocal.domain.secret.Secret;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;

/**
 * S6 answer all three questions → S7 set a new master key. The ticket from S6 (holding the DEK)
 * lives only until S7 finishes or is cancelled; the user can't keep the vault open without
 * setting a new key.
 */
public final class RecoveryScreen implements Navigator.Screen {

    private final Navigator nav;
    private final StackPane root = new StackPane();
    private final List<SecretField> answers = new ArrayList<>();
    private MasterKeyForm keyForm;
    private RecoveryTicket ticket;

    public RecoveryScreen(Navigator nav) {
        this.nav = nav;
        root.getStyleClass().add("screen-center");
        showQuestions();
    }

    private void showQuestions() {
        var ctx = nav.context();
        List<SecurityQuestion> questions = ctx.services().vault().securityQuestions();
        Label error = Ui.error("recoverError");
        LockoutCountdown countdown = new LockoutCountdown();
        countdown.setId("recoverLockout");
        BooleanProperty busy = new SimpleBooleanProperty(false);
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setPrefSize(18, 18);
        spinner.visibleProperty().bind(busy);

        VBox fields = new VBox(12);
        for (int i = 0; i < questions.size(); i++) {
            SecretField answer = Ui.secret("recoverAnswer" + (i + 1), "Answer");
            answer.disableProperty().bind(busy.or(countdown.lockedProperty()));
            answers.add(answer);
            fields.getChildren().add(Ui.field(questions.get(i).text(), answer));
        }
        CheckBox show = Ui.showToggle("recoverShow", "Show answers", answers.toArray(new SecretField[0]));

        Button cancel = Ui.button("Back to unlock", "recoverCancel");
        cancel.setOnAction(e -> nav.showUnlock());
        Button submit = Ui.primary("Continue", "recoverSubmit");
        submit.disableProperty().bind(busy.or(countdown.lockedProperty()));
        submit.setOnAction(e -> {
            error.setText("");
            busy.set(true);
            List<Secret> entered = answers.stream().map(SecretField::takeSecret).toList();
            ctx.worker().run(() -> {
                try {
                    return ctx.services().vault().verifyAnswers(entered);
                } finally {
                    entered.forEach(Secret::close);
                }
            }, t -> {
                ticket = t;
                showNewKey();
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
            });
        });

        VBox header = new VBox(4, Ui.title("Recover your vault"),
                Ui.body("Answer all three security questions. You'll then set a new master key."));
        VBox body = new VBox(12, fields, show, Ui.muted(AnswerNormalizer.RULES_TEXT));
        VBox footer = new VBox(8, countdown, error, Ui.row(cancel, Ui.spacer(), spinner, submit));
        root.getChildren().setAll(Ui.scrollPage(600, header, body, footer));
        countdown.start(ctx.services().vault().lockoutRemaining(LockoutChannel.RECOVERY));
    }

    private void showNewKey() {
        var ctx = nav.context();
        keyForm = new MasterKeyForm(ctx);
        Label error = Ui.error("recoverKeyError");
        Button cancel = Ui.button("Cancel", "recoverKeyCancel");
        cancel.setOnAction(e -> nav.showUnlock());
        Button finish = Ui.primary("Set new master key", "recoverFinish");
        finish.disableProperty().bind(keyForm.validProperty().not());
        finish.setOnAction(e -> {
            Secret newKey = keyForm.takeKey();
            RecoveryTicket t = ticket;
            ticket = null;
            ProgressIndicator spinner = new ProgressIndicator();
            VBox busy = Ui.card(420, Ui.title("Re-encrypting your vault…"),
                    Ui.muted("Your old master key is being replaced, and every entry is re-encrypted with a new key."),
                    spinner);
            busy.setAlignment(Pos.CENTER);
            root.getChildren().setAll(busy);
            ctx.worker().run(() -> {
                try (newKey) {
                    ctx.services().vault().completeRecovery(t, newKey);
                }
            }, nav::showVault, failure -> {
                t.close();
                nav.showUnlock();
                UiErrors.log("Recovery failed", failure);
            });
        });
        VBox header = new VBox(4, Ui.title("Set a new master key"),
                Ui.body("Answers accepted. Choose a new master key; the old one will stop working."));
        root.getChildren().setAll(Ui.scrollPage(600, header, keyForm, new VBox(8, error, Ui.buttons(cancel, finish))));
        keyForm.focusFirst();
    }

    @Override
    public Parent root() {
        return root;
    }

    @Override
    public void dispose() {
        answers.forEach(SecretField::clear);
        if (keyForm != null) {
            keyForm.dispose();
        }
        if (ticket != null) {
            ticket.close();
            ticket = null;
        }
    }
}
