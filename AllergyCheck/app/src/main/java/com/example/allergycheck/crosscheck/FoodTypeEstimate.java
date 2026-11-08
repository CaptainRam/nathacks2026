package com.example.allergycheck.crosscheck;

import com.example.allergycheck.analysis.Allergen;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** A food-category estimate, never evidence that this particular product contains an allergen. */
public final class FoodTypeEstimate implements Serializable {
    private static final long serialVersionUID = 1L;
    static final int MAX_FOOD_TYPE_CHARS = 120;

    private final String foodType;
    private final List<Allergen> commonAllergens;

    public FoodTypeEstimate(String foodType, List<Allergen> commonAllergens) {
        if (foodType == null || foodType.trim().isEmpty()
                || foodType.length() > MAX_FOOD_TYPE_CHARS || commonAllergens == null) {
            throw new IllegalArgumentException("A food type and common-allergen list are required");
        }
        for (int i = 0; i < foodType.length(); i++) {
            if (Character.isISOControl(foodType.charAt(i))) {
                throw new IllegalArgumentException("The food type contains control characters");
            }
        }
        Set<String> seen = new HashSet<>();
        List<Allergen> copy = new ArrayList<>();
        for (Allergen allergen : commonAllergens) {
            if (allergen == null || !seen.add(allergen.id())) {
                throw new IllegalArgumentException("Common allergens must be nonnull and unique");
            }
            copy.add(allergen);
        }
        this.foodType = foodType.trim();
        this.commonAllergens = Collections.unmodifiableList(copy);
    }

    public String foodType() { return foodType; }

    /** Only the selected allergens supplied to the photo model can appear here. */
    public List<Allergen> commonAllergens() { return commonAllergens; }
}
