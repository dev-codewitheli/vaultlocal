package com.codewitheli.vaultlocal.adapter.ui.screens;

import com.codewitheli.vaultlocal.adapter.ui.Navigator;
import com.codewitheli.vaultlocal.adapter.ui.Ui;
import com.codewitheli.vaultlocal.adapter.ui.UiContext;
import com.codewitheli.vaultlocal.adapter.ui.UiErrors;
import com.codewitheli.vaultlocal.adapter.ui.components.Dialogs;
import com.codewitheli.vaultlocal.adapter.ui.components.MasterKeyForm;
import com.codewitheli.vaultlocal.adapter.ui.components.MasterKeyPrompt;
import com.codewitheli.vaultlocal.adapter.ui.components.QuestionsForm;
import com.codewitheli.vaultlocal.adapter.ui.controls.LockoutCountdown;
import com.codewitheli.vaultlocal.adapter.ui.controls.SecretField;
import com.codewitheli.vaultlocal.application.port.in.VaultUseCases.ImportMode;
import com.codewitheli.vaultlocal.application.port.in.VaultUseCases.ImportResult;
import com.codewitheli.vaultlocal.application.port.in.VaultUseCases.Theme;
import com.codewitheli.vaultlocal.domain.audit.ActivityEvent;
import com.codewitheli.vaultlocal.domain.entry.Entry;
import com.codewitheli.vaultlocal.domain.entry.EntryId;
import com.codewitheli.vaultlocal.domain.error.LockedOutException;
import com.codewitheli.vaultlocal.domain.error.VaultException;
import com.codewitheli.vaultlocal.domain.error.WrongSecretException;
import com.codewitheli.vaultlocal.domain.policy.AutoLockPolicy;
import com.codewitheli.vaultlocal.domain.policy.LockoutChannel;
import com.codewitheli.vaultlocal.domain.policy.StalenessPolicy;
import com.codewitheli.vaultlocal.domain.recovery.SecurityQuestion;
import com.codewitheli.vaultlocal.domain.secret.Secret;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** S10: preferences, credentials, backup, and the activity log. */
public final class SettingsScreen implements Navigator.Screen {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm:ss");

    private final Navigator nav;
    private final UiContext ctx;
    private final BorderPane root = new BorderPane();
    private final Label toast = new Label();

    public SettingsScreen(Navigator nav) {
        this.nav = nav;
        this.ctx = nav.context();
        Button back = Ui.button("← Back to vault", "settingsBack");
        back.setOnAction(e -> nav.showVault());
        toast.setId("settingsToast");
        toast.getStyleClass().add("toast");
        HBox top = new HBox(12, back, Ui.title("Settings"), Ui.spacer(), toast);
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(12, 16, 12, 16));
        top.getStyleClass().add("toolbar");
        root.setTop(top);

        VBox body = new VBox(22, preferences(), security(), backup(), activity());
        body.setPadding(new Insets(20));
        body.setMaxWidth(860);
        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("bare");
        root.setCenter(scroll);
    }

    // ------------------------------------------------------------------ preferences

    private VBox preferences() {
        var settings = ctx.services().settings();
        Spinner<Integer> autoLock = new Spinner<>(AutoLockPolicy.MIN_MINUTES, AutoLockPolicy.MAX_MINUTES,
                settings.autoLockMinutes());
        autoLock.setId("settingAutoLock");
        autoLock.setEditable(true);
        autoLock.valueProperty().addListener((o, a, b) -> settings.setAutoLockMinutes(b));
        Spinner<Integer> stale = new Spinner<>(StalenessPolicy.MIN_DAYS, StalenessPolicy.MAX_DAYS, settings.staleDays(), 15);
        stale.setId("settingStaleDays");
        stale.setEditable(true);
        stale.valueProperty().addListener((o, a, b) -> settings.setStaleDays(b));
        ComboBox<Theme> theme = new ComboBox<>();
        theme.setId("settingTheme");
        theme.getItems().setAll(Theme.values());
        theme.setValue(settings.theme());
        theme.valueProperty().addListener((o, a, b) -> {
            settings.setTheme(b);
            nav.applyTheme(b);
        });

        GridPane grid = new GridPane();
        grid.setHgap(14);
        grid.setVgap(10);
        grid.addRow(0, new Label("Auto-lock after idle (minutes)"), autoLock);
        grid.addRow(1, new Label("Mark passwords stale after (days)"), stale);
        grid.addRow(2, new Label("Theme"), theme);
        return section("Preferences", grid,
                Ui.muted("The vault also locks when the computer sleeps or the screen is locked."));
    }

    // ------------------------------------------------------------------ security

    private VBox security() {
        Button changeKey = Ui.button("Change master key…", "changeMasterKey");
        changeKey.setOnAction(e -> changeMasterKey());
        Button changeQuestions = Ui.button(ctx.services().vault().recoveryEnabled()
                ? "Change security questions…" : "Turn on recovery (security questions)…", "changeQuestions");
        changeQuestions.setOnAction(e -> changeQuestions());
        Button turnOff = Ui.button("Turn off recovery…", "turnOffRecovery");
        turnOff.getStyleClass().add("danger-text");
        turnOff.setVisible(ctx.services().vault().recoveryEnabled());
        turnOff.managedProperty().bind(turnOff.visibleProperty());
        turnOff.setOnAction(e -> Dialogs.confirm(nav, "Turn off recovery?",
                "Your security questions will be deleted. If you then forget your master key, the vault cannot be "
                        + "opened by anyone.", "Turn off", true,
                () -> MasterKeyPrompt.ask(nav, "Turn off recovery", "Enter your master key to confirm.", key -> {
                    ctx.services().vault().turnOffRecovery(key);
                    return true;
                }, ok -> nav.showSettings().showToast("Recovery turned off."))));
        return section("Master key and recovery", new HBox(10, changeKey, changeQuestions, turnOff));
    }

    /**
     * Prepares a credential change on the FX thread (validating the form and taking its secrets)
     * and returns the work to run on the worker thread with the current master key.
     */
    @FunctionalInterface
    private interface CredentialChange {
        java.util.function.Consumer<Secret> prepare();
    }

    private void changeMasterKey() {
        MasterKeyForm form = new MasterKeyForm(ctx);
        credentialsDialog("Change master key",
                "Your vault stays the same; only the key that unlocks it changes.", form, form.validProperty(),
                () -> {
                    Secret next = form.takeKey();
                    return current -> {
                        try (next) {
                            ctx.services().vault().changeMasterKey(current, next);
                        }
                    };
                }, form::dispose, "Master key changed.");
    }

    private void changeQuestions() {
        QuestionsForm form = new QuestionsForm();
        credentialsDialog("Security questions",
                "Choose three questions and answer each twice. This replaces any previous questions.", form,
                null, () -> {
                    List<SecurityQuestion> questions = form.questions();
                    List<Secret> answers = form.takeAnswers();
                    return current -> {
                        try {
                            ctx.services().vault().changeSecurityQuestions(current, questions, answers);
                        } finally {
                            answers.forEach(Secret::close);
                        }
                    };
                }, form::dispose, "Security questions saved.");
    }

    /** A dialog with the current master key (with lockout handling) plus a form. */
    private void credentialsDialog(String title, String explanation, Parent form,
                                   javafx.beans.value.ObservableBooleanValue formValid,
                                   CredentialChange change, Runnable dispose, String done) {
        SecretField current = Ui.secret("currentKey", "Current master key");
        Label error = Ui.error("credentialsError");
        LockoutCountdown countdown = new LockoutCountdown();
        Button cancel = Ui.button("Cancel", "credentialsCancel");
        Button save = Ui.primary("Save", "credentialsSave");
        if (formValid != null) {
            save.disableProperty().bind(javafx.beans.binding.Bindings.not(formValid)
                    .or(countdown.lockedProperty()).or(current.charCountProperty().isEqualTo(0)));
        } else {
            save.disableProperty().bind(countdown.lockedProperty().or(current.charCountProperty().isEqualTo(0)));
        }
        current.disableProperty().bind(countdown.lockedProperty());
        VBox content = new VBox(14, Ui.heading(title), Ui.muted(explanation), form,
                Ui.field("Current master key", current), countdown, error, Ui.buttons(cancel, save));
        content.setPadding(new Insets(22));
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setPrefSize(640, 640);
        scroll.getStyleClass().add("bare");
        Stage stage = nav.dialog(title, scroll);
        stage.setResizable(true);
        stage.setOnHidden(e -> {
            current.clear();
            dispose.run();
        });
        cancel.setOnAction(e -> stage.close());
        javafx.beans.property.BooleanProperty busy = new javafx.beans.property.SimpleBooleanProperty(false);
        save.setOnAction(e -> {
            if (busy.get()) {
                return;
            }
            error.setText("");
            java.util.function.Consumer<Secret> work;
            try {
                work = change.prepare();
            } catch (VaultException ex) {
                error.setText(ex.getMessage());
                return;
            }
            Secret key = current.takeSecret();
            busy.set(true);
            content.setDisable(true);
            ctx.worker().run(() -> {
                try (key) {
                    work.accept(key);
                }
            }, () -> {
                stage.close();
                nav.showSettings().showToast(done);
            }, failure -> {
                busy.set(false);
                content.setDisable(false);
                handleCredentialFailure(failure, error, countdown);
                if (failure instanceof WrongSecretException) {
                    error.setText(error.getText() + " Please fill in the form again.");
                }
            });
        });
        countdown.start(ctx.services().vault().lockoutRemaining(LockoutChannel.MASTER));
        stage.show();
    }

    private void handleCredentialFailure(Throwable failure, Label error, LockoutCountdown countdown) {
        if (failure instanceof WrongSecretException wrong) {
            error.setText(wrong.getMessage());
            countdown.start(wrong.lockout());
        } else if (failure instanceof LockedOutException locked) {
            countdown.start(locked.remaining());
        } else {
            error.setText(UiErrors.message(failure));
        }
    }

    // ------------------------------------------------------------------ backup

    private VBox backup() {
        Button export = Ui.button("Export encrypted backup…", "exportBackup");
        export.setOnAction(e -> exportBackup());
        Button importButton = Ui.button("Import backup…", "importBackup");
        importButton.setOnAction(e -> importBackup());
        return section("Backup", new HBox(10, export, importButton),
                Ui.muted("A .vlbackup file is encrypted with your master key (Argon2id + AES-256-GCM). Keep it "
                        + "somewhere safe, such as a USB drive. To restore it you need the master key that was "
                        + "active when it was made."));
    }

    private void exportBackup() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save encrypted backup");
        chooser.setInitialFileName("vaultlocal-" + java.time.LocalDate.now() + ".vlbackup");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("VaultLocal backup", "*.vlbackup"));
        File file = chooser.showSaveDialog(nav.stage());
        if (file == null) {
            return;
        }
        MasterKeyPrompt.ask(nav, "Export backup", "Enter your master key. It will also protect the backup file.",
                key -> {
                    byte[] data = ctx.services().backups().exportBackup(key);
                    try {
                        Files.write(file.toPath(), data);
                    } catch (IOException ex) {
                        throw new VaultException(com.codewitheli.vaultlocal.domain.error.ErrorCode.STORAGE_FAILURE, ex);
                    }
                    return file.getName();
                }, name -> showToast("Backup saved as " + name + "."));
    }

    private void importBackup() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open backup");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("VaultLocal backup", "*.vlbackup"));
        File file = chooser.showOpenDialog(nav.stage());
        if (file == null) {
            return;
        }
        byte[] data;
        try {
            data = Files.readAllBytes(file.toPath());
        } catch (IOException ex) {
            Dialogs.info(nav, "Import failed", "The file could not be read.");
            return;
        }
        SecretField key = Ui.secret("importKey", "Master key used when the backup was made");
        ToggleGroup mode = new ToggleGroup();
        RadioButton merge = new RadioButton("Merge: add entries that aren't in this vault yet");
        RadioButton replace = new RadioButton("Replace: delete everything in this vault first");
        merge.setToggleGroup(mode);
        replace.setToggleGroup(mode);
        merge.setSelected(true);
        Label error = Ui.error("importError");
        Button cancel = Ui.button("Cancel", "importCancel");
        Button go = Ui.primary("Import", "importGo");
        go.disableProperty().bind(key.charCountProperty().isEqualTo(0));
        VBox content = new VBox(12, Ui.heading("Import " + file.getName()),
                Ui.field("Backup's master key", key), merge, replace, error, Ui.buttons(cancel, go));
        content.setPadding(new Insets(22));
        content.setPrefWidth(460);
        Stage stage = nav.dialog("Import backup", content);
        stage.setOnHidden(e -> key.clear());
        cancel.setOnAction(e -> stage.close());
        Runnable run = () -> {
            ImportMode m = replace.isSelected() ? ImportMode.REPLACE : ImportMode.MERGE;
            Secret k = key.takeSecret();
            go.disableProperty().unbind();
            go.setDisable(true);
            ctx.worker().run(() -> {
                try (k) {
                    return ctx.services().backups().importBackup(data, k, m);
                }
            }, (ImportResult result) -> {
                stage.close();
                showToast("Imported " + result.imported() + " entries, skipped " + result.skipped() + " duplicates.");
            }, failure -> {
                go.setDisable(false);
                error.setText(UiErrors.message(failure));
            });
        };
        go.setOnAction(e -> {
            if (replace.isSelected()) {
                Dialogs.confirm(nav, "Replace the whole vault?",
                        "Every entry currently in this vault will be deleted and replaced by the backup.",
                        "Replace", true, run);
            } else {
                run.run();
            }
        });
        stage.show();
    }

    // ------------------------------------------------------------------ activity log

    private VBox activity() {
        TableView<ActivityEvent> table = new TableView<>();
        table.setId("activityLog");
        table.setPrefHeight(260);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(Ui.muted("No activity yet."));
        TableColumn<ActivityEvent, String> when = new TableColumn<>("When");
        when.setCellValueFactory(c -> new ReadOnlyStringWrapper(TIME.format(c.getValue().occurredAt().atZone(ZoneId.systemDefault()))));
        when.setPrefWidth(170);
        TableColumn<ActivityEvent, String> what = new TableColumn<>("Event");
        what.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().type().description()));
        what.setPrefWidth(260);
        TableColumn<ActivityEvent, String> entry = new TableColumn<>("Entry");
        table.getColumns().setAll(List.of(when, what, entry));

        ctx.worker().run(() -> new Object[]{ctx.services().settings().recentActivity(200),
                ctx.services().entries().listEntries()}, loaded -> {
            @SuppressWarnings("unchecked") List<ActivityEvent> events = (List<ActivityEvent>) loaded[0];
            @SuppressWarnings("unchecked") List<Entry> entries = (List<Entry>) loaded[1];
            Map<EntryId, String> labels = entries.stream()
                    .collect(Collectors.toMap(Entry::id, Entry::accountLabel, (a, b) -> a));
            entry.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().entryId() == null ? ""
                    : labels.getOrDefault(c.getValue().entryId(), "(deleted entry)")));
            table.getItems().setAll(events);
        }, failure -> table.setPlaceholder(Ui.muted(UiErrors.message(failure))));
        return section("Activity log", table,
                Ui.muted("Unlocks, reveals, copies and failed attempts on this computer. Secrets are never logged."));
    }

    // ------------------------------------------------------------------ helpers

    private static VBox section(String title, javafx.scene.Node... content) {
        VBox box = new VBox(10);
        box.getStyleClass().add("settings-section");
        box.getChildren().add(Ui.heading(title));
        box.getChildren().addAll(content);
        return box;
    }

    public void showToast(String text) {
        toast.setText(text);
    }

    @Override
    public Parent root() {
        return root;
    }
}
