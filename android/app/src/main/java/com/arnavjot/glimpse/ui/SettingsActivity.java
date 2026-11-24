package com.arnavjot.glimpse.ui;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.preference.EditTextPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreferenceCompat;

import com.arnavjot.glimpse.BuildConfig;
import com.arnavjot.glimpse.GlimpseApp;
import com.arnavjot.glimpse.R;
import com.arnavjot.glimpse.auth.SessionManager;
import com.arnavjot.glimpse.config.Flag;
import com.arnavjot.glimpse.config.RemoteConfig;
import com.arnavjot.glimpse.data.Prefs;
import com.arnavjot.glimpse.databinding.ActivitySettingsBinding;
import com.arnavjot.glimpse.push.PushNotifications;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import okhttp3.HttpUrl;

public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        ActivitySettingsBinding binding = ActivitySettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        SystemBarInsets.pad(binding.settingsContainer, SystemBarInsets.BOTTOM);
        setSupportActionBar(binding.appBar.toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.settings_container, new Fragment())
                    .commit();
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    /** An absolute http(s) URL, e.g. http://192.168.1.40:8080/ */
    static boolean isValidApiUrl(@Nullable String value) {
        return value != null && HttpUrl.parse(value.trim()) != null;
    }

    public static class Fragment extends PreferenceFragmentCompat {

        /** Android 13+: asked only when the user turns notifications on. */
        private final ActivityResultLauncher<String> askNotificationPermission =
                registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                    if (granted) {
                        turnOnNewClipNotifications();
                    } else {
                        Toast.makeText(requireContext(), R.string.pref_new_clips_denied, Toast.LENGTH_LONG).show();
                    }
                });

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.preferences, rootKey);
            setUpAccount();
            setUpNewClipNotifications();
            EditTextPreference url = findPreference(Prefs.KEY_API_URL);
            if (url != null) {
                if (url.getText() == null) url.setText(BuildConfig.DEFAULT_API_URL);
                url.setSummaryProvider(EditTextPreference.SimpleSummaryProvider.getInstance());
                // Reject what the API client can't use, instead of silently falling back.
                url.setOnPreferenceChangeListener((preference, value) -> {
                    boolean valid = isValidApiUrl(String.valueOf(value));
                    if (!valid) {
                        Toast.makeText(requireContext(), R.string.pref_api_url_invalid, Toast.LENGTH_LONG).show();
                    }
                    return valid;
                });
            }
        }

        /**
         * The opt-in switch: on subscribes to the new-clips topic (after the runtime
         * permission on Android 13+), off unsubscribes. Disabled in builds without Firebase.
         */
        private void setUpNewClipNotifications() {
            SwitchPreferenceCompat toggle = findPreference(Prefs.KEY_NEW_CLIP_NOTIFICATIONS);
            if (toggle == null) return;
            if (!PushNotifications.available(requireContext())) {
                toggle.setChecked(false);
                toggle.setEnabled(false);
                toggle.setSummary(R.string.pref_new_clips_unavailable);
                return;
            }
            RemoteConfig config = ((GlimpseApp) requireActivity().getApplication()).component().remoteConfig();
            if (!config.isOn(Flag.PUSH_NOTIFICATIONS)) {
                // Switched off remotely: keep the user's choice, but they can't change it now.
                toggle.setEnabled(false);
                toggle.setSummary(R.string.pref_new_clips_paused);
                return;
            }
            toggle.setOnPreferenceChangeListener((preference, value) -> {
                if (!Boolean.TRUE.equals(value)) {
                    PushNotifications.setSubscribed(requireContext(), false);
                    return true;
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                        && ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
                    askNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
                    return false; // switched on in the permission callback, if granted
                }
                if (!PushNotifications.canPost(requireContext())) {
                    Toast.makeText(requireContext(), R.string.pref_new_clips_denied, Toast.LENGTH_LONG).show();
                    return false;
                }
                PushNotifications.setSubscribed(requireContext(), true);
                return true;
            });
        }

        @Override
        public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
            super.onViewCreated(view, savedInstanceState);
            // Observed with the view's lifecycle (not the fragment's), so no stale observer
            // outlives a destroyed view.
            Preference account = findPreference("account");
            if (account == null) return;
            SessionManager sessions = ((GlimpseApp) requireActivity().getApplication()).component().sessionManager();
            Preference myUploads = findPreference("my_uploads");
            sessions.session().observe(getViewLifecycleOwner(), session -> {
                if (myUploads != null) myUploads.setVisible(session != null);
                if (session == null) {
                    account.setTitle(R.string.pref_sign_in);
                    account.setSummary(R.string.pref_sign_in_summary);
                } else {
                    account.setTitle(getString(R.string.pref_signed_in_as, session.handle));
                    account.setSummary(R.string.pref_sign_out_summary);
                }
            });
        }

        /** "Sign in" when signed out; "Signed in as @handle" (tap to sign out) when signed in. */
        private void setUpAccount() {
            Preference myUploads = findPreference("my_uploads");
            if (myUploads != null) {
                myUploads.setOnPreferenceClickListener(p -> {
                    startActivity(new Intent(requireContext(), MyUploadsActivity.class));
                    return true;
                });
            }
            Preference account = findPreference("account");
            if (account == null) return;
            GlimpseApp app = (GlimpseApp) requireActivity().getApplication();
            SessionManager sessions = app.component().sessionManager();
            account.setOnPreferenceClickListener(p -> {
                if (sessions.current() == null) {
                    startActivity(new Intent(requireContext(), LoginActivity.class));
                } else {
                    new MaterialAlertDialogBuilder(requireContext())
                            .setTitle(R.string.pref_sign_out_confirm)
                            .setNegativeButton(android.R.string.cancel, null)
                            .setPositiveButton(R.string.pref_sign_out, (d, w) -> {
                                app.component().io().execute(sessions::logout);
                                Toast.makeText(requireContext(), R.string.signed_out, Toast.LENGTH_SHORT).show();
                            })
                            .show();
                }
                return true;
            });
        }

        private void turnOnNewClipNotifications() {
            SwitchPreferenceCompat toggle = findPreference(Prefs.KEY_NEW_CLIP_NOTIFICATIONS);
            if (toggle != null) toggle.setChecked(true); // doesn't call the change listener
            PushNotifications.setSubscribed(requireContext(), true);
        }
    }
}
