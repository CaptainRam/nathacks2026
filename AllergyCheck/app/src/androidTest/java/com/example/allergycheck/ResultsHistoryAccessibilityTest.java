package com.example.allergycheck;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.allergycheck.analysis.AllergenDictionary;
import com.example.allergycheck.analysis.AnalysisReport;
import com.example.allergycheck.analysis.Analyzer;
import com.example.allergycheck.history.ScanHistory;
import com.example.allergycheck.history.ScanHistoryDatabase;
import com.example.allergycheck.history.ScanHistoryItem;
import com.example.allergycheck.history.ScanHistoryStore;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;

/** Speech groups and the non-gesture history action exposed to TalkBack. */
@RunWith(AndroidJUnit4.class)
public class ResultsHistoryAccessibilityTest {
    private final Context context = new ContextThemeWrapper(
            InstrumentationRegistry.getInstrumentation().getTargetContext(),
            R.style.Theme_AllergyCheck);

    @Test
    public void allergenSpeechGroupKeepsNameStatusAndEvidenceTogether() {
        AllergenDictionary dictionary = AllergenDictionaryProvider.get(context);
        AnalysisReport report = Analyzer.analyse("Ingredients: milk powder, sugar.", dictionary,
                Collections.singletonList(dictionary.byId("MILK")));

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            ViewGroup root = (ViewGroup) LayoutInflater.from(context).inflate(
                    R.layout.view_analysis_result, new FrameLayout(context), false);
            new AnalysisResultView(root).showReport(report, true);
            ViewGroup rows = root.findViewById(R.id.allergen_rows);
            assertEquals(1, rows.getChildCount());
            View row = rows.getChildAt(0);
            assertTrue(row.isScreenReaderFocusable());
            assertEquals("Milk", ((TextView) row.findViewById(R.id.allergen_name))
                    .getText().toString());
            assertFalse(((TextView) row.findViewById(R.id.allergen_status)).getText()
                    .toString().isEmpty());
            // Evidence starts collapsed; tapping the row (or TalkBack's click) reveals it.
            assertEquals(View.GONE, row.findViewById(R.id.allergen_evidence).getVisibility());
            assertFalse(((TextView) row.findViewById(R.id.allergen_evidence)).getText()
                    .toString().isEmpty());
            assertTrue(row.isClickable());
            assertEquals(context.getString(R.string.a11y_allergen_row_collapsed),
                    String.valueOf(row.getStateDescription()));
            assertTrue(row.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null));
            assertEquals(View.VISIBLE, row.findViewById(R.id.allergen_evidence).getVisibility());
            assertEquals(context.getString(R.string.a11y_allergen_row_expanded),
                    String.valueOf(row.getStateDescription()));
            assertTrue(root.findViewById(R.id.verdict_title).isAccessibilityHeading());
        });
    }

    @Test
    public void displayChangesReuseTheRemainingAllergenReadingNodesAndPreserveEvidence() {
        AllergenDictionary dictionary = AllergenDictionaryProvider.get(context);
        AnalysisReport report = Analyzer.analyse("Ingredients: sugar, water, salt. May contain soy.",
                dictionary, Arrays.asList(dictionary.byId("SOY"), dictionary.byId("MILK")));
        boolean hadPreference = AllergenPreferences.preferences(context).contains("may_contain_red_warning");
        boolean original = AllergenPreferences.mayContainRedWarning(context);
        boolean hadShowPreference = AllergenPreferences.preferences(context).contains("show_not_detected");
        boolean originalShowNotDetected = AllergenPreferences.showNotDetected(context);
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                ViewGroup root = (ViewGroup) LayoutInflater.from(context).inflate(
                        R.layout.view_analysis_result, new FrameLayout(context), false);
                AnalysisResultView renderer = new AnalysisResultView(root);
                AllergenPreferences.saveMayContainRedWarning(context, true);
                renderer.showReport(report, true);
                ViewGroup rows = root.findViewById(R.id.allergen_rows);
                assertEquals(2, rows.getChildCount());
                View soy = rows.getChildAt(0);
                View heading = root.findViewById(R.id.verdict_title);
                String status = ((TextView) soy.findViewById(R.id.allergen_status)).getText().toString();
                String evidence = ((TextView) soy.findViewById(R.id.allergen_evidence)).getText().toString();
                // A new renderer over an already populated subtree must reuse every current row.
                renderer = new AnalysisResultView(root);
                renderer.showReport(report, true);
                assertEquals(2, rows.getChildCount());
                assertSame(soy, rows.getChildAt(0));
                // Not-detected rows carry no evidence, so they are plain, non-expandable text.
                View milk = rows.getChildAt(1);
                assertFalse(milk.isClickable());
                assertEquals(View.INVISIBLE, milk.findViewById(R.id.allergen_expand_icon).getVisibility());
                assertEquals(View.VISIBLE, soy.findViewById(R.id.allergen_expand_icon).getVisibility());
                assertTrue(soy.performClick());
                AllergenPreferences.saveMayContainRedWarning(context, false);
                AllergenPreferences.saveShowNotDetected(context, false);
                renderer.refreshDisplayPreferences();
                assertEquals(1, rows.getChildCount());
                assertSame(soy, rows.getChildAt(0));
                assertSame(heading, root.findViewById(R.id.verdict_title));
                TextView statusView = soy.findViewById(R.id.allergen_status);
                assertEquals(status, statusView.getText().toString());
                assertEquals(evidence, ((TextView) soy.findViewById(R.id.allergen_evidence)).getText().toString());
                // An opened row stays open when display preferences refresh the result.
                assertEquals(View.VISIBLE, soy.findViewById(R.id.allergen_evidence).getVisibility());
                assertEquals(context.getColor(R.color.status_warning_container),
                        statusView.getBackgroundTintList().getDefaultColor());
                AllergenPreferences.saveShowNotDetected(context, true);
                renderer.refreshDisplayPreferences();
                assertSame(soy, rows.getChildAt(0));
                assertEquals(2, rows.getChildCount());
                // A new scan reuses the row but must not inherit the previous scan's open state.
                assertEquals(View.VISIBLE, soy.findViewById(R.id.allergen_evidence).getVisibility());
                renderer.showReport(Analyzer.analyse("Ingredients: sugar, water, salt. May contain soy.",
                        dictionary, Arrays.asList(dictionary.byId("SOY"), dictionary.byId("MILK"))), true);
                assertSame(soy, rows.getChildAt(0));
                assertEquals(View.GONE, soy.findViewById(R.id.allergen_evidence).getVisibility());
                renderer.showMessage("Read failed", "Fixture body");
                renderer.refreshDisplayPreferences();
                assertEquals("Read failed", ((TextView) root.findViewById(R.id.verdict_title)).getText().toString());
                assertEquals(0, rows.getChildCount());
            });
        } finally {
            if (hadPreference) AllergenPreferences.saveMayContainRedWarning(context, original);
            else AllergenPreferences.preferences(context).edit().remove("may_contain_red_warning").commit();
            if (hadShowPreference) AllergenPreferences.saveShowNotDetected(context, originalShowNotDetected);
            else AllergenPreferences.preferences(context).edit().remove("show_not_detected").commit();
        }
    }

    @Test
    public void deleteActionUsesTheCurrentScanAfterTheHolderIsRebound() {
        AllergenDictionary dictionary = AllergenDictionaryProvider.get(context);
        AnalysisReport report = Analyzer.analyse("Ingredients: milk powder, sugar.", dictionary,
                Collections.singletonList(dictionary.byId("MILK")));
        ScanHistoryDatabase database = new ScanHistoryDatabase(context);
        ScanHistoryStore store = new ScanHistoryStore(database,
                new File(context.getFilesDir(), "history"));
        long firstId = store.insert(System.currentTimeMillis(), report);
        long secondId = store.insert(System.currentTimeMillis(), report);
        try {
            ScanHistoryItem first = store.detail(firstId).item();
            ScanHistoryItem second = store.detail(secondId).item();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                long[] deletedId = {-1};
                ScanHistoryAdapter adapter = new ScanHistoryAdapter(new HistorySummary(context),
                        ScanHistory.get(context), new ScanHistoryAdapter.Listener() {
                    @Override
                    public void onScanClicked(@NonNull ScanHistoryItem item) { }

                    @Override
                    public void onScanSwiped(int position, @NonNull ScanHistoryItem item) { }

                    @Override
                    public void onScanLongPressed(@NonNull ScanHistoryItem item) {
                        deletedId[0] = item.id();
                    }
                });
                try {
                    adapter.submit(Arrays.asList(first, second));
                    ScanHistoryAdapter.Row holder = adapter.onCreateViewHolder(
                            new FrameLayout(context), 0);
                    adapter.onBindViewHolder(holder, 0);
                    String firstDescription = holder.itemView.getContentDescription().toString();
                    int deleteAction = deleteAction(holder.itemView);
                    assertTrue(holder.itemView.performAccessibilityAction(deleteAction, null));
                    assertEquals(firstId, deletedId[0]);

                    adapter.onBindViewHolder(holder, 1);
                    assertFalse(firstDescription.contentEquals(
                            holder.itemView.getContentDescription()));
                    assertEquals(deleteAction, deleteAction(holder.itemView));
                    assertTrue(holder.itemView.performAccessibilityAction(deleteAction, null));
                    assertEquals(secondId, deletedId[0]);
                    ViewGroup contents = (ViewGroup) ((ViewGroup) holder.itemView).getChildAt(0);
                    assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
                            contents.getImportantForAccessibility());

                    adapter.onViewRecycled(holder);
                    assertFalse(holder.itemView.performAccessibilityAction(deleteAction, null));
                } finally {
                    adapter.shutDown();
                }
            });
        } finally {
            store.delete(firstId);
            store.delete(secondId);
            database.close();
        }
    }

    private int deleteAction(View row) {
        AccessibilityNodeInfo node = row.createAccessibilityNodeInfo();
        assertNotNull(node);
        int matches = 0;
        int actionId = -1;
        for (AccessibilityNodeInfo.AccessibilityAction action : node.getActionList()) {
            if (action.getId() != AccessibilityNodeInfo.ACTION_LONG_CLICK
                    && context.getString(R.string.a11y_history_delete).contentEquals(
                    action.getLabel() == null ? "" : action.getLabel())) {
                matches++;
                actionId = action.getId();
            }
        }
        assertEquals("Exactly one delete action should be exposed: " + node.getActionList(),
                1, matches);
        return actionId;
    }
}
