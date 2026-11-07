package com.example.allergycheck.analysis;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Text normalisation shared by the parser and the matcher. Pure Java. */
final class TextUtil {

    private TextUtil() {
    }

    /** Lower-case, accent-free, letters/digits only, single spaces. {@code "Blé (5%)"} → {@code "ble 5"}. */
    static String normalise(String text) {
        if (text == null) {
            return "";
        }
        String s = text.replace("œ", "oe").replace("Œ", "oe").replace("æ", "ae").replace("Æ", "ae");
        s = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        s = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
        return s;
    }

    /**
     * Word tokens of {@code text}: normalised, purely numeric tokens (percentages, quantities)
     * and the qualifier "organic" removed.
     */
    static List<String> tokens(String text) {
        String n = normalise(text);
        if (n.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>();
        for (String token : n.split(" ")) {
            if (token.isEmpty() || token.matches("\\d+") || token.equals("organic") || token.equals("bio")) {
                continue;
            }
            out.add(token);
        }
        return out;
    }

    static int letterCount(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (Character.isLetter(text.charAt(i))) {
                count++;
            }
        }
        return count;
    }

    /** Classic edit distance, with an early exit once the distance is certain to exceed {@code max}. */
    static int levenshtein(String a, String b, int max) {
        if (Math.abs(a.length() - b.length()) > max) {
            return max + 1;
        }
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            int rowMin = curr[0];
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
                rowMin = Math.min(rowMin, curr[j]);
            }
            if (rowMin > max) {
                return max + 1;
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[b.length()];
    }
}
