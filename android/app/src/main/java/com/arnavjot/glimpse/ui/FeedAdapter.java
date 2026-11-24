package com.arnavjot.glimpse.ui;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.arnavjot.glimpse.databinding.ItemVideoBinding;
import com.arnavjot.glimpse.pojo.VideoEntity;
import com.bumptech.glide.Glide;

import java.util.List;
import java.util.Objects;

/**
 * One full-screen page per clip: the poster until the first frame, and the surface the
 * single shared player attaches to. Captions and the like rail are not part of the
 * page; they live in the feed's fixed {@link FeedOverlay}.
 */
public class FeedAdapter extends ListAdapter<VideoEntity, FeedAdapter.Holder> {

    /**
     * Partial-bind marker: only fields the page doesn't draw changed (likes, title).
     * The page is kept as is, so its poster isn't reloaded and the player stays attached.
     */
    static final Object PAYLOAD_NO_VISUAL_CHANGE = new Object();

    private static final DiffUtil.ItemCallback<VideoEntity> DIFF = new DiffUtil.ItemCallback<VideoEntity>() {
        @Override
        public boolean areItemsTheSame(@NonNull VideoEntity a, @NonNull VideoEntity b) {
            return a.id.equals(b.id);
        }

        @Override
        public boolean areContentsTheSame(@NonNull VideoEntity a, @NonNull VideoEntity b) {
            return a.equals(b);
        }

        @Nullable
        @Override
        public Object getChangePayload(@NonNull VideoEntity a, @NonNull VideoEntity b) {
            return Objects.equals(a.posterUrl, b.posterUrl) ? PAYLOAD_NO_VISUAL_CHANGE : null;
        }
    };

    /** A tap on the video itself (not on the overlay's buttons). */
    public interface PageTapListener {
        void onPageTapped();
    }

    private final PageTapListener tapListener;

    public FeedAdapter(@NonNull PageTapListener tapListener) {
        super(DIFF);
        this.tapListener = tapListener;
        setHasStableIds(true);
    }

    @Override
    public long getItemId(int position) {
        return getItem(position).id.hashCode();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        Holder holder = new Holder(ItemVideoBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        // A swipe never becomes a click: the pager takes over the touch once it moves.
        holder.binding.getRoot().setOnClickListener(v -> tapListener.onPageTapped());
        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        Glide.with(holder.binding.poster).load(getItem(position).posterUrl).into(holder.binding.poster);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position, @NonNull List<Object> payloads) {
        // With a payload, nothing the page draws has changed: leave it alone.
        if (payloads.isEmpty()) onBindViewHolder(holder, position);
    }

    @Override
    public void onViewRecycled(@NonNull Holder holder) {
        Glide.with(holder.binding.poster).clear(holder.binding.poster);
    }

    public static final class Holder extends RecyclerView.ViewHolder {
        public final ItemVideoBinding binding;

        Holder(ItemVideoBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
