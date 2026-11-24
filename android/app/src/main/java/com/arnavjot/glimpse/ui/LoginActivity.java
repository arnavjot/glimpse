package com.arnavjot.glimpse.ui;

import android.os.Bundle;
import android.view.View;
import android.view.inputmethod.EditorInfo;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import com.arnavjot.glimpse.GlimpseApp;
import com.arnavjot.glimpse.R;
import com.arnavjot.glimpse.databinding.ActivityLoginBinding;
import com.arnavjot.glimpse.di.ViewModelFactory;
import com.arnavjot.glimpse.viewmodel.LoginViewModel;

import javax.inject.Inject;

/** Sign in, or create a creator account. Finishes (RESULT_OK) once signed in. */
public class LoginActivity extends AppCompatActivity {

    private static final String KEY_CREATE = "create_mode";

    @Inject ViewModelFactory factory;

    private ActivityLoginBinding binding;
    private LoginViewModel viewModel;
    private boolean createMode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ((GlimpseApp) getApplication()).component().inject(this);
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        binding = ActivityLoginBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        SystemBarInsets.pad(binding.scroll, SystemBarInsets.BOTTOM);
        setSupportActionBar(binding.appBar.toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        viewModel = new ViewModelProvider(this, factory).get(LoginViewModel.class);

        createMode = savedInstanceState != null && savedInstanceState.getBoolean(KEY_CREATE);
        renderMode();
        binding.switchMode.setOnClickListener(v -> {
            createMode = !createMode;
            renderMode();
        });
        binding.submit.setOnClickListener(v -> submit());
        binding.password.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_DONE) return false;
            submit();
            return true;
        });
        viewModel.state().observe(this, this::render);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean(KEY_CREATE, createMode);
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    private void submit() {
        String email = text(binding.email.getText());
        String password = text(binding.password.getText());
        if (createMode) {
            viewModel.createAccount(email, password, text(binding.handle.getText()));
        } else {
            viewModel.signIn(email, password);
        }
    }

    private void renderMode() {
        setTitle(createMode ? R.string.login_create_title : R.string.login_title);
        binding.handleLayout.setVisibility(createMode ? View.VISIBLE : View.GONE);
        binding.submit.setText(createMode ? R.string.login_create : R.string.login_sign_in);
        binding.switchMode.setText(createMode ? R.string.login_to_sign_in : R.string.login_to_create);
        binding.error.setVisibility(View.GONE);
    }

    private void render(@NonNull LoginViewModel.UiState s) {
        if (s.done) {
            setResult(RESULT_OK);
            finish();
            return;
        }
        binding.progress.setVisibility(s.busy ? View.VISIBLE : View.GONE);
        binding.submit.setEnabled(!s.busy);
        binding.switchMode.setEnabled(!s.busy);
        binding.error.setVisibility(s.error != null ? View.VISIBLE : View.GONE);
        binding.error.setText(s.error);
    }

    @NonNull
    private static String text(CharSequence c) {
        return c == null ? "" : c.toString();
    }
}
