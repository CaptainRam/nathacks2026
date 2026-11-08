package com.example.allergycheck.analysis.ai;

import com.example.allergycheck.analysis.Allergen;

import java.util.List;

/** Worker-thread model contract. Every request targets exactly one selected allergen. */
public interface AiModel extends AutoCloseable {
    /** Single-ingredient inference, used by local models and Decisions. */
    AiDecision evaluate(String ingredient, Allergen allergen) throws Exception;

    /** Ordinary cloud LLMs can evaluate the complete ingredient list in one request. */
    default boolean supportsBatch() { return false; }

    /**
     * One result per input ingredient in the original order. A null entry denotes a missing
     * model answer and must remain unchecked in the report, never become a negative result.
     */
    default List<AiDecision> evaluateBatch(List<String> ingredients, Allergen allergen) throws Exception {
        throw new UnsupportedOperationException("This model evaluates one ingredient per request");
    }

    /** Local native models close on their inference worker; cloud clients also allow cancellation. */
    @Override
    default void close() { }
}
