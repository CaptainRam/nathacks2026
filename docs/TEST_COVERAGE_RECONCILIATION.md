# Test coverage reconciliation

Compared `origin/analysis` at `9a9623b` with `main` at `d8f8b00` on 2026-11-08.
The [imported test plan](TEST_CASES.md) describes the older `24bb5b1` baseline.
The follow-up fixes all 31 reproduced regressions identified during reconciliation
and removes their `@Ignore` annotations. Production parsing, matching, confidence,
model reply handling, model discovery and OCR cancellation now reflect these fixes.

## The 31 regressions

| Original cases | Count | Fix and enabled coverage |
| --- | --- | --- |
| X-12/20/21/30/32/34/41/50 | 9 | `IngredientExtractorCoverageTest`: preserve post-period ingredients; repair heading I/l, punctuation and marketing precedence; restore uppercase wraps; keep E-numbers; separate traces warnings |
| S-05/10/12/14 | 5 | `LabelParserCoverageTest`: unclosed brackets cannot hide warnings; shared equipment, may-also/could-contain forms recognized; quantity prefixes stay with ingredients |
| A-30/40/41 | 6 | `AllergenSafetyRegressionTest`: punctuation cannot turn Milk, Free Range Egg into milk-free; bounded visual OCR confusions produce POSSIBLE with original evidence |
| Z-07 | 1 | `AnalyzerCoverageTest`: a truncated external ingredient list triggers low confidence and retains omitted raw-text findings, including nested milk evidence |
| B-07, P-09/15/16/20/21/31/32 | 8 | `IngredientPromptCoverageTest`: escape model control tokens; accept dash variants; preserve continuation/nesting; deduplicate case-insensitively; reject unrelated replies ending in NONE |
| M-03 | 1 | `ModelFilesCoverageTest`: discover and delete `.task` files without case sensitivity |
| O-07 failure variant | 1 | `OcrPipelineContractTest`: queued decode/preparation errors recheck cancellation on delivery, including runtime and out-of-memory failures |

Z-07 now explicitly supplies a deliberately truncated list rather than relying on
the real extractor to truncate at a period. It still asserts low confidence and
additionally requires the omitted whey to produce a CONTAINS finding. The real
extractor is tested separately and no longer drops that text.

## Additional regression guards

- `LabelSyntax` shares structural rules between extraction and fallback parsing.
  It retains uncertain punctuation until a recognized storage/nutrition panel,
  warning, or second ingredient heading. Both parsers now use only the first
  ingredient list, while statements are still collected from the full label.
  The older `AnalyzerTest` that appended the second list was updated to this
  consistent behavior and checks that a later peanut warning is retained.
- Hyphen repair joins known single-word spellings while preserving real compound
  and free-claim boundaries. Dictionary-driven tests split every eligible shipped
  single-word allergen across three lines and require exact ingredient evidence
  through both raw and extracted paths. All dictionary vocabulary tokens are also
  checked so future dictionary changes cannot silently outgrow the spelling aid.
- Marketing-semicolon headings cannot beat a later real colon heading. Balanced
  parenthesized mentions, prose and longer-word lookalikes are rejected as headings.
- Attached/wrapped Unicode hyphens preserve tree-nut, milk-fat and free claims;
  spaced list dashes keep adjacent declarations separate. Short-word typo recovery
  uses one recognized visual confusion, not unrestricted edit distance. Ordinary
  words, punctuation-separated fragments and explicit free claims have controls.
- Missing raw ingredient evidence is recovered without duplicating statements or
  changing the supplied ingredient display hierarchy. Complete extraction is not
  marked uncertain; unrelated nutrition text does not become ingredient evidence.
  A wholly unusable external list falls back to the readable raw label so callers
  cannot hide a recovered verdict by treating its display label as empty.
- Model replies mixing brackets with indented child bullets keep milk fat once at
  child depth. Strict NONE handling retains the existing sanctioned Answer: NONE
  and standalone sentinel forms without accepting prose or allergen verdicts.
- Device tests cover cancellation during bitmap/URI preparation, including OOM,
  suppress both completion and stage callbacks, and check ownership/recycling.
  Worker termination plus a main-thread barrier replaces the prior polling delay.
- Existing preference migration, UI wording/colors/icons, selection rotation,
  selection-triggered rescan, serialization and vocabulary tests remain enabled.
  A real cropped-panel scan additionally checks that milk and its low-confidence
  warning are shown even without an Ingredients heading.

## Validation

Full JVM suite: **328 passed, zero failures, zero skipped**. The application and
instrumentation APKs compile successfully with the existing Android SDK/JDK 25.
The final Android 17 / API 37 arm64 emulator run includes the four new coverage
classes plus the existing `MainActivityOcrIntegrationTest` and `ocr.OcrPipelineTest`:
**43 passed, zero failures, zero skipped**, in 66.454 seconds. This includes all
30 tests in the new device coverage classes and 13 existing OCR/photo-flow tests.
All 31 original regressions are enabled and passing; no test-source `@Ignore`
annotations remain. A result-action assertion waits for the reveal animation to
settle while retaining its original final-state expectation.

```sh
cd AllergyCheck
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --offline --console=plain
```

This machine uses an isolated ADB service to avoid other sessions competing for
emulator connections. Device execution installs both APKs with
`adb -P 5041 -s emulator-5690 install --no-streaming -r -t <apk>`, then invokes
`adb -P 5041 -s emulator-5690 shell am instrument -w -r -e class <classes> com.example.allergycheck.test/androidx.test.runner.AndroidJUnitRunner`.
Counts are checked against AndroidJUnitRunner status records, rather than treating
an APK build or Gradle exit status alone as passing device-test evidence.

Current local artifacts (generated, not committed):

- `AllergyCheck/app/build/test-results/testDebugUnitTest/TEST-*.xml`
- `AllergyCheck/app/build/reports/coverage-reconciliation/android-instrumentation-fixed.txt`

Earlier reproduction logs are historical evidence, not current failures.
Symbolic-link setup tests skip on filesystems that prohibit links; all ran here.

## Remaining scope from the historical plan

The original plan predates matching/local-AI/OpenRouter/Decisions modes and scan
history. Existing AI/history tests remain in place; cloud privacy expectations
must be scoped by mode. F-10's old claim that fallback findings are always discarded
is stale on current main: it checks whether the parsed label is empty, and the
new cropped-panel device test exercises the actual fallback behavior. X-32's
period-damaged heading now has an enabled extraction regression.

The 31 fixes do not add the unbuilt dictionary groups/cereals (A-50/A-51), decide
ambiguous warning vocabulary (A-42), or establish a caller-visible prompt-truncation
contract (B-05). Checked-allergen visibility with hidden clean rows (W-11/G3),
process-kill preference persistence, remaining camera/picker/error flows, revoked
URI permissions, EXIF fixtures, native-model/download fault injection, real 50 MP
memory pressure, physical label accuracy, accessibility, backup/privacy review and
performance measurements still need their respective implementation or dedicated
manual/device work. The current test results do not establish release readiness.
