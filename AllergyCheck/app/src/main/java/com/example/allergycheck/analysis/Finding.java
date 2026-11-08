package com.example.allergycheck.analysis;

import java.io.Serializable;

/** One piece of label evidence relating to one allergen. */
public final class Finding implements Serializable {

    private static final long serialVersionUID = 2L;

    /** How strongly the evidence says the allergen is present. Ordered strongest first. */
    public enum Level {
        /** An ingredient or "Contains:" statement names the allergen. */
        CONTAINS,
        /** A precautionary statement ("may contain…") names the allergen. */
        MAY_CONTAIN,
        /** Worth a look but not asserted: an ambiguous ingredient or an uncertain OCR read. */
        POSSIBLE
    }

    /** Where on the label the evidence was found. */
    public enum Source {
        INGREDIENT,
        CONTAINS_STATEMENT,
        MAY_CONTAIN_STATEMENT
    }

    /** Why a {@link Level#POSSIBLE} finding is only possible. */
    public enum Reason {
        NONE,
        /** The ingredient can be made from the allergen but need not be (e.g. "lecithin"). */
        AMBIGUOUS_INGREDIENT,
        /** The text only nearly matches — probably an OCR misread, so it needs a human check. */
        UNCERTAIN_READ,
        /** An AI model flagged this original ingredient; this is an inference, not label proof. */
        AI_RISK,
        /** The model could not rule out this ingredient as a source of the selected allergen. */
        AI_UNCERTAIN,
        /** This ingredient could not be checked, and needs manual review. */
        AI_UNAVAILABLE
    }

    private final Allergen allergen;
    private final Level level;
    private final Source source;
    private final Reason reason;
    private final String matchedTerm;
    private final String evidence;
    private final Double score;

    Finding(Allergen allergen, Level level, Source source, Reason reason, String matchedTerm, String evidence) {
        this(allergen, level, source, reason, matchedTerm, evidence, null);
    }

    Finding(Allergen allergen, Level level, Source source, Reason reason,
            String matchedTerm, String evidence, Double score) {
        this.allergen = allergen;
        this.level = level;
        this.source = source;
        this.reason = reason;
        this.matchedTerm = matchedTerm;
        this.evidence = evidence;
        this.score = score;
    }

    public Allergen allergen() {
        return allergen;
    }

    public Level level() {
        return level;
    }

    public Source source() {
        return source;
    }

    public Reason reason() {
        return reason;
    }

    /** The vocabulary entry that matched, e.g. "casein". */
    public String matchedTerm() {
        return matchedTerm;
    }

    /** The label text it was found in, exactly as read, for quoting back to the user. */
    public String evidence() {
        return evidence;
    }

    /** Optional Decisions-model score, not a calibrated probability of ingredient presence. */
    public Double score() {
        return score;
    }

    @Override
    public String toString() {
        return allergen + " " + level + " (" + source + ", \"" + matchedTerm + "\") in \"" + evidence + "\"";
    }
}
