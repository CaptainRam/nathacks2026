package com.example.allergycheck;

import android.content.Context;
import android.util.AttributeSet;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.card.MaterialCardView;

/** A photo menu is a button; apply its role after MaterialCardView populates its own node. */
public final class PhotoActionCardView extends MaterialCardView {
    public PhotoActionCardView(@NonNull Context context, @Nullable AttributeSet attributes) {
        super(context, attributes);
    }

    public PhotoActionCardView(@NonNull Context context, @Nullable AttributeSet attributes,
                               int defaultStyleAttribute) {
        super(context, attributes, defaultStyleAttribute);
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(@NonNull AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName(Button.class.getName());
    }
}
