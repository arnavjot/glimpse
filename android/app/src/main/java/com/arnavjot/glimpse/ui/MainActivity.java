package com.arnavjot.glimpse.ui;

import com.arnavjot.glimpse.viewmodel.FeedViewModel;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;

import androidx.activity.EdgeToEdge;
import androidx.activity.SystemBarStyle;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.datasource.DataSource;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.arnavjot.glimpse.GlimpseApp;
import com.arnavjot.glimpse.R;
import com.arnavjot.glimpse.auth.SessionManager;
import com.arnavjot.glimpse.common.Links;
import com.arnavjot.glimpse.config.Flag;
import com.arnavjot.glimpse.config.RemoteConfig;
import com.arnavjot.glimpse.analytics.PlaybackAnalytics;
import com.arnavjot.glimpse.repository.FeedRepository;
import com.arnavjot.glimpse.data.Prefs;
import com.arnavjot.glimpse.pojo.VideoEntity;
import com.arnavjot.glimpse.databinding.ActivityMainBinding;
import com.arnavjot.glimpse.di.ViewModelFactory;
import com.arnavjot.glimpse.player.FeedPlayer;
import com.bumptech.glide.Glide;
import com.google.android.material.snackbar.Snackbar;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.inject.Inject;
import javax.inject.Named;

/** The vertical feed: swipe up for the next clip. */
public class MainActivity extends AppCompatActivity {

    @Inject ViewModelFactory factory;
    @Inject DataSource.Factory cachedDataSource;
    @Inject PlaybackAnalytics analytics;
    @Inject Prefs prefs;
    @Inject RemoteConfig remoteConfig;
    @Inject SessionManager sessions;
    @Inject @Named("io") ExecutorService io;

    private ActivityMainBinding binding;
    private FeedViewModel viewModel;
    private FeedAdapter adapter;
    private FeedPlayer player;
    private FeedOverlay feedOverlay;
    private int currentPage = -1;
    private int scrollState = ViewPager2.SCROLL_STATE_IDLE;
    /** A clip to scroll to once the feed shows it (shared link, pull to refresh). */
    @Nullable private FeedRepository.Show pendingShow;
    private boolean sharing;
    private static final long SHARE_IMAGE_TIMEOUT_S = 5;

    /** Playback progress is polled: ExoPlayer has no position callback. */
    private static final long PROGRESS_TICK_MS = 100;
    private static final int PROGRESS_MAX = 1000;
    private final Runnable progressTicker = new Runnable() {
        @Override
        public void run() {
            if (player != null) {
                binding.progress.setProgressCompat(Math.round(player.progress() * PROGRESS_MAX), false);
            }
            binding.progress.postDelayed(this, PROGRESS_TICK_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ((GlimpseApp) getApplication()).component().inject(this);
        // The feed is always dark: light status/navigation bar icons on transparent bars.
        EdgeToEdge.enable(this,
                SystemBarStyle.dark(Color.TRANSPARENT),
                SystemBarStyle.dark(Color.TRANSPARENT));
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        SystemBarInsets.pad(binding.topBar, SystemBarInsets.TOP);
        SystemBarInsets.pad(binding.empty, SystemBarInsets.TOP | SystemBarInsets.BOTTOM);
        SystemBarInsets.pad(binding.feedOverlay.getRoot(), SystemBarInsets.BOTTOM);
        SystemBarInsets.pad(binding.progressContainer, SystemBarInsets.BOTTOM);
        viewModel = new ViewModelProvider(this, factory).get(FeedViewModel.class);

        adapter = new FeedAdapter(this::togglePause);
        feedOverlay = new FeedOverlay(binding.feedOverlay, new FeedOverlay.Actions() {
            @Override
            public void onLikeChanged(@NonNull VideoEntity video, boolean liked) {
                viewModel.setLiked(video.id, liked);
            }

            @Override
            public void onShare(@NonNull VideoEntity video) {
                share(video);
            }

            @Override
            public void onSponsoredShown(@NonNull VideoEntity video) {
                analytics.record(PlaybackAnalytics.AD_IMPRESSION, video.id, null, null);
            }

            @Override
            public void onSponsoredCta(@NonNull VideoEntity video) {
                analytics.record(PlaybackAnalytics.AD_CLICK, video.id, null, null);
                openWebLink(video.ctaUrl);
            }

            @Override
            public void onMuteToggled() {
                boolean muted = !prefs.muted();
                prefs.setMuted(muted);
                if (player != null) player.setMuted(muted);
                feedOverlay.setMuted(muted);
            }
        });
        feedOverlay.setMuted(prefs.muted());
        binding.pager.setOrientation(ViewPager2.ORIENTATION_VERTICAL);
        binding.pager.setOffscreenPageLimit(1);
        binding.pager.setAdapter(adapter);
        // Full-screen pages don't need item change animations. The default animator
        // cross-fades a *new* ViewHolder in on every change, which would move the page
        // (and the PlayerView the shared player renders into) out from under us.
        pagerRecycler().setItemAnimator(null);
        binding.pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                currentPage = position;
                binding.pausedIcon.setVisibility(View.GONE); // a new clip always starts playing
                playPage(position);
                viewModel.onPageSelected(position, adapter.getItemCount());
                // A programmatic jump has no drag: update the overlay now.
                if (scrollState == ViewPager2.SCROLL_STATE_IDLE) updateOverlay();
            }

            @Override
            public void onPageScrollStateChanged(int state) {
                scrollState = state;
                // The overlay keeps the old clip's details while swiping, then
                // cross-fades once the new page has settled.
                if (state == ViewPager2.SCROLL_STATE_IDLE) updateOverlay();
            }
        });

        binding.refresh.setOnRefreshListener(viewModel::refresh);
        binding.refresh.setColorSchemeResources(R.color.pn_dark_brand);
        binding.refresh.setProgressBackgroundColorSchemeResource(R.color.pn_dark_surface_2);
        // Edge to edge: start the pull-to-refresh spinner below the status bar, not under it.
        int spinnerTravel = getResources().getDimensionPixelSize(R.dimen.pn_refresh_travel);
        ViewCompat.setOnApplyWindowInsetsListener(binding.refresh, (v, insets) -> {
            int statusBar = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top;
            binding.refresh.setProgressViewOffset(false, statusBar, statusBar + spinnerTravel);
            return insets;
        });
        binding.retry.setOnClickListener(v -> viewModel.refresh());
        // Posting needs an account: signed out, "+" leads to sign-in first.
        binding.postClip.setOnClickListener(v -> startActivity(new Intent(this,
                sessions.current() != null ? UploadActivity.class : LoginActivity.class)));
        binding.openPerformance.setOnClickListener(
                v -> startActivity(new Intent(this, PerformanceActivity.class)));
        binding.openSettings.setOnClickListener(
                v -> startActivity(new Intent(this, SettingsActivity.class)));

        viewModel.feed().observe(this, this::showFeed);
        viewModel.state().observe(this, this::showState);
        viewModel.likeFailures().observe(this, event -> {
            FeedRepository.Failure failure = event.consume();
            if (failure == null) return; // already shown (e.g. before a rotation)
            Snackbar snackbar = Snackbar.make(binding.getRoot(), failure == FeedRepository.Failure.SERVER
                    ? R.string.like_failed_server : R.string.like_failed_network, Snackbar.LENGTH_SHORT);
            // Above the captions and like rail, not over them.
            if (binding.feedOverlay.getRoot().getVisibility() == View.VISIBLE) {
                snackbar.setAnchorView(binding.feedOverlay.getRoot());
            }
            snackbar.show();
        });
        viewModel.openFailures().observe(this, event -> {
            FeedRepository.Failure failure = event.consume();
            if (failure == null) return;
            pendingShow = null; // give up on scrolling to it
            Snackbar.make(binding.getRoot(), failure == FeedRepository.Failure.SERVER
                    ? R.string.open_failed_missing : R.string.open_failed_network, Snackbar.LENGTH_LONG).show();
        });
        viewModel.showVideo().observe(this, event -> {
            FeedRepository.Show show = event.consume();
            if (show == null) return;
            pendingShow = show;
            showPendingIfLoaded(); // or once the refreshed list arrives
        });

        // A deep link that launched us. On recreation (rotation) it was already handled.
        if (savedInstanceState == null) {
            boolean opened = handleDeepLink(getIntent());
            // First launch after a Play Store install from a shared link: open that clip.
            // Checked even when a link opened the app, so it never fires on a later launch.
            DeferredDeepLink.checkOnce(this, prefs, videoId -> runOnUiThread(() -> {
                if (!opened && !isDestroyed()) viewModel.openVideo(videoId);
            }));
        }
    }

    @Override
    protected void onNewIntent(@NonNull Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleDeepLink(intent); // singleTop: a link opened while the feed is showing
    }

    /**
     * glimpse://video/<id> or https://<share host>/v/<id>: bring that clip into the feed
     * and scroll to it. @return true if the intent opened a clip
     */
    private boolean handleDeepLink(@Nullable Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) return false;
        String videoId = Links.videoIdFromDeepLink(intent.getDataString());
        if (videoId == null) return false;
        viewModel.openVideo(videoId);
        return true;
    }

    @Override
    protected void onStart() {
        super.onStart();
        // The kill switch can hide posting at any time; checked whenever the feed comes back.
        binding.postClip.setVisibility(remoteConfig.isOn(Flag.UPLOADS) ? View.VISIBLE : View.GONE);
        // Settings may have switched preloading on or off: rebuild the player to match.
        // Preloading needs both the user's Settings switch and the remote "preload" flag
        // (the kill switch). Either changing rebuilds the player when the feed comes back.
        boolean preload = prefs.preloadEnabled() && remoteConfig.isOn(Flag.PRELOAD);
        if (player == null || player.preloadEnabled() != preload) {
            if (player != null) player.release();
            player = new FeedPlayer(this, cachedDataSource, analytics, preload);
            player.setFirstFrameListener(this::showFirstFrame);
            player.setMuted(prefs.muted());
            binding.pausedIcon.setVisibility(View.GONE); // a new player isn't paused
            List<VideoEntity> feed = viewModel.feed().getValue();
            if (feed != null) player.setFeed(feed);
            if (currentPage >= 0) binding.pager.post(() -> playPage(currentPage));
        }
        binding.overlay.setVisibility(prefs.overlayEnabled() ? View.VISIBLE : View.GONE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (player != null) player.resume();
        binding.progress.removeCallbacks(progressTicker);
        binding.progress.post(progressTicker);
    }

    @Override
    protected void onPause() {
        binding.progress.removeCallbacks(progressTicker);
        if (player != null) player.pause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (player != null) player.release();
        player = null;
        super.onDestroy();
    }

    private void showFeed(List<VideoEntity> feed) {
        boolean wasEmpty = adapter.getItemCount() == 0;
        adapter.submitList(feed, () -> {
            if (player != null) player.setFeed(feed);
            if (wasEmpty && !feed.isEmpty()) currentPage = binding.pager.getCurrentItem();
            // Keep the player on the visible page's view. A no-op when nothing moved;
            // if an update did rebind the page, this re-attaches without restarting.
            if (currentPage >= 0) binding.pager.post(() -> playPage(currentPage));
            if (scrollState == ViewPager2.SCROLL_STATE_IDLE) updateOverlay();
            showPendingIfLoaded();
        });
        binding.empty.setVisibility(feed.isEmpty() ? View.VISIBLE : View.GONE);
        binding.scrim.setVisibility(feed.isEmpty() ? View.GONE : View.VISIBLE);
        binding.progressContainer.setVisibility(feed.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void showState(@NonNull FeedRepository.State state) {
        // Spin for the first load, or keep spinning for a pull the user started.
        // Page loads (loadMore) never show it. Every request ends with loading=false.
        binding.refresh.setRefreshing(state.loading
                && (adapter.getItemCount() == 0 || binding.refresh.isRefreshing()));
        boolean failed = state.error != null;
        binding.emptyTitle.setText(failed ? R.string.feed_error_title : R.string.feed_empty_title);
        if (!failed) {
            binding.emptyText.setText(R.string.feed_empty);
        } else {
            binding.emptyText.setText(state.error == FeedRepository.Failure.SERVER
                    ? R.string.feed_error_server : R.string.feed_error_network);
        }
        binding.offlineBanner.setVisibility(
                failed && adapter.getItemCount() > 0 ? View.VISIBLE : View.GONE);
    }

    private void playPage(int position) {
        if (player == null || position < 0 || position >= adapter.getItemCount()) return;
        RecyclerView.ViewHolder vh = pagerRecycler().findViewHolderForAdapterPosition(position);
        if (!(vh instanceof FeedAdapter.Holder)) {
            // Not laid out yet (first launch, fast fling): try again after the next layout.
            binding.pager.post(() -> {
                if (currentPage == position) playPage(position);
            });
            return;
        }
        VideoEntity video = adapter.getCurrentList().get(position);
        player.play(position, video.id, ((FeedAdapter.Holder) vh).binding.playerView);
    }

    /** Tap on the video: pause or continue, with a play icon in the middle while paused. */
    private void togglePause() {
        if (player == null) return;
        boolean paused = player.togglePause();
        binding.pausedIcon.setVisibility(paused ? View.VISIBLE : View.GONE);
        binding.getRoot().announceForAccessibility(getString(paused ? R.string.paused : R.string.play_pause));
    }

    /**
     * Scrolls to the requested clip once the list on screen has it where expected. The
     * event can arrive before or after the refreshed list, so this runs on both; for
     * {@code atTop} it waits for the list whose first clip is the target, otherwise the
     * old list (where the clip sits further down) would win.
     */
    private void showPendingIfLoaded() {
        FeedRepository.Show show = pendingShow;
        if (show == null) return;
        List<VideoEntity> list = adapter.getCurrentList();
        int index = -1;
        if (show.atTop) {
            if (!list.isEmpty() && list.get(0).id.equals(show.videoId)) index = 0;
        } else {
            for (int i = 0; i < list.size() && index < 0; i++) {
                if (list.get(i).id.equals(show.videoId)) index = i;
            }
        }
        if (index < 0) return;
        pendingShow = null;
        binding.pager.setCurrentItem(index, false);
    }

    /**
     * Shares the clip: a card image (poster + play badge) plus the text and web link, whose
     * page opens the app on this clip. The card is made off the main thread; if the poster
     * can't be loaded in time, the text and link are shared on their own.
     */
    private void share(@NonNull VideoEntity video) {
        if (sharing) return; // one share sheet at a time
        if (!remoteConfig.isOn(Flag.SHARE_CARDS)) { // switched off remotely: text and link only
            openShareSheet(video, null);
            return;
        }
        sharing = true;
        Context app = getApplicationContext();
        io.execute(() -> {
            Uri card = null;
            try {
                Bitmap poster = Glide.with(app).asBitmap().load(video.posterUrl)
                        .submit(ShareCard.WIDTH, ShareCard.HEIGHT).get(SHARE_IMAGE_TIMEOUT_S, TimeUnit.SECONDS);
                File file = ShareCard.write(app, video.id, ShareCard.compose(app, poster));
                card = FileProvider.getUriForFile(app, app.getPackageName() + ".shares", file);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException | TimeoutException | IOException | IllegalArgumentException e) {
                // No card: share the text and link alone.
            }
            Uri image = card;
            runOnUiThread(() -> {
                sharing = false;
                if (isFinishing() || isDestroyed()) return;
                openShareSheet(video, image);
            });
        });
    }

    /**
     * Opens a sponsored clip's link in the browser. Only http(s): the URL comes from the
     * server, and an intent: or other scheme must not be able to start app components.
     */
    private void openWebLink(@Nullable String url) {
        if (!Links.isWebUrl(url)) return;
        Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(url.trim()))
                .addCategory(Intent.CATEGORY_BROWSABLE);
        try {
            startActivity(view);
        } catch (ActivityNotFoundException e) {
            Snackbar.make(binding.getRoot(), R.string.no_browser, Snackbar.LENGTH_SHORT).show();
        }
    }

    private void openShareSheet(@NonNull VideoEntity video, @Nullable Uri image) {
        String link = Links.shareUrl(prefs.shareBaseUrl(), video.id);
        Intent send = new Intent(Intent.ACTION_SEND)
                .putExtra(Intent.EXTRA_SUBJECT, video.title)
                .putExtra(Intent.EXTRA_TEXT, getString(R.string.share_text, video.title, video.creator, link));
        if (image != null) {
            send.setType("image/jpeg")
                    .putExtra(Intent.EXTRA_STREAM, image)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            send.setClipData(ClipData.newRawUri(null, image)); // shows the preview in the sheet
        } else {
            send.setType("text/plain");
        }
        startActivity(Intent.createChooser(send, getString(R.string.share_chooser)));
    }

    /** Points the fixed overlay at the clip on screen (hidden when there is none). */
    private void updateOverlay() {
        List<VideoEntity> list = adapter.getCurrentList();
        feedOverlay.show(currentPage >= 0 && currentPage < list.size() ? list.get(currentPage) : null);
    }

    /** ViewPager2's internal RecyclerView (its only child). */
    private RecyclerView pagerRecycler() {
        return (RecyclerView) binding.pager.getChildAt(0);
    }

    private void showFirstFrame(@NonNull String videoId, long millis, boolean preloaded) {
        if (binding.overlay.getVisibility() == View.VISIBLE) {
            binding.overlay.setText(getString(R.string.overlay_first_frame, millis,
                    getString(preloaded ? R.string.preload_on : R.string.preload_off)));
        }
    }
}
