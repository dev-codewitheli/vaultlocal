package com.codewitheli.vaultlocal.domain.recovery;

import com.codewitheli.vaultlocal.domain.error.ErrorCode;
import com.codewitheli.vaultlocal.domain.error.VaultException;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A chosen security question. The question (and custom text) is stored in plaintext because it
 * has to be shown before unlock. Answers are never stored.
 *
 * @param questionKey a {@link PredefinedQuestion} name, or {@link #CUSTOM}
 * @param customText  the user's own question when {@code questionKey} is {@link #CUSTOM}
 */
public record SecurityQuestion(String questionKey, String customText) {

    public static final String CUSTOM = "CUSTOM";
    public static final int COUNT = 3;
    public static final int MAX_CUSTOM_TEXT = 200;

    public SecurityQuestion {
        if (CUSTOM.equals(questionKey)) {
            customText = customText == null ? "" : customText.strip();
            if (customText.length() < 5 || customText.length() > MAX_CUSTOM_TEXT) {
                throw new VaultException(ErrorCode.QUESTIONS_INVALID);
            }
        } else {
            PredefinedQuestion.valueOf(questionKey);
            customText = null;
        }
    }

    public static SecurityQuestion predefined(PredefinedQuestion question) {
        return new SecurityQuestion(question.name(), null);
    }

    public static SecurityQuestion custom(String text) {
        return new SecurityQuestion(CUSTOM, text);
    }

    public String text() {
        return CUSTOM.equals(questionKey) ? customText : PredefinedQuestion.valueOf(questionKey).text();
    }

    /** Exactly three questions, all different. */
    public static void validateSet(List<SecurityQuestion> questions) {
        if (questions == null || questions.size() != COUNT) {
            throw new VaultException(ErrorCode.QUESTIONS_INVALID);
        }
        Set<String> seen = new HashSet<>();
        for (SecurityQuestion q : questions) {
            if (!seen.add(q.text().toLowerCase(Locale.ROOT))) {
                throw new VaultException(ErrorCode.QUESTIONS_INVALID);
            }
        }
    }
}
