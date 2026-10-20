package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fetches the latest movies and series (sorted by release date, PremiereDate descending)
 * from a Jellyfin server, caches their backdrop images on disk, darkens them with a cinematic vignette
 * for home-screen legibility, and crossfades them in the background.
 */
final class JellyfinCarousel {
    private static final String TAG = "TvHome.Jellyfin";
    private static final long REFRESH_INTERVAL_MS = 15 * 60_000L;
    private static final int CONNECT_TIMEOUT_MS = 6_000;
    private static final int READ_TIMEOUT_MS = 12_000;

    private final Context ctx;
    private final ImageView viewA;
    private final ImageView viewB;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "jellyfin-carousel");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        t.setDaemon(true);
        return t;
    });

    private String serverUrl = "";
    private String apiKey = "";
    private long rotateIntervalMs = 10_000L;
    private int moviesCount = 10;
    private int seriesCount = 10;
    private boolean reducedMotion = false;
    private boolean running = false;
    private boolean showingA = true;
    private boolean hasShownFirst = false;
    private long lastSyncElapsed = 0L;

    private final List<File> backdropFiles = new ArrayList<>();
    private int currentIndex = 0;

    private final Runnable rotateRunnable = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            showNext();
            mainHandler.postDelayed(this, rotateIntervalMs);
        }
    };

    JellyfinCarousel(Context ctx, ImageView viewA, ImageView viewB) {
        this.ctx = ctx.getApplicationContext();
        this.viewA = viewA;
        this.viewB = viewB;
    }

    void configure(Config cfg) {
        String cleanUrl = normalizeUrl(cfg.jellyfinUrl);
        String cleanKey = cfg.jellyfinApiKey != null ? cfg.jellyfinApiKey.trim() : "";
        long wantIntervalMs = Math.max(2, cfg.jellyfinCarouselIntervalSeconds) * 1000L;
        int wantMovies = Math.max(0, cfg.jellyfinMoviesCount);
        int wantSeries = Math.max(0, cfg.jellyfinSeriesCount);
        boolean changed = !cleanUrl.equals(this.serverUrl)
                || !cleanKey.equals(this.apiKey)
                || wantMovies != this.moviesCount
                || wantSeries != this.seriesCount;
        this.serverUrl = cleanUrl;
        this.apiKey = cleanKey;
        this.rotateIntervalMs = wantIntervalMs;
        this.moviesCount = wantMovies;
        this.seriesCount = wantSeries;
        this.reducedMotion = cfg.reducedMotion;
        if (changed) {
            lastSyncElapsed = 0L;
            if (running && !cleanKey.isEmpty()) {
                executor.execute(this::syncFromServer);
            }
        }
    }

    void start() {
        if (running) return;
        if (apiKey.isEmpty()) {
            Log.i(TAG, "No Jellyfin API key configured; skipping backdrop carousel.");
            stop();
            viewA.setAlpha(0f);
            viewB.setAlpha(0f);
            return;
        }
        running = true;
        // Load any existing cached backdrops immediately so the home screen isn't blank while syncing.
        executor.execute(() -> {
            List<File> cached = loadCachedFiles();
            if (!cached.isEmpty()) {
                mainHandler.post(() -> {
                    if (!running) return;
                    if (backdropFiles.isEmpty()) {
                        backdropFiles.addAll(cached);
                        showNext();
                        mainHandler.removeCallbacks(rotateRunnable);
                        mainHandler.postDelayed(rotateRunnable, rotateIntervalMs);
                    }
                });
            }
            long now = System.currentTimeMillis();
            if (now - lastSyncElapsed >= REFRESH_INTERVAL_MS || backdropFiles.isEmpty()) {
                syncFromServer();
            }
        });
        if (!backdropFiles.isEmpty()) {
            mainHandler.removeCallbacks(rotateRunnable);
            mainHandler.postDelayed(rotateRunnable, rotateIntervalMs);
        }
    }

    void stop() {
        running = false;
        mainHandler.removeCallbacks(rotateRunnable);
    }

    private void showNext() {
        if (backdropFiles.isEmpty()) return;
        if (currentIndex >= backdropFiles.size()) currentIndex = 0;
        final File file = backdropFiles.get(currentIndex);
        currentIndex = (currentIndex + 1) % backdropFiles.size();

        executor.execute(() -> {
            final Bitmap bmp = decodeAndDarken(file);
            if (bmp == null) return;
            mainHandler.post(() -> {
                if (!running) return;
                crossfadeTo(bmp);
            });
        });
    }

    private void crossfadeTo(Bitmap bmp) {
        final ImageView fadeIn = showingA ? viewA : viewB;
        final ImageView fadeOut = showingA ? viewB : viewA;
        showingA = !showingA;

        fadeIn.animate().cancel();
        fadeOut.animate().cancel();
        fadeIn.setImageBitmap(bmp);

        long duration = !hasShownFirst ? 600L : (reducedMotion ? 400L : 1200L);
        hasShownFirst = true;

        fadeIn.setAlpha(0f);
        fadeIn.animate()
                .alpha(1f)
                .setDuration(duration)
                .setInterpolator(new DecelerateInterpolator())
                .start();
        if (fadeOut.getAlpha() > 0f) {
            fadeOut.animate()
                    .alpha(0f)
                    .setDuration(duration)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        }
    }

    private File cacheDir() {
        File d = new File(ctx.getCacheDir(), "jellyfin_backdrops");
        if (!d.exists()) {
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        }
        return d;
    }

    private List<File> loadCachedFiles() {
        File dir = cacheDir();
        File[] files = dir.listFiles((d, name) -> name.endsWith(".jpg"));
        if (files == null || files.length == 0) return new ArrayList<>();
        Arrays.sort(files, Comparator.comparing(File::getName));
        List<File> valid = new ArrayList<>();
        for (File f : files) {
            if (f.isFile() && f.length() > 0) valid.add(f);
        }
        return valid;
    }

    private void syncFromServer() {
        try {
            String baseUrl = serverUrl;
            if (baseUrl.isEmpty() || "auto".equalsIgnoreCase(baseUrl)) {
                baseUrl = discoverServerUrl();
            }
            if (baseUrl == null || baseUrl.isEmpty() || apiKey.isEmpty()) {
                Log.w(TAG, "Missing Jellyfin URL or API key");
                return;
            }
            Log.i(TAG, "Syncing backdrops from " + baseUrl);

            List<BackdropRef> movies = moviesCount > 0
                    ? fetchLatestByReleaseDate(baseUrl, apiKey, null, "Movie", moviesCount)
                    : Collections.emptyList();
            List<BackdropRef> series = seriesCount > 0
                    ? fetchLatestByReleaseDate(baseUrl, apiKey, null, "Series", seriesCount)
                    : Collections.emptyList();
            if (movies.isEmpty() && series.isEmpty()) {
                String userId = fetchFirstUserId(baseUrl, apiKey);
                if (userId != null) {
                    if (moviesCount > 0) {
                        movies = fetchLatestByReleaseDate(baseUrl, apiKey, userId, "Movie", moviesCount);
                    }
                    if (seriesCount > 0) {
                        series = fetchLatestByReleaseDate(baseUrl, apiKey, userId, "Series", seriesCount);
                    }
                }
            }

            Log.i(TAG, "Found " + movies.size() + " movies and " + series.size() + " series with backdrops");
            List<BackdropRef> interleaved = interleave(movies, series);
            if (interleaved.isEmpty()) return;

            File dir = cacheDir();
            List<File> downloaded = new ArrayList<>();
            Set<String> keepNames = new HashSet<>();

            for (int i = 0; i < interleaved.size(); i++) {
                BackdropRef ref = interleaved.get(i);
                String fileName = String.format(java.util.Locale.US, "%02d_%s_%s.jpg", i, ref.itemId, ref.tag);
                keepNames.add(fileName);
                File target = new File(dir, fileName);
                if (!target.isFile() || target.length() == 0) {
                    boolean ok = downloadBackdrop(baseUrl, apiKey, ref, target);
                    if (!ok) continue;
                }
                downloaded.add(target);
                // Show the very first backdrop immediately without waiting for the remaining items to download.
                if (downloaded.size() == 1) {
                    final File firstFile = target;
                    mainHandler.post(() -> {
                        if (backdropFiles.isEmpty()) {
                            backdropFiles.add(firstFile);
                            if (running) {
                                showNext();
                                mainHandler.removeCallbacks(rotateRunnable);
                                mainHandler.postDelayed(rotateRunnable, rotateIntervalMs);
                            }
                        }
                    });
                }
            }

            if (downloaded.isEmpty()) return;

            // Remove stale cached backdrops no longer in the active list.
            File[] existing = dir.listFiles();
            if (existing != null) {
                for (File f : existing) {
                    if (!keepNames.contains(f.getName())) {
                        //noinspection ResultOfMethodCallIgnored
                        f.delete();
                    }
                }
            }

            lastSyncElapsed = System.currentTimeMillis();
            final List<File> freshList = downloaded;
            mainHandler.post(() -> {
                boolean wasEmpty = backdropFiles.isEmpty();
                backdropFiles.clear();
                backdropFiles.addAll(freshList);
                if (currentIndex >= backdropFiles.size()) currentIndex = 0;
                if (running && wasEmpty) {
                    showNext();
                    mainHandler.removeCallbacks(rotateRunnable);
                    mainHandler.postDelayed(rotateRunnable, rotateIntervalMs);
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "Jellyfin sync failed: " + e.getMessage());
        }
    }

    static List<BackdropRef> interleave(List<BackdropRef> movies, List<BackdropRef> series) {
        List<BackdropRef> out = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        int max = Math.max(movies.size(), series.size());
        for (int i = 0; i < max; i++) {
            if (i < movies.size()) {
                BackdropRef m = movies.get(i);
                if (seenIds.add(m.itemId)) out.add(m);
            }
            if (i < series.size()) {
                BackdropRef s = series.get(i);
                if (seenIds.add(s.itemId)) out.add(s);
            }
        }
        return out;
    }

    private static List<BackdropRef> fetchLatestByReleaseDate(String baseUrl, String apiKey, String userId,
                                                              String itemType, int limit) {
        List<BackdropRef> out = new ArrayList<>();
        String path = (userId != null && !userId.isEmpty())
                ? "/Users/" + userId + "/Items"
                : "/Items";
        String query = "?IncludeItemTypes=" + itemType
                + "&Recursive=true"
                + "&SortBy=PremiereDate,DateCreated"
                + "&SortOrder=Descending"
                + "&ImageTypes=Backdrop"
                + "&Fields=PremiereDate,BackdropImageTags"
                + "&Limit=" + limit;
        try {
            String body = httpGetString(baseUrl + path + query, apiKey);
            if (body == null) return out;
            return parseItemsJson(body, limit);
        } catch (Exception e) {
            Log.w(TAG, "Failed fetching " + itemType + ": " + e.getMessage());
            return out;
        }
    }

    static List<BackdropRef> parseItemsJson(String jsonText, int limit) {
        List<BackdropRef> out = new ArrayList<>();
        if (jsonText == null || jsonText.isEmpty()) return out;
        try {
            JSONObject root = new JSONObject(jsonText);
            JSONArray items = root.optJSONArray("Items");
            if (items == null) return out;
            for (int i = 0; i < items.length() && out.size() < limit; i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                String id = item.optString("Id", "").trim();
                if (id.isEmpty()) continue;
                JSONArray tags = item.optJSONArray("BackdropImageTags");
                String tag = (tags != null && tags.length() > 0) ? tags.optString(0, "").trim() : "";
                if (tag.isEmpty()) continue;
                out.add(new BackdropRef(id, tag));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static String fetchFirstUserId(String baseUrl, String apiKey) {
        try {
            String body = httpGetString(baseUrl + "/Users", apiKey);
            if (body == null) return null;
            JSONArray users = new JSONArray(body);
            for (int i = 0; i < users.length(); i++) {
                JSONObject u = users.optJSONObject(i);
                if (u == null) continue;
                JSONObject policy = u.optJSONObject("Policy");
                if (policy != null && policy.optBoolean("IsDisabled", false)) continue;
                String id = u.optString("Id", "").trim();
                if (!id.isEmpty()) return id;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static void applyAuthHeaders(HttpURLConnection conn, String apiKey) {
        conn.setRequestProperty("Authorization", "MediaBrowser Token=\"" + apiKey + "\"");
        conn.setRequestProperty("X-Emby-Token", apiKey);
    }

    private static boolean downloadBackdrop(String baseUrl, String apiKey, BackdropRef ref, File target) {
        String url = baseUrl + "/Items/" + ref.itemId + "/Images/Backdrop/0"
                + "?maxWidth=1920&maxHeight=1080&quality=85&tag=" + ref.tag;
        HttpURLConnection conn = null;
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            applyAuthHeaders(conn, apiKey);
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "Backdrop HTTP " + code + " for " + ref.itemId);
                return false;
            }
            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[8192];
                int n;
                long total = 0;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > 8 * 1024 * 1024) return false; // safety cap: 8 MB
                    out.write(buf, 0, n);
                }
            }
            if (tmp.length() > 0 && tmp.renameTo(target)) {
                return true;
            }
        } catch (Exception e) {
            Log.w(TAG, "Backdrop download failed for " + ref.itemId + ": " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
            if (tmp.exists()) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }
        return false;
    }

    private static String httpGetString(String urlStr, String apiKey) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/json");
            applyAuthHeaders(conn, apiKey);
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "HTTP " + code + " from " + urlStr);
                return null;
            }
            try (InputStream in = conn.getInputStream();
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > 1024 * 1024) return null;
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            Log.w(TAG, "HTTP error for " + urlStr + ": " + e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Auto-discovers a Jellyfin server on the local network using Jellyfin's UDP 7359 discovery protocol.
     */
    private static String discoverServerUrl() {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            socket.setSoTimeout(1500);
            byte[] probe = "Who is JellyfinServer?".getBytes(StandardCharsets.UTF_8);
            DatagramPacket packet = new DatagramPacket(
                    probe, probe.length, InetAddress.getByName("255.255.255.255"), 7359);
            socket.send(packet);
            byte[] buf = new byte[1024];
            DatagramPacket reply = new DatagramPacket(buf, buf.length);
            socket.receive(reply);
            String jsonStr = new String(reply.getData(), 0, reply.getLength(), StandardCharsets.UTF_8);
            JSONObject obj = new JSONObject(jsonStr);
            return normalizeUrl(obj.optString("Address", ""));
        } catch (Exception e) {
            return "";
        }
    }

    static String normalizeUrl(String url) {
        if (url == null) return "";
        String s = url.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    /**
     * Decodes a cached backdrop image at screen resolution (RGB_565) and bakes in a dark cinematic
     * vignette so the top clock/buttons and the bottom dock remain crisp and readable without extra
     * GPU overdraw during crossfades.
     */
    private Bitmap decodeAndDarken(File file) {
        try {
            DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            int targetW = Math.min(1920, Math.max(960, dm.widthPixels));
            int targetH = Math.min(1080, Math.max(540, dm.heightPixels));

            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= targetW && bounds.outHeight / (sample * 2) >= targetH) {
                sample *= 2;
            }

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap raw = BitmapFactory.decodeFile(file.getPath(), opts);
            if (raw == null) return null;

            Bitmap out = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.RGB_565);
            Canvas canvas = new Canvas(out);

            // Center-crop raw into targetW x targetH
            float scale = Math.max((float) targetW / raw.getWidth(), (float) targetH / raw.getHeight());
            int srcW = Math.round(targetW / scale);
            int srcH = Math.round(targetH / scale);
            int srcX = Math.max(0, (raw.getWidth() - srcW) / 2);
            int srcY = Math.max(0, (raw.getHeight() - srcH) / 2);
            Rect srcRect = new Rect(srcX, srcY, Math.min(raw.getWidth(), srcX + srcW), Math.min(raw.getHeight(), srcY + srcH));
            Rect dstRect = new Rect(0, 0, targetW, targetH);
            Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
            canvas.drawBitmap(raw, srcRect, dstRect, bmpPaint);
            if (raw != out) raw.recycle();

            // Darken overall + top/bottom cinematic gradient so UI elements pop cleanly
            Paint scrim = new Paint();
            scrim.setShader(new LinearGradient(
                    0f, 0f, 0f, targetH,
                    new int[]{0xB3000000, 0x59000000, 0x66000000, 0xCC000000, 0xF2000000},
                    new float[]{0f, 0.25f, 0.55f, 0.82f, 1f},
                    Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, targetW, targetH, scrim);
            return out;
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    static final class BackdropRef {
        final String itemId;
        final String tag;

        BackdropRef(String itemId, String tag) {
            this.itemId = itemId;
            this.tag = tag;
        }
    }
}
