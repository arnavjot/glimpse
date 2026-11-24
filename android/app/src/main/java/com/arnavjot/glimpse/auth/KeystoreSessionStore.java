package com.arnavjot.glimpse.auth;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Keeps the session in SharedPreferences, encrypted with AES-256-GCM under a key that
 * lives in the Android Keystore: the key never leaves secure hardware (where the device
 * has it) and can't be copied out with the app's files. GCM also detects tampering.
 *
 * <p>If the data can't be decrypted (key lost after a restore to a new device, data
 * corrupted), the session is dropped and the creator simply signs in again.
 */
public final class KeystoreSessionStore implements SessionStore {

    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "glimpse_session_key";
    private static final String PREFS = "session";
    private static final String FIELD = "encrypted";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String TAG = "SessionStore";

    private final SharedPreferences prefs;
    private final Gson gson = new Gson();

    public KeystoreSessionStore(@NonNull Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @Nullable
    @Override
    public Session load() {
        String stored = prefs.getString(FIELD, null);
        if (stored == null) return null;
        try {
            ByteBuffer in = ByteBuffer.wrap(Base64.decode(stored, Base64.NO_WRAP));
            byte[] iv = new byte[IV_BYTES];
            in.get(iv);
            byte[] sealed = new byte[in.remaining()];
            in.get(sealed);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
            String json = new String(cipher.doFinal(sealed), StandardCharsets.UTF_8);
            return gson.fromJson(json, Session.class);
        } catch (GeneralSecurityException | RuntimeException e) {
            Log.w(TAG, "saved session unreadable; signing out", e);
            clear();
            return null;
        }
    }

    @Override
    public void save(@NonNull Session session) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key()); // the Keystore picks a fresh random IV
            byte[] sealed = cipher.doFinal(gson.toJson(session).getBytes(StandardCharsets.UTF_8));
            byte[] iv = cipher.getIV();
            byte[] out = ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array();
            prefs.edit().putString(FIELD, Base64.encodeToString(out, Base64.NO_WRAP)).apply();
        } catch (GeneralSecurityException e) {
            // Without a working Keystore the session isn't persisted: it lasts until the app closes.
            Log.w(TAG, "can't encrypt the session; it won't survive a restart", e);
        }
    }

    @Override
    public void clear() {
        prefs.edit().remove(FIELD).apply();
    }

    @NonNull
    private static SecretKey key() throws GeneralSecurityException {
        KeyStore ks = KeyStore.getInstance(KEYSTORE);
        try {
            ks.load(null);
        } catch (java.io.IOException e) {
            throw new GeneralSecurityException(e);
        }
        KeyStore.Entry entry = ks.getEntry(KEY_ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry) return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        KeyGenerator gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        gen.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return gen.generateKey();
    }
}
