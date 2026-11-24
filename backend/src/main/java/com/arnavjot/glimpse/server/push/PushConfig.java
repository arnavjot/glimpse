package com.arnavjot.glimpse.server.push;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.arnavjot.glimpse.server.feed.FeedPage;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;

/** Firebase when its credentials file is present; otherwise a sender that reports push as off. */
@Configuration
public class PushConfig {

    private static final Logger log = LoggerFactory.getLogger(PushConfig.class);

    @Bean
    PushSender pushSender(PushProperties props) throws IOException {
        String file = props.credentialsFile();
        if (file == null || file.isBlank() || !Files.isRegularFile(Path.of(file))) {
            log.info("Push is off: no Firebase credentials file (FIREBASE_CREDENTIALS)");
            return new DisabledPushSender();
        }
        try (InputStream in = Files.newInputStream(Path.of(file))) {
            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.fromStream(in))
                    .build();
            FirebaseApp app = FirebaseApp.getApps().isEmpty()
                    ? FirebaseApp.initializeApp(options)
                    : FirebaseApp.getInstance();
            log.info("Push is on (Firebase project {})", app.getOptions().getProjectId());
            return new FirebasePushSender(app);
        }
    }

    static final class DisabledPushSender implements PushSender {
        @Override
        public boolean enabled() {
            return false;
        }

        @Override
        public String sendNewClip(FeedPage.Video video) {
            throw new IllegalStateException("push is off");
        }
    }
}
