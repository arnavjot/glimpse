package com.arnavjot.glimpse.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.widget.ImageViewCompat;

import com.arnavjot.glimpse.R;
import com.arnavjot.glimpse.databinding.ViewFeedOverlayBinding;
import com.arnavjot.glimpse.pojo.VideoEntity;

import java.text.NumberFormat;

/**
 * The feed's fixed overlay: creator, title and the like rail for the clip on screen.
 *
 * <p>One instance for the whole feed (not one per page), so it stays still while clips
 * slide underneath. {@link #show} swaps in a new clip with a short cross-fade and
 * updates the same clip (a like confirmed by Room) in place.
 *
 * <p>Like taps: an instant optimistic flip plus a "set liked = X" intent to the
 * listener; Room's confirmation arrives later through {@link #show}.
 */
final class FeedOverlay {

    interface Actions {
        /** The user asked for {@code video} to be liked ({@code true}) or unliked. */
        void onLikeChanged(@NonNull VideoEntity video, boolean liked);

        void onShare(@NonNull VideoEntity video);

        void onMuteToggled();

        /** A sponsored clip came on screen (an impression). */
        void onSponsoredShown(@NonNull VideoEntity video);

        /** Its "Learn more" was tapped. */
        void onSponsoredCta(@NonNull VideoEntity video);
    }

    private static final long FADE_OUT_MS = 90;
    private static final long FADE_IN_MS = 160;
    private static final float POP_SCALE = 1.25f;
    private static final long POP_UP_MS = 120;
    private static final long POP_DOWN_MS = 220;

    private final ViewFeedOverlayBinding binding;
    private final Actions listener;
    private final NumberFormat countFormat = NumberFormat.getIntegerInstance();
    private final ColorStateList likedTint;
    private final ColorStateList idleTint;

    /** The clip the overlay is showing (or fading to); null when hidden. */
    @Nullable private VideoEntity shown;
    /** What the like button currently shows, so a tap flips from that. */
    private boolean shownLiked;
    private int shownCount;

    FeedOverlay(@NonNull ViewFeedOverlayBinding binding, @NonNull Actions listener) {
        this.binding = binding;
        this.listener = listener;
        Context context = binding.getRoot().getContext();
        likedTint = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.pn_dark_like));
        idleTint = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.pn_feed_ink));
        binding.like.setOnClickListener(this::onLikeClicked);
        binding.share.setOnClickListener(v -> {
            if (shown != null) listener.onShare(shown);
        });
        binding.mute.setOnClickListener(v -> listener.onMuteToggled());
        binding.cta.setOnClickListener(v -> {
            if (shown != null && shown.sponsored) listener.onSponsoredCta(shown);
        });
    }

    /** Shows the feed-wide sound state on the mute button. */
    void setMuted(boolean muted) {
        binding.mute.setImageResource(muted ? R.drawable.ic_volume_off : R.drawable.ic_volume_up);
        binding.mute.setContentDescription(binding.getRoot().getContext()
                .getString(muted ? R.string.unmute : R.string.mute));
    }

    /** Shows {@code video}'s details, or hides the overlay when it is null. */
    void show(@Nullable VideoEntity video) {
        View root = binding.getRoot();
        if (video == null) {
            root.animate().cancel();
            root.setVisibility(View.GONE);
            binding.like.setEnabled(true);
            shown = null;
            return;
        }
        boolean sameClip = shown != null && shown.id.equals(video.id) && root.getVisibility() == View.VISIBLE;
        shown = video;
        if (sameClip) {
            // A like confirmed by Room: update in place, without cutting off the pop.
            renderLike(video.liked, video.likeCount);
            return;
        }
        root.animate().cancel();
        if (root.getVisibility() != View.VISIBLE) {
            // First appearance: nothing old to fade out, so fill it in and fade in.
            renderClip(video);
            root.setAlpha(0f);
            root.setVisibility(View.VISIBLE);
            root.animate().alpha(1f).setDuration(FADE_IN_MS).start();
            return;
        }
        // While the old details fade out the like button would act on the new clip
        // with the old clip's state, so it is off until the new details are in.
        binding.like.setEnabled(false);
        root.animate()
                .alpha(0f)
                .setDuration(FADE_OUT_MS)
                .withEndAction(() -> {
                    // Render the latest clip, not the one captured when the fade began.
                    if (shown != null) renderClip(shown);
                    binding.like.setEnabled(true);
                    root.animate().alpha(1f).setDuration(FADE_IN_MS).start();
                })
                .start();
    }

    private void onLikeClicked(@NonNull View view) {
        VideoEntity video = shown;
        if (video == null) return;
        boolean liked = !shownLiked;
        renderLike(liked, Math.max(0, shownCount + (liked ? 1 : -1))); // instant feedback
        if (liked) pop(binding.like);
        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
        listener.onLikeChanged(video, liked);
    }

    /** A different clip: all fields, and the heart back at rest. */
    private void renderClip(@NonNull VideoEntity v) {
        Context context = binding.getRoot().getContext();
        binding.avatar.setText(initial(v.creator));
        binding.creator.setText(context.getString(R.string.creator_handle, v.creator));
        binding.title.setText(v.title);
        // Sponsored: label and "Learn more"; no like or share (it isn't a creator's clip).
        int organic = v.sponsored ? View.GONE : View.VISIBLE;
        int sponsoredOnly = v.sponsored ? View.VISIBLE : View.GONE;
        binding.sponsoredLabel.setVisibility(sponsoredOnly);
        binding.cta.setVisibility(sponsoredOnly);
        binding.like.setVisibility(organic);
        binding.likeCount.setVisibility(organic);
        binding.share.setVisibility(organic);
        if (v.sponsored) listener.onSponsoredShown(v);
        binding.like.animate().cancel();
        binding.like.setScaleX(1f);
        binding.like.setScaleY(1f);
        renderLike(v.liked, v.likeCount);
    }

    private void renderLike(boolean liked, int count) {
        shownLiked = liked;
        shownCount = count;
        Context context = binding.getRoot().getContext();
        String formatted = countFormat.format(count);
        binding.likeCount.setText(formatted);
        binding.like.setImageResource(liked ? R.drawable.ic_favorite : R.drawable.ic_favorite_border);
        ImageViewCompat.setImageTintList(binding.like, liked ? likedTint : idleTint);
        // The count is hidden from TalkBack, so the button says both: "Unlike, 128 likes".
        binding.like.setContentDescription(context.getResources().getQuantityString(
                R.plurals.like_a11y, count, context.getString(liked ? R.string.unlike : R.string.like), formatted));
    }

    /** The heart's "pop" on like: 1 -> 1.25 -> 1, with a little overshoot on the way back. */
    private static void pop(@NonNull View heart) {
        heart.animate().cancel();
        heart.animate()
                .scaleX(POP_SCALE).scaleY(POP_SCALE)
                .setDuration(POP_UP_MS)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> heart.animate()
                        .scaleX(1f).scaleY(1f)
                        .setDuration(POP_DOWN_MS)
                        .setInterpolator(new OvershootInterpolator(3f)))
                .start();
    }

    /** First letter of the handle, upper-cased (code-point safe for non-Latin scripts). */
    @NonNull
    static String initial(@Nullable String creator) {
        if (creator == null || creator.isEmpty()) return "";
        int first = Character.toUpperCase(creator.codePointAt(0));
        return new String(Character.toChars(first));
    }
}
