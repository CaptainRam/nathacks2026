package com.example.allergycheck;

import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;

import com.example.allergycheck.analysis.Allergen;
import com.example.allergycheck.analysis.AllergenDictionary;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** A focused allergen picker that keeps edits local until Save is pressed. */
public class AllergenPreferencesDialogFragment extends DialogFragment {
    static final String TAG = "allergen_preferences_dialog";
    static final String RESULT_KEY = "allergen_preferences_result";
    static final String SAVED = "saved";
    private static final String DRAFT_ALLERGENS = "draft_allergens";

    /** Allergen ids, not labels — the labels are only ever the row text. */
    private final Set<String> selectedAllergens = new HashSet<>();
    private boolean userDismissed;
    private boolean preferencesSaved;

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        AllergenDictionary dictionary = AllergenDictionaryProvider.get(requireContext());
        ArrayList<String> draft = savedInstanceState == null ? null
                : savedInstanceState.getStringArrayList(DRAFT_ALLERGENS);
        selectedAllergens.clear();
        selectedAllergens.addAll(draft == null
                ? AllergenPreferences.selectedAllergenIds(requireContext(), dictionary) : draft);
        List<Allergen> allergens = dictionary.allergens();
        String[] options = new String[allergens.size()];
        String[] ids = new String[allergens.size()];
        boolean[] checked = new boolean[allergens.size()];
        for (int i = 0; i < options.length; i++) {
            options[i] = allergens.get(i).label();
            ids[i] = allergens.get(i).id();
            checked[i] = selectedAllergens.contains(ids[i]);
        }

        return new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.allergens_title)
                .setMultiChoiceItems(options, checked, (dialog, which, isChecked) -> {
                    if (isChecked) {
                        selectedAllergens.add(ids[which]);
                    } else {
                        selectedAllergens.remove(ids[which]);
                    }
                })
                .setNegativeButton(R.string.cancel, (dialog, which) -> userDismissed = true)
                .setPositiveButton(R.string.save_preferences, (dialog, which) -> {
                    AllergenPreferences.saveSelectedAllergenIds(
                            requireContext(), selectedAllergens);
                    preferencesSaved = true;
                    userDismissed = true;
                })
                .create();
    }

    @Override
    public void onCancel(@NonNull DialogInterface dialog) {
        userDismissed = true;
        super.onCancel(dialog);
    }

    @Override
    public void onDismiss(@NonNull DialogInterface dialog) {
        super.onDismiss(dialog);
        // Destroying the dialog for recreation must not discard or submit its draft.
        if (userDismissed) {
            Bundle result = new Bundle();
            result.putBoolean(SAVED, preferencesSaved);
            getParentFragmentManager().setFragmentResult(RESULT_KEY, result);
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        outState.putStringArrayList(DRAFT_ALLERGENS, new ArrayList<>(selectedAllergens));
        super.onSaveInstanceState(outState);
    }
}
