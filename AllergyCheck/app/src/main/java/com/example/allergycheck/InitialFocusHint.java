package com.example.allergycheck;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;

import java.util.function.BooleanSupplier;

/** Declares a fallback reading target; the screen reader retains control of focus. */
final class InitialFocusHint {
    private InitialFocusHint() { }

    static void install(View target, BooleanSupplier requested) {
        target.setAccessibilityDelegate(new Delegate(target) {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                // A candidate when TalkBack needs an initial position, never a focus command.
                info.setRequestInitialAccessibilityFocus(requested.getAsBoolean());
            }
        });
    }

    /** Keeps existing Material / RecyclerView semantics, named actions and virtual nodes intact. */
    static class Delegate extends View.AccessibilityDelegate {
        private final View.AccessibilityDelegate original;

        Delegate(View target) {
            View.AccessibilityDelegate delegate = target.getAccessibilityDelegate();
            original = delegate == null ? new View.AccessibilityDelegate() : delegate;
        }

        @Override
        public void sendAccessibilityEvent(View host, int type) {
            original.sendAccessibilityEvent(host, type);
        }

        @Override
        public boolean performAccessibilityAction(View host, int action, Bundle arguments) {
            return original.performAccessibilityAction(host, action, arguments);
        }

        @Override
        public void sendAccessibilityEventUnchecked(View host, AccessibilityEvent event) {
            original.sendAccessibilityEventUnchecked(host, event);
        }

        @Override
        public boolean dispatchPopulateAccessibilityEvent(View host, AccessibilityEvent event) {
            return original.dispatchPopulateAccessibilityEvent(host, event);
        }

        @Override
        public void onPopulateAccessibilityEvent(View host, AccessibilityEvent event) {
            original.onPopulateAccessibilityEvent(host, event);
        }

        @Override
        public void onInitializeAccessibilityEvent(View host, AccessibilityEvent event) {
            original.onInitializeAccessibilityEvent(host, event);
        }

        @Override
        public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
            original.onInitializeAccessibilityNodeInfo(host, info);
        }

        @Override
        public void addExtraDataToAccessibilityNodeInfo(View host, AccessibilityNodeInfo info,
                                                        String key, Bundle arguments) {
            original.addExtraDataToAccessibilityNodeInfo(host, info, key, arguments);
        }

        @Override
        public boolean onRequestSendAccessibilityEvent(ViewGroup host, View child,
                                                        AccessibilityEvent event) {
            return original.onRequestSendAccessibilityEvent(host, child, event);
        }

        @Override
        public AccessibilityNodeProvider getAccessibilityNodeProvider(View host) {
            return original.getAccessibilityNodeProvider(host);
        }
    }
}
