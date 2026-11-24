package com.arnavjot.glimpse.ui;

import android.view.View;

import androidx.annotation.NonNull;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * Edge-to-edge support: pads a view by the system bars (and display cutout) on the
 * sides asked for, on top of the padding it already has in XML.
 *
 * <p>The XML padding is captured once, so repeated inset passes (rotation, keyboard,
 * bars showing or hiding) never stack. Insets are not consumed, so sibling and child
 * views still receive them.
 */
public final class SystemBarInsets {

    public static final int TOP = 1;
    public static final int BOTTOM = 1 << 1;

    private SystemBarInsets() {
    }

    /** Pads {@code view} by the system bar insets on {@code sides} ({@link #TOP}, {@link #BOTTOM}). */
    public static void pad(@NonNull View view, int sides) {
        // Relative padding, because the layouts use paddingStart/paddingEnd (RTL-safe).
        final int start = view.getPaddingStart();
        final int top = view.getPaddingTop();
        final int end = view.getPaddingEnd();
        final int bottom = view.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(view, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            boolean rtl = v.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            v.setPaddingRelative(
                    start + (rtl ? bars.right : bars.left),
                    top + ((sides & TOP) != 0 ? bars.top : 0),
                    end + (rtl ? bars.left : bars.right),
                    bottom + ((sides & BOTTOM) != 0 ? bars.bottom : 0));
            return windowInsets;
        });
        requestWhenAttached(view);
    }

    /**
     * Views attached after the window's last inset pass (RecyclerView pages, for one)
     * don't get insets until something asks again, so ask on attach.
     */
    private static void requestWhenAttached(@NonNull View view) {
        if (view.isAttachedToWindow()) {
            ViewCompat.requestApplyInsets(view);
            return;
        }
        view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(@NonNull View v) {
                v.removeOnAttachStateChangeListener(this);
                ViewCompat.requestApplyInsets(v);
            }

            @Override
            public void onViewDetachedFromWindow(@NonNull View v) {
            }
        });
    }
}
