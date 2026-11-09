package com.example.allergycheck.crosscheck;

/** A valid photo-model answer explicitly could not identify a single food category. */
public final class UnknownFoodTypeException extends Exception {
    private static final long serialVersionUID = 1L;

    public UnknownFoodTypeException() {
        super("The photo model could not identify a food type");
    }
}
