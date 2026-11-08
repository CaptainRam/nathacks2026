package com.example.allergycheck.analysis;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Gives tests the dictionary the app ships, plus a way to build bespoke ones. */
final class TestDictionaries {

    private static final String RELATIVE = "src/main/res/raw/allergen_dictionary.json";

    private static AllergenDictionary shipped;

    private TestDictionaries() {
    }

    /**
     * The dictionary the app ships. Cached, so every {@link Allergen} in a test run is the same
     * instance.
     */
    static synchronized AllergenDictionary shipped() {
        if (shipped == null) {
            try (Reader reader = Files.newBufferedReader(shippedFile(), StandardCharsets.UTF_8)) {
                shipped = AllergenDictionary.load(reader);
            } catch (IOException e) {
                throw new AssertionError("could not read " + shippedFile(), e);
            }
        }
        return shipped;
    }

    /** Parses a bespoke dictionary, for testing the loader and unusual vocabularies. */
    static AllergenDictionary of(String json) {
        try {
            return AllergenDictionary.load(new StringReader(json));
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    /**
     * {@code res/raw} is not on the unit-test classpath, so the file is found on disk. Walks up
     * from the working directory so this works whether the runner starts in {@code app/}, in
     * {@code AllergyCheck/} or at the repository root.
     */
    static Path shippedFile() {
        Path from = Paths.get("").toAbsolutePath();
        for (Path dir = from; dir != null; dir = dir.getParent()) {
            Path direct = dir.resolve(RELATIVE);
            if (Files.isRegularFile(direct)) {
                return direct;
            }
            Path viaModule = dir.resolve("app").resolve(RELATIVE);
            if (Files.isRegularFile(viaModule)) {
                return viaModule;
            }
        }
        throw new AssertionError("could not find " + RELATIVE + " searching up from " + from);
    }
}
