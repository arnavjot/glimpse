package com.arnavjot.glimpse.ui;

import android.content.Context;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.NonNull;

import com.android.installreferrer.api.InstallReferrerClient;
import com.android.installreferrer.api.InstallReferrerStateListener;
import com.arnavjot.glimpse.common.Links;
import com.arnavjot.glimpse.data.Prefs;

/**
 * Deferred deep link: someone without the app taps a shared clip, installs from the
 * Play Store, and the first launch opens that clip. The share page sends them to the
 * Play Store with {@code video_id=<id>} in the install referrer; this reads it back
 * through the Play Install Referrer API.
 *
 * <p>Checked once per install. If Google Play is temporarily unreachable it is tried
 * again on the next launch; any definite answer (including "no referrer", e.g. a
 * sideloaded or organic install) ends it.
 */
final class DeferredDeepLink {

    interface Callback {
        void onVideo(@NonNull String videoId);
    }

    private static final String TAG = "DeferredDeepLink";

    private DeferredDeepLink() {
    }

    static void checkOnce(@NonNull Context context, @NonNull Prefs prefs, @NonNull Callback callback) {
        if (prefs.installReferrerChecked()) return;
        InstallReferrerClient client = InstallReferrerClient.newBuilder(context.getApplicationContext()).build();
        try {
            client.startConnection(new InstallReferrerStateListener() {
                @Override
                public void onInstallReferrerSetupFinished(int responseCode) {
                    String referrer = null;
                    if (responseCode == InstallReferrerClient.InstallReferrerResponse.OK) {
                        try {
                            referrer = client.getInstallReferrer().getInstallReferrer();
                        } catch (RemoteException e) {
                            Log.w(TAG, "install referrer unavailable", e);
                            client.endConnection();
                            return; // try again next launch
                        }
                    }
                    if (responseCode != InstallReferrerClient.InstallReferrerResponse.SERVICE_UNAVAILABLE) {
                        prefs.setInstallReferrerChecked();
                    }
                    client.endConnection();
                    String videoId = Links.videoIdFromReferrer(referrer);
                    if (videoId != null) callback.onVideo(videoId);
                }

                @Override
                public void onInstallReferrerServiceDisconnected() {
                    // Not marked as checked: the next launch tries again.
                }
            });
        } catch (SecurityException e) {
            // Some devices without Google Play refuse the binding: nothing to read.
            prefs.setInstallReferrerChecked();
        }
    }
}
