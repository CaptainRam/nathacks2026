package com.example.allergycheck;

import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;

import com.example.allergycheck.analysis.Allergen;
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

    private final Set<String> selectedAllergens = new HashSet<>();
    private boolean userDismissed;
    private boolean preferencesSaved;

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        ArrayList<String> draft = savedInstanceState == null ? null
                : savedInstanceState.getStringArrayList(DRAFT_ALLERGENS);
        selectedAllergens.clear();
        selectedAllergens.addAll(draft == null
                ? AllergenPreferences.selectedAllergens(requireContext()) : draft);
        List<Allergen> allergens = AllergenDictionaryProvider.get(requireContext()).allergens();
        String[] options = new String[allergens.size()];
        for (int i = 0; i < options.length; i++) {
            options[i] = allergens.get(i).label();
        }
        boolean[] checked = new boolean[options.length];
        for (int i = 0; i < options.length; i++) {
            checked[i] = selectedAllergens.contains(options[i]);
        }

        return new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.allergens_title)
                .setMultiChoiceItems(options, checked, (dialog, which, isChecked) -> {
                    if (isChecked) {
                        selectedAllergens.add(options[which]);
                    } else {
                        selectedAllergens.remove(options[which]);
                    }
                })
                .setNegativeButton(R.string.cancel, (dialog, which) -> userDismissed = true)
                .setPositiveButton(R.string.save_preferences, (dialog, which) -> {
                    AllergenPreferences.saveSelectedAllergens(requireContext(), selectedAllergens);
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
