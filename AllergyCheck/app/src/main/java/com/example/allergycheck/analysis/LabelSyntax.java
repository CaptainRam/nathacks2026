package com.example.allergycheck.analysis;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Structural OCR rules shared by ingredient extraction and fallback parsing; no allergen verdicts. */
public final class LabelSyntax {
    // I/l is a common OCR confusion, but word boundaries still reject "ingredientship" and suffixes.
    public static final Pattern HEADING = Pattern.compile(
            "(?iu)\\b[il]ngred[il]ents?\\b(?:\\s+list)?(\\s*[:;.\\-–])?");

    private static final String PRECAUTIONARY =
            "(?:(?:may(?:\\s+also)?|might|could)\\s+contain|traces?\\s+of"
                    + "|(?:produced|made|manufactured|processed|packaged|packed)\\s+(?:in|on)\\s+"
                    + "(?:(?:a|an|the)\\s+)?(?:(?:shared|common)\\s+)?"
                    + "(?:facility|plant|factory|equipment|line))";

    public static final Pattern STATEMENT_START = Pattern.compile(
            "(?iu)\\b(?:allerg(?:en|y|ens|ies)\\s*"
                    + "(?:information|advice|warning|statement|info)?\\s*[:\\-–]?\\s*)?"
                    + "(?:" + PRECAUTIONARY
                    + "|contains?\\b(?!\\s*(?:\\d|less\\s+than\\s+\\d))\\s*:?)");
    public static final Pattern MAY_CONTAIN = Pattern.compile("(?iu)^" + PRECAUTIONARY + ".*");

    public static final Pattern TERMINATOR = Pattern.compile(
            "(?iu)\\b(?:nutrition(?:al)? (?:facts|information)|distributed by"
                    + "|manufactured (?:by|for)|best before|net w(?:eigh)?t"
                    + "|keep (?:refrigerated|frozen|in|out of)|store in|refrigerate after"
                    + "|storage instructions|cooking instructions|serving suggestions|directions)\\b");

    public static final Pattern MINOR_MARKER = Pattern.compile(
            "(?iu)\\b(?:and\\s+)?(?:contains?\\s+)?"
                    + "(?:less\\s+than\\s+\\d+(?:[.,]\\d+)?\\s*%|\\d+(?:[.,]\\d+)?\\s*%\\s+or\\s+less)"
                    + "\\s+of(?:\\s+each\\s+of)?(?:\\s+the\\s+following)?\\s*:?");

    private static final Pattern WRAPPED_WORD = Pattern.compile(
            "\\p{L}+(?:-[ \\t]*\\n[ \\t]*\\p{L}+)+");
    private static final Pattern E_NUMBER = Pattern.compile("(?iu)e\\s*\\d{3,4}[a-z]?");
    private static final Pattern PROSE_AFTER_HEADING = Pattern.compile(
            "(?iu)^(?:you|we|that|which|are|were|is|include|make|can)\\b");

    /*
     * Only unambiguous single-word food spellings are dehyphenated. Blindly joining every wrap
     * would destroy meaningful token boundaries in TREE-\nNUT or GLUTEN-\nFREE.
     * This spelling aid includes vocabulary tokens from the shipped dictionary, with a test guarding
     * against dictionary drift. It is not allergen classification: unknown compounds keep their
     * original hyphen, and remain available to the matcher's uncertain-read rules.
     */
    private static final Set<String> JOINED_WORDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "abalone", "acorn", "aioli", "albumen", "albumin", "ale", "alfredo", "almond", "almonds",
            "amaranth", "anchovies", "anchovy", "and", "apple", "arachis", "arctic", "arrowroot",
            "asiago", "atar", "atta", "avena", "avidin", "baba", "bagel", "banana", "barley", "basa",
            "bass", "batter", "bean", "bechamel", "beechnut", "beer", "benne", "bisque", "bitter",
            "black", "bonito", "bouillabaisse", "bran", "brazil", "bread", "breadcrumb", "breadcrumbs",
            "breadfruit", "breading", "brewer", "brie", "broth", "buckwheat", "bulgar", "bulghur",
            "bulgur", "bun", "burrata", "butter", "butterfat", "buttermilk", "caesar", "calamari",
            "camembert", "canola", "caramel", "carob", "casein", "caseinate", "cashew", "cashews",
            "cassava", "catfish", "caviar", "char", "cheddar", "cheese", "chestnut", "chestnuts",
            "chia", "chickpea", "chips", "chitosan", "chocolate", "clam", "cob", "cobnut", "cockle",
            "cocoa", "coconut", "coconuts", "cod", "colby", "conalbumin", "conch", "corn", "couscous",
            "crab", "crabmeat", "cracker", "crawdad", "crawfish", "crayfish", "cream", "crema",
            "creme", "cricket", "crouton", "crumb", "crumbs", "crustacean", "curd", "curry", "custard",
            "cuttlefish", "dairy", "dashi", "dijon", "doenjang", "dried", "dukkah", "durum", "earth",
            "edam", "edamame", "eel", "egg", "eggnog", "eggplant", "einkorn", "emmer", "escargot",
            "extract", "faba", "falafel", "farina", "farro", "fat", "fava", "feta", "filbert", "filo",
            "fish", "flavor", "flavoring", "flavour", "flavouring", "flax", "flaxseed", "flounder",
            "flour", "food", "frangipane", "free", "freekeh", "frittata", "furikake", "ganoush",
            "garbanzo", "gelatin", "gelato", "geoduck", "germ", "ghee", "gianduia", "gianduja",
            "gingelly", "ginger", "glass", "glucosamine", "gluten", "gochujang", "gomashio", "gomasio",
            "goober", "gouda", "graham", "granola", "gravlax", "ground", "groundnut", "groundnuts",
            "grouper", "gruyere", "haddock", "hake", "half", "halibut", "halloumi", "halva", "halvah",
            "havarti", "hazelnut", "hazelnuts", "hemp", "herring", "hickory", "hoisin", "hollandaise",
            "horse", "hummus", "hydrolysed", "hydrolyzed", "ice", "icing", "isinglass", "kamut",
            "katsuobushi", "kefir", "khorasan", "kimchi", "kinako", "kipper", "krill", "lactalbumin",
            "lactoferrin", "lactoglobulin", "lactose", "lager", "langostino", "langoustine",
            "lecithin", "lentil", "lettuce", "limpet", "livetin", "lobster", "lox", "lupin", "lupine",
            "lysozyme", "macadamia", "macaron", "macaroni", "mackerel", "mahi", "maida", "maize",
            "malt", "malted", "mam", "mandelona", "margarine", "marshmallow", "marzipan", "mascarpone",
            "matzah", "matzo", "mayonnaise", "meal", "meat", "meringue", "milk", "milkfat", "millet",
            "miso", "mix", "mixed", "modified", "mollusc", "mollusk", "monkey", "monkfish", "mousse",
            "mozzarella", "muesli", "mushroom", "mussel", "mustard", "nam", "natto", "natural", "non",
            "noodle", "nougat", "nuoc", "nut", "nutella", "nuts", "oat", "oatmeal", "octopus", "of",
            "oil", "okara", "omega", "orzo", "ovalbumin", "ovomucin", "ovomucoid", "ovotransferrin",
            "ovovitellin", "oyster", "paella", "paneer", "pangasius", "panko", "parmesan",
            "parmigiano", "pasta", "paste", "pea", "peanut", "peanuts", "pearl", "pearled", "pecan",
            "perch", "periwinkle", "pesto", "phyllo", "pignoli", "pignolia", "pike", "pine", "pinon",
            "pistachio", "pla", "plaice", "plantain", "pollack", "pollock", "ponzu", "potato",
            "powder", "praline", "prawn", "prepared", "protein", "provolone", "pumpernickel", "quahog",
            "quark", "queensland", "queso", "quiche", "quinoa", "rapeseed", "rice", "ricotta",
            "rockfish", "roe", "root", "royal", "rusk", "rye", "sago", "salmon", "sans", "sardine",
            "satay", "sauce", "scallop", "scampi", "seafood", "seasoning", "seed", "seitan",
            "semolina", "sesame", "sesamin", "sesamol", "sesamum", "shea", "shell", "shellfish",
            "sherbet", "shoyu", "shrimp", "simsim", "sinapis", "skim", "smelt", "snail", "snapper",
            "soba", "sole", "solid", "solids", "sorghum", "soy", "soya", "soybean", "soybeans",
            "spaghetti", "spelt", "spice", "spices", "squid", "starch", "stock", "stout", "sunflower",
            "surimi", "swedish", "swordfish", "syrup", "tabbouleh", "tahina", "tahini", "tamari",
            "tapioca", "tartar", "teff", "tempeh", "tempura", "teriyaki", "textured", "thistle",
            "tigernut", "tilapia", "tofu", "trail", "tree", "triticale", "trout", "tuna", "turbot",
            "tvp", "tzatziki", "udon", "unagi", "vegan", "vegetable", "vermicelli", "vinaigrette",
            "vinegar", "vitellin", "walnut", "walnuts", "water", "wheat", "whelk", "whey", "white",
            "whitefish", "whiting", "whole", "wonton", "worcestershire", "yam", "yeast", "yoghurt",
            "yogourt", "yogurt", "yolk", "yuba", "zaatar")));

    private LabelSyntax() { }

    public static final class Heading {
        public final int start;
        public final int end;

        private Heading(int start, int end) {
            this.start = start;
            this.end = end;
        }
    }

    public static String prepare(String raw) {
        String text = raw.replace("\r", "").replace('\u0001', ' ');
        Matcher wraps = WRAPPED_WORD.matcher(text);
        StringBuffer joined = new StringBuffer();
        while (wraps.find()) {
            wraps.appendReplacement(joined, Matcher.quoteReplacement(repairWrappedWord(wraps.group())));
        }
        wraps.appendTail(joined);
        return joined.toString().replaceAll("[ \\t]*\\n[ \\t]*", " ").trim();
    }

    private static String repairWrappedWord(String wrapped) {
        String[] fragments = wrapped.split("-[ \\t]*\\n[ \\t]*");
        StringBuilder repaired = new StringBuilder();
        for (int start = 0; start < fragments.length;) {
            int end = start + 1;
            String best = fragments[start];
            String candidate = best;
            for (int next = start + 1; next < fragments.length; next++) {
                candidate += fragments[next];
                if (isKnownSpelling(candidate)) {
                    best = candidate;
                    end = next + 1;
                }
            }
            if (repaired.length() > 0) repaired.append("-\n");
            repaired.append(best);
            start = end;
        }
        return repaired.toString();
    }

    private static boolean isKnownSpelling(String candidate) {
        String word = candidate.toLowerCase(Locale.ROOT);
        if (JOINED_WORDS.contains(word)) return true;
        if (word.endsWith("s") && JOINED_WORDS.contains(word.substring(0, word.length() - 1))) return true;
        if (word.endsWith("es") && JOINED_WORDS.contains(word.substring(0, word.length() - 2))) return true;
        return word.endsWith("ies")
                && JOINED_WORDS.contains(word.substring(0, word.length() - 3) + "y");
    }

    /** Prefer a label-like heading over an incidental mention in marketing prose. */
    public static Heading findHeading(String text) {
        Matcher matcher = HEADING.matcher(text);
        boolean[] unclosed = unclosedOpens(text);
        List<int[]> candidates = new ArrayList<>();
        List<String> separators = new ArrayList<>();
        while (matcher.find()) {
            if (depthAt(text, matcher.start(), unclosed) > 0
                    || PROSE_AFTER_HEADING.matcher(text.substring(matcher.end()).trim()).find()) continue;
            if (matcher.group(1) != null && ":-–".contains(matcher.group(1).trim())) {
                return new Heading(matcher.start(), matcher.end());
            }
            candidates.add(new int[] {matcher.start(), matcher.end()});
            separators.add(matcher.group(1));
        }
        Heading best = null;
        int bestScore = 0;
        for (int i = 0; i < candidates.size(); i++) {
            int start = candidates.get(i)[0];
            int end = candidates.get(i)[1];
            String separator = separators.get(i);
            int score = separator == null ? 0 : 4;
            String word = text.substring(start, end).trim();
            int previous = start - 1;
            while (previous >= 0 && Character.isWhitespace(text.charAt(previous))) previous--;
            boolean atBoundary = previous < 0 || ".:;".indexOf(text.charAt(previous)) >= 0;
            int next = i + 1 < candidates.size() ? candidates.get(i + 1)[0] : text.length();
            String following = text.substring(end, next);
            int period = following.indexOf('.');
            String firstSentence = period < 0 ? following : following.substring(0, period);
            boolean listFollows = firstSentence.indexOf(',') >= 0 || firstSentence.indexOf(';') >= 0;
            if (!atBoundary && !listFollows) continue;
            if (atBoundary) score += 4;
            if (listFollows) score += 3;
            if (word.equals(word.toUpperCase(Locale.ROOT))) score += 2;
            if (score > bestScore) {
                best = new Heading(start, end);
                bestScore = score;
            }
        }
        return best;
    }

    /** The first ingredient list only; unknown post-period text is retained rather than discarded. */
    public static String ingredientBody(String text, Heading heading) {
        String body = text.substring(heading.end);
        int end = Math.min(firstOutsideBrackets(HEADING, body),
                Math.min(firstOutsideBrackets(TERMINATOR, body), firstOutsideBrackets(STATEMENT_START, body)));
        return removeMinorMarkers(body.substring(0, end));
    }

    public static String removeMinorMarkers(String text) {
        boolean[] unclosed = unclosedOpens(text);
        Matcher matcher = MINOR_MARKER.matcher(text);
        StringBuilder result = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            if (depthAt(text, matcher.start(), unclosed) == 0) {
                result.append(text, last, matcher.start()).append(',');
                last = matcher.end();
            }
        }
        return result.append(text.substring(last)).toString();
    }

    public static int firstOutsideBrackets(Pattern pattern, String text) {
        boolean[] unclosed = unclosedOpens(text);
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            if (depthAt(text, matcher.start(), unclosed) == 0) return matcher.start();
        }
        return text.length();
    }

    public static boolean isUsableIngredient(String text) {
        int letters = 0;
        for (int i = 0; i < text.length(); i++) if (Character.isLetter(text.charAt(i))) letters++;
        return letters >= 2 || E_NUMBER.matcher(text.trim()).matches();
    }

    /** Decimal points and a short abbreviation followed by an initial ("vit. C") stay literal. */
    public static boolean isInternalPoint(String text, int at) {
        if (at > 0 && at + 1 < text.length() && Character.isDigit(text.charAt(at - 1))
                && Character.isDigit(text.charAt(at + 1))) return true;
        int before = at - 1;
        while (before >= 0 && Character.isLetter(text.charAt(before))) before--;
        int length = at - before - 1;
        int after = at + 1;
        while (after < text.length() && Character.isWhitespace(text.charAt(after))) after++;
        return length > 0 && length <= 4 && after < text.length()
                && Character.isUpperCase(text.charAt(after))
                && (after + 1 == text.length() || !Character.isLetter(text.charAt(after + 1)));
    }

    public static List<String> splitItems(String text) {
        boolean[] unclosed = unclosedOpens(text);
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c == '(' || c == '[') && !unclosed[i]) depth++;
            else if ((c == ')' || c == ']') && depth > 0) depth--;
            if (depth == 0 && (",;•·".indexOf(c) >= 0 || (c == '.' && !isInternalPoint(text, i)))) {
                result.add(current.toString());
                current.setLength(0);
            } else current.append(c);
        }
        result.add(current.toString());
        return result;
    }

    /** Unclosed OCR brackets are literal characters, not groups extending to the end of the label. */
    public static boolean[] unclosedOpens(String text) {
        boolean[] unclosed = new boolean[text.length()];
        int[] opens = new int[text.length()];
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(' || c == '[') opens[count++] = i;
            else if ((c == ')' || c == ']') && count > 0) count--;
        }
        for (int i = 0; i < count; i++) unclosed[opens[i]] = true;
        return unclosed;
    }

    private static int depthAt(String text, int end, boolean[] unclosed) {
        int depth = 0;
        for (int i = 0; i < end; i++) {
            char c = text.charAt(i);
            if ((c == '(' || c == '[') && !unclosed[i]) depth++;
            else if ((c == ')' || c == ']') && depth > 0) depth--;
        }
        return depth;
    }
}
