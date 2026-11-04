package com.codewitheli.vaultlocal.adapter.ui.components;

import com.codewitheli.vaultlocal.adapter.ui.UiContext;
import com.codewitheli.vaultlocal.adapter.ui.Ui;
import com.codewitheli.vaultlocal.adapter.ui.controls.SecretField;
import com.codewitheli.vaultlocal.adapter.ui.controls.StrengthMeter;
import com.codewitheli.vaultlocal.domain.policy.MasterKeyPolicy;
import com.codewitheli.vaultlocal.domain.secret.Secret;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * S2 / S7 / Settings: choose a master key by typing it (twice, with a strength meter) or by
 * generating one (shown once, with Copy and an "I've saved it" confirmation).
 */
public final class MasterKeyForm extends VBox {

    private final UiContext ctx;
    private final RadioButton typeMode = new RadioButton("Type my own");
    private final RadioButton generateMode = new RadioButton("Generate one for me");
    private final SecretField key = Ui.secret("mkKey", "Master key (at least 12 characters)");
    private final SecretField confirm = Ui.secret("mkConfirm", "Type it again");
    private final StrengthMeter meter = new StrengthMeter();
    private final Label matchStatus = new Label();
    private final Label generatedText = new Label();
    private final CheckBox saved = new CheckBox("I've saved it somewhere safe");
    private final BooleanProperty valid = new SimpleBooleanProperty(false);
    private Secret generated;

    public MasterKeyForm(UiContext ctx) {
        this.ctx = ctx;
        getStyleClass().add("master-key-form");
        setSpacing(12);

        ToggleGroup group = new ToggleGroup();
        typeMode.setToggleGroup(group);
        generateMode.setToggleGroup(group);
        typeMode.setId("mkTypeMode");
        generateMode.setId("mkGenerateMode");
        typeMode.setSelected(true);

        // --- typed
        CheckBox show = Ui.showToggle("mkShow", "Show", key, confirm);
        meter.bindTo(key, ctx.services().generator()::strength);
        matchStatus.setId("mkMatch");
        matchStatus.getStyleClass().add("muted");
        VBox typed = new VBox(10,
                Ui.field("Master key", key),
                meter,
                Ui.field("Confirm master key", confirm),
                new HBox(12, show, matchStatus),
                Ui.muted("At least " + MasterKeyPolicy.MIN_CODE_POINTS + " characters, and not too easy to guess. "
                        + "VaultLocal never stores your master key. It keeps only its length, so the Hint "
                        + "can tell you how many characters it has."));

        // --- generated
        Button generate = Ui.button("Generate", "mkGenerate");
        Button copy = Ui.button("Copy", "mkCopy");
        copy.setDisable(true);
        generatedText.setId("mkGenerated");
        generatedText.getStyleClass().add("generated-key");
        saved.setId("mkSaved");
        saved.setDisable(true);
        VBox generatedBox = new VBox(10,
                new HBox(8, generate, copy),
                generatedText,
                Ui.muted("This is the only time it will be shown. The spaces are only for readability; "
                        + "don't type them. Copying puts it on the clipboard for "
                        + ctx.timings().clipboardClear().toSeconds() + " seconds."),
                saved);

        typed.visibleProperty().bind(typeMode.selectedProperty());
        typed.managedProperty().bind(typed.visibleProperty());
        generatedBox.visibleProperty().bind(generateMode.selectedProperty());
        generatedBox.managedProperty().bind(generatedBox.visibleProperty());

        getChildren().addAll(new HBox(16, typeMode, generateMode), typed, generatedBox);

        generate.setOnAction(e -> {
            clearGenerated();
            generated = ctx.services().generator().generateMasterKey();
            generatedText.setText(grouped(generated));
            copy.setDisable(false);
            saved.setDisable(false);
            saved.setSelected(false);
            generate.setText("Generate another");
            revalidate();
        });
        copy.setOnAction(e -> {
            if (generated != null) {
                ctx.clipboard().copy(generated);
            }
        });

        key.revisionProperty().addListener((o, a, b) -> revalidate());
        confirm.revisionProperty().addListener((o, a, b) -> revalidate());
        saved.selectedProperty().addListener((o, a, b) -> revalidate());
        group.selectedToggleProperty().addListener((o, a, b) -> revalidate());
    }

    private static String grouped(Secret s) {
        StringBuilder sb = new StringBuilder();
        char[] c = s.chars();
        for (int i = 0; i < c.length; i++) {
            if (i > 0 && i % 6 == 0) {
                sb.append(' ');
            }
            sb.append(c[i]);
        }
        return sb.toString();
    }

    private void revalidate() {
        if (typeMode.isSelected()) {
            boolean longEnough = key.codePointCount() >= MasterKeyPolicy.MIN_CODE_POINTS;
            boolean strong = meter.score() >= MasterKeyPolicy.MIN_STRENGTH_SCORE;
            boolean match = !confirm.isEmpty() && key.contentEquals(confirm);
            matchStatus.setText(confirm.isEmpty() ? "" : match ? "Keys match" : "Keys don't match yet");
            valid.set(longEnough && strong && match);
        } else {
            valid.set(generated != null && saved.isSelected());
        }
    }

    public ReadOnlyBooleanProperty validProperty() {
        return valid;
    }

    /** Hands the chosen key over (the caller closes it) and wipes the form. */
    public Secret takeKey() {
        Secret out = typeMode.isSelected() ? key.takeSecret() : generated.copy();
        dispose();
        return out;
    }

    public void focusFirst() {
        key.requestFocus();
    }

    private void clearGenerated() {
        if (generated != null) {
            generated.close();
            generated = null;
        }
        generatedText.setText("");
    }

    /** Wipes typed and generated keys. */
    public void dispose() {
        key.clear();
        confirm.clear();
        clearGenerated();
        saved.setSelected(false);
        revalidate();
    }
}
