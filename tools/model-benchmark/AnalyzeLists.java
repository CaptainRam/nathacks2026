import com.example.allergycheck.analysis.Allergen;
import com.example.allergycheck.analysis.AllergenResult;
import com.example.allergycheck.analysis.AnalysisReport;
import com.example.allergycheck.analysis.Analyzer;
import com.example.allergycheck.analysis.Finding;
import com.example.allergycheck.analysis.IngredientItem;
import com.example.allergycheck.analysis.ParsedLabel;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;

/** A UTF-8/base64 TSV bridge to the app's actual compiled, pure-Java analysis rules. */
public final class AnalyzeLists {
    private static String decode(String value) {
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static void row(PrintWriter output, Object... fields) {
        for (int index = 0; index < fields.length; index++) {
            if (index > 0) output.print('\t');
            output.print(fields[index]);
        }
        output.println();
    }

    public static void main(String[] args) throws Exception {
        try (BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
                PrintWriter output = new PrintWriter(System.out, true, StandardCharsets.UTF_8)) {
            String line;
            while ((line = input.readLine()) != null) {
                String[] fields = line.split("\t", -1);
                String key = fields.length > 0 ? fields[0] : "";
                try {
                    if (fields.length < 3) throw new IllegalArgumentException("Expected key, OCR text and item count");
                    String text = decode(fields[1]);
                    int count = Integer.parseInt(fields[2]);
                    if (count < 0 || fields.length != count + 3) {
                        throw new IllegalArgumentException("Ingredient count does not match TSV fields");
                    }
                    List<String> ingredients = new ArrayList<>();
                    for (int index = 0; index < count; index++) ingredients.add(decode(fields[index + 3]));
                    AnalysisReport report = Analyzer.analyse(ingredients, text, EnumSet.allOf(Allergen.class));
                    row(output, "REPORT", key, report.overall(), report.lowConfidence(),
                            report.label().ingredientHeadingFound());
                    for (IngredientItem item : report.label().ingredients()) {
                        row(output, "INGREDIENT", key, item.depth(), encode(item.original()));
                    }
                    for (ParsedLabel.Statement statement : report.label().statements()) {
                        row(output, "STATEMENT", key, statement.kind(), encode(statement.text()));
                    }
                    for (AllergenResult result : report.results()) {
                        row(output, "ALLERGEN", key, result.allergen(), result.status());
                        for (Finding finding : result.findings()) {
                            row(output, "FINDING", key, result.allergen(), finding.level(), finding.source(),
                                    finding.reason(), encode(finding.matchedTerm()), encode(finding.evidence()));
                        }
                    }
                } catch (Throwable error) {
                    // A bad record is a failed analysis, never an empty or clean label result.
                    row(output, "ERROR", key, encode(error.getClass().getName() + ": " + error.getMessage()));
                }
                row(output, "END", key);
            }
        }
    }
}
