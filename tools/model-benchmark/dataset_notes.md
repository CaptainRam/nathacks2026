# Ingredient benchmark dataset

`cases.json` contains 10 diagnostic text cases. Three are actual ML Kit outputs from the **same one real packaging photo**: sideways/enhanced, upright/enhanced, and sideways/unmodified. They test the specific failure observed in the emulator and must not be described as three independent photographs. The remaining cases are synthetic controls. This small suite can show regressions and improvements on these inputs, but cannot establish general label-reading accuracy.

## Source images and transcription

- `/tmp/allergy-current-photo.jpeg`: the current 1200 × 1600 packaging photograph. `/tmp/allergy-label-upright.png` is a deterministic 90° counterclockwise rotation for diagnosis. Their shared English transcription and ground truth are in `source_groups.current_packaging_photo`.
- `/tmp/allergy-label-test.jpg`: a 900 × 1200 rendered six-ingredient label, with a precautionary peanut statement. It is synthetic.
- `/tmp/allergy-sample-label.png`: a 1100 × 850 granola label explicitly marked `SAMPLE LABEL · UI TEST IMAGE`. It is synthetic.

The real label has 18 transcribed English top-level items. The starts of **Soy flour** and **Vegetable oil** sit on the folded left edge. French duplicate text supports those readings, but both are optional in scoring so a model is not penalized for failing to reconstruct obscured letters. The comma between **Peanut butter** and **Soy protein concentrate** is unclear; the words and French duplicate establish two separate items. The English heading and `Contains` prefix are also partly hidden. The raw OCR inputs are copied exactly, not repaired with this transcription before inference.

## Output and scoring contract

Use the project's ingredient prompt: one dash-prefixed top-level ingredient per line, or `NONE`. Preserve bracketed children with the parent. Allergen declarations and precautionary statements remain outside this task's ingredient output.

For semantic ingredient precision/recall, normalize case, accents, punctuation, whitespace and organic asterisks. `expected_aliases` maps canonical items to acceptable French equivalents and spelling variants. Match each canonical item once. Optional items neither reduce recall when missing nor count as a false positive when returned. Extra unrelated items are false positives. Duplicate translations, order violations, and malformed replies should be reported separately. Avoid substring matches: **Cocoa** and **Cocoa butter** are different ingredients.

The two negative cases contain no ingredient list. They must yield `NONE`; returning a precautionary allergen as an ingredient is an extraction failure. This scoring rule does not mean the food has no allergens.

For direct-image runs, use only cases marked `evaluate_vision: true`: the sideways real photo, its upright variant, and the two synthetic fixture images. Report image extraction separately from OCR-text cleanup. Model file size and host memory measurements must also be reported separately from actual Android measurements.

## Reproducibility

Image SHA-256 values are stored in `source_groups`. Raw OCR came from `/tmp/current-photo-diagnostics.txt`, generated with the app's current ML Kit pipeline at 1200 × 1600. Temporary image paths should be copied into an explicitly retained benchmark asset directory if the suite is to be rerun after temporary files are removed.
