package com.arnavjot.glimpse.push;

import androidx.annotation.NonNull;

import com.arnavjot.glimpse.GlimpseApp;
import com.arnavjot.glimpse.config.Flag;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

/** Receives FCM messages (on a background thread) and turns new-clip pushes into notifications. */
public class GlimpseMessagingService extends FirebaseMessagingService {

    @Override
    public void onMessageReceived(@NonNull RemoteMessage remoteMessage) {
        // Kill switch: a message already on its way is dropped once push is switched off.
        if (!((GlimpseApp) getApplication()).component().remoteConfig().isOn(Flag.PUSH_NOTIFICATIONS)) return;
        NewClipMessage message = NewClipMessage.from(remoteMessage.getData());
        if (message != null) PushNotifications.show(getApplicationContext(), message);
    }

    @Override
    public void onNewToken(@NonNull String token) {
        // Topic messaging: the server never needs this device's token, so nothing to send.
        // FCM keeps topic subscriptions across token rotation.
    }
}
