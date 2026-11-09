package com.example.allergycheck.ocr;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Judges whether a live frame's ingredient list was read <em>cleanly</em>, as opposed to merely
 * being parsable.
 *
 * <p>This exists because "the extractor found a list" and "the recogniser read it correctly" are
 * different claims, and auto-capture was acting on the first while promising the second. A frame
 * that yields {@code "citric ac d"} parses perfectly well — it is a heading plus comma-separated
 * items, which is all {@link com.example.allergycheck.ingredients.IngredientExtractor} asks — so the
 * frame went green and the shutter fired on a read that had already lost a word. On an allergen term
 * the same misread is worse than a failed scan, because the report comes back confident and wrong.
 *
 * <p><b>What it looks for: split words.</b> The characteristic failure of a marginal OCR frame is
 * not invented letters but <em>fragmentation</em> — a word broken into pieces at a blur, a fold, or
 * the edge of focus. {@code "citric acid"} becomes {@code "citric ac d"}, {@code "peanut"} becomes
 * {@code "pean ut"}. Fragments are cheap to spot without any dictionary of either official language:
 * they are one- or two-letter alphabetic tokens in a position where no real word sits. Two in a row
 * is near-conclusive, since ordinary ingredient text essentially never puts two such tokens
 * together.
 *
 * <p><b>Why not invented characters or digit substitutions.</b> {@code "s0y"} for {@code "soy"} is a
 * real artifact, but labels are full of legitimate letter-digit tokens — {@code B12}, {@code E322},
 * {@code Red 40}, {@code D3} — and no rule separated the two without flagging ordinary additives.
 * Fragmentation needs no such discrimination.
 *
 * <p><b>Why not frame-to-frame agreement.</b> Requiring consecutive frames to produce the same text
 * is tempting and language-agnostic, but a hand-held phone perturbs the read every frame even when
 * it is reading well, so the threshold would have to be a similarity score tuned on a device. This
 * check is deterministic and decides from one frame.
 *
 * <p><b>Advisory only.</b> Nothing here gates {@link FrameReadability.Verdict#READY} or the capture
 * button. It gates auto-capture alone. The post-capture pipeline conditions the image far more
 * aggressively than the live path can afford to — grayscale, histogram, contrast stretch — and
 * routinely reads a panel the live frame fumbled, so refusing the user a shot on this evidence would
 * be the overreach {@link FrameReadability} documents having already removed four times.
 *
 * <p>Pure Java, no Android imports, so the whole judgement is unit-testable off-device.
 */
final class ReadQuality {

    /**
     * Longest alphabetic token treated as possibly-a-fragment. Three letters is a real word often
     * enough ({@code sel}, {@code eau}, {@code soy}, {@code dye}, {@code BHT}) that including it
     * would flag ordinary labels.
     */
    static final int MAX_FRAGMENT_LENGTH = 2;

    /** Two fragments in a row: one word broken in half. The strongest single signal there is. */
    static final int GARBLED_RUN = 2;

    /**
     * Share of word tokens that may be fragments before the whole read is suspect.
     *
     * <p>Not zero. A few isolated one-letter tokens are legitimate and unavoidable — the
     * stereochemistry prefixes in {@code "D-alpha tocopheryl acetate"} and {@code "N-acetyl
     * cysteine"} tokenise to a bare letter — so a list carrying several of them must not be rejected
     * on their account alone.
     */
    static final float MAX_FRAGMENT_RATIO = 0.08f;

    /**
     * Word tokens needed before {@link #MAX_FRAGMENT_RATIO} means anything. Below this a single
     * legitimate fragment would blow the ratio on its own, so only the run rule applies.
     */
    static final int MIN_TOKENS_FOR_RATIO = 10;

    /**
     * One- and two-letter tokens that are ordinary label vocabulary rather than debris.
     *
     * <p>Both official languages, because Canadian labels are bilingual and a French function word
     * is not a misread. Units are here for the same reason.
     *
     * <p>Scoped to words that occur <em>on labels</em>, not to English and French at large. The
     * first version took the broader view and listed {@code "so"} and {@code "y"} among others,
     * which quietly cost the check its best catch: {@code "so y lecithin"} — soy lecithin split in
     * two, an allergen term destroyed — read as two innocent function words and passed. Nothing is
     * gained by tolerating a word no ingredient list contains, and an allergen term is exactly what
     * fragments most consequentially.
     *
     * <p>Maintained, not exhaustive — in the same spirit as the allergen dictionary's exclusion
     * lists. A false positive here costs a delayed auto-capture, never a missed allergen.
     */
    private static final Set<String> SHORT_WORDS = new HashSet<>(Arrays.asList(
            // English function words that actually occur on labels.
            "a", "of", "or", "in", "on", "no", "to",
            // French function words, for the other half of a bilingual panel.
            "de", "du", "la", "le", "et", "en", "au", "ou", "un",
            // Units and measures.
            "g", "kg", "mg", "ml", "l", "oz", "lb", "iu",
            // Label shorthand: "pH adjusted", "FD&C Red No. 40", "DL-alpha tocopherol".
            "ph", "fd", "dl"));

    /**
     * Tokens that license a bare single letter immediately after them.
     *
     * <p>{@code "vitamin D"} and {@code "FD&C"} are the two places a lone letter is genuinely
     * expected. Licensing them contextually rather than adding {@code "d"} and {@code "c"} to
     * {@link #SHORT_WORDS} is the whole point: a bare {@code "d"} with no vitamin in front of it is
     * exactly the tail of {@code "citric ac d"}, and blanket-allowing the letter would read the
     * app's worst case as ordinary text.
     */
    private static final Set<String> LICENSES_SINGLE_LETTER = new HashSet<>(Arrays.asList(
            "vitamin", "vitamins", "vitamine", "vitamines", "vit", "fd"));

    private ReadQuality() {
    }

    /**
     * Whether {@code ingredients} — the items {@code IngredientExtractor} pulled out of this frame —
     * read cleanly enough to shoot without being asked.
     *
     * <p>Judged on the extracted items rather than the frame's full text on purpose. The full text
     * carries the brand name, the nutrition panel and whatever else is in shot, none of which the
     * analysis will use and all of which contributes debris; these items are precisely the text the
     * verdict is promising to have read.
     *
     * @return {@code true} when nothing suggests a misread, including for an empty list — absence of
     *         evidence is not evidence of garbling, and the caller has already decided separately
     *         whether a list is present at all
     */
    static boolean looksClean(List<String> ingredients) {
        if (ingredients == null || ingredients.isEmpty()) {
            return true;
        }
        int wordTokens = 0;
        int fragments = 0;
        for (int index = 0; index < ingredients.size(); index++) {
            List<String> tokens = tokenise(ingredients.get(index));
            int run = 0;
            String previous = null;
            for (int t = 0; t < tokens.size(); t++) {
                String token = tokens.get(t);
                if (!hasLetter(token)) {
                    // A pure number — "2", "40", "100" — is neither a word nor debris. It does not
                    // break a run either: "ac 2 d" is still one word in pieces.
                    continue;
                }
                wordTokens++;
                boolean leadsItem = previous == null && t + 1 < tokens.size();
                if (isFragment(token, previous, leadsItem)) {
                    fragments++;
                    run++;
                    if (run >= GARBLED_RUN) {
                        return false;
                    }
                } else {
                    run = 0;
                }
                previous = token;
            }
        }
        if (wordTokens < MIN_TOKENS_FOR_RATIO) {
            // Too little text for a ratio to mean anything; the run rule above has had its say.
            return true;
        }
        return fragments <= wordTokens * MAX_FRAGMENT_RATIO;
    }

    /**
     * Whether {@code token} is debris rather than a word, given its position within the item.
     *
     * @param previous  the token before it in the same item, or {@code null} if it is the first
     * @param leadsItem whether it opens an item that has more tokens after it
     */
    private static boolean isFragment(String token, String previous, boolean leadsItem) {
        if (token.length() > MAX_FRAGMENT_LENGTH || !isAllLetters(token)) {
            // Anything carrying a digit is an additive code or a quantity, not a broken word.
            return false;
        }
        if (SHORT_WORDS.contains(token)) {
            return false;
        }
        if (token.length() != 1) {
            return true;
        }
        if (previous != null) {
            // "vitamin D", "FD&C" — the two places a lone letter is genuinely expected mid-item.
            return !LICENSES_SINGLE_LETTER.contains(previous);
        }
        // A lone letter opening an item is a chemical prefix whose hyphen the tokeniser split:
        // "D-alpha tocopheryl acetate", "N-acetyl cysteine", "L-ascorbic acid". Licensing it by
        // position rather than by letter covers all of them without a list, and costs nothing —
        // the fragments this check is for sit where a word was broken, not where an item begins.
        return !leadsItem;
    }

    /**
     * Splits on everything that is not a letter or digit, lower-cased.
     *
     * <p>Its own tokeniser rather than {@code analysis.TextUtil}: that one is package-private to the
     * layer above this, and reaching up into it is the dependency {@link FrameReadability} is at
     * pains to avoid. Hyphens split, so {@code "D-alpha"} becomes {@code "d"} and {@code "alpha"} —
     * which is why the stereochemistry prefixes are tolerated above.
     */
    private static List<String> tokenise(String text) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        StringBuilder current = new StringBuilder();
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (Character.isLetterOrDigit(character)) {
                current.append(Character.toLowerCase(character));
            } else if (current.length() > 0) {
                out.add(current.toString());
                current.setLength(0);
            }
        }
        if (current.length() > 0) {
            out.add(current.toString());
        }
        return out;
    }

    private static boolean isAllLetters(String token) {
        for (int index = 0; index < token.length(); index++) {
            if (!Character.isLetter(token.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasLetter(String token) {
        for (int index = 0; index < token.length(); index++) {
            if (Character.isLetter(token.charAt(index))) {
                return true;
            }
        }
        return false;
    }
}
