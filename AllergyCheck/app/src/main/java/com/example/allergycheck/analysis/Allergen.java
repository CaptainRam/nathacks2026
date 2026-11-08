package com.example.allergycheck.analysis;

import androidx.annotation.NonNull;

import java.io.Serializable;

/**
 * One allergen the app can check for: a stable id, a user-facing label, and its position in the
 * dictionary.
 *
 * <p>Instances come from an {@link AllergenDictionary} — they are never constructed by hand, and
 * only ones the dictionary issued may be passed to {@link Analyzer}.
 *
 * <p>Deliberately does <em>not</em> carry the matching vocabulary: these objects travel inside
 * {@link Finding} and {@link AllergenResult}, which are {@link Serializable}, so keeping them to
 * three fields means the whole dictionary never ends up in a serialised stream. The vocabulary
 * lives in {@link AllergenDictionary}, keyed by {@link #id()}.
 */
public final class Allergen implements Serializable, Comparable<Allergen> {

    private static final long serialVersionUID = 1L;

    private final String id;
    private final String label;
    private final int order;

    Allergen(String id, String label, int order) {
        this.id = id;
        this.label = label;
        this.order = order;
    }

    /** Stable identifier, e.g. "TREE_NUTS". Safe to persist or pass between screens. */
    @NonNull
    public String id() {
        return id;
    }

    /** User-facing name, e.g. "Tree nuts". Also the key the chosen allergens are saved under. */
    @NonNull
    public String label() {
        return label;
    }

    /** Position in the dictionary, which is the order results and pickers are shown in. */
    public int order() {
        return order;
    }

    /**
     * Orders by dictionary position. Tie-breaks on {@link #id()} so this stays consistent with
     * {@link #equals(Object)} — {@link Analyzer} keeps selected allergens in a sorted set, and
     * without the tie-break two allergens sharing a position would silently collapse into one,
     * dropping an allergen the user asked about from the report.
     */
    @Override
    public int compareTo(@NonNull Allergen other) {
        int byOrder = Integer.compare(order, other.order);
        return byOrder != 0 ? byOrder : id.compareTo(other.id);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Allergen)) {
            return false;
        }
        return id.equals(((Allergen) other).id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    /** The id, so a {@link Finding} reads as "MILK CONTAINS …". */
    @NonNull
    @Override
    public String toString() {
        return id;
    }
}
