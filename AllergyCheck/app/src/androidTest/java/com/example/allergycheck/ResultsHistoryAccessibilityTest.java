package com.example.allergycheck;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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
            assertEquals(View.VISIBLE, row.findViewById(R.id.allergen_evidence).getVisibility());
            assertFalse(((TextView) row.findViewById(R.id.allergen_evidence)).getText()
                    .toString().isEmpty());
            assertTrue(root.findViewById(R.id.verdict_title).isAccessibilityHeading());
        });
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
