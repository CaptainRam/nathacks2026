package com.example.allergycheck.analysis;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
 * A label's text organised into the parts that matter for allergen checking.
 *
 * <p>Precautionary statements ("may contain", "produced in a facility…") are kept apart from the
 * ingredient list and from definite "Contains:" statements, because they mean different things
 * and must never be blended together in the UI.
 */
public final class ParsedLabel implements Serializable {

    private static final long serialVersionUID = 1L;

    /** What kind of statement a {@link Statement} is. */
    public enum Kind {
        /** "Contains: milk, soy" — a definite declaration. */
        CONTAINS,
        /** "May contain peanuts", "produced in a facility that processes…" — a precaution. */
        MAY_CONTAIN
    }

    /** A "contains" or "may contain" sentence, quoted as read. */
    public static final class Statement implements Serializable {

        private static final long serialVersionUID = 1L;

        private final Kind kind;
        private final String text;

        Statement(Kind kind, String text) {
            this.kind = kind;
            this.text = text;
        }

        public Kind kind() {
            return kind;
        }

        public String text() {
            return text;
        }
    }

    private final String rawText;
    private final boolean ingredientHeadingFound;
    private final List<IngredientItem> ingredients;
    private final List<Statement> statements;

    ParsedLabel(String rawText, boolean ingredientHeadingFound,
                List<IngredientItem> ingredients, List<Statement> statements) {
        this.rawText = rawText;
        this.ingredientHeadingFound = ingredientHeadingFound;
        this.ingredients = Collections.unmodifiableList(ingredients);
        this.statements = Collections.unmodifiableList(statements);
    }

    /** The OCR text this was parsed from, untouched. */
    public String rawText() {
        return rawText;
    }

    /** {@code false} for e.g. a tight crop of just the panel — parsing is then less certain. */
    public boolean ingredientHeadingFound() {
        return ingredientHeadingFound;
    }

    public List<IngredientItem> ingredients() {
        return ingredients;
    }

    public List<Statement> statements() {
        return statements;
    }

    /** True when nothing usable (no ingredients and no statements) could be extracted. */
    public boolean isEmpty() {
        return ingredients.isEmpty() && statements.isEmpty();
    }
}
