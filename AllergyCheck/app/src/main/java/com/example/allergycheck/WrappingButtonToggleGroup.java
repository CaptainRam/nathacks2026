package com.example.allergycheck;

import android.content.Context;
import android.util.AttributeSet;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;

/** Native single-selection buttons whose labels may grow with the user's font size. */
public final class WrappingButtonToggleGroup extends MaterialButtonToggleGroup {
    public WrappingButtonToggleGroup(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        for (int index = 0; index < getChildCount(); index++) {
            MaterialButton button = (MaterialButton) getChildAt(index);
            // MaterialButtonToggleGroup forces one line when adding children. Restore wrapping
            // after inflation so a large font does not silently abbreviate an option's name.
            button.setSingleLine(false);
            button.setEllipsize(null);
            // LinearLayout measures the tallest label first, then gives both segments its height.
            ViewGroup.LayoutParams params = button.getLayoutParams();
            params.height = ViewGroup.LayoutParams.MATCH_PARENT;
            button.setLayoutParams(params);
        }
    }
}
