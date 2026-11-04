package com.codewitheli.vaultlocal.adapter.ui.controls;

import com.codewitheli.vaultlocal.application.port.out.PasswordStrengthPort.StrengthResult;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.function.Function;

/** Five-segment strength bar with a label and generic advice. Updates as a {@link SecretField} changes. */
public final class StrengthMeter extends VBox {

    private final Region[] segments = new Region[5];
    private final Label label = new Label();
    private final Label advice = new Label();
    private int score = -1;

    public StrengthMeter() {
        getStyleClass().add("strength-meter");
        HBox bar = new HBox(4);
        bar.setAlignment(Pos.CENTER_LEFT);
        for (int i = 0; i < segments.length; i++) {
            segments[i] = new Region();
            segments[i].getStyleClass().add("strength-segment");
            HBox.setHgrow(segments[i], Priority.ALWAYS);
            segments[i].setMaxWidth(Double.MAX_VALUE);
            bar.getChildren().add(segments[i]);
        }
        HBox row = new HBox(10, bar, label);
        HBox.setHgrow(bar, Priority.ALWAYS);
        row.setAlignment(Pos.CENTER_LEFT);
        label.getStyleClass().add("strength-label");
        label.setMinWidth(80);
        advice.getStyleClass().add("muted");
        advice.setWrapText(true);
        setSpacing(4);
        getChildren().addAll(row, advice);
        show(null);
    }

    /** Re-estimates whenever the field changes. {@code estimator} receives a copy and must not keep it. */
    public void bindTo(SecretField field, Function<com.codewitheli.vaultlocal.domain.secret.Secret, StrengthResult> estimator) {
        field.revisionProperty().addListener((obs, a, b) -> {
            if (field.isEmpty()) {
                show(null);
                return;
            }
            try (var copy = field.copySecret()) {
                show(estimator.apply(copy));
            }
        });
    }

    public void show(StrengthResult result) {
        for (int i = 0; i < segments.length; i++) {
            segments[i].getStyleClass().removeAll("s0", "s1", "s2", "s3", "s4", "on");
        }
        if (result == null) {
            score = -1;
            label.setText("");
            advice.setText("");
            return;
        }
        score = result.score();
        for (int i = 0; i <= score; i++) {
            segments[i].getStyleClass().addAll("on", "s" + score);
        }
        label.setText(result.label());
        String text = result.warning().isEmpty() ? String.join(" ", result.suggestions())
                : result.warning() + " " + String.join(" ", result.suggestions());
        advice.setText(text.strip());
    }

    public int score() {
        return score;
    }
}
