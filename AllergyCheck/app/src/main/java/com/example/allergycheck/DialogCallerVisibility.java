package com.example.allergycheck;

import android.graphics.Rect;
import android.view.View;
import android.view.ViewTreeObserver;

import java.util.function.BooleanSupplier;

/** Keeps a modal's caller in its scroll viewport without choosing accessibility focus. */
final class DialogCallerVisibility implements AutoCloseable {
    private final View container;
    private final View caller;
    private final BooleanSupplier stillModalActive;
    private final ViewTreeObserver observer;
    private final ViewTreeObserver.OnGlobalLayoutListener layoutListener = this::scheduleCheck;
    private final Runnable pendingCheck = this::ensureCallerVisible;
    private boolean posted;
    private boolean closed;

    /**
     * Must be started and closed on the main thread. The guard must include the original request,
     * modal visibility and resumed lifecycle; dismissing the modal must immediately close this.
     */
    static DialogCallerVisibility start(View container, View caller,
                                        BooleanSupplier stillModalActive) {
        return new DialogCallerVisibility(container, caller, stillModalActive);
    }

    private DialogCallerVisibility(View container, View caller,
                                   BooleanSupplier stillModalActive) {
        this.container = container;
        this.caller = caller;
        this.stillModalActive = stillModalActive;
        observer = container.getViewTreeObserver();
        observer.addOnGlobalLayoutListener(layoutListener);
        scheduleCheck();
    }

    private void scheduleCheck() {
        if (!closed && !posted) {
            posted = container.post(pendingCheck);
        }
    }

    private void ensureCallerVisible() {
        posted = false;
        if (closed) return;
        if (!stillModalActive.getAsBoolean()) {
            close();
            return;
        }
        if (!container.isAttachedToWindow() || !caller.isAttachedToWindow()
                || !caller.isLaidOut() || !caller.isShown()
                || caller.getWidth() == 0 || caller.getHeight() == 0) {
            // A subsequent layout can check again; there is no timed retry after this pass.
            return;
        }
        Rect visible = new Rect();
        Rect bounds = new Rect(0, 0, caller.getWidth(), caller.getHeight());
        if (!caller.getLocalVisibleRect(visible) || !visible.contains(bounds)) {
            caller.requestRectangleOnScreen(bounds, true);
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        container.removeCallbacks(pendingCheck);
        posted = false;
        if (observer.isAlive()) observer.removeOnGlobalLayoutListener(layoutListener);
        ViewTreeObserver current = container.getViewTreeObserver();
        if (current != observer && current.isAlive()) {
            // A pre-attachment observer can have transferred its listeners to the window's tree.
            current.removeOnGlobalLayoutListener(layoutListener);
        }
    }
}
