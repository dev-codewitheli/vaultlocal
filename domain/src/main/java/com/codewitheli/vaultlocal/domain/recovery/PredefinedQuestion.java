package com.codewitheli.vaultlocal.domain.recovery;

/** The built-in questions, chosen to be harder to research than "mother's maiden name". */
public enum PredefinedQuestion {
    FIRST_PET("What was the name of your first pet?"),
    FIRST_CONCERT("What was the first concert you attended?"),
    FIRST_CAR("What was the make and model of your first car?"),
    PARENTS_MET_CITY("In what city did your parents meet?"),
    CHILDHOOD_FRIEND_NICKNAME("What was your childhood best friend's nickname?"),
    THIRD_GRADE_STREET("What was the name of the street you lived on in third grade?"),
    FIRST_DISH("What was the first dish you learned to cook?"),
    FAVORITE_TEACHER("What was the name of your favorite teacher in primary school?"),
    FIRST_BOOK("What is the title of the first book you remember loving?");

    private final String text;

    PredefinedQuestion(String text) {
        this.text = text;
    }

    public String text() {
        return text;
    }
}
