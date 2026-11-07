package com.example.allergycheck.analysis;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The allergens the app can check for, with the vocabulary used to recognise each on a label.
 *
 * <p>{@link #label()} matches the strings in the home screen's {@code allergen_options} array, so
 * saved preferences can be mapped back with {@link #fromLabel(String)}; {@link #name()} is the
 * stable id to persist or pass between screens.
 *
 * <ul>
 *   <li>{@code terms} — words that mean the allergen is present (synonyms and derivatives),
 *       English and French, written in plain lower-case without accents.</li>
 *   <li>{@code ambiguous} — words that <em>might</em> hide the allergen depending on source
 *       (e.g. "lecithin"). These only ever produce a "possible" finding, never a firm one.</li>
 *   <li>{@code exclusions} — phrases that contain a term but do not mean the allergen
 *       (e.g. "cocoa butter" is not dairy). Tokens inside them are ignored when matching.</li>
 * </ul>
 */
public enum Allergen {

    MILK("Milk",
            s("milk", "skim milk", "whole milk", "milk powder", "milk solids", "milk protein", "milk fat",
                    "cream", "butter", "buttermilk", "butterfat", "cheese", "yogurt", "yoghurt", "yogourt",
                    "whey", "casein", "caseinate", "lactose", "lactalbumin", "lactoglobulin", "ghee", "curd",
                    "custard", "kefir", "ice cream", "dairy", "lait", "creme", "beurre", "fromage",
                    "lactoserum", "babeurre", "lactose", "paneer", "ricotta", "mozzarella", "cheddar"),
            s("natural flavour", "natural flavor", "margarine", "nougat", "caramel", "chocolate"),
            s("cocoa butter", "shea butter", "peanut butter", "almond butter", "nut butter", "cashew butter",
                    "sunflower butter", "seed butter", "coconut milk", "coconut cream", "almond milk",
                    "soy milk", "soya milk", "oat milk", "rice milk", "cashew milk", "hazelnut milk",
                    "pea milk", "cream of tartar", "milk thistle", "lait de coco", "beurre de cacao",
                    "beurre d arachide", "beurre de karite")),

    EGGS("Eggs",
            s("egg", "egg white", "egg yolk", "dried egg", "albumin", "albumen", "ovalbumin", "ovomucoid",
                    "ovomucin", "ovovitellin", "livetin", "lysozyme", "mayonnaise", "meringue", "oeuf",
                    "albumine", "eggnog"),
            s("natural flavour", "natural flavor", "lecithin", "pasta", "marshmallow"),
            s("egg free", "eggplant")),

    PEANUTS("Peanuts",
            s("peanut", "peanut butter", "peanut oil", "peanut flour", "groundnut", "ground nut", "arachis",
                    "monkey nut", "beer nut", "goober", "arachide", "cacahuete", "cacahouete"),
            s("nuts", "mixed nuts", "satay", "vegetable oil"),
            s("peanut free")),

    TREE_NUTS("Tree nuts",
            s("almond", "brazil nut", "cashew", "chestnut", "filbert", "hazelnut", "hickory", "macadamia",
                    "pecan", "pine nut", "pistachio", "walnut", "marzipan", "praline", "gianduja", "tree nut",
                    "amande", "noisette", "noix", "pistache", "pacane", "noix de cajou"),
            s("nuts", "mixed nuts", "nougat", "coconut", "nut butter", "nut oil"),
            s("water chestnut", "noix de coco", "nut free", "tree nut free")),

    SOY("Soy",
            s("soy", "soya", "soybean", "soy sauce", "soy protein", "soy flour", "soy lecithin", "soy milk",
                    "soy oil", "edamame", "tofu", "tempeh", "miso", "tamari", "shoyu", "natto", "soja",
                    "textured soy", "feve de soja"),
            s("lecithin", "vegetable protein", "hydrolyzed vegetable protein", "hydrolysed vegetable protein",
                    "textured vegetable protein", "natural flavour", "natural flavor", "vegetable oil",
                    "vegetable broth", "lecithine"),
            s("sunflower lecithin", "soy free", "soya free")),

    WHEAT("Wheat",
            s("wheat", "wheat flour", "wheat starch", "wheat germ", "wheat bran", "wheat protein", "semolina",
                    "spelt", "durum", "kamut", "farina", "couscous", "bulgur", "seitan", "triticale", "gluten",
                    "einkorn", "emmer", "graham", "ble", "farine de ble", "orzo", "freekeh"),
            s("flour", "starch", "modified starch", "modified food starch", "malt", "bran", "breadcrumbs",
                    "bread crumbs", "rusk", "pasta", "farine", "amidon"),
            s("gluten free", "wheat free", "sans gluten", "buckwheat", "buckwheat flour",
                    // Qualified non-wheat flours/starches, so bare "flour"/"starch" stays the only trigger.
                    "rice flour", "corn flour", "maize flour", "potato flour", "tapioca flour", "almond flour",
                    "coconut flour", "chickpea flour", "oat flour", "corn starch", "maize starch",
                    "potato starch", "tapioca starch", "rice starch", "modified corn starch",
                    "modified tapioca starch", "modified potato starch", "modified maize starch",
                    "farine de mais", "farine de riz", "amidon de mais")),

    SESAME("Sesame",
            s("sesame", "sesame seed", "sesame oil", "tahini", "tahina", "halvah", "halva", "gomasio", "benne",
                    "gingelly"),
            s("hummus", "falafel", "spices"),
            s("sesame free")),

    FISH("Fish",
            s("fish", "anchovy", "anchovies", "bass", "catfish", "cod", "flounder", "haddock", "hake", "halibut",
                    "herring", "mackerel", "perch", "pike", "pollock", "salmon", "sardine", "snapper", "sole",
                    "swordfish", "tilapia", "trout", "tuna", "fish sauce", "fish oil", "fish gelatin", "surimi",
                    "isinglass", "poisson", "saumon", "thon", "morue", "anchois"),
            s("worcestershire", "omega 3", "caesar"),
            s("fish free", "fish and chips")),

    SHELLFISH("Shellfish",
            s("shellfish", "crustacean", "mollusc", "mollusk", "shrimp", "prawn", "crab", "lobster", "crayfish",
                    "crawfish", "langoustine", "krill", "clam", "mussel", "oyster", "scallop", "squid", "calamari",
                    "octopus", "snail", "escargot", "abalone", "cuttlefish", "whelk", "cockle", "crevette",
                    "homard", "crabe", "moule", "huitre", "palourde", "petoncle", "calmar", "fruits de mer"),
            s("seafood", "glucosamine"),
            s("shellfish free")),

    MUSTARD("Mustard",
            s("mustard", "mustard seed", "mustard flour", "mustard oil", "mustard powder", "prepared mustard",
                    "moutarde"),
            s("spices", "spice", "seasoning", "curry", "curry powder", "vinaigrette"),
            s("mustard free"));

    private final String label;
    private final List<String[]> terms = new ArrayList<>();
    private final List<String[]> ambiguous = new ArrayList<>();
    private final List<String[]> exclusions = new ArrayList<>();

    Allergen(String label, String[] terms, String[] ambiguous, String[] exclusions) {
        this.label = label;
        tokenise(terms, this.terms);
        tokenise(ambiguous, this.ambiguous);
        tokenise(exclusions, this.exclusions);
    }

    private static String[] s(String... values) {
        return values;
    }

    private static void tokenise(String[] phrases, List<String[]> into) {
        for (String phrase : phrases) {
            List<String> tokens = TextUtil.tokens(phrase);
            if (!tokens.isEmpty()) {
                into.add(tokens.toArray(new String[0]));
            }
        }
    }

    /** User-facing name; identical to the entry in the home screen's allergen list. */
    @NonNull
    public String label() {
        return label;
    }

    /** Maps a saved preference string such as "Tree nuts" back to the enum; {@code null} if unknown. */
    @Nullable
    public static Allergen fromLabel(@Nullable String label) {
        if (label == null) {
            return null;
        }
        for (Allergen allergen : values()) {
            if (allergen.label.equalsIgnoreCase(label.trim())) {
                return allergen;
            }
        }
        return null;
    }

    /** Tokenised definite terms. Package-private: the matcher is the only consumer. */
    List<String[]> terms() {
        return terms;
    }

    List<String[]> ambiguousTerms() {
        return ambiguous;
    }

    List<String[]> exclusions() {
        return exclusions;
    }
}
