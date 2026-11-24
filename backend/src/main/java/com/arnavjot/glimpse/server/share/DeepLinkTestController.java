package com.arnavjot.glimpse.server.share;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

/**
 * Developer page for trying deep-link routing with a real app from the Play Store.
 * Defaults to Spotify:
 *
 * <pre>/dev/deeplink-test                       Spotify, opens a song
 * /dev/deeplink-test?package=...&amp;uri=...     any app and deep link</pre>
 *
 * "Open in the app" opens the deep link if the app is installed, and otherwise its Play
 * Store page with {@code utm_source=share...} in the install referrer, which is what
 * Glimpse's own share links do. Off unless {@code DEV_TOOLS=true}.
 */
@RestController
public class DeepLinkTestController {

    static final String DEFAULT_PACKAGE = "com.spotify.music";
    /** A song (PinkPantheress, Stateside) in Spotify's URI format. */
    static final String DEFAULT_URI = "spotify:track:5rVhh5aM1xyVe6JUXPTte3";

    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+");
    /** scheme:rest, with a conservative character set (no quotes, spaces or '#'). */
    private static final Pattern URI = Pattern.compile("([a-z][a-z0-9+.-]{1,30}):([A-Za-z0-9:/._?=&%-]{1,200})");

    private final boolean enabled;

    public DeepLinkTestController(@Value("${glimpse.dev-tools:false}") boolean enabled) {
        this.enabled = enabled;
    }

    @GetMapping(value = "/dev/deeplink-test", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> page(@RequestParam(name = "package", defaultValue = DEFAULT_PACKAGE) String pkg,
                                       @RequestParam(name = "uri", required = false) String uri) {
        if (!enabled) return ResponseEntity.notFound().build();
        String deepLink = uri != null ? uri.trim() : DEFAULT_PACKAGE.equals(pkg) ? DEFAULT_URI : null;
        Matcher m = deepLink == null ? null : URI.matcher(deepLink);
        if (pkg.length() > 100 || !PACKAGE.matcher(pkg).matches() || (m != null && !m.matches())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("invalid package or uri");
        }
        String store = playStoreUrl(pkg);
        String fallback = ";S.browser_fallback_url=" + URLEncoder.encode(store, StandardCharsets.UTF_8) + ";end";
        // With a deep link: open that content. Without: open the app's home screen.
        String intent = m != null
                ? "intent:" + m.group(2) + "#Intent;scheme=" + m.group(1) + ";package=" + pkg + fallback
                : "intent:#Intent;action=android.intent.action.MAIN;category=android.intent.category.LAUNCHER;"
                        + "package=" + pkg + fallback;
        return ResponseEntity.ok(render(pkg, deepLink, intent, store));
    }

    /** The app's Play Store page, with a referrer like Glimpse's share links carry. */
    static String playStoreUrl(String pkg) {
        return "https://play.google.com/store/apps/details?id=" + pkg + "&referrer="
                + URLEncoder.encode("utm_source=share&utm_medium=link&utm_campaign=deeplink-test", StandardCharsets.UTF_8);
    }

    private static String render(String pkg, String deepLink, String intent, String store) {
        String target = deepLink == null ? "its home screen" : "<code>" + HtmlUtils.htmlEscape(deepLink) + "</code>";
        return """
                <!doctype html>
                <html lang="en">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>Deep-link test · %1$s</title>
                <style>
                  body{margin:0;padding:24px 16px;background:#0B0B0F;color:#F5F5F7;font:16px/1.5 Roboto,system-ui,sans-serif}
                  main{max-width:520px;margin:auto}
                  h1{font-size:22px;margin:0 0 4px}
                  p,li{color:#A1A1AA}
                  code{color:#C6F432;word-break:break-all}
                  .btn{display:block;margin:16px 0;padding:14px 20px;border-radius:999px;text-align:center;
                    background:#C6F432;color:#0B0B0F;font-weight:600;text-decoration:none}
                  .ghost{background:transparent;color:#F5F5F7;border:1px solid #2E2E38}
                </style>
                </head>
                <body>
                <main>
                  <h1>Deep-link test</h1>
                  <p>App <code>%1$s</code>, opening %2$s</p>
                  <a class="btn" href="%3$s">Open in the app (or install it)</a>
                  <a class="btn ghost" href="%4$s">Play Store page only</a>
                  <p>Expected in Chrome on Android:</p>
                  <ul>
                    <li>App installed: it opens %2$s.</li>
                    <li>Not installed: its Play Store page opens, with an install referrer
                        (<code>utm_source=share&amp;utm_medium=link&amp;...</code>).</li>
                  </ul>
                  <p>Glimpse's share links work the same way, and Glimpse also reads its referrer
                     after the install to open the shared clip (the deferred part).</p>
                </main>
                </body>
                </html>
                """.formatted(HtmlUtils.htmlEscape(pkg), target, HtmlUtils.htmlEscape(intent), HtmlUtils.htmlEscape(store));
    }
}
