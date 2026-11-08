package com.example.allergycheck.analysis.ai;

/** A model prediction about the supplied ingredient only; never contains generated ingredients. */
public final class AiDecision {
    public enum Verdict { RISK, NO_EVIDENCE, UNCERTAIN }

    private final Verdict verdict;
    private final Double score;

    private AiDecision(Verdict verdict, Double score) {
        if (verdict == null) throw new IllegalArgumentException("Missing verdict");
        this.verdict = verdict;
        this.score = score;
    }

    public static AiDecision of(Verdict verdict) {
        return new AiDecision(verdict, null);
    }

    public static AiDecision scored(double score, double threshold) {
        if (!Double.isFinite(score) || score < 0 || score > 1) {
            throw new IllegalArgumentException("The model score must be between 0 and 1");
        }
        if (!Double.isFinite(threshold) || threshold < 0 || threshold > 1) {
            throw new IllegalArgumentException("The decision threshold must be between 0 and 1");
        }
        return new AiDecision(score >= threshold ? Verdict.RISK : Verdict.NO_EVIDENCE, score);
    }

    public Verdict verdict() { return verdict; }

    /** Null for a language-model verdict; otherwise the Decisions prediction in [0, 1]. */
    public Double score() { return score; }
}
