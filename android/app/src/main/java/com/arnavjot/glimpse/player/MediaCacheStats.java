package com.arnavjot.glimpse.player;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;
import androidx.media3.datasource.cache.CacheDataSource;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Counts media bytes served from the disk cache vs downloaded, since app start.
 * cacheBytes / (cacheBytes + networkBytes) is the byte hit ratio on the
 * Performance screen: swiping back, replays and preloaded clips should be cache.
 */
@OptIn(markerClass = UnstableApi.class)
public final class MediaCacheStats implements CacheDataSource.EventListener, TransferListener {

    private final AtomicLong cacheBytes = new AtomicLong();
    private final AtomicLong networkBytes = new AtomicLong();

    public long cacheBytes() {
        return cacheBytes.get();
    }

    public long networkBytes() {
        return networkBytes.get();
    }

    /** @return 0..1, or -1 before any media has been read. */
    public double byteHitRatio() {
        long c = cacheBytes.get();
        long total = c + networkBytes.get();
        return total == 0 ? -1 : (double) c / total;
    }

    public void reset() {
        cacheBytes.set(0);
        networkBytes.set(0);
    }

    @Override
    public void onCachedBytesRead(long cacheSizeBytes, long cachedBytesRead) {
        cacheBytes.addAndGet(cachedBytesRead);
    }

    @Override
    public void onCacheIgnored(int reason) {}

    @Override
    public void onTransferInitializing(@NonNull DataSource source, @NonNull DataSpec spec, boolean isNetwork) {}

    @Override
    public void onTransferStart(@NonNull DataSource source, @NonNull DataSpec spec, boolean isNetwork) {}

    @Override
    public void onBytesTransferred(
            @NonNull DataSource source, @NonNull DataSpec spec, boolean isNetwork, int bytes) {
        if (isNetwork) networkBytes.addAndGet(bytes);
    }

    @Override
    public void onTransferEnd(@NonNull DataSource source, @NonNull DataSpec spec, boolean isNetwork) {}
}
