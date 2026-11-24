package com.arnavjot.glimpse.viewmodel;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.ViewModel;

import com.arnavjot.glimpse.analytics.PlaybackAnalytics;
import com.arnavjot.glimpse.common.Event;
import com.arnavjot.glimpse.repository.FeedRepository;
import com.arnavjot.glimpse.pojo.VideoEntity;

import java.util.List;

import javax.inject.Inject;

public class FeedViewModel extends ViewModel {

    /** Start fetching the next page when this many clips are left. */
    static final int PREFETCH_DISTANCE = 3;

    private final FeedRepository repository;
    private final PlaybackAnalytics analytics;

    @Inject
    public FeedViewModel(FeedRepository repository, PlaybackAnalytics analytics) {
        this.repository = repository;
        this.analytics = analytics;
        repository.refresh();
    }

    public LiveData<List<VideoEntity>> feed() {
        return repository.feed();
    }

    public LiveData<FeedRepository.State> state() {
        return repository.state();
    }

    /** One-shot: a like couldn't be saved and was undone. */
    public LiveData<Event<FeedRepository.Failure>> likeFailures() {
        return repository.likeFailures();
    }

    /** Pull to refresh or Retry: fetch again and show the new first clip. */
    public void refresh() {
        repository.refreshFromUser();
    }

    public void onPageSelected(int position, int itemCount) {
        if (itemCount > 0 && position >= itemCount - PREFETCH_DISTANCE) {
            repository.loadMore();
        }
    }

    /** A shared link was opened: the feed is fetched with that clip first. */
    public void openVideo(@NonNull String videoId) {
        repository.openVideo(videoId);
    }

    /** One-shot: scroll to this clip once the feed shows it. */
    public LiveData<Event<FeedRepository.Show>> showVideo() {
        return repository.showVideo();
    }

    /** One-shot: the shared clip couldn't be opened. */
    public LiveData<Event<FeedRepository.Failure>> openFailures() {
        return repository.openFailures();
    }

    /** The user tapped like/unlike: {@code liked} is the state they asked for. */
    public void setLiked(@NonNull String videoId, boolean liked) {
        repository.setLiked(videoId, liked);
    }

    @Override
    protected void onCleared() {
        analytics.flush();
    }
}
