package com.codewitheli.vaultlocal.adapter.ui.screens;

import com.codewitheli.vaultlocal.adapter.ui.Navigator;
import com.codewitheli.vaultlocal.adapter.ui.Ui;
import com.codewitheli.vaultlocal.adapter.ui.UiContext;
import com.codewitheli.vaultlocal.adapter.ui.UiErrors;
import com.codewitheli.vaultlocal.adapter.ui.components.Dialogs;
import com.codewitheli.vaultlocal.adapter.ui.components.EntryEditorDialog;
import com.codewitheli.vaultlocal.adapter.ui.components.HistoryDialog;
import com.codewitheli.vaultlocal.adapter.ui.components.MasterKeyPrompt;
import com.codewitheli.vaultlocal.adapter.ui.controls.PlatformBadge;
import com.codewitheli.vaultlocal.domain.entry.Entry;
import com.codewitheli.vaultlocal.domain.entry.EntryId;
import com.codewitheli.vaultlocal.domain.entry.Platform;
import javafx.animation.PauseTransition;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.util.Duration;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * S8, the vault. Columns: Platform | Account label | Username | Password (masked) | Last updated
 * | Actions. Every reveal and copy asks for the master key. Revealed passwords mask again after
 * 15 s, and immediately when the window is minimized or loses focus.
 */
public final class VaultScreen implements Navigator.Screen {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMM d, yyyy");

    private final Navigator nav;
    private final UiContext ctx;
    private final VaultViewModel vm = new VaultViewModel();
    private final BorderPane root = new BorderPane();
    private final TableView<EntryRow> table = new TableView<>();
    private final Label status = new Label();
    private final Label toast = new Label();
    private final ChangeListener<Boolean> maskOnBlur;
    private final ChangeListener<Boolean> maskOnMinimize;
    private int staleDays;

    public VaultScreen(Navigator nav) {
        this.nav = nav;
        this.ctx = nav.context();
        this.staleDays = ctx.services().settings().staleDays();
        root.getStyleClass().add("vault-screen");
        root.setTop(toolbar());
        root.setCenter(buildTable());
        root.setBottom(statusBar());

        maskOnBlur = (o, was, focused) -> {
            if (!focused) {
                vm.maskAll();
            }
        };
        maskOnMinimize = (o, was, minimized) -> {
            if (minimized) {
                vm.maskAll();
            }
        };
        nav.stage().focusedProperty().addListener(maskOnBlur);
        nav.stage().iconifiedProperty().addListener(maskOnMinimize);
        refresh();
    }

    // ------------------------------------------------------------------ layout

    private HBox toolbar() {
        Label title = new Label("VaultLocal");
        title.getStyleClass().add("app-title");

        TextField search = new TextField();
        search.setId("search");
        search.setPromptText("Search platform, label or username");
        search.setPrefWidth(280);
        vm.searchProperty().bind(search.textProperty());

        ComboBox<PlatformChoice> platform = new ComboBox<>();
        platform.setId("platformFilter");
        platform.setPrefWidth(160);
        Runnable fillPlatforms = () -> {
            PlatformChoice selected = platform.getValue();
            platform.getItems().setAll(PlatformChoice.ALL);
            vm.platforms().forEach(p -> platform.getItems().add(new PlatformChoice(p)));
            platform.setValue(selected != null && platform.getItems().contains(selected) ? selected : PlatformChoice.ALL);
        };
        vm.platforms().addListener((javafx.collections.ListChangeListener<Platform>) c -> fillPlatforms.run());
        fillPlatforms.run();
        platform.valueProperty().addListener((o, a, b) -> vm.platformFilterProperty().set(b == null ? null : b.platform()));

        ToggleButton favorites = new ToggleButton("★ Favorites");
        favorites.setId("favoritesOnly");
        vm.favoritesOnlyProperty().bind(favorites.selectedProperty());

        Button add = Ui.primary("+ Add entry", "addEntry");
        add.setOnAction(e -> EntryEditorDialog.open(nav, null, saved -> {
            refresh();
            showToast("Entry added.");
        }));
        Button report = Ui.button("Security report", "openReport");
        report.setOnAction(e -> nav.showReport());
        Button settings = Ui.button("Settings", "openSettings");
        settings.setOnAction(e -> nav.showSettings());
        Button lock = Ui.button("Lock", "lockNow");
        lock.setOnAction(e -> nav.lockNow(false));

        HBox bar = new HBox(10, title, search, platform, favorites, Ui.spacer(), add, report, settings, lock);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(12, 16, 12, 16));
        bar.getStyleClass().add("toolbar");
        return bar;
    }

    private TableView<EntryRow> buildTable() {
        table.setId("entries");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(Ui.muted("No entries yet. Click “+ Add entry” to store your first password."));

        TableColumn<EntryRow, Platform> platform = new TableColumn<>("Platform");
        platform.setCellValueFactory(c -> Bindings.createObjectBinding(() -> c.getValue().entry().platform(),
                c.getValue().entryProperty()));
        platform.setComparator(Comparator.comparing(Platform::displayName, String.CASE_INSENSITIVE_ORDER));
        platform.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Platform p, boolean empty) {
                super.updateItem(p, empty);
                if (empty || p == null) {
                    setGraphic(null);
                    return;
                }
                HBox box = new HBox(8, new PlatformBadge(p), new Label(p.displayName()));
                box.setAlignment(Pos.CENTER_LEFT);
                setGraphic(box);
            }
        });
        platform.setPrefWidth(150);

        TableColumn<EntryRow, Entry> label = new TableColumn<>("Account label");
        label.setCellValueFactory(c -> c.getValue().entryProperty());
        label.setComparator(Comparator.comparing(Entry::accountLabel, String.CASE_INSENSITIVE_ORDER));
        label.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Entry e, boolean empty) {
                super.updateItem(e, empty);
                if (empty || e == null) {
                    setGraphic(null);
                    return;
                }
                HBox box = new HBox(6);
                box.setAlignment(Pos.CENTER_LEFT);
                if (e.favorite()) {
                    Label star = new Label("★");
                    star.getStyleClass().add("favorite-star");
                    box.getChildren().add(star);
                }
                box.getChildren().add(new Label(e.accountLabel()));
                if (VaultViewModel.isStale(e, ctx.clock().now(), staleDays)) {
                    Label stale = new Label("Stale");
                    stale.getStyleClass().addAll("chip", "chip-warn", "stale-badge");
                    stale.setTooltip(new Tooltip("Password not changed for " + staleDays + "+ days"));
                    box.getChildren().add(stale);
                }
                setGraphic(box);
            }
        });
        label.setPrefWidth(200);

        TableColumn<EntryRow, String> username = new TableColumn<>("Username");
        username.setCellValueFactory(c -> Bindings.createStringBinding(() -> c.getValue().entry().username(),
                c.getValue().entryProperty()));
        username.setPrefWidth(190);

        TableColumn<EntryRow, EntryRow> password = new TableColumn<>("Password");
        password.setSortable(false);
        password.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        password.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(EntryRow row, boolean empty) {
                super.updateItem(row, empty);
                if (empty || row == null) {
                    setGraphic(null);
                    return;
                }
                Label value = new Label();
                value.getStyleClass().add("password-cell");
                value.textProperty().bind(row.passwordProperty());
                value.setMinWidth(0);
                HBox.setHgrow(value, javafx.scene.layout.Priority.ALWAYS);
                Label left = new Label();
                left.getStyleClass().addAll("chip", "reveal-countdown");
                left.textProperty().bind(row.secondsLeftProperty().asString().concat("s"));
                left.visibleProperty().bind(row.secondsLeftProperty().greaterThan(0));
                left.managedProperty().bind(left.visibleProperty());
                left.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
                HBox box = new HBox(8, value, left);
                Integer score = row.strengthProperty().get();
                if (score != null) {
                    Label strength = new Label(score <= 1 ? "Weak" : score == 2 ? "Fair" : "Strong");
                    strength.getStyleClass().addAll("chip", score <= 1 ? "chip-danger" : score == 2 ? "chip-warn" : "chip-ok");
                    strength.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
                    box.getChildren().add(strength);
                }
                box.setAlignment(Pos.CENTER_LEFT);
                setGraphic(box);
            }
        });
        password.setPrefWidth(250);
        password.setMinWidth(210);

        TableColumn<EntryRow, Instant> updated = new TableColumn<>("Last updated");
        updated.setCellValueFactory(c -> Bindings.createObjectBinding(() -> c.getValue().entry().updatedAt(),
                c.getValue().entryProperty()));
        updated.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Instant at, boolean empty) {
                super.updateItem(at, empty);
                setText(empty || at == null ? null : DATE.format(at.atZone(ZoneId.systemDefault())));
            }
        });
        updated.setPrefWidth(120);

        TableColumn<EntryRow, EntryRow> actions = new TableColumn<>("Actions");
        actions.setSortable(false);
        actions.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        actions.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(EntryRow row, boolean empty) {
                super.updateItem(row, empty);
                setGraphic(empty || row == null ? null : actionButtons(row));
            }
        });
        actions.setPrefWidth(340);
        actions.setMinWidth(320);

        table.getColumns().setAll(List.of(platform, label, username, password, updated, actions));
        // Right-click: copy the non-secret fields. The URL is never opened by VaultLocal (DESIGN.md §11.12).
        table.setRowFactory(tv -> {
            javafx.scene.control.TableRow<EntryRow> row = new javafx.scene.control.TableRow<>();
            javafx.scene.control.MenuItem copyUser = new javafx.scene.control.MenuItem("Copy username");
            copyUser.setOnAction(e -> copyPlain(row.getItem().entry().username(), "Username copied."));
            javafx.scene.control.MenuItem copyUrl = new javafx.scene.control.MenuItem("Copy website URL");
            copyUrl.setOnAction(e -> copyPlain(row.getItem().entry().details().websiteUrl(), "URL copied."));
            javafx.scene.control.ContextMenu menu = new javafx.scene.control.ContextMenu(copyUser, copyUrl);
            menu.setOnShowing(e -> copyUrl.setDisable(row.getItem() == null
                    || row.getItem().entry().details().websiteUrl() == null));
            row.contextMenuProperty().bind(Bindings.when(row.emptyProperty()).then((javafx.scene.control.ContextMenu) null)
                    .otherwise(menu));
            return row;
        });
        label.setSortType(TableColumn.SortType.ASCENDING);
        table.getSortOrder().add(label);
        vm.sorted().comparatorProperty().bind(Bindings.createObjectBinding(
                () -> VaultViewModel.favoritesFirst(table.getComparator()), table.comparatorProperty()));
        table.setItems(vm.sorted());
        return table;
    }

    private HBox actionButtons(EntryRow row) {
        Entry e = row.entry();
        Button reveal = small("Reveal", "reveal-button");
        reveal.setOnAction(ev -> reveal(e.id(), e.accountLabel()));
        Button copy = small("Copy", "copy-button");
        copy.setOnAction(ev -> copy(e.id(), e.accountLabel()));
        Button edit = small("Edit", "edit-button");
        edit.setOnAction(ev -> EntryEditorDialog.open(nav, row.entry(), saved -> {
            refresh();
            showToast("Changes saved.");
        }));
        Button history = small("History", "history-button");
        history.setOnAction(ev -> HistoryDialog.open(nav, row.entry()));
        Button fav = small(e.favorite() ? "★" : "☆", "favorite-button");
        fav.setTooltip(new Tooltip(e.favorite() ? "Unpin" : "Pin to top"));
        fav.setOnAction(ev -> ctx.worker().run(() -> ctx.services().entries().setFavorite(e.id(), !row.entry().favorite()),
                updated -> {
                    vm.replace(updated, null);
                    table.refresh();
                }, this::showError));
        Button delete = small("Delete", "delete-button");
        delete.getStyleClass().add("danger-text");
        delete.setOnAction(ev -> Dialogs.confirm(nav, "Delete entry?",
                "Delete “" + row.entry().accountLabel() + "” (" + row.entry().platform().displayName()
                        + ")? This also deletes its password history and cannot be undone.",
                "Delete", true, () -> ctx.worker().run(() -> ctx.services().entries().deleteEntry(e.id()),
                        () -> {
                            refresh();
                            showToast("Entry deleted.");
                        }, this::showError)));
        HBox box = new HBox(4, reveal, copy, edit, history, fav, delete);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private static Button small(String text, String styleClass) {
        Button b = new Button(text);
        b.getStyleClass().addAll("small", styleClass);
        b.setFocusTraversable(true);
        return b;
    }

    private HBox statusBar() {
        status.setId("statusText");
        status.getStyleClass().add("muted");
        toast.setId("toast");
        toast.getStyleClass().add("toast");
        HBox bar = new HBox(12, status, Ui.spacer(), toast);
        bar.setPadding(new Insets(8, 16, 8, 16));
        bar.getStyleClass().add("status-bar");
        vm.filtered().addListener((javafx.collections.ListChangeListener<EntryRow>) c -> updateStatus());
        return bar;
    }

    private void updateStatus() {
        int total = vm.rows().size();
        int shown = vm.visibleCount();
        String count = shown == total ? total + (total == 1 ? " entry" : " entries") : shown + " of " + total + " entries";
        status.setText(count + " · Auto-lock after " + ctx.services().settings().autoLockMinutes() + " min idle");
    }

    // ------------------------------------------------------------------ actions

    private void refresh() {
        record Loaded(List<Entry> entries, Map<EntryId, Integer> scores) {
        }
        ctx.worker().run(() -> new Loaded(ctx.services().entries().listEntries(), ctx.services().reports().strengthScores()),
                loaded -> {
                    vm.setEntries(loaded.entries(), loaded.scores());
                    updateStatus();
                }, this::showError);
    }

    private void reveal(EntryId id, String label) {
        MasterKeyPrompt.ask(nav, "Reveal password",
                "Enter your master key to show the password for “" + label + "” for "
                        + ctx.timings().reveal().toSeconds() + " seconds.",
                key -> ctx.services().entries().revealPassword(id, key),
                secret -> {
                    try (secret) {
                        EntryRow row = vm.find(id);
                        if (row != null) {
                            row.reveal(secret, ctx.timings().reveal());
                        }
                    }
                });
    }

    private void copy(EntryId id, String label) {
        MasterKeyPrompt.ask(nav, "Copy password",
                "Enter your master key to copy the password for “" + label + "”.",
                key -> ctx.services().entries().passwordForCopy(id, key),
                secret -> {
                    try (secret) {
                        ctx.clipboard().copy(secret);
                    }
                    showToast("Password copied. The clipboard clears in " + ctx.timings().clipboardClear().toSeconds()
                            + " seconds.");
                });
    }

    private void copyPlain(String value, String message) {
        if (value == null) {
            return;
        }
        javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
        content.putString(value);
        javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
        showToast(message);
    }

    private void showToast(String text) {
        toast.setText(text);
        PauseTransition hide = new PauseTransition(Duration.seconds(6));
        hide.setOnFinished(e -> {
            if (text.equals(toast.getText())) {
                toast.setText("");
            }
        });
        hide.play();
    }

    private void showError(Throwable t) {
        Dialogs.info(nav, "Something went wrong", UiErrors.message(t));
    }

    @Override
    public Parent root() {
        return root;
    }

    @Override
    public void dispose() {
        nav.stage().focusedProperty().removeListener(maskOnBlur);
        nav.stage().iconifiedProperty().removeListener(maskOnMinimize);
        vm.clear();
        table.setItems(null);
    }

    /** Platform filter choice; {@code platform == null} means all. */
    record PlatformChoice(Platform platform) {
        static final PlatformChoice ALL = new PlatformChoice(null);

        @Override
        public String toString() {
            return platform == null ? "All platforms" : platform.displayName();
        }
    }
}
