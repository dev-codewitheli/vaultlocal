package com.codewitheli.vaultlocal.adapter.ui.screens;

import com.codewitheli.vaultlocal.adapter.ui.Navigator;
import com.codewitheli.vaultlocal.adapter.ui.Ui;
import com.codewitheli.vaultlocal.adapter.ui.UiErrors;
import com.codewitheli.vaultlocal.adapter.ui.components.MasterKeyForm;
import com.codewitheli.vaultlocal.adapter.ui.components.QuestionsForm;
import com.codewitheli.vaultlocal.domain.error.VaultException;
import com.codewitheli.vaultlocal.domain.recovery.SecurityQuestion;
import com.codewitheli.vaultlocal.domain.secret.Secret;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.List;

/** S1 Welcome → S2 Master key → S3 Security questions → S4 Creating vault. */
public final class SetupScreen implements Navigator.Screen {

    private final Navigator nav;
    private final StackPane root = new StackPane();
    private final MasterKeyForm keyForm;
    private final QuestionsForm questionsForm = new QuestionsForm();
    private Secret masterKey;

    public SetupScreen(Navigator nav) {
        this.nav = nav;
        this.keyForm = new MasterKeyForm(nav.context());
        root.getStyleClass().add("screen-center");
        showWelcome();
    }

    private void showWelcome() {
        Button start = Ui.primary("Get started", "setupStart");
        start.setDefaultButton(true);
        start.setOnAction(e -> showMasterKey());
        set(Ui.card(560,
                Ui.title("Welcome to VaultLocal"),
                Ui.body("A password vault that lives only on this computer. Nothing is ever sent anywhere: "
                        + "no cloud, no sync, no telemetry."),
                Ui.body("First you'll create a master key, which unlocks the vault. Then you'll choose three "
                        + "security questions, so you can recover the vault if you forget the key."),
                Ui.buttons(start)));
    }

    private void showMasterKey() {
        Label error = Ui.error("setupKeyError");
        Button next = Ui.primary("Next", "setupKeyNext");
        next.disableProperty().bind(keyForm.validProperty().not());
        next.setOnAction(e -> {
            if (masterKey != null) {
                masterKey.close();
            }
            masterKey = keyForm.takeKey();
            showQuestions();
        });
        VBox header = new VBox(4, Ui.muted("Step 1 of 2"), Ui.title("Create your master key"));
        set(Ui.scrollPage(600, header, keyForm, new VBox(8, error, Ui.buttons(next))));
        keyForm.focusFirst();
    }

    private void showQuestions() {
        Label error = Ui.error("setupQuestionsError");
        CheckBox noRecovery = new CheckBox("Advanced: don't set up recovery (most secure)");
        noRecovery.setId("setupNoRecovery");
        Label noRecoveryWarning = Ui.callout("Without recovery, a forgotten master key means your data is gone "
                + "for good. Nobody, including you, can get it back.", "danger");
        noRecoveryWarning.visibleProperty().bind(noRecovery.selectedProperty());
        noRecoveryWarning.managedProperty().bind(noRecoveryWarning.visibleProperty());
        questionsForm.disableProperty().bind(noRecovery.selectedProperty());

        Button back = Ui.button("Back", "setupQuestionsBack");
        back.setOnAction(e -> {
            discardKey();
            questionsForm.dispose();
            showMasterKey();
        });
        Button create = Ui.primary("Create vault", "setupCreate");
        create.setOnAction(e -> {
            error.setText("");
            boolean recovery = !noRecovery.isSelected();
            List<SecurityQuestion> questions;
            List<Secret> answers;
            try {
                questions = recovery ? questionsForm.questions() : List.of();
                answers = recovery ? questionsForm.takeAnswers() : List.of();
            } catch (VaultException ex) {
                error.setText(ex.getMessage());
                return;
            }
            create(recovery, questions, answers);
        });
        VBox header = new VBox(4, Ui.muted("Step 2 of 2"), Ui.title("Choose your security questions"));
        VBox body = new VBox(14, questionsForm, noRecovery, noRecoveryWarning);
        VBox footer = new VBox(8, error, Ui.buttons(back, create));
        set(Ui.scrollPage(680, header, body, footer));
    }

    private void create(boolean recovery, List<SecurityQuestion> questions, List<Secret> answers) {
        ProgressIndicator spinner = new ProgressIndicator();
        VBox busy = Ui.card(420, Ui.title("Creating your vault…"),
                Ui.muted("Deriving encryption keys. This takes a few seconds on purpose: it makes guessing "
                        + "your master key or answers very slow for an attacker."), spinner);
        busy.setAlignment(Pos.CENTER);
        busy.setId("setupCreating");
        set(busy);
        Secret key = masterKey;
        masterKey = null;
        nav.context().worker().run(() -> {
            try (key) {
                nav.context().services().vault().createVault(key, recovery, questions, answers);
            } finally {
                answers.forEach(Secret::close);
            }
        }, nav::showVault, failure -> {
            String message = UiErrors.message(failure);
            showMasterKey();
            root.lookupAll("#setupKeyError").forEach(n -> ((Label) n).setText(message));
        });
    }

    private void set(Parent content) {
        root.getChildren().setAll(content);
    }

    private void discardKey() {
        if (masterKey != null) {
            masterKey.close();
            masterKey = null;
        }
    }

    @Override
    public Parent root() {
        return root;
    }

    @Override
    public void dispose() {
        discardKey();
        keyForm.dispose();
        questionsForm.dispose();
    }
}
