package com.codewitheli.vaultlocal.adapter.ui.screens;

import com.codewitheli.vaultlocal.adapter.ui.Navigator;
import com.codewitheli.vaultlocal.adapter.ui.Ui;
import com.codewitheli.vaultlocal.adapter.ui.UiErrors;
import com.codewitheli.vaultlocal.adapter.ui.controls.PlatformBadge;
import com.codewitheli.vaultlocal.application.port.in.VaultUseCases.SecurityReport;
import com.codewitheli.vaultlocal.domain.entry.Entry;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.List;

/** S9: weak, reused and stale passwords. Only entry names are shown, never passwords. */
public final class ReportScreen implements Navigator.Screen {

    private final BorderPane root = new BorderPane();

    public ReportScreen(Navigator nav) {
        var ctx = nav.context();
        Button back = Ui.button("← Back to vault", "reportBack");
        back.setOnAction(e -> nav.showVault());
        HBox top = new HBox(12, back, Ui.title("Security report"));
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(12, 16, 12, 16));
        top.getStyleClass().add("toolbar");
        root.setTop(top);

        VBox body = new VBox(18);
        body.setPadding(new Insets(20));
        body.setMaxWidth(820);
        body.getChildren().add(new ProgressIndicator());
        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("bare");
        root.setCenter(scroll);

        int staleDays = ctx.services().settings().staleDays();
        ctx.worker().run(() -> ctx.services().reports().securityReport(staleDays), report -> {
            body.getChildren().setAll(summary(report),
                    section("Weak passwords", "Easy to guess. Replace them with generated passwords.", report.weak()));
            VBox reused = new VBox(8, Ui.heading("Reused passwords (" + plural(report.reused().size(), "group") + ")"),
                    Ui.muted("The same password on several accounts: one leak exposes all of them."));
            if (report.reused().isEmpty()) {
                reused.getChildren().add(Ui.muted("None."));
            }
            for (int i = 0; i < report.reused().size(); i++) {
                reused.getChildren().add(group("Group " + (i + 1), report.reused().get(i)));
            }
            body.getChildren().addAll(reused, section("Stale passwords",
                    "Not changed for " + staleDays + " days or more.", report.stale()));
        }, failure -> body.getChildren().setAll(Ui.callout(UiErrors.message(failure), "danger")));
    }

    private static Label summary(SecurityReport r) {
        if (r.isClean()) {
            Label l = Ui.callout("Looking good: no weak, reused or stale passwords.", "ok");
            l.setId("reportSummary");
            return l;
        }
        Label l = Ui.callout(r.weak().size() + " weak · " + plural(r.reused().size(), "reused group") + " · "
                + r.stale().size() + " stale", "warning");
        l.setId("reportSummary");
        return l;
    }

    private static String plural(int n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }

    private static VBox section(String title, String explanation, List<Entry> entries) {
        VBox box = new VBox(8, Ui.heading(title + " (" + entries.size() + ")"), Ui.muted(explanation));
        if (entries.isEmpty()) {
            box.getChildren().add(Ui.muted("None."));
        }
        entries.forEach(e -> box.getChildren().add(line(e)));
        return box;
    }

    private static VBox group(String title, List<Entry> entries) {
        VBox box = new VBox(6, new Label(title));
        box.getStyleClass().add("report-group");
        entries.forEach(e -> box.getChildren().add(line(e)));
        return box;
    }

    private static HBox line(Entry e) {
        HBox row = new HBox(8, new PlatformBadge(e.platform()), new Label(e.accountLabel()),
                Ui.muted(e.platform().displayName() + " · " + e.username()));
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    @Override
    public Parent root() {
        return root;
    }
}
