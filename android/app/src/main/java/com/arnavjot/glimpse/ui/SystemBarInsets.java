package com.arnavjot.glimpse.ui;

import android.graphics.Rect;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.widget.NestedScrollView;

import com.arnavjot.glimpse.R;
import com.google.android.material.textfield.TextInputLayout;

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
    /** Bottom padding also covers the on-screen keyboard while it is open. */
    private static final int KEYBOARD = 1 << 2;

    private SystemBarInsets() {
    }

    /** Pads {@code view} by the system bar insets on {@code sides} ({@link #TOP}, {@link #BOTTOM}). */
    public static void pad(@NonNull View view, int sides) {
        pad(view, sides, null);
    }

    /**
     * For a scrolling form: pads it by the keyboard as well as the navigation bar, and keeps
     * the focused field on screen when the keyboard opens or focus moves to another field.
     *
     * <p>Needed because the activity is edge-to-edge: the window is no longer resized for the
     * keyboard, so {@code adjustResize} alone does nothing and the keyboard covers the lower
     * fields (the password, a clip's title). The bottom padding makes room to scroll them up,
     * and the scroll brings the focused one, with its label and helper text, into view.
     */
    public static void padForKeyboard(@NonNull NestedScrollView scroll) {
        pad(scroll, BOTTOM | KEYBOARD, () -> revealFocused(scroll));
        // Moving from one field to the next while the keyboard is already open.
        scroll.getViewTreeObserver().addOnGlobalFocusChangeListener(
                (oldFocus, newFocus) -> revealFocused(scroll));
    }

    private static void pad(@NonNull View view, int sides, @Nullable Runnable afterApply) {
        // Relative padding, because the layouts use paddingStart/paddingEnd (RTL-safe).
        final int start = view.getPaddingStart();
        final int top = view.getPaddingTop();
        final int end = view.getPaddingEnd();
        final int bottom = view.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(view, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            int bottomInset = bars.bottom;
            if ((sides & KEYBOARD) != 0) {
                // The keyboard's inset already includes the navigation bar below it.
                bottomInset = Math.max(bottomInset, windowInsets.getInsets(WindowInsetsCompat.Type.ime()).bottom);
            }
            boolean rtl = v.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            v.setPaddingRelative(
                    start + (rtl ? bars.right : bars.left),
                    top + ((sides & TOP) != 0 ? bars.top : 0),
                    end + (rtl ? bars.left : bars.right),
                    bottom + ((sides & BOTTOM) != 0 ? bottomInset : 0));
            if (afterApply != null) afterApply.run();
            return windowInsets;
        });
        requestWhenAttached(view);
    }

    /** Scrolls so the focused field (its whole TextInputLayout) sits above the keyboard. */
    private static void revealFocused(@NonNull NestedScrollView scroll) {
        // Posted: runs after the new padding has been laid out.
        scroll.post(() -> {
            View focused = scroll.findFocus();
            if (focused == null) return;
            View field = focused;
            for (View v = focused; v != null && v != scroll; v = v.getParent() instanceof View ? (View) v.getParent() : null) {
                if (v instanceof TextInputLayout) {
                    field = v;
                    break;
                }
            }
            Rect r = new Rect();
            field.getDrawingRect(r);
            scroll.offsetDescendantRectToMyCoords(field, r);
            int margin = scroll.getResources().getDimensionPixelSize(R.dimen.pn_space_4);
            int visibleTop = scroll.getScrollY();
            int visibleBottom = visibleTop + scroll.getHeight() - scroll.getPaddingBottom();
            if (r.bottom + margin > visibleBottom) {
                scroll.smoothScrollBy(0, r.bottom + margin - visibleBottom);
            } else if (r.top - margin < visibleTop) {
                scroll.smoothScrollBy(0, r.top - margin - visibleTop);
            }
        });
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
