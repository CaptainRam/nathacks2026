package com.example.allergycheck.analysis;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The allergens the app can check for and the vocabulary used to recognise each on a label, read
 * from a JSON document (see {@code res/raw/allergen_dictionary.json}).
 *
 * <p>Each allergen carries three phrase lists, all plain lower-case English without accents:
 * <ul>
 *   <li>{@code terms} — words that mean the allergen is present (synonyms and derivatives).</li>
 *   <li>{@code ambiguous} — words that <em>might</em> hide the allergen depending on source
 *       (e.g. "lecithin"). These only ever produce a "possible" finding, never a firm one.</li>
 *   <li>{@code exclusions} — phrases that contain a term but do not mean the allergen
 *       (e.g. "cocoa butter" is not dairy). Tokens inside them are ignored when matching.</li>
 * </ul>
 *
 * <p>Order is meaningful and preserved: the order of {@code allergens} drives
 * {@link Allergen#order()}, the order of a report's results and the order of the pickers, while
 * the order within a phrase list decides which term a {@link Finding} reports when several match.
 *
 * <p>Phrases are tokenised once at load time, so matching never works on substrings.
 *
 * <p>Pure Java apart from {@code org.json}: loading takes a {@link Reader} or {@link InputStream}
 * rather than an Android resource, so the whole analysis package stays unit-testable. Loading is
 * strict — anything malformed raises {@link MalformedDictionaryException} rather than quietly
 * yielding a dictionary with a term missing. Intentionally not {@link java.io.Serializable}, so
 * the vocabulary can never travel inside an {@link AnalysisReport}.
 */
public final class AllergenDictionary {

    /** The only schema this code understands; a different version is an error, not a warning. */
    private static final int SCHEMA_VERSION = 1;

    private static final Pattern ID = Pattern.compile("[A-Z][A-Z0-9_]*");

    /** Raised when the JSON is absent, unparseable, or does not satisfy the documented schema. */
    public static final class MalformedDictionaryException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        MalformedDictionaryException(String message) {
            super(message);
        }

        MalformedDictionaryException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Tokenised phrase lists for one allergen. */
    private static final class Entry {
        final List<String[]> terms;
        final List<String[]> ambiguous;
        final List<String[]> exclusions;

        Entry(List<String[]> terms, List<String[]> ambiguous, List<String[]> exclusions) {
            this.terms = terms;
            this.ambiguous = ambiguous;
            this.exclusions = exclusions;
        }
    }

    private final List<Allergen> allergens;
    private final Map<String, Entry> entries;
    private final Map<String, Allergen> byId;
    private final Map<String, Allergen> byLabel;

    private AllergenDictionary(List<Allergen> allergens, Map<String, Entry> entries,
                               Map<String, Allergen> byId, Map<String, Allergen> byLabel) {
        this.allergens = Collections.unmodifiableList(allergens);
        this.entries = entries;
        this.byId = byId;
        this.byLabel = byLabel;
    }

    public static AllergenDictionary load(@NonNull InputStream in) throws IOException {
        return load(new InputStreamReader(in, StandardCharsets.UTF_8));
    }

    /**
     * Reads a dictionary from {@code reader}, which is left open for the caller to close.
     *
     * @throws MalformedDictionaryException if the document is not a valid dictionary
     */
    public static AllergenDictionary load(@NonNull Reader reader) throws IOException {
        JSONObject root = parse(readFully(reader));

        int version = root.optInt("schemaVersion", -1);
        if (version != SCHEMA_VERSION) {
            throw new MalformedDictionaryException(
                    "schemaVersion must be " + SCHEMA_VERSION + ", was " + version);
        }
        JSONArray array = root.optJSONArray("allergens");
        if (array == null) {
            throw new MalformedDictionaryException("\"allergens\" must be present and an array");
        }
        if (array.length() == 0) {
            throw new MalformedDictionaryException("\"allergens\" must not be empty");
        }

        List<Allergen> allergens = new ArrayList<>();
        Map<String, Entry> entries = new LinkedHashMap<>();
        Map<String, Allergen> byId = new LinkedHashMap<>();
        Map<String, Allergen> byLabel = new LinkedHashMap<>();

        for (int i = 0; i < array.length(); i++) {
            JSONObject json = array.optJSONObject(i);
            if (json == null) {
                throw new MalformedDictionaryException("allergens[" + i + "] must be an object");
            }
            String id = json.optString("id", "").trim();
            if (!ID.matcher(id).matches()) {
                throw new MalformedDictionaryException("allergens[" + i
                        + "].id must match " + ID.pattern() + ", was \"" + id + "\"");
            }
            if (byId.containsKey(id)) {
                throw new MalformedDictionaryException("duplicate allergen id \"" + id + "\"");
            }
            String label = json.optString("label", "").trim();
            if (label.isEmpty()) {
                throw new MalformedDictionaryException(id + ".label must not be blank");
            }
            String labelKey = label.toLowerCase(Locale.ROOT);
            if (byLabel.containsKey(labelKey)) {
                // Labels are matched case-insensitively, so two that differ only in case would
                // make byLabel — and therefore the saved preferences — ambiguous.
                throw new MalformedDictionaryException(
                        "duplicate allergen label \"" + label + "\" (ignoring case)");
            }

            List<String[]> terms = tokenise(json, "terms", id, true);
            List<String[]> ambiguous = tokenise(json, "ambiguous", id, false);
            List<String[]> exclusions = tokenise(json, "exclusions", id, false);

            Allergen allergen = new Allergen(id, label, i);
            allergens.add(allergen);
            entries.put(id, new Entry(terms, ambiguous, exclusions));
            byId.put(id, allergen);
            byLabel.put(labelKey, allergen);
        }
        return new AllergenDictionary(allergens, entries, byId, byLabel);
    }

    /** Every allergen in the dictionary, in document order. */
    @NonNull
    public List<Allergen> allergens() {
        return allergens;
    }

    /** The allergen with this id; {@code null} if this dictionary does not define one. */
    @Nullable
    public Allergen byId(@Nullable String id) {
        return id == null ? null : byId.get(id.trim());
    }

    /** Maps a saved preference such as "Tree nuts" back to an allergen; {@code null} if unknown. */
    @Nullable
    public Allergen byLabel(@Nullable String label) {
        return label == null ? null : byLabel.get(label.trim().toLowerCase(Locale.ROOT));
    }

    /** Whether {@code allergen} came from this dictionary, so its vocabulary can be looked up. */
    public boolean contains(@Nullable Allergen allergen) {
        return allergen != null && byId.containsKey(allergen.id());
    }

    /** Tokenised definite terms. Package-private: the matcher is the only consumer. */
    List<String[]> terms(Allergen allergen) {
        return entry(allergen).terms;
    }

    List<String[]> ambiguousTerms(Allergen allergen) {
        return entry(allergen).ambiguous;
    }

    List<String[]> exclusions(Allergen allergen) {
        return entry(allergen).exclusions;
    }

    /**
     * Throws rather than returning empty lists for an allergen this dictionary does not define:
     * empty vocabulary would produce no findings at all, and the report would say "not detected"
     * because of a wiring bug.
     */
    private Entry entry(Allergen allergen) {
        Entry entry = allergen == null ? null : entries.get(allergen.id());
        if (entry == null) {
            throw new IllegalArgumentException("allergen is not in this dictionary: " + allergen);
        }
        return entry;
    }

    private static List<String[]> tokenise(JSONObject json, String key, String id, boolean required) {
        JSONArray array = json.optJSONArray(key);
        if (array == null) {
            if (required || json.has(key)) {
                throw new MalformedDictionaryException(id + "." + key + " must be an array");
            }
            return Collections.emptyList();
        }
        if (required && array.length() == 0) {
            throw new MalformedDictionaryException(id + "." + key + " must not be empty");
        }
        List<String[]> out = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            String phrase = array.optString(i, "");
            List<String> tokens = TextUtil.tokens(phrase);
            if (tokens.isEmpty()) {
                // Silently dropping these is how a typo'd phrase becomes a term that never matches.
                throw new MalformedDictionaryException(id + "." + key + "[" + i
                        + "] has no matchable words: \"" + phrase + "\"");
            }
            out.add(tokens.toArray(new String[0]));
        }
        return Collections.unmodifiableList(out);
    }

    private static JSONObject parse(String text) {
        try {
            return new JSONObject(text);
        } catch (JSONException e) {
            throw new MalformedDictionaryException("not a JSON object: " + e.getMessage(), e);
        }
    }

    /**
     * Android's framework {@code org.json} has no {@code JSONTokener(Reader)}, so the document is
     * read into memory first. It is a few kilobytes.
     */
    private static String readFully(Reader reader) throws IOException {
        StringBuilder out = new StringBuilder();
        char[] buffer = new char[8192];
        int read;
        while ((read = reader.read(buffer)) != -1) {
            out.append(buffer, 0, read);
        }
        return out.toString();
    }
}
