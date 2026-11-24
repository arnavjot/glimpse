package com.arnavjot.glimpse.viewmodel;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.arnavjot.glimpse.auth.SessionManager;

import java.util.concurrent.ExecutorService;
import java.util.regex.Pattern;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * Sign in / create account. Checks input on the device first (so most mistakes never
 * reach the server), then calls the session manager off the main thread.
 */
public class LoginViewModel extends ViewModel {

    /** What the screen shows. */
    public static final class UiState {
        public final boolean busy;
        public final boolean done;
        @Nullable public final String error;

        UiState(boolean busy, boolean done, @Nullable String error) {
            this.busy = busy;
            this.done = done;
            this.error = error;
        }
    }

    static final int MIN_PASSWORD = 8;
    static final Pattern HANDLE = Pattern.compile("[A-Za-z0-9_.]{3,30}");
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");

    private final SessionManager sessions;
    private final ExecutorService io;
    private final MutableLiveData<UiState> state = new MutableLiveData<>(new UiState(false, false, null));

    @Inject
    public LoginViewModel(SessionManager sessions, @Named("io") ExecutorService io) {
        this.sessions = sessions;
        this.io = io;
    }

    public LiveData<UiState> state() {
        return state;
    }

    public void signIn(@NonNull String email, @NonNull String password) {
        String problem = checkEmailAndPassword(email, password);
        if (problem != null) {
            state.setValue(new UiState(false, false, problem));
            return;
        }
        run(() -> sessions.login(email, password));
    }

    public void createAccount(@NonNull String email, @NonNull String password, @NonNull String handle) {
        String problem = checkEmailAndPassword(email, password);
        if (problem == null) problem = checkHandle(handle);
        if (problem != null) {
            state.setValue(new UiState(false, false, problem));
            return;
        }
        run(() -> sessions.register(email, password, handle));
    }

    private interface AuthCall {
        void call() throws SessionManager.AuthException;
    }

    private void run(@NonNull AuthCall call) {
        UiState now = state.getValue();
        if (now != null && now.busy) return; // a double tap doesn't send two requests
        state.setValue(new UiState(true, false, null));
        io.execute(() -> {
            try {
                call.call();
                state.postValue(new UiState(false, true, null));
            } catch (SessionManager.AuthException e) {
                state.postValue(new UiState(false, false, e.getMessage()));
            }
        });
    }

    @Nullable
    static String checkEmailAndPassword(@NonNull String email, @NonNull String password) {
        if (!EMAIL.matcher(email.trim()).matches()) return "Enter a valid email address.";
        if (password.length() < MIN_PASSWORD) return "Use at least " + MIN_PASSWORD + " characters for the password.";
        return null;
    }

    @Nullable
    static String checkHandle(@NonNull String handle) {
        return HANDLE.matcher(handle.trim()).matches()
                ? null : "Handles are 3 to 30 letters, numbers, _ or .";
    }
}
