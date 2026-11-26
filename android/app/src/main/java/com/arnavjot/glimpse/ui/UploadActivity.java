package com.arnavjot.glimpse.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import com.arnavjot.glimpse.GlimpseApp;
import com.arnavjot.glimpse.R;
import com.arnavjot.glimpse.databinding.ActivityUploadBinding;
import com.arnavjot.glimpse.di.ViewModelFactory;
import com.arnavjot.glimpse.viewmodel.UploadViewModel;
import com.bumptech.glide.Glide;

import javax.inject.Inject;

/**
 * Post a clip. Opens the system photo picker straight away (no storage permission
 * needed), shows a preview, takes a title, and hands the clip to the background upload.
 */
public class UploadActivity extends AppCompatActivity {

    private static final String KEY_PICKED = "picked";

    @Inject ViewModelFactory factory;

    private ActivityUploadBinding binding;
    private UploadViewModel viewModel;
    @Nullable private Uri picked;

    private final ActivityResultLauncher<PickVisualMediaRequest> picker =
            registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
                if (uri == null) {
                    if (picked == null) finish(); // backed out of the picker without a video
                    return;
                }
                showPicked(uri);
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ((GlimpseApp) getApplication()).component().inject(this);
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        binding = ActivityUploadBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        SystemBarInsets.padForKeyboard(binding.scroll);
        setSupportActionBar(binding.appBar.toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        viewModel = new ViewModelProvider(this, factory).get(UploadViewModel.class);

        binding.pick.setOnClickListener(v -> pick());
        binding.post.setOnClickListener(v -> post());
        binding.title.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_DONE) return false;
            post();
            return true;
        });
        viewModel.state().observe(this, this::render);

        Uri saved = savedInstanceState != null ? savedInstanceState.getParcelable(KEY_PICKED) : null;
        if (saved != null) {
            showPicked(saved);
        } else if (savedInstanceState == null) {
            pick();
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        if (picked != null) out.putParcelable(KEY_PICKED, picked);
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    private void pick() {
        picker.launch(new PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.VideoOnly.INSTANCE)
                .build());
    }

    private void showPicked(@NonNull Uri uri) {
        picked = uri;
        Glide.with(this).load(uri).centerCrop().into(binding.preview); // a frame of the video
        binding.post.setEnabled(true);
        binding.error.setVisibility(View.GONE);
    }

    private void post() {
        if (picked == null) return;
        CharSequence title = binding.title.getText();
        viewModel.post(picked, title == null ? "" : title.toString());
    }

    private void render(@NonNull UploadViewModel.UiState s) {
        if (s.done) {
            Toast.makeText(this, R.string.upload_started, Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, MyUploadsActivity.class));
            finish();
            return;
        }
        binding.progress.setVisibility(s.busy ? View.VISIBLE : View.GONE);
        binding.post.setEnabled(!s.busy && picked != null);
        binding.pick.setEnabled(!s.busy);
        binding.error.setVisibility(s.error != null ? View.VISIBLE : View.GONE);
        binding.error.setText(s.error);
    }
}
