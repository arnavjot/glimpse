package com.arnavjot.glimpse.server.feed;

import java.time.Duration;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

import com.arnavjot.glimpse.server.share.AppProperties;

import jakarta.validation.constraints.Pattern;

/**
 * The page a shared link points at: /v/{id}. Chat apps read its Open Graph tags for
 * the link preview (title, creator, poster); a person who taps it gets an "Open in
 * Glimpse" button that deep-links into the app (glimpse://video/{id}).
 *
 * <p>Every value from the database is HTML-escaped: titles are user content.
 */
@RestController
@Validated
public class SharePageController {

    static final String APP_SCHEME = "glimpse://video/";

    private final FeedRepository repository;
    private final AppProperties app;

    public SharePageController(FeedRepository repository, AppProperties app) {
        this.repository = repository;
        this.app = app;
    }

    @GetMapping(value = "/v/{videoId}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> page(@PathVariable @Pattern(regexp = VideoController.ID_PATTERN) String videoId) {
        return repository.find(videoId)
                .map(video -> ResponseEntity.ok()
                        .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
                        .body(render(video, app)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(notFound()));
    }

    /**
     * The "Open in Glimpse" button is a plain glimpse:// link, which a small script
     * swaps for an Android intent URL on Android: Chrome then opens the app on the clip
     * if it is installed and otherwise the Play Store, with the clip id in the install
     * referrer so the first launch after installing opens it (a deferred deep link).
     * Elsewhere (desktop, iOS) the Google Play link is shown. URLs sit in data-
     * attributes, never in script, so nothing from the database can become code.
     */
    static String render(FeedPage.Video v, AppProperties app) {
        String title = HtmlUtils.htmlEscape(v.title());
        String creator = HtmlUtils.htmlEscape("@" + v.creator());
        String poster = HtmlUtils.htmlEscape(v.posterUrl());
        String deepLink = HtmlUtils.htmlEscape(APP_SCHEME + v.id());
        String intentUrl = HtmlUtils.htmlEscape(app.androidIntentUrl(v.id()));
        String playStore = HtmlUtils.htmlEscape(app.playStoreUrl(v.id()));
        return """
                <!doctype html>
                <html lang="en">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>%1$s · Glimpse</title>
                <meta property="og:type" content="video.other">
                <meta property="og:site_name" content="Glimpse">
                <meta property="og:title" content="%1$s">
                <meta property="og:description" content="A clip by %2$s on Glimpse">
                <meta property="og:image" content="%3$s">
                <meta name="twitter:card" content="summary_large_image">
                <style>
                  body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;
                    background:#0B0B0F;color:#F5F5F7;font:16px/1.5 Roboto,system-ui,sans-serif}
                  main{width:min(360px,100%% - 32px);text-align:center}
                  img{width:100%%;aspect-ratio:9/16;object-fit:cover;border-radius:16px;background:#16161C}
                  h1{font-size:22px;line-height:28px;margin:16px 0 4px}
                  p{margin:0;color:#A1A1AA}
                  a.open{display:inline-block;margin-top:24px;padding:12px 24px;border-radius:999px;
                    background:#C6F432;color:#0B0B0F;font-weight:600;text-decoration:none}
                  a.store{display:block;margin-top:16px;color:#A1A1AA;font-size:14px}
                </style>
                </head>
                <body>
                <main>
                  <img src="%3$s" alt="">
                  <h1>%1$s</h1>
                  <p>%2$s</p>
                  <a class="open" id="open" href="%4$s" data-android="%5$s">Open in Glimpse</a>
                  <a class="store" href="%6$s">Get Glimpse on Google Play</a>
                </main>
                <script>
                  // Android: one tap opens the app if installed, else the Play Store.
                  if (/Android/i.test(navigator.userAgent)) {
                    var open = document.getElementById("open");
                    open.href = open.dataset.android;
                  }
                </script>
                </body>
                </html>
                """.formatted(title, creator, poster, deepLink, intentUrl, playStore);
    }

    static String notFound() {
        return """
                <!doctype html>
                <html lang="en"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>Clip not found · Glimpse</title></head>
                <body style="margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;
                background:#0B0B0F;color:#F5F5F7;font:16px/1.5 Roboto,system-ui,sans-serif">
                <p>This clip isn't available any more.</p></body></html>
                """;
    }
}
