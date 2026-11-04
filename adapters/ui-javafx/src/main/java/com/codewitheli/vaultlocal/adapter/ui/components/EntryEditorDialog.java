package com.codewitheli.vaultlocal.adapter.ui.components;

import com.codewitheli.vaultlocal.adapter.ui.Navigator;
import com.codewitheli.vaultlocal.adapter.ui.Ui;
import com.codewitheli.vaultlocal.adapter.ui.UiErrors;
import com.codewitheli.vaultlocal.adapter.ui.controls.SecretField;
import com.codewitheli.vaultlocal.adapter.ui.controls.StrengthMeter;
import com.codewitheli.vaultlocal.application.port.in.VaultUseCases.GeneratorOptions;
import com.codewitheli.vaultlocal.domain.entry.Entry;
import com.codewitheli.vaultlocal.domain.entry.EntryDetails;
import com.codewitheli.vaultlocal.domain.entry.Platform;
import com.codewitheli.vaultlocal.domain.entry.PlatformKind;
import com.codewitheli.vaultlocal.domain.error.ErrorCode;
import com.codewitheli.vaultlocal.domain.error.VaultException;
import com.codewitheli.vaultlocal.domain.secret.Secret;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.StringConverter;

import java.util.function.Consumer;

/** D1: add or edit an entry, with a password generator and a strength indicator. */
public final class EntryEditorDialog {

    private EntryEditorDialog() {
    }

    /** @param existing {@code null} to add a new entry */
    public static void open(Navigator nav, Entry existing, Consumer<Entry> onSaved) {
        var ctx = nav.context();
        boolean editing = existing != null;

        ComboBox<PlatformKind> platform = new ComboBox<>();
        platform.setId("editPlatform");
        platform.getItems().setAll(PlatformKind.values());
        platform.setConverter(new StringConverter<>() {
            @Override
            public String toString(PlatformKind k) {
                return k == null ? "" : k.displayName();
            }

            @Override
            public PlatformKind fromString(String s) {
                return null;
            }
        });
        TextField customName = new TextField();
        customName.setId("editCustomPlatform");
        customName.setPromptText("Platform name");
        customName.visibleProperty().bind(platform.valueProperty().isEqualTo(PlatformKind.CUSTOM));
        customName.managedProperty().bind(customName.visibleProperty());
        HBox platformRow = new HBox(8, platform, customName);
        HBox.setHgrow(customName, Priority.ALWAYS);

        TextField label = new TextField();
        label.setId("editLabel");
        label.setPromptText("e.g. Personal, Work, Savings");
        TextField username = new TextField();
        username.setId("editUsername");
        SecretField password = Ui.secret("editPassword", editing ? "Leave empty to keep the current password" : "Password");
        CheckBox show = Ui.showToggle("editShowPassword", "Show", password);
        StrengthMeter meter = new StrengthMeter();
        meter.bindTo(password, ctx.services().generator()::strength);
        TextField url = new TextField();
        url.setId("editUrl");
        url.setPromptText("Optional. Never opened by VaultLocal.");
        TextArea notes = new TextArea();
        notes.setId("editNotes");
        notes.setPromptText("Optional");
        notes.setPrefRowCount(3);
        notes.setWrapText(true);
        CheckBox favorite = new CheckBox("Favorite (pinned to the top)");
        favorite.setId("editFavorite");

        // Generator
        Spinner<Integer> length = new Spinner<>(GeneratorOptions.MIN_LENGTH, GeneratorOptions.MAX_LENGTH, 20);
        length.setId("genLength");
        length.setEditable(true);
        length.setPrefWidth(90);
        CheckBox upper = new CheckBox("A–Z");
        CheckBox lower = new CheckBox("a–z");
        CheckBox digits = new CheckBox("0–9");
        CheckBox symbols = new CheckBox("Symbols");
        CheckBox noAmbiguous = new CheckBox("Exclude look-alikes (0 O o 1 l I |)");
        for (CheckBox c : new CheckBox[]{upper, lower, digits, symbols, noAmbiguous}) {
            c.setSelected(true);
        }
        symbols.setId("genSymbols");
        digits.setId("genDigits");
        Button generate = Ui.button("Generate", "genGenerate");
        generate.setOnAction(e -> {
            try (Secret generated = ctx.services().generator().generatePassword(new GeneratorOptions(
                    length.getValue(), upper.isSelected(), lower.isSelected(), digits.isSelected(),
                    symbols.isSelected(), noAmbiguous.isSelected()))) {
                password.setSecret(generated);
            }
        });
        FlowPane genOptions = new FlowPane(10, 8, new Label("Length"), length, upper, lower, digits, symbols, noAmbiguous);
        TitledPane generator = new TitledPane("Password generator", new VBox(8, genOptions, generate));
        generator.setExpanded(false);
        generator.setId("generatorPane");

        if (editing) {
            EntryDetails d = existing.details();
            platform.setValue(d.platform().kind());
            if (d.platform().kind() == PlatformKind.CUSTOM) {
                customName.setText(d.platform().customName());
            }
            label.setText(d.accountLabel());
            username.setText(d.username());
            url.setText(d.websiteUrl() == null ? "" : d.websiteUrl());
            notes.setText(d.notes() == null ? "" : d.notes());
            favorite.setSelected(existing.favorite());
        } else {
            platform.setValue(PlatformKind.GMAIL);
        }

        GridPane form = new GridPane();
        form.setHgap(12);
        form.setVgap(10);
        int r = 0;
        form.addRow(r++, new Label("Platform"), platformRow);
        form.addRow(r++, new Label("Account label"), label);
        form.addRow(r++, new Label("Username / email"), username);
        form.addRow(r++, new Label("Password"), new VBox(6, password, new HBox(12, show), meter));
        form.add(generator, 1, r++);
        form.addRow(r++, new Label("Website URL"), url);
        form.addRow(r++, new Label("Notes"), notes);
        form.add(favorite, 1, r);
        form.getColumnConstraints().addAll(new javafx.scene.layout.ColumnConstraints(120),
                new javafx.scene.layout.ColumnConstraints(340, 400, Double.MAX_VALUE));

        Label error = Ui.error("editError");
        Button cancel = Ui.button("Cancel", "editCancel");
        Button save = Ui.primary(editing ? "Save changes" : "Add entry", "editSave");
        VBox content = new VBox(14, Ui.heading(editing ? "Edit entry" : "Add entry"), form, error, Ui.buttons(cancel, save));
        content.setPadding(new Insets(22));

        Stage stage = nav.dialog(editing ? "Edit entry" : "Add entry", content);
        stage.setOnHidden(e -> password.clear());
        cancel.setCancelButton(true);
        cancel.setOnAction(e -> stage.close());
        save.setOnAction(e -> {
            error.setText("");
            EntryDetails details;
            try {
                PlatformKind kind = platform.getValue();
                Platform p = kind == PlatformKind.CUSTOM ? Platform.custom(customName.getText()) : Platform.of(kind);
                details = new EntryDetails(p, label.getText(), username.getText(), url.getText(), notes.getText());
                if (!editing && password.isEmpty()) {
                    throw new VaultException(ErrorCode.ENTRY_INVALID);
                }
            } catch (VaultException ex) {
                error.setText(ex.getMessage());
                return;
            }
            save.setDisable(true);
            Secret pw = password.isEmpty() ? null : password.takeSecret();
            boolean fav = favorite.isSelected();
            ctx.worker().run(() -> {
                try {
                    return editing
                            ? ctx.services().entries().updateEntry(existing.id(), details, pw, fav)
                            : ctx.services().entries().addEntry(details, pw, fav);
                } finally {
                    if (pw != null) {
                        pw.close();
                    }
                }
            }, saved -> {
                stage.close();
                onSaved.accept(saved);
            }, failure -> {
                save.setDisable(false);
                error.setText(UiErrors.message(failure));
            });
        });
        stage.show();
        label.requestFocus();
    }
}
