package com.liskovsoft.smartyoutubetv2.tv.ui.main;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.view.KeyEvent;
import android.content.Intent;
import android.app.SearchManager;
import android.speech.RecognizerIntent;
import android.app.Activity;
import com.liskovsoft.smartyoutubetv2.tv.ui.common.LeanbackActivity;
import android.view.View;
import android.widget.Toast;
import android.text.TextUtils;

import com.liskovsoft.smartyoutubetv2.common.misc.MotherActivity;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import com.liskovsoft.mediaserviceinterfaces.ContentService;
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * YouTube-style curated Home supplied by the user. Full-screen playback uses the
 * original SmartTube PlaybackPresenter and PlaybackActivity, not a second app.
 * YouTube-wide search uses the original SmartTube ContentService.
 */
public class CuratedActivity extends LeanbackActivity {
    private static final int REQUEST_VOICE_SEARCH = 8051;
    private String initialSearch = null;
    private static final String CONFIG_URL =
            "https://raw.githubusercontent.com/sloom059-svg/images-repo/main/curated-tv/channels.json";
    private final ExecutorService io = Executors.newFixedThreadPool(6);
    private final AtomicInteger generation = new AtomicInteger(0);
    private WebView web;
    private SharedPreferences prefs;

    @Override
    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("curated_yt_home", MODE_PRIVATE);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        web = new WebView(this);
        web.setBackgroundColor(Color.rgb(15, 15, 15));
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setAllowFileAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setDefaultTextEncodingName("UTF-8");
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                if (initialSearch != null) {
                    String query = initialSearch;
                    initialSearch = null;
                    startSearchInHome(query);
                }
            }
        });
        web.addJavascriptInterface(new Bridge(), "NativeBridge");
        setContentView(web);
        String q = getIntent() != null ? getIntent().getStringExtra(SearchManager.QUERY) : null;
        if (TextUtils.isEmpty(q) && getIntent() != null)
            q = getIntent().getStringExtra("query");
        if (!TextUtils.isEmpty(q)) initialSearch = q;
        web.loadUrl("file:///android_asset/curated_home.html");
    }

    private void startSearchInHome(String query) {
        if (TextUtils.isEmpty(query)) return;
        String js = "window.appVoiceSearch(" + JSONObject.quote(query) + ");";
        runOnUiThread(() -> { if (web != null && !isFinishing()) web.evaluateJavascript(js, null); });
    }

    private void startVoiceSearch() {
        try {
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-SA");
            intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "ابحث في YouTube Me");
            startActivityForResult(intent, REQUEST_VOICE_SEARCH);
        } catch (Exception e) {
            Toast.makeText(this, "التعرف الصوتي غير متاح على هذا الجهاز. استخدم كتابة البحث.", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public boolean onSearchRequested() {
        startVoiceSearch();
        return true;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_SEARCH) {
            startVoiceSearch();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_VOICE_SEARCH && resultCode == Activity.RESULT_OK && data != null) {
            ArrayList<String> matches = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (matches != null && !matches.isEmpty()) startSearchInHome(matches.get(0));
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String q = intent.getStringExtra(SearchManager.QUERY);
        if (TextUtils.isEmpty(q)) q = intent.getStringExtra("query");
        if (!TextUtils.isEmpty(q)) startSearchInHome(q);
    }

    private ContentService service() {
        return YouTubeServiceManager.instance().getContentService();
    }

    private void send(JSONObject payload) {
        String expression = "window.appReceive(" + JSONObject.quote(payload.toString()) + ");";
        runOnUiThread(() -> {
            if (web != null && !isFinishing()) web.evaluateJavascript(expression, null);
        });
    }

    private String readUrl(String address) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(9500);
        connection.setReadTimeout(9500);
        connection.setRequestProperty("Cache-Control", "no-cache");
        connection.setRequestProperty("User-Agent", "SmartTube-Curated/3.1");
        try {
            if (connection.getResponseCode() != 200) throw new Exception("HTTP " + connection.getResponseCode());
            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) body.append(line);
            }
            return body.toString();
        } finally {
            connection.disconnect();
        }
    }

    private JSONObject channelConfig() throws Exception {
        try {
            String text = readUrl(CONFIG_URL + "?stamp=" + (System.currentTimeMillis() / 60000));
            JSONObject config = new JSONObject(text);
            prefs.edit().putString("config", text).apply();
            return config;
        } catch (Exception e) {
            String old = prefs.getString("config", null);
            if (old != null) return new JSONObject(old);
            throw e;
        }
    }

    private JSONObject asVideo(MediaItem item, String channelName, String requestedChannel) {
        try {
            String id = item.getVideoId();
            if (id == null || !id.matches("[A-Za-z0-9_-]{11}")) return null;
            String title = item.getTitle();
            if (TextUtils.isEmpty(title)) title = "فيديو";
            String author = item.getAuthor();
            if (TextUtils.isEmpty(author)) author = channelName;
            String thumbnail = item.getCardImageUrl();
            if (TextUtils.isEmpty(thumbnail))
                thumbnail = "https://i.ytimg.com/vi/" + id + "/hqdefault.jpg";
            JSONObject result = new JSONObject();
            result.put("id", id);
            result.put("title", title);
            result.put("channel", author);
            result.put("channelId", requestedChannel);
            result.put("thumbnail", thumbnail);
            long duration = item.getDurationMs() / 1000L;
            if (duration > 0) result.put("duration", String.format(Locale.US,
                    "%d:%02d", duration / 60, duration % 60));
            return result;
        } catch (Exception ignored) {
            return null;
        }
    }

    private JSONArray extract(List<MediaGroup> groups, String name, String id, int limit) {
        JSONArray videos = new JSONArray();
        HashSet<String> seen = new HashSet<>();
        for (MediaGroup group : groups) {
            if (group == null || group.getMediaItems() == null) continue;
            for (MediaItem item : group.getMediaItems()) {
                JSONObject video = asVideo(item, name, id);
                if (video == null) continue;
                String videoId = video.optString("id");
                if (seen.add(videoId)) videos.put(video);
                if (videos.length() >= limit) return videos;
            }
        }
        return videos;
    }

    private JSONArray loadChannel(String name, String id) throws Exception {
        ContentService content = service();
        try {
            List<MediaGroup> groups = content.getChannelObserve(id)
                    .timeout(15, TimeUnit.SECONDS).blockingFirst();
            JSONArray videos = extract(groups, name, id, 16);
            if (videos.length() > 0) return videos;
        } catch (Exception ignored) {}
        // Fallback to official SmartTube RSS service if channel page is unavailable.
        MediaGroup rss = content.getRssFeedObserve(id)
                .timeout(15, TimeUnit.SECONDS).blockingFirst();
        List<MediaGroup> single = new ArrayList<>();
        single.add(rss);
        return extract(single, name, id, 16);
    }

    private void loadHome() {
        int ticket = generation.incrementAndGet();
        io.execute(() -> {
            JSONObject output = new JSONObject();
            JSONArray sections = new JSONArray();
            JSONArray all = new JSONArray();
            JSONArray errors = new JSONArray();
            try {
                JSONObject config = channelConfig();
                JSONArray list = config.optJSONArray("channels");
                if (list == null) list = new JSONArray();
                final int total = Math.min(list.length(), 30);
                // Load each channel concurrently, not sequentially. Prevent slow and
                // unavailable channels from hiding later channels behind Roblox.
                JSONArray[] perChannel = new JSONArray[total];
                java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(total);
                for (int i = 0; i < total; i++) {
                    final int position = i;
                    JSONObject c = list.optJSONObject(i);
                    if (c == null) { latch.countDown(); continue; }
                    String id = c.optString("channelId", "");
                    String name = c.optString("name", id);
                    JSONObject metadata = new JSONObject();
                    metadata.put("channelId", id);
                    metadata.put("name", name);
                    sections.put(metadata);
                    io.execute(() -> {
                        try {
                            if (!id.matches("UC[A-Za-z0-9_-]{22}")) throw new Exception("معرف القناة غير صحيح");
                            perChannel[position] = loadChannel(name, id);
                            if (perChannel[position] == null || perChannel[position].length() == 0)
                                synchronized (errors) { errors.put("لا توجد مقاطع: " + name); }
                        } catch (Exception e) {
                            synchronized (errors) { errors.put("تعذر تحميل: " + name); }
                        } finally {
                            latch.countDown();
                        }
                    });
                }
                latch.await(38, TimeUnit.SECONDS);
                // Round-robin ensures every channel's first items are visible.
                HashSet<String> seen = new HashSet<>();
                for (int row = 0; row < 16; row++) {
                    for (int col = 0; col < total; col++) {
                        JSONArray arr = perChannel[col];
                        if (arr == null || row >= arr.length()) continue;
                        JSONObject item = arr.optJSONObject(row);
                        if (item != null && seen.add(item.optString("id"))) all.put(item);
                    }
                }
            } catch (Exception e) {
                errors.put("تعذر قراءة قائمة GitHub: " + e.getMessage());
            }
            if (ticket != generation.get()) return;
            try {
                output.put("type", "home");
                output.put("channels", sections);
                output.put("videos", all);
                output.put("errors", errors);
                send(output);
            } catch (Exception ignored) {}
        });
    }

    private void performSuggest(String query) {
        if (TextUtils.isEmpty(query) || query.trim().length() == 0) return;
        String q = query.trim();
        io.execute(() -> {
            JSONArray suggestions = new JSONArray();
            try {
                List<String> terms = service().getSearchTagsObserve(q)
                        .timeout(9, TimeUnit.SECONDS).blockingFirst();
                int max = Math.min(terms.size(), 9);
                for (int i = 0; i < max; i++) {
                    String term = terms.get(i);
                    if (!TextUtils.isEmpty(term)) suggestions.put(term);
                }
            } catch (Exception ignored) {
                // SmartTube search autocomplete endpoint may be temporarily unavailable.
            }
            try {
                JSONObject payload = new JSONObject();
                payload.put("type", "suggest");
                payload.put("query", q);
                payload.put("suggestions", suggestions);
                send(payload);
            } catch (Exception ignored) {}
        });
    }

    private void performSearch(String query) {
        if (TextUtils.isEmpty(query)) return;
        int ticket = generation.incrementAndGet();
        io.execute(() -> {
            JSONObject out = new JSONObject();
            JSONArray found = new JSONArray();
            try {
                List<MediaGroup> groups = service().getSearchObserve(query, 0)
                        .timeout(25, TimeUnit.SECONDS).blockingFirst();
                found = extract(groups, "", "", 70);
            } catch (Exception e) {
                try { out.put("error", "فشل البحث في يوتيوب: " + e.getMessage()); }
                catch (Exception ignored) {}
            }
            if (ticket != generation.get()) return;
            try {
                out.put("type", "search");
                out.put("query", query);
                out.put("videos", found);
                send(out);
            } catch (Exception ignored) {}
        });
    }

    private void showHistory() {
        JSONObject out = new JSONObject();
        try {
            out.put("type", "history");
            out.put("videos", new JSONArray(prefs.getString("history", "[]")));
            send(out);
        } catch (Exception ignored) {}
    }

    private void playVideo(String id, String title, String channel, String thumbnail) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{11}")) return;
        runOnUiThread(() -> {
            try {
                JSONArray prior = new JSONArray(prefs.getString("history", "[]"));
                JSONArray next = new JSONArray();
                JSONObject current = new JSONObject();
                current.put("id", id); current.put("title", title);
                current.put("channel", channel); current.put("thumbnail", thumbnail);
                next.put(current);
                for (int i = 0; i < prior.length() && next.length() < 150; i++) {
                    JSONObject entry = prior.optJSONObject(i);
                    if (entry != null && !id.equals(entry.optString("id"))) next.put(entry);
                }
                prefs.edit().putString("history", next.toString()).apply();
                // Open the real SmartTube native player, within this same APK.
                PlaybackPresenter.instance(this).openVideo(id);
            } catch (Exception e) {
                Toast.makeText(this, "تعذر تشغيل الفيديو: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }

    private class Bridge {
        @JavascriptInterface public void home() { loadHome(); }
        @JavascriptInterface public void voice() { runOnUiThread(() -> startVoiceSearch()); }
        @JavascriptInterface public void search(String query) { performSearch(query); }
        @JavascriptInterface public void suggest(String query) { performSuggest(query); }
        @JavascriptInterface public void history() { showHistory(); }
        @JavascriptInterface public void play(String id, String title, String channel, String thumb) {
            playVideo(id, title, channel, thumb);
        }
    }

    @Override
    protected void onDestroy() {
        generation.incrementAndGet();
        io.shutdownNow();
        if (web != null) {
            web.removeJavascriptInterface("NativeBridge");
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
