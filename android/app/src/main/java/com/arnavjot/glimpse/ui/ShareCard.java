package com.arnavjot.glimpse.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;
import androidx.core.content.ContextCompat;

import com.arnavjot.glimpse.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * The image attached when a clip is shared: its poster, cropped to 9:16, with a round
 * play badge in the middle so it reads as a video in a chat. Colours are the design
 * system's (pn_scrim, pn_feed_ink).
 */
final class ShareCard {

    static final int WIDTH = 720;
    static final int HEIGHT = 1280;
    /** Folder under the cache dir; must match res/xml/share_paths.xml. */
    static final String DIR = "shared";
    private static final long KEEP_MS = TimeUnit.DAYS.toMillis(1);

    private ShareCard() {
    }

    /** Poster, centre-cropped to WIDTH x HEIGHT, with the play badge. */
    @NonNull
    static Bitmap compose(@NonNull Context context, @NonNull Bitmap poster) {
        Bitmap out = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);

        float scale = Math.max((float) WIDTH / poster.getWidth(), (float) HEIGHT / poster.getHeight());
        Matrix m = new Matrix();
        m.setScale(scale, scale);
        m.postTranslate((WIDTH - poster.getWidth() * scale) / 2f, (HEIGHT - poster.getHeight() * scale) / 2f);
        canvas.drawBitmap(poster, m, new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));

        int ink = ContextCompat.getColor(context, R.color.pn_feed_ink);
        float cx = WIDTH / 2f;
        float cy = HEIGHT / 2f;
        float radius = WIDTH * 0.14f;

        Paint disc = new Paint(Paint.ANTI_ALIAS_FLAG);
        disc.setColor(ContextCompat.getColor(context, R.color.pn_scrim));
        canvas.drawCircle(cx, cy, radius, disc);

        Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(radius * 0.06f);
        ring.setColor(ink);
        canvas.drawCircle(cx, cy, radius, ring);

        // Triangle nudged right of centre so it looks centred (its mass is on the left).
        float side = radius * 0.9f;
        Path play = new Path();
        play.moveTo(cx - side * 0.35f, cy - side * 0.5f);
        play.lineTo(cx + side * 0.55f, cy);
        play.lineTo(cx - side * 0.35f, cy + side * 0.5f);
        play.close();
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(ink);
        canvas.drawPath(play, fill);
        return out;
    }

    /** Saves the card as cache/shared/<id>.jpg and drops cards older than a day. */
    @WorkerThread
    @NonNull
    static File write(@NonNull Context context, @NonNull String videoId, @NonNull Bitmap card) throws IOException {
        File dir = new File(context.getCacheDir(), DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("can't create " + dir);
        File[] old = dir.listFiles();
        if (old != null) {
            long cutoff = System.currentTimeMillis() - KEEP_MS;
            for (File f : old) {
                if (f.lastModified() < cutoff) //noinspection ResultOfMethodCallIgnored
                    f.delete();
            }
        }
        File file = new File(dir, videoId + ".jpg");
        try (FileOutputStream out = new FileOutputStream(file)) {
            if (!card.compress(Bitmap.CompressFormat.JPEG, 88, out)) throw new IOException("encode failed");
        }
        return file;
    }
}
