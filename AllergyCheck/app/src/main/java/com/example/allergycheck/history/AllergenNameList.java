package com.example.allergycheck.history;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

/**
 * Joins allergen labels into the phrase a history row reads out, e.g. "Milk, Soy, and Wheat".
 *
 * <p>Deliberately takes the conjunction as an argument rather than reaching for {@code Resources}:
 * that keeps the whole class pure, so the comma rules can be covered by a plain JVM unit test.
 */
public final class AllergenNameList {

    private AllergenNameList() {
    }

    /**
     * One name on its own, two joined by {@code andWord} with no comma, three or more with an
     * Oxford comma before the conjunction. Blank entries are skipped; order is preserved, which
     * matters because the caller stored the names in report order (most serious first).
     *
     * @return the joined phrase, or {@code ""} when nothing usable was passed
     */
    @NonNull
    public static String format(@Nullable List<String> names, @NonNull String andWord) {
        if (names == null || names.isEmpty()) {
            return "";
        }
        // Collect first: a blank entry must not leave a dangling comma or conjunction behind.
        List<String> usable = new java.util.ArrayList<>(names.size());
        for (String name : names) {
            if (name != null && !name.trim().isEmpty()) {
                usable.add(name.trim());
            }
        }
        switch (usable.size()) {
            case 0:
                return "";
            case 1:
                return usable.get(0);
            case 2:
                return usable.get(0) + " " + andWord + " " + usable.get(1);
            default:
                StringBuilder out = new StringBuilder();
                for (int i = 0; i < usable.size() - 1; i++) {
                    out.append(usable.get(i)).append(", ");
                }
                // The Oxford comma is already on the end of the loop's last append.
                return out.append(andWord).append(' ').append(usable.get(usable.size() - 1)).toString();
        }
    }
}
