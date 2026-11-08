package com.example.allergycheck.analysis;

import java.io.Serializable;

/**
 * One ingredient as it appeared on the label.
 *
 * <p>Nested lists such as {@code Chocolate (sugar, cocoa butter, soy lecithin)} become the parent
 * ({@code depth 0}) followed by each sub-ingredient ({@code depth 1}), in label order.
 */
public final class IngredientItem implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String original;
    private final String matchText;
    private final int depth;

    IngredientItem(String original, String matchText, int depth) {
        this.original = original;
        this.matchText = matchText;
        this.depth = depth;
    }

    /** The text exactly as read from the label (for display and evidence quoting). */
    public String original() {
        return original;
    }

    /**
     * The part of {@link #original()} that names this ingredient itself. For a parent with a
     * bracketed sub-list this excludes the brackets, since children are matched on their own.
     */
    String matchText() {
        return matchText;
    }

    /** 0 for a top-level ingredient, 1+ for sub-ingredients inside brackets. */
    public int depth() {
        return depth;
    }

    @Override
    public String toString() {
        return "IngredientItem{" + depth + ":" + original + "}";
    }
}
