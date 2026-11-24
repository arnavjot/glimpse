package com.arnavjot.glimpse.data.db;

import com.arnavjot.glimpse.pojo.FeedCursor;
import com.arnavjot.glimpse.pojo.LikedVideo;
import com.arnavjot.glimpse.pojo.LocalSample;
import com.arnavjot.glimpse.pojo.PendingEvent;
import com.arnavjot.glimpse.pojo.PendingLike;
import com.arnavjot.glimpse.pojo.VideoEntity;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(
        entities = {VideoEntity.class, FeedCursor.class, PendingEvent.class, LocalSample.class,
                LikedVideo.class, PendingLike.class},
        version = 4,
        exportSchema = true)
public abstract class AppDatabase extends RoomDatabase {

    /** 1 -> 2: the liked set moves to its own table, seeded from the cached feed. */
    public static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `liked_videos` (`id` TEXT NOT NULL, PRIMARY KEY(`id`))");
            db.execSQL("INSERT OR IGNORE INTO `liked_videos` (`id`) SELECT `id` FROM `videos` WHERE `liked` = 1");
        }
    };

    /** 3 -> 4: sponsored clips in the cached feed. */
    public static final Migration MIGRATION_3_4 = new Migration(3, 4) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE `videos` ADD COLUMN `sponsored` INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE `videos` ADD COLUMN `ctaUrl` TEXT");
        }
    };

    /** 2 -> 3: the offline like queue. */
    public static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `pending_likes` (`videoId` TEXT NOT NULL, "
                    + "`liked` INTEGER NOT NULL, `version` INTEGER NOT NULL, `queuedAt` INTEGER NOT NULL, "
                    + "PRIMARY KEY(`videoId`))");
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_pending_likes_queuedAt` ON `pending_likes` (`queuedAt`)");
        }
    };

    public abstract VideoDao videos();

    public abstract PendingEventDao events();

    public abstract LocalSampleDao samples();

    public abstract PendingLikeDao pendingLikes();
}
