package com.arnavjot.glimpse.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.arnavjot.glimpse.GlimpseApp;
import com.arnavjot.glimpse.R;
import com.arnavjot.glimpse.common.Links;
import com.arnavjot.glimpse.databinding.ActivityMyUploadsBinding;
import com.arnavjot.glimpse.di.ViewModelFactory;
import com.arnavjot.glimpse.pojo.UploadDto;
import com.arnavjot.glimpse.upload.UploadVideoWorker;
import com.arnavjot.glimpse.viewmodel.MyUploadsViewModel;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import javax.inject.Inject;

/**
 * Your uploads. Two sources: jobs still on this phone (WorkManager: sending with %, or
 * waiting for a network) and the server's list (waiting to process, processing, live,
 * failed). While anything is in progress it re-checks every few seconds, only while the
 * screen is visible.
 */
public class MyUploadsActivity extends AppCompatActivity {

    private static final long POLL_MILLIS = 3_000;

    @Inject ViewModelFactory factory;

    private ActivityMyUploadsBinding binding;
    private MyUploadsViewModel viewModel;
    private UploadsAdapter adapter;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private List<UploadRow> localRows = Collections.emptyList();
    private boolean localActive;
    @Nullable private List<UploadDto.Upload> serverRows;
    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            viewModel.refresh();
            handler.postDelayed(this, POLL_MILLIS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ((GlimpseApp) getApplication()).component().inject(this);
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        binding = ActivityMyUploadsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        SystemBarInsets.pad(binding.list, SystemBarInsets.BOTTOM);
        setSupportActionBar(binding.appBar.toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        viewModel = new ViewModelProvider(this, factory).get(MyUploadsViewModel.class);

        adapter = new UploadsAdapter(this::openVideo, this::deleteOrCancel);
        binding.list.setLayoutManager(new LinearLayoutManager(this));
        binding.list.setAdapter(adapter);

        viewModel.uploads().observe(this, list -> {
            serverRows = list;
            render();
            schedulePolling();
        });
        viewModel.offline().observe(this, off -> binding.offline.setVisibility(
                Boolean.TRUE.equals(off) ? View.VISIBLE : View.GONE));
        WorkManager.getInstance(this).getWorkInfosByTagLiveData(UploadVideoWorker.TAG)
                .observe(this, this::onLocalJobs);
        viewModel.deleteResults().observe(this, event -> {
            MyUploadsViewModel.DeleteResult result = event.consume();
            if (result == null) return;
            Snackbar.make(binding.getRoot(), result == MyUploadsViewModel.DeleteResult.DELETED
                    ? R.string.upload_deleted : R.string.upload_delete_failed, Snackbar.LENGTH_SHORT).show();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        viewModel.refresh();
        schedulePolling();
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(poll);
        super.onPause();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    private void onLocalJobs(@Nullable List<WorkInfo> infos) {
        List<UploadRow> rows = new ArrayList<>();
        boolean active = false;
        boolean justFinished = false;
        if (infos != null) {
            for (WorkInfo w : infos) {
                String title = titleOf(w);
                switch (w.getState()) {
                    case RUNNING: {
                        active = true;
                        int percent = w.getProgress().getInt(UploadVideoWorker.KEY_PERCENT, 0);
                        rows.add(new UploadRow(UploadRow.LOCAL + w.getId(), title, UploadRow.Kind.SENDING, percent, null, null));
                        break;
                    }
                    case ENQUEUED:
                    case BLOCKED:
                        active = true;
                        rows.add(new UploadRow(UploadRow.LOCAL + w.getId(), title, UploadRow.Kind.WAITING_NETWORK, -1, null, null));
                        break;
                    case FAILED: {
                        String error = w.getOutputData().getString(UploadVideoWorker.KEY_ERROR);
                        // Failed before the server heard of it: only this phone knows, so show it.
                        if (error != null) {
                            rows.add(new UploadRow(UploadRow.LOCAL + w.getId(), title, UploadRow.Kind.FAILED, -1, error, null));
                        }
                        break;
                    }
                    case SUCCEEDED:
                        justFinished = true; // it's on the server now: that list takes over
                        break;
                    default:
                        break;
                }
            }
        }
        localRows = rows;
        localActive = active;
        if (justFinished) viewModel.refresh();
        render();
        schedulePolling();
    }

    @NonNull
    private static String titleOf(@NonNull WorkInfo w) {
        for (String tag : w.getTags()) {
            if (tag.startsWith(UploadVideoWorker.TITLE_TAG)) return tag.substring(UploadVideoWorker.TITLE_TAG.length());
        }
        return "";
    }

    private void render() {
        List<UploadRow> rows = new ArrayList<>(localRows);
        if (serverRows != null) {
            for (UploadDto.Upload u : serverRows) {
                // While the file is being sent, this phone's job is the source of truth (it
                // has the %); an abandoned pending upload isn't worth showing at all.
                if (!UploadDto.PENDING.equals(u.status)) rows.add(UploadRow.of(u));
            }
        }
        adapter.submitList(rows);
        boolean loaded = serverRows != null || !localRows.isEmpty();
        binding.empty.setVisibility(loaded && rows.isEmpty() ? View.VISIBLE : View.GONE);
    }

    /** Poll while something is moving; stop once everything is live or failed. */
    private void schedulePolling() {
        handler.removeCallbacks(poll);
        if (localActive || MyUploadsViewModel.anyInProgress(serverRows)) handler.postDelayed(poll, POLL_MILLIS);
    }

    /**
     * Still sending: cancel the job on this phone (the server forgets the unfinished upload
     * by itself). Otherwise: delete it on the server, after a confirmation.
     */
    private void deleteOrCancel(@NonNull UploadRow row) {
        if (row.isLocal()) {
            WorkManager.getInstance(this).cancelWorkById(UUID.fromString(row.id()));
            Snackbar.make(binding.getRoot(), R.string.upload_cancelled, Snackbar.LENGTH_SHORT).show();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(row.title)
                .setMessage(R.string.upload_delete_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.upload_delete_action, (d, w) -> viewModel.delete(row.id(), row.videoId))
                .show();
    }

    /** A live clip: open it at the top of the feed (the same path as a shared link). */
    private void openVideo(@NonNull String videoId) {
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(Links.deepLink(videoId)))
                .setClass(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP));
    }
}
