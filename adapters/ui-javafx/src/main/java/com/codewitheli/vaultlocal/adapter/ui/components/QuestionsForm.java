package com.codewitheli.vaultlocal.adapter.ui.components;

import com.codewitheli.vaultlocal.adapter.ui.Ui;
import com.codewitheli.vaultlocal.adapter.ui.controls.SecretField;
import com.codewitheli.vaultlocal.domain.error.ErrorCode;
import com.codewitheli.vaultlocal.domain.error.VaultException;
import com.codewitheli.vaultlocal.domain.recovery.AnswerNormalizer;
import com.codewitheli.vaultlocal.domain.recovery.PredefinedQuestion;
import com.codewitheli.vaultlocal.domain.recovery.SecurityQuestion;
import com.codewitheli.vaultlocal.domain.secret.Secret;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;

/**
 * S3 / Settings: pick three different questions (predefined or custom) and answer each twice.
 * Shows the normalization rules and the "weaker than your master key" warning.
 */
public final class QuestionsForm extends VBox {

    /** A dropdown choice; {@code question == null} means "Write my own…". */
    public record Choice(PredefinedQuestion question) {
        @Override
        public String toString() {
            return question == null ? "Write my own…" : question.text();
        }
    }

    private final List<ComboBox<Choice>> pickers = new ArrayList<>();
    private final List<TextField> customs = new ArrayList<>();
    private final List<SecretField> answers = new ArrayList<>();
    private final List<SecretField> confirms = new ArrayList<>();

    public QuestionsForm() {
        getStyleClass().add("questions-form");
        setSpacing(12);
        List<Choice> choices = new ArrayList<>();
        for (PredefinedQuestion q : PredefinedQuestion.values()) {
            choices.add(new Choice(q));
        }
        choices.add(new Choice(null));
        PredefinedQuestion[] defaults = {PredefinedQuestion.FIRST_PET, PredefinedQuestion.FIRST_CONCERT,
                PredefinedQuestion.FIRST_CAR};

        getChildren().add(Ui.callout("Security questions are weaker than your master key. Anyone who can guess "
                + "or look up all three answers can open your vault, so prefer answers only you know. "
                + "You can even treat them like passwords.", "warning"));

        for (int i = 0; i < SecurityQuestion.COUNT; i++) {
            int n = i + 1;
            ComboBox<Choice> picker = new ComboBox<>();
            picker.setId("q" + n);
            picker.getItems().setAll(choices);
            picker.getSelectionModel().select(new Choice(defaults[i]));
            picker.setMaxWidth(Double.MAX_VALUE);
            TextField custom = new TextField();
            custom.setId("q" + n + "custom");
            custom.setPromptText("Your question (stored unencrypted, so don't put secrets in it)");
            custom.visibleProperty().bind(picker.valueProperty().isEqualTo(new Choice(null)));
            custom.managedProperty().bind(custom.visibleProperty());
            SecretField answer = Ui.secret("q" + n + "answer", "Answer");
            SecretField confirm = Ui.secret("q" + n + "confirm", "Answer again");

            GridPane grid = new GridPane();
            grid.setHgap(8);
            grid.setVgap(6);
            grid.add(answer, 0, 0);
            grid.add(confirm, 1, 0);
            GridPane.setHgrow(answer, Priority.ALWAYS);
            GridPane.setHgrow(confirm, Priority.ALWAYS);

            VBox row = new VBox(6, Ui.heading("Question " + n), picker, custom, grid);
            row.getStyleClass().add("question-row");
            getChildren().add(row);
            pickers.add(picker);
            customs.add(custom);
            answers.add(answer);
            confirms.add(confirm);
        }
        List<SecretField> all = new ArrayList<>(answers);
        all.addAll(confirms);
        CheckBox show = Ui.showToggle("qShow", "Show answers", all.toArray(new SecretField[0]));
        getChildren().addAll(show, Ui.muted("How answers are compared: " + AnswerNormalizer.RULES_TEXT));
    }

    /** The chosen questions; validates that they are three different ones. */
    public List<SecurityQuestion> questions() {
        List<SecurityQuestion> out = new ArrayList<>();
        for (int i = 0; i < pickers.size(); i++) {
            Choice c = pickers.get(i).getValue();
            out.add(c == null || c.question() == null
                    ? SecurityQuestion.custom(customs.get(i).getText())
                    : SecurityQuestion.predefined(c.question()));
        }
        SecurityQuestion.validateSet(out);
        return out;
    }

    /** Checks both copies match, then hands the answers over (the caller closes them). */
    public List<Secret> takeAnswers() {
        for (int i = 0; i < answers.size(); i++) {
            if (answers.get(i).isEmpty() || !answers.get(i).contentEquals(confirms.get(i))) {
                throw new VaultException(ErrorCode.ANSWER_MISMATCH);
            }
        }
        List<Secret> out = new ArrayList<>();
        for (SecretField a : answers) {
            out.add(a.takeSecret());
        }
        confirms.forEach(SecretField::clear);
        return out;
    }

    public void dispose() {
        answers.forEach(SecretField::clear);
        confirms.forEach(SecretField::clear);
    }
}
