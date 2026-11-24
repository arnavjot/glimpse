package com.arnavjot.glimpse.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.arnavjot.glimpse.R;
import com.arnavjot.glimpse.databinding.ItemUploadBinding;

import java.util.function.Consumer;

/** My uploads rows. A ready row opens the clip; the others aren't clickable. */
final class UploadsAdapter extends ListAdapter<UploadRow, UploadsAdapter.Holder> {

    private static final DiffUtil.ItemCallback<UploadRow> DIFF = new DiffUtil.ItemCallback<UploadRow>() {
        @Override
        public boolean areItemsTheSame(@NonNull UploadRow a, @NonNull UploadRow b) {
            return a.key.equals(b.key);
        }

        @Override
        public boolean areContentsTheSame(@NonNull UploadRow a, @NonNull UploadRow b) {
            return a.equals(b);
        }
    };

    private final Consumer<String> openVideo;
    private final Consumer<UploadRow> delete;

    /** @param delete the row's delete (server uploads) or cancel (still sending) button */
    UploadsAdapter(@NonNull Consumer<String> openVideo, @NonNull Consumer<UploadRow> delete) {
        super(DIFF);
        this.openVideo = openVideo;
        this.delete = delete;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        Holder h = new Holder(ItemUploadBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        h.binding.getRoot().setOnClickListener(v -> {
            int pos = h.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            String id = getItem(pos).videoId;
            if (id != null) openVideo.accept(id);
        });
        h.binding.delete.setOnClickListener(v -> {
            int pos = h.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION) delete.accept(getItem(pos));
        });
        return h;
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        UploadRow r = getItem(position);
        Context c = h.binding.getRoot().getContext();
        h.binding.title.setText(r.title);
        h.binding.status.setText(statusText(c, r));
        h.binding.status.setTextColor(c.getColor(r.kind == UploadRow.Kind.READY ? R.color.pn_success
                : r.kind == UploadRow.Kind.FAILED ? R.color.pn_error : R.color.pn_ink_muted));
        boolean sending = r.kind == UploadRow.Kind.SENDING;
        boolean busy = sending || r.kind == UploadRow.Kind.UPLOADED || r.kind == UploadRow.Kind.PROCESSING
                || r.kind == UploadRow.Kind.PENDING;
        h.binding.progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        if (busy) {
            h.binding.progress.setIndeterminate(!sending);
            if (sending) h.binding.progress.setProgressCompat(r.percent, true);
        }
        h.binding.error.setVisibility(r.error != null ? View.VISIBLE : View.GONE);
        h.binding.error.setText(r.error);
        // A job still on this phone is cancelled; anything on the server is deleted. A job
        // that already failed on this phone has nothing to cancel.
        h.binding.delete.setVisibility(r.isLocal() && r.kind == UploadRow.Kind.FAILED ? View.GONE : View.VISIBLE);
        h.binding.delete.setContentDescription(c.getString(r.isLocal() ? R.string.upload_cancel : R.string.upload_delete));
        h.binding.getRoot().setClickable(r.videoId != null);
        h.binding.getRoot().setFocusable(r.videoId != null);
    }

    @NonNull
    private static String statusText(@NonNull Context c, @NonNull UploadRow r) {
        switch (r.kind) {
            case SENDING: return c.getString(R.string.upload_status_sending, Math.max(0, r.percent));
            case WAITING_NETWORK: return c.getString(R.string.upload_status_waiting_network);
            case PENDING: return c.getString(R.string.upload_status_pending);
            case UPLOADED: return c.getString(R.string.upload_status_uploaded);
            case PROCESSING: return c.getString(R.string.upload_status_processing);
            case READY: return c.getString(R.string.upload_status_ready);
            default: return c.getString(R.string.upload_status_failed);
        }
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final ItemUploadBinding binding;

        Holder(ItemUploadBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
