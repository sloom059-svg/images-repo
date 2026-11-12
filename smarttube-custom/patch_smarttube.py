#!/usr/bin/env python3
"""Patch SmartTube ORIGINAL source to display only user-selected channel rows on Home."""
from pathlib import Path

p = Path("SmartTube/common/src/main/java/com/liskovsoft/smartyoutubetv2/common/app/presenters/BrowsePresenter.java")
source = p.read_text(encoding="utf-8")
needle = 'mRowMapping.put(MediaGroup.TYPE_HOME, getContentService().getHomeObserve());'
assert source.count(needle) == 1, "Upstream Home mapping changed; stop instead of building an unfiltered app."
source = source.replace(needle, 'mRowMapping.put(MediaGroup.TYPE_HOME, getCuratedHomeObserve());')
extra_imports = '''import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import org.json.JSONArray;
import org.json.JSONObject;
import io.reactivex.schedulers.Schedulers;
'''
assert 'import java.util.ArrayList;' in source
source = source.replace('import java.util.ArrayList;', extra_imports + 'import java.util.ArrayList;', 1)
anchor = '    private void initRowAndGridMapping() {'
assert source.count(anchor) == 1
injected = '''
    // Curated TV: the Home rows are built only from channels.json. Search and player
    // remain the ORIGINAL SmartTube implementations; do not add generic recommendations.
    private static final String CURATED_CHANNELS_URL =
            "https://raw.githubusercontent.com/sloom059-svg/images-repo/main/curated-tv/channels.json";

    private Observable<List<MediaGroup>> getCuratedHomeObserve() {
        return Observable.fromCallable(this::fetchCuratedChannelIds)
                .subscribeOn(Schedulers.io())
                .flatMap(ids -> Observable.fromIterable(ids)
                        .concatMap(channelId -> getContentService().getChannelObserve(channelId)
                                .take(1)
                                .onErrorReturnItem(Collections.<MediaGroup>emptyList()))
                        .toList()
                        .toObservable()
                        .map(groups -> {
                            List<MediaGroup> result = new ArrayList<>();
                            for (List<MediaGroup> group : groups) {
                                result.addAll(group);
                            }
                            return result;
                        }))
                .onErrorReturnItem(Collections.<MediaGroup>emptyList());
    }

    private List<String> fetchCuratedChannelIds() throws Exception {
        HttpURLConnection connection = (HttpURLConnection)
                new URL(CURATED_CHANNELS_URL + "?v=" + (System.currentTimeMillis() / 60000)).openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(8000);
        connection.setRequestProperty("Cache-Control", "no-cache");
        StringBuilder body = new StringBuilder();
        try {
            if (connection.getResponseCode() != 200) {
                throw new IllegalStateException("Cannot fetch curated channel list");
            }
            try (BufferedReader input = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = input.readLine()) != null) body.append(line);
            }
        } finally {
            connection.disconnect();
        }
        JSONArray channels = new JSONObject(body.toString()).optJSONArray("channels");
        List<String> result = new ArrayList<>();
        if (channels == null) return result;
        for (int i = 0; i < channels.length() && i < 40; i++) {
            JSONObject channel = channels.optJSONObject(i);
            if (channel == null) continue;
            String id = channel.optString("channelId", "");
            if (id.matches("UC[A-Za-z0-9_-]{22}") && !result.contains(id)) result.add(id);
        }
        return result;
    }

'''
source = source.replace(anchor, injected + anchor, 1)
# Give the customized app a different package and visible label so it cannot
# be confused with (or replace) the official SmartTube installation.
gradle_path = Path("SmartTube/smarttubetv/build.gradle")
gradle = gradle_path.read_text(encoding="utf-8")
flavor_needle = 'ststable {\n            applicationId "org.smarttube.stable"'
assert gradle.count(flavor_needle) == 1, "Stable flavor changed upstream"
gradle = gradle.replace(flavor_needle,
                        'ststable {\n            applicationId "org.smarttube.curated"')
gradle_path.write_text(gradle, encoding="utf-8")

manifest_path = Path("SmartTube/smarttubetv/src/main/AndroidManifest.xml")
manifest = manifest_path.read_text(encoding="utf-8")
label_needle = 'android:label="@string/app_name"'
assert manifest.count(label_needle) == 1, "Manifest label changed upstream"
manifest = manifest.replace(label_needle, 'android:label="قنواتي SmartTube"')
manifest_path.write_text(manifest, encoding="utf-8")

p.write_text(source, encoding="utf-8")
print("Patched official SmartTube Home mapping; all other sections and playback untouched.")
