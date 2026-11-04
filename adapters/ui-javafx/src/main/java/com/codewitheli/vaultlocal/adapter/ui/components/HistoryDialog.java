package com.codewitheli.vaultlocal.adapter.ui.components;

import com.codewitheli.vaultlocal.adapter.ui.Navigator;
import com.codewitheli.vaultlocal.adapter.ui.Ui;
import com.codewitheli.vaultlocal.adapter.ui.UiErrors;
import com.codewitheli.vaultlocal.adapter.ui.screens.EntryRow;
import com.codewitheli.vaultlocal.domain.entry.Entry;
import com.codewitheli.vaultlocal.domain.entry.PasswordHistoryItem;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** D4: the last five passwords of an entry, masked; each reveal asks for the master key. */
public final class HistoryDialog {

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm");

    private HistoryDialog() {
    }

    public static void open(Navigator nav, Entry entry) {
        var ctx = nav.context();
        VBox list = new VBox(8);
        Button close = Ui.primary("Close", "historyClose");
        VBox content = new VBox(14,
                Ui.heading("Password history: " + entry.accountLabel()),
                Ui.muted("Up to " + PasswordHistoryItem.MAX_KEPT + " previous passwords are kept, encrypted."),
                list, Ui.buttons(close));
        content.setPadding(new Insets(22));
        content.setPrefWidth(480);
        Stage stage = nav.dialog("Password history", content);
        List<EntryRow> holders = new ArrayList<>();
        stage.setOnHidden(e -> holders.forEach(EntryRow::mask));
        close.setOnAction(e -> stage.close());

        ctx.worker().run(() -> ctx.services().entries().passwordHistory(entry.id()), items -> {
            if (items.isEmpty()) {
                list.getChildren().add(Ui.muted("No previous passwords yet."));
            }
            for (PasswordHistoryItem item : items) {
                EntryRow holder = new EntryRow(entry);
                holders.add(holder);
                Label when = new Label("Replaced " + FORMAT.format(item.replacedAt().atZone(ZoneId.systemDefault())));
                when.setMinWidth(200);
                Label value = new Label();
                value.getStyleClass().add("password-cell");
                value.textProperty().bind(holder.passwordProperty());
                Label left = new Label();
                left.getStyleClass().add("muted");
                left.textProperty().bind(holder.secondsLeftProperty().asString().concat("s"));
                left.visibleProperty().bind(holder.secondsLeftProperty().greaterThan(0));
                Button reveal = Ui.button("Reveal", "historyReveal" + item.seq());
                reveal.setOnAction(e -> MasterKeyPrompt.ask(nav, "Reveal old password",
                        "Enter your master key to show this previous password for "
                                + ctx.timings().reveal().toSeconds() + " seconds.",
                        key -> ctx.services().entries().revealHistoricPassword(entry.id(), item.seq(), key),
                        secret -> {
                            try (secret) {
                                holder.reveal(secret, ctx.timings().reveal());
                            }
                        }));
                HBox row = new HBox(10, when, value, left, Ui.spacer(), reveal);
                row.setAlignment(Pos.CENTER_LEFT);
                list.getChildren().add(row);
            }
            stage.sizeToScene();
        }, failure -> list.getChildren().add(Ui.callout(UiErrors.message(failure), "danger")));
        stage.show();
    }
}
