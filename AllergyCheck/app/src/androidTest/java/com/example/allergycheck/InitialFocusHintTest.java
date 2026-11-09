package com.example.allergycheck;

import static org.junit.Assert.*;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import android.widget.FrameLayout;

import androidx.appcompat.view.ContextThemeWrapper;
import androidx.core.view.AccessibilityDelegateCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.google.android.material.button.MaterialButton;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** The advisory decorator must coexist with real Material and app-defined accessibility behavior. */
@RunWith(AndroidJUnit4.class)
public class InitialFocusHintTest {
    @Test
    public void decorationPreservesNamedActionsVirtualNodesAndEventPolicyWithoutFocusing() {
        Context context = new ContextThemeWrapper(
                InstrumentationRegistry.getInstrumentation().getTargetContext(),
                R.style.Theme_AllergyCheck);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            AtomicBoolean requested = new AtomicBoolean();
            AtomicInteger forcedActions = new AtomicInteger();
            AtomicInteger deleted = new AtomicInteger();
            MaterialButton button = new MaterialButton(context);
            button.setText("Delete saved scan");
            button.setCheckable(true);
            button.setChecked(true);
            ViewCompat.setAccessibilityDelegate(button, new AccessibilityDelegateCompat() {
                @Override
                public void onInitializeAccessibilityNodeInfo(View host,
                                                              AccessibilityNodeInfoCompat info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setContentDescription("Original saved scan name");
                    info.setRoleDescription("Saved scan action");
                }

                @Override
                public boolean performAccessibilityAction(View host, int action, Bundle arguments) {
                    if (action == AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS) {
                        forcedActions.incrementAndGet();
                    }
                    return super.performAccessibilityAction(host, action, arguments);
                }
            });
            int deleteAction = ViewCompat.addAccessibilityAction(button,
                    context.getString(R.string.a11y_history_delete), (host, arguments) -> {
                        deleted.incrementAndGet();
                        return true;
                    });
            AccessibilityNodeInfo before = button.createAccessibilityNodeInfo();
            CharSequence materialRole = before.getClassName();
            assertTrue(before.isCheckable());
            assertTrue(before.isChecked());
            before.recycle();

            InitialFocusHint.install(button, requested::get);
            for (boolean value : new boolean[] {false, true, false}) {
                requested.set(value);
                AccessibilityNodeInfo node = button.createAccessibilityNodeInfo();
                try {
                    assertEquals(value, node.hasRequestInitialAccessibilityFocus());
                    assertEquals(materialRole, node.getClassName());
                    assertTrue(node.isCheckable());
                    assertTrue(node.isChecked());
                    assertEquals("Original saved scan name", node.getContentDescription());
                    assertEquals("Saved scan action",
                            AccessibilityNodeInfoCompat.wrap(node).getRoleDescription());
                    assertTrue(node.getActionList().stream().anyMatch(action ->
                            action.getId() == deleteAction && context.getString(
                                    R.string.a11y_history_delete).contentEquals(action.getLabel())));
                } finally {
                    node.recycle();
                }
            }
            assertTrue(button.performAccessibilityAction(deleteAction, null));
            assertEquals(1, deleted.get());
            assertEquals("Changing an advisory property must never issue a focus command",
                    0, forcedActions.get());

            // A second app-defined component exposes virtual nodes and extra data, and owns an
            // event policy. These callbacks must retain their original return values and payloads.
            FrameLayout component = new FrameLayout(context);
            ExistingComponentDelegate original = new ExistingComponentDelegate(forcedActions);
            component.setAccessibilityDelegate(original);
            requested.set(true);
            InitialFocusHint.install(component, requested::get);
            assertSame(original.provider, component.getAccessibilityNodeProvider());
            View.AccessibilityDelegate decorated = component.getAccessibilityDelegate();
            AccessibilityNodeInfo virtualHost = AccessibilityNodeInfo.obtain();
            AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_SELECTED);
            try {
                decorated.onInitializeAccessibilityNodeInfo(component, virtualHost);
                assertEquals("Original virtual component", virtualHost.getContentDescription());
                assertEquals("fixture.VirtualControl", virtualHost.getClassName());
                assertTrue(virtualHost.hasRequestInitialAccessibilityFocus());
                Bundle arguments = new Bundle();
                arguments.putInt("requestedOffset", 42);
                decorated.addExtraDataToAccessibilityNodeInfo(component, virtualHost,
                        "fixture.characterRanges", arguments);
                assertEquals(42, virtualHost.getExtras().getInt("fixture.offset"));
                assertEquals("fixture.characterRanges", virtualHost.getExtras().getString("fixture.key"));

                assertEquals(0, original.publishedEvents);
                component.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED);
                assertEquals(AccessibilityEvent.TYPE_VIEW_SELECTED, original.lastEventType);
                component.sendAccessibilityEventUnchecked(event);
                assertEquals("Original unchecked policy", event.getContentDescription());
                assertEquals(2, original.publishedEvents);
                assertTrue(component.dispatchPopulateAccessibilityEvent(event));
                assertTrue(event.getText().contains("Original dispatch summary"));
                component.onPopulateAccessibilityEvent(event);
                assertTrue(event.getText().contains("Original populated summary"));
                component.onInitializeAccessibilityEvent(event);
                assertEquals("Original initialized summary", event.getContentDescription());
                assertFalse("The decorator must preserve the app's child-event veto",
                        decorated.onRequestSendAccessibilityEvent(component, button, event));
                assertEquals(0, forcedActions.get());
            } finally {
                virtualHost.recycle();
                event.recycle();
            }
        });
    }

    /** Observable behavior of a component that already owns its node and event semantics. */
    private static final class ExistingComponentDelegate extends View.AccessibilityDelegate {
        final AccessibilityNodeProvider provider = new AccessibilityNodeProvider() { };
        final AtomicInteger forcedActions;
        int publishedEvents;
        int lastEventType;

        ExistingComponentDelegate(AtomicInteger forcedActions) {
            this.forcedActions = forcedActions;
        }

        @Override public boolean performAccessibilityAction(View host, int action, Bundle arguments) {
            if (action == AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS) {
                forcedActions.incrementAndGet();
            }
            return super.performAccessibilityAction(host, action, arguments);
        }

        @Override public AccessibilityNodeProvider getAccessibilityNodeProvider(View host) {
            return provider;
        }

        @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
            info.setContentDescription("Original virtual component");
            info.setClassName("fixture.VirtualControl");
        }

        @Override public void addExtraDataToAccessibilityNodeInfo(View host, AccessibilityNodeInfo info,
                                                                 String key, Bundle arguments) {
            info.getExtras().putString("fixture.key", key);
            info.getExtras().putInt("fixture.offset", arguments.getInt("requestedOffset"));
        }

        @Override public void sendAccessibilityEvent(View host, int type) {
            publishedEvents++;
            lastEventType = type;
        }

        @Override public void sendAccessibilityEventUnchecked(View host, AccessibilityEvent event) {
            publishedEvents++;
            event.setContentDescription("Original unchecked policy");
        }

        @Override public boolean dispatchPopulateAccessibilityEvent(View host, AccessibilityEvent event) {
            event.getText().add("Original dispatch summary");
            return true;
        }

        @Override public void onPopulateAccessibilityEvent(View host, AccessibilityEvent event) {
            event.getText().add("Original populated summary");
        }

        @Override public void onInitializeAccessibilityEvent(View host, AccessibilityEvent event) {
            event.setContentDescription("Original initialized summary");
        }

        @Override public boolean onRequestSendAccessibilityEvent(ViewGroup host, View child,
                                                                AccessibilityEvent event) {
            return false;
        }
    }
}
