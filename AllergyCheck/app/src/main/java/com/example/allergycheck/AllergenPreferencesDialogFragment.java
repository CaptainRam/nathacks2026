package com.example.allergycheck;

import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.Lifecycle;

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
    static final String SOURCE_VIEW_ID = "source_view_id";
    static final String REQUEST_ID = "request_id";
    static final String RETURN_FOCUS = "return_focus";
    static final String FOCUS_GENERATION = "focus_generation";
    private static final String DRAFT_ALLERGENS = "draft_allergens";

    /** Allergen ids, not labels — the labels are only ever the row text. */
    private final Set<String> selectedAllergens = new HashSet<>();
    private boolean userDismissed;
    private boolean preferencesSaved;
    private int dismissalFocusGeneration = -1;

    static AllergenPreferencesDialogFragment of(int sourceViewId, int requestId) {
        AllergenPreferencesDialogFragment fragment = new AllergenPreferencesDialogFragment();
        Bundle arguments = new Bundle();
        arguments.putInt(SOURCE_VIEW_ID, sourceViewId);
        arguments.putInt(REQUEST_ID, requestId);
        fragment.setArguments(arguments);
        return fragment;
    }

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
                .setNegativeButton(R.string.cancel, (dialog, which) -> markUserDismissed())
                .setPositiveButton(R.string.save_preferences, (dialog, which) -> {
                    AllergenPreferences.saveSelectedAllergenIds(
                            requireContext(), selectedAllergens);
                    preferencesSaved = true;
                    markUserDismissed();
                })
                .create();
    }

    @Override
    public void onCancel(@NonNull DialogInterface dialog) {
        markUserDismissed();
        super.onCancel(dialog);
    }

    private void markUserDismissed() {
        userDismissed = true;
        FragmentActivity host = getActivity();
        dismissalFocusGeneration = host instanceof MainActivity
                ? ((MainActivity) host).allergenDialogFocusGeneration() : -1;
    }

    @Override
    public void onDismiss(@NonNull DialogInterface dialog) {
        FragmentActivity host = getActivity();
        boolean foreground = host != null && !host.isFinishing()
                && !host.isChangingConfigurations()
                && host.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED);
        if (host instanceof MainActivity && getArguments() != null) {
            ((MainActivity) host).onAllergenDialogDismissed(getArguments().getInt(REQUEST_ID));
        }
        super.onDismiss(dialog);
        // Destroying the dialog for recreation must not discard or submit its draft.
        if (userDismissed) {
            Bundle result = new Bundle();
            result.putBoolean(SAVED, preferencesSaved);
            Bundle arguments = getArguments();
            result.putInt(SOURCE_VIEW_ID, arguments == null ? R.id.allergens_button
                    : arguments.getInt(SOURCE_VIEW_ID, R.id.allergens_button));
            result.putInt(REQUEST_ID, arguments == null ? 0 : arguments.getInt(REQUEST_ID));
            result.putBoolean(RETURN_FOCUS, foreground);
            result.putInt(FOCUS_GENERATION, dismissalFocusGeneration);
            getParentFragmentManager().setFragmentResult(RESULT_KEY, result);
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        outState.putStringArrayList(DRAFT_ALLERGENS, new ArrayList<>(selectedAllergens));
        super.onSaveInstanceState(outState);
    }
}
