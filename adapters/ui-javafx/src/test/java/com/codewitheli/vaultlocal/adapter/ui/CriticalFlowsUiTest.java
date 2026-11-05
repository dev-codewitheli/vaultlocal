package com.codewitheli.vaultlocal.adapter.ui;

import com.codewitheli.vaultlocal.adapter.ui.screens.EntryRow;
import com.codewitheli.vaultlocal.domain.secret.Secret;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.input.KeyCode;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.util.WaitForAsyncUtils;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DESIGN.md §8 critical UI flows, against a real vault: setup (typed and generated key), unlock
 * with hint and lockout, add + reveal with auto re-mask, forgot master key, delete confirmation.
 */
@ExtendWith(ApplicationExtension.class)
class CriticalFlowsUiTest {

    @TempDir
    Path dir;

    private UiHarness harness;

    @AfterEach
    void tearDown() throws Exception {
        WaitForAsyncUtils.waitForFxEvents();
        if (harness != null) {
            harness.close();
        }
    }

    private void launch(FxRobot robot, UiHarness h) {
        harness = h;
        robot.interact(() -> {
            Stage stage = new Stage();
            new Navigator(stage, h.ctx).start();
        });
        WaitForAsyncUtils.waitForFxEvents();
    }

    @Test
    void setupWithTypedKeyThenLockHintLockoutAndUnlock(FxRobot robot) {
        launch(robot, new UiHarness(dir));
        robot.clickOn("#setupStart");
        type(robot, "#mkKey", UiHarness.MASTER);
        type(robot, "#mkConfirm", UiHarness.MASTER);
        assertThat(robot.lookup("#mkMatch").queryAs(Label.class).getText()).isEqualTo("Keys match");
        screenshot(robot, "01-setup-master-key");
        click(robot, "#setupKeyNext");
        for (int i = 1; i <= 3; i++) {
            type(robot, "#q" + i + "answer", UiHarness.ANSWERS[i - 1]);
            type(robot, "#q" + i + "confirm", UiHarness.ANSWERS[i - 1]);
        }
        screenshot(robot, "02-setup-questions");
        click(robot, "#setupCreate");
        waitFor(() -> robot.lookup("#entries").tryQuery().isPresent());
        assertThat(harness.services.vault().masterKeyLength()).isEqualTo(UiHarness.MASTER.length());
        screenshot(robot, "03-vault-empty");

        robot.clickOn("#lockNow");
        waitFor(() -> robot.lookup("#unlockKey").tryQuery().isPresent());
        robot.clickOn("#unlockHint");
        assertThat(robot.lookup("#unlockHintText").queryAs(Label.class).getText())
                .isEqualTo("Your master key has " + UiHarness.MASTER.length() + " characters.");

        type(robot, "#unlockKey", "definitely-not-it");
        robot.type(KeyCode.ENTER);
        waitFor(() -> !robot.lookup("#unlockError").queryAs(Label.class).getText().isEmpty());
        assertThat(robot.lookup("#unlockError").queryAs(Label.class).getText()).isEqualTo("The master key is incorrect.");
        assertThat(robot.lookup("#unlockLockout").queryAs(Label.class).isVisible()).isTrue();
        assertThat(robot.lookup("#unlockKey").query().isDisabled()).isTrue();
        screenshot(robot, "04-unlock-wrong-key-lockout");

        waitFor(() -> !robot.lookup("#unlockKey").query().isDisabled());
        type(robot, "#unlockKey", UiHarness.MASTER);
        robot.type(KeyCode.ENTER);
        waitFor(() -> robot.lookup("#entries").tryQuery().isPresent());
    }

    @Test
    void setupWithGeneratedKeyAndRecoveryTurnedOff(FxRobot robot) {
        launch(robot, new UiHarness(dir));
        robot.clickOn("#setupStart");
        click(robot, "#mkGenerateMode");
        click(robot, "#mkGenerate");
        String shown = robot.lookup("#mkGenerated").queryAs(Label.class).getText();
        assertThat(shown.replace(" ", "")).hasSize(24);
        assertThat(robot.lookup("#setupKeyNext").query().isDisabled()).isTrue();
        screenshot(robot, "05-setup-generated-key");
        click(robot, "#mkSaved");
        assertThat(robot.lookup("#setupKeyNext").query().isDisabled()).isFalse();
        click(robot, "#setupKeyNext");
        click(robot, "#setupNoRecovery");
        click(robot, "#setupCreate");
        waitFor(() -> robot.lookup("#entries").tryQuery().isPresent());
        assertThat(harness.services.vault().recoveryEnabled()).isFalse();
        assertThat(harness.services.vault().masterKeyLength()).isEqualTo(24);
        try (Secret key = UiHarness.s(shown.replace(" ", ""))) {
            harness.services.vault().lock(false);
            harness.services.vault().unlock(key);
        }
    }

    @Test
    void addEntryRevealWithMasterKeyThenAutoRemask(FxRobot robot) {
        launch(robot, new UiHarness(dir).withVault());
        unlock(robot);

        robot.clickOn("#addEntry");
        type(robot, "#editLabel", "Personal");
        type(robot, "#editUsername", "me@example.com");
        type(robot, "#editPassword", "S3cret-Gmail-Pass!");
        screenshot(robot, "06-add-entry");
        robot.clickOn("#editSave");
        waitFor(() -> table(robot).getItems().size() == 1);
        assertThat(passwordCellTexts(robot)).containsExactly(EntryRow.MASK);

        robot.clickOn(".reveal-button");
        waitFor(() -> robot.lookup("#promptKey").tryQuery().isPresent());
        robot.clickOn("#promptHint");
        assertThat(robot.lookup("#promptHintText").queryAs(Label.class).getText())
                .isEqualTo("Your master key has " + UiHarness.MASTER.length() + " characters.");
        type(robot, "#promptKey", UiHarness.MASTER);
        screenshot(robot, "07-reveal-prompt");
        robot.clickOn("#promptConfirm");
        waitFor(() -> passwordCellTexts(robot).contains("S3cret-Gmail-Pass!"));
        screenshot(robot, "08-revealed");
        // FAST timings: re-masks after 2 s.
        waitFor(() -> passwordCellTexts(robot).contains(EntryRow.MASK));
        assertThat(passwordCellTexts(robot)).doesNotContain("S3cret-Gmail-Pass!");
    }

    @Test
    void wrongKeyInRevealPromptRevealsNothing(FxRobot robot) {
        launch(robot, new UiHarness(dir).withVault("Work"));
        unlock(robot);
        robot.clickOn(".reveal-button");
        waitFor(() -> robot.lookup("#promptKey").tryQuery().isPresent());
        type(robot, "#promptKey", "wrong wrong wrong");
        robot.clickOn("#promptConfirm");
        waitFor(() -> !robot.lookup("#promptError").queryAs(Label.class).getText().isEmpty());
        assertThat(robot.lookup("#promptLockout").queryAs(Label.class).isVisible()).isTrue();
        robot.clickOn("#promptCancel");
        assertThat(passwordCellTexts(robot)).containsOnly(EntryRow.MASK);
    }

    @Test
    void forgotMasterKeySetsANewOneAndTheOldOneStopsWorking(FxRobot robot) {
        launch(robot, new UiHarness(dir).withVault("Bank"));
        robot.clickOn("#unlockForgot");
        type(robot, "#recoverAnswer1", "  FLUFFY ");
        type(robot, "#recoverAnswer2", "metallica");
        type(robot, "#recoverAnswer3", "toyota   corolla");
        screenshot(robot, "09-recovery-questions");
        click(robot, "#recoverSubmit");
        waitFor(() -> robot.lookup("#mkKey").tryQuery().isPresent());
        type(robot, "#mkKey", "A-Brand-New-Master-Key-7");
        type(robot, "#mkConfirm", "A-Brand-New-Master-Key-7");
        click(robot, "#recoverFinish");
        waitFor(() -> robot.lookup("#entries").tryQuery().isPresent());
        waitFor(() -> table(robot).getItems().size() == 1);

        harness.services.vault().lock(false);
        try (Secret old = UiHarness.s(UiHarness.MASTER)) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> harness.services.vault().unlock(old))
                    .hasMessage("The master key is incorrect.");
        }
    }

    @Test
    void deleteAsksForConfirmation(FxRobot robot) {
        launch(robot, new UiHarness(dir).withVault("Keep me"));
        unlock(robot);
        robot.clickOn(".delete-button");
        waitFor(() -> robot.lookup("#confirmCancel").tryQuery().isPresent());
        screenshot(robot, "10-delete-confirm");
        robot.clickOn("#confirmCancel");
        assertThat(table(robot).getItems()).hasSize(1);
        robot.clickOn(".delete-button");
        waitFor(() -> robot.lookup("#confirmOk").tryQuery().isPresent());
        robot.clickOn("#confirmOk");
        waitFor(() -> table(robot).getItems().isEmpty());
    }

    @Test
    void settingsAndReportScreensRender(FxRobot robot) {
        launch(robot, new UiHarness(dir).withVault("Alpha", "Beta"));
        unlock(robot);
        screenshot(robot, "11-vault-with-entries");
        robot.clickOn("#openReport");
        waitFor(() -> robot.lookup("#reportSummary").tryQuery().isPresent());
        screenshot(robot, "12-report");
        robot.clickOn("#reportBack");
        waitFor(() -> robot.lookup("#entries").tryQuery().isPresent());
        robot.clickOn("#openSettings");
        waitFor(() -> robot.lookup("#activityLog").tryQuery().isPresent());
        waitFor(() -> !((TableView<?>) robot.lookup("#activityLog").query()).getItems().isEmpty());
        screenshot(robot, "13-settings");
    }

    @Test
    void entryEditorLabelsLineUpWithTheFirstLineOfTheirFields(FxRobot robot) {
        launch(robot, new UiHarness(dir).withVault());
        unlock(robot);
        robot.clickOn("#addEntry");
        waitFor(() -> robot.lookup("#editPassword").tryQuery().isPresent());
        javafx.scene.Parent editor = robot.lookup("#editPassword").query().getScene().getRoot();
        screenshot(robot, "14-entry-editor-alignment");

        for (String[] pair : new String[][] {{"Password", "#editPassword"}, {"Notes", "#editNotes"},
                {"Account label", "#editLabel"}}) {
            Label label = robot.from(editor).lookup((Node n) -> n instanceof Label l && l.getText().equals(pair[0]))
                    .queryAs(Label.class);
            Node field = robot.from(editor).lookup(pair[1]).query();
            assertThat(baselineInScene(label)).as(pair[0] + " label vs its field")
                    .isCloseTo(baselineInScene(field), org.assertj.core.data.Offset.offset(2.0));
        }
        // The heading style must not leak into the TitledPane header (it also has style class "title").
        javafx.scene.text.Text generatorTitle = robot.from(editor)
                .lookup((Node n) -> n instanceof javafx.scene.text.Text t && "Password generator".equals(t.getText()))
                .queryAs(javafx.scene.text.Text.class);
        assertThat(generatorTitle.getFont().getSize()).isLessThan(16);
    }

    // ------------------------------------------------------------------ helpers

    private static double baselineInScene(Node n) {
        return n.localToScene(0, n.getBaselineOffset()).getY();
    }

    private void unlock(FxRobot robot) {
        type(robot, "#unlockKey", UiHarness.MASTER);
        robot.type(KeyCode.ENTER);
        waitFor(() -> robot.lookup("#entries").tryQuery().isPresent());
    }

    private static void type(FxRobot robot, String query, String text) {
        click(robot, query);
        robot.write(text);
    }

    /** Scrolls the enclosing ScrollPane (if any) so the node is on screen, then clicks it. */
    private static void click(FxRobot robot, String query) {
        robot.interact(() -> {
            Node n = robot.lookup(query).query();
            javafx.scene.Parent p = n.getParent();
            while (p != null && !(p instanceof javafx.scene.control.ScrollPane)) {
                p = p.getParent();
            }
            if (p instanceof javafx.scene.control.ScrollPane sp) {
                Node content = sp.getContent();
                javafx.geometry.Bounds b = content.sceneToLocal(n.localToScene(n.getBoundsInLocal()));
                double h = content.getBoundsInLocal().getHeight() - sp.getViewportBounds().getHeight();
                if (h > 0) {
                    sp.setVvalue(Math.max(0, Math.min(1, (b.getMinY() - 20) / h)));
                }
            }
        });
        WaitForAsyncUtils.waitForFxEvents();
        robot.clickOn(query);
    }

    @SuppressWarnings("unchecked")
    private static TableView<EntryRow> table(FxRobot robot) {
        return (TableView<EntryRow>) robot.lookup("#entries").query();
    }

    private static java.util.List<String> passwordCellTexts(FxRobot robot) {
        return robot.lookup(".password-cell").queryAllAs(Label.class).stream()
                .filter(Node::isVisible).map(Label::getText).toList();
    }

    private static void waitFor(Callable<Boolean> condition) {
        try {
            WaitForAsyncUtils.waitFor(15, TimeUnit.SECONDS, () -> {
                WaitForAsyncUtils.waitForFxEvents();
                return condition.call();
            });
        } catch (java.util.concurrent.TimeoutException e) {
            throw new AssertionError("Condition not met within 15 s", e);
        }
    }

    private static void screenshot(FxRobot robot, String name) {
        WaitForAsyncUtils.waitForFxEvents();
        Optional<Window> top = robot.listTargetWindows().stream().filter(Window::isShowing)
                .reduce((first, second) -> second);
        top.ifPresent(w -> UiHarness.screenshot(w, name));
    }
}
