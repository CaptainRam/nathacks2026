package com.example.allergycheck.analysis.ai;

import org.json.JSONException;
import org.junit.Test;

import static org.junit.Assert.*;

public class LocalAllergenPromptTest {
    @Test
    public void acceptsRealGemmaBareVerdictsAndWhitespace() throws Exception {
        assertVerdict(AiDecision.Verdict.NO_EVIDENCE, "no_evidence\n");
        assertVerdict(AiDecision.Verdict.RISK, "risk");
        assertVerdict(AiDecision.Verdict.UNCERTAIN, " \r\nUNCERTAIN\t");
        assertVerdict(AiDecision.Verdict.NO_EVIDENCE, " NO_EVIDENCE ");
    }

    @Test
    public void acceptsSingleVerdictJsonAndCompleteCodeFences() throws Exception {
        assertVerdict(AiDecision.Verdict.RISK, "{\"verdict\":\"risk\"}");
        assertVerdict(AiDecision.Verdict.NO_EVIDENCE,
                "{ \n\"verdict\" : \" NO_EVIDENCE \"\n}");
        assertVerdict(AiDecision.Verdict.RISK, "```json\n{\"verdict\":\"risk\"}\n```");
        assertVerdict(AiDecision.Verdict.UNCERTAIN, " ```JSON\r\n{\"verdict\":\"uncertain\"}\r\n``` ");
        assertVerdict(AiDecision.Verdict.NO_EVIDENCE, "```\nno_evidence\n```");
        assertVerdict(AiDecision.Verdict.NO_EVIDENCE,
                "```\n{\"verdict\":\"no_evidence\"}\n```");
    }

    @Test
    public void malformedConflictingOrExplainedRepliesStayUnchecked() {
        for (String reply : new String[]{null, "", " \n", "null", "safe", "no evidence", "[]", "{}",
                "\"no_evidence\"", "{\"verdict\":false}", "{\"verdict\":\"safe\"}",
                "{\"verdict\":\"no_evidence\",\"reason\":\"none\"}",
                "{\"verdict\":\"risk\",\"verdict\":\"no_evidence\"}",
                "{\"verdict\":\"risk\",\"verdict\":\"risk\"}",
                "{\"verdict\":\"no_evidence\"", "{verdict:\"no_evidence\"}",
                "{'verdict':'no_evidence'}", "{\"verdict\":\"risk\",}",
                "risk no_evidence", "no_evidence\nrisk", "Answer: no_evidence",
                "no_evidence because salt is unrelated", "{\"verdict\":\"no_evidence\"} trailing",
                "{\"verdict\":\"risk\"}{\"verdict\":\"no_evidence\"}",
                "```json\n{\"verdict\":\"risk\"}", "```python\nrisk\n```",
                "```json\n{\"verdict\":\"risk\"}\n``` trailing",
                "Explanation\n```json\n{\"verdict\":\"risk\"}\n```",
                "```\nrisk\n```\n```\nno_evidence\n```"}) {
            assertThrows("Accepted invalid local reply: " + reply, AiResponseFormatException.class,
                    () -> AllergenPrompt.parseLocal(reply));
        }
    }

    @Test
    public void oversizedRepliesAreRejectedBeforeRemovingWhitespaceOrFences() {
        String oversized = "no_evidence" + " ".repeat(4096);
        assertThrows(AiResponseFormatException.class, () -> AllergenPrompt.parseLocal(oversized));
    }

    @Test
    public void cloudParserRetainsItsStrictContract() throws Exception {
        assertEquals(AiDecision.Verdict.NO_EVIDENCE,
                AllergenPrompt.parse("{\"verdict\":\"no_evidence\"}").verdict());
        for (String reply : new String[]{"no_evidence\n", "```json\n{\"verdict\":\"risk\"}\n```",
                "{\"verdict\":\"NO_EVIDENCE\"}", "{\"verdict\":\" risk \"}"}) {
            try {
                AllergenPrompt.parse(reply);
                fail("Cloud parser accepted local-only format: " + reply);
            } catch (IllegalArgumentException | JSONException expected) {
                // Tolerant local parsing must not change cloud response validation.
            }
        }
    }

    private static void assertVerdict(AiDecision.Verdict expected, String reply) throws Exception {
        AiDecision decision = AllergenPrompt.parseLocal(reply);
        assertEquals(expected, decision.verdict());
        assertNull(decision.score());
    }
}
