package com.example.allergycheck.ingredients;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The ingredients read from a label, split into the main ingredients and the additives /
 * chemicals (preservatives, emulsifiers, acids, salts like sodium acetate, …). Both lists keep
 * label order and hold each item exactly as written.
 */
public final class IngredientList {

    private static final IngredientList EMPTY =
            new IngredientList(Collections.<String>emptyList(), Collections.<String>emptyList());

    private final List<String> ingredients;
    private final List<String> additives;

    IngredientList(List<String> ingredients, List<String> additives) {
        this.ingredients = Collections.unmodifiableList(new ArrayList<>(ingredients));
        this.additives = Collections.unmodifiableList(new ArrayList<>(additives));
    }

    static IngredientList empty() {
        return EMPTY;
    }

    /** The main ingredients, in label order. */
    public List<String> ingredients() {
        return ingredients;
    }

    /** Additives and specialised chemicals, in label order. */
    public List<String> additives() {
        return additives;
    }

    /** True when nothing was found at all — e.g. the text had no "Ingredients" heading. */
    public boolean isEmpty() {
        return ingredients.isEmpty() && additives.isEmpty();
    }
}
