package com.codewitheli.vaultlocal.adapter.ui;

import com.codewitheli.vaultlocal.adapter.ui.controls.SecretField;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/** Small builders so every screen looks and behaves the same. */
public final class Ui {

    private Ui() {
    }

    public static Label title(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("title");
        l.setWrapText(true);
        return l;
    }

    public static Label heading(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("heading");
        l.setWrapText(true);
        return l;
    }

    public static Label body(String text) {
        Label l = new Label(text);
        l.setWrapText(true);
        l.setMinHeight(Region.USE_PREF_SIZE);
        return l;
    }

    public static Label muted(String text) {
        Label l = body(text);
        l.getStyleClass().add("muted");
        return l;
    }

    public static Label callout(String text, String kind) {
        Label l = body(text);
        l.getStyleClass().addAll("callout", kind);
        l.setMaxWidth(Double.MAX_VALUE);
        return l;
    }

    public static Label error(String id) {
        Label l = new Label();
        l.setId(id);
        l.getStyleClass().add("error-text");
        l.setWrapText(true);
        l.managedProperty().bind(l.visibleProperty());
        l.visibleProperty().bind(l.textProperty().isNotEmpty());
        return l;
    }

    public static Button primary(String text, String id) {
        Button b = new Button(text);
        b.setId(id);
        b.getStyleClass().add("primary");
        b.setDefaultButton(false);
        return b;
    }

    public static Button button(String text, String id) {
        Button b = new Button(text);
        b.setId(id);
        return b;
    }

    public static Button link(String text, String id) {
        Button b = button(text, id);
        b.getStyleClass().add("link");
        return b;
    }

    public static SecretField secret(String id, String prompt) {
        SecretField f = new SecretField();
        f.setId(id);
        f.setPromptText(prompt);
        f.setMaxWidth(Double.MAX_VALUE);
        return f;
    }

    /** "Show" toggle that reveals the given fields while checked. */
    public static CheckBox showToggle(String id, String text, SecretField... fields) {
        CheckBox c = new CheckBox(text);
        c.setId(id);
        for (SecretField f : fields) {
            f.revealedProperty().bind(c.selectedProperty());
        }
        return c;
    }

    public static VBox field(String label, Node input) {
        Label l = new Label(label);
        l.getStyleClass().add("field-label");
        l.setLabelFor(input);
        VBox box = new VBox(4, l, input);
        box.setFillWidth(true);
        return box;
    }

    public static HBox buttons(Node... nodes) {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox box = new HBox(8);
        box.getChildren().add(spacer);
        box.getChildren().addAll(nodes);
        box.setAlignment(Pos.CENTER_RIGHT);
        return box;
    }

    public static Region spacer() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        VBox.setVgrow(r, Priority.ALWAYS);
        return r;
    }

    /**
     * A scrollable, horizontally centred page: {@code header} and {@code footer} stay fixed while
     * {@code body} scrolls, so action buttons are always reachable.
     */
    public static javafx.scene.layout.BorderPane scrollPage(double maxWidth, Node header, Node body, Node footer) {
        VBox inner = new VBox(14, body);
        inner.setMaxWidth(maxWidth);
        javafx.scene.layout.StackPane centring = new javafx.scene.layout.StackPane(inner);
        centring.setAlignment(Pos.TOP_CENTER);
        centring.setPadding(new Insets(8, 24, 16, 24));
        javafx.scene.control.ScrollPane scroll = new javafx.scene.control.ScrollPane(centring);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("bare");
        javafx.scene.layout.BorderPane page = new javafx.scene.layout.BorderPane(scroll);
        page.getStyleClass().add("scroll-page");
        if (header != null) {
            VBox top = new VBox(header);
            top.setMaxWidth(maxWidth);
            javafx.scene.layout.StackPane t = new javafx.scene.layout.StackPane(top);
            t.setPadding(new Insets(24, 24, 8, 24));
            page.setTop(t);
        }
        if (footer != null) {
            VBox bottom = new VBox(footer);
            bottom.setMaxWidth(maxWidth);
            javafx.scene.layout.StackPane b = new javafx.scene.layout.StackPane(bottom);
            b.setPadding(new Insets(12, 24, 16, 24));
            b.getStyleClass().add("page-footer");
            page.setBottom(b);
        }
        return page;
    }

    /** A centred card of fixed max width, used by setup/unlock/recovery screens. */
    public static VBox card(double maxWidth, Node... children) {
        VBox card = new VBox(14, children);
        card.getStyleClass().add("card");
        card.setMaxWidth(maxWidth);
        card.setMaxHeight(Region.USE_PREF_SIZE);
        card.setPadding(new Insets(28));
        return card;
    }

    /** A left-to-right row (no leading spacer; add {@link #spacer()} where needed). */
    public static HBox row(Node... nodes) {
        HBox box = new HBox(8, nodes);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }
}
