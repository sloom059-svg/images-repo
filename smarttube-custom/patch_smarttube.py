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
# Curated YouTube-style homepage: exclude SmartTube's Music, Trending, Shorts,
# News and all its other browsing shelves. Keep Settings accessible.
# Search is an independent SmartTube action in the top toolbar.
section_block = '''    private void initPinnedSections() {
        mSections.clear();
        BrowseSection home = mSectionsMapping.get(MediaGroup.TYPE_HOME);
        if (home != null) {
            home.setEnabled(true);
            mSections.add(home);
        }
        BrowseSection settings = mSectionsMapping.get(MediaGroup.TYPE_SETTINGS);
        if (settings != null) {
            settings.setEnabled(true);
            mSections.add(settings);
        }
    }
'''
old_start = source.index('    private void initPinnedSections() {')
old_end = source.index('    private void initPinnedCallbacks() {', old_start)
source = source[:old_start] + section_block + '\n' + source[old_end:]
# The original SmartTube deliberately boots Music for logged-out fresh installs;
# overriding this is essential or users get arbitrary music recommendations.
fallback = '''        // Empty Home on first run fix. Switch to something non-empty.
        if (!getSignInService().isSigned() && VideoStateService.instance(getContext()).isEmpty()) {
            bootSectionId = MediaGroup.TYPE_MUSIC;
        }'''
assert source.count(fallback) == 1, 'Original forced-music startup logic moved'
source = source.replace(fallback, '        // Always open the curated home; never boot into Music.\n        bootSectionId = MediaGroup.TYPE_HOME;')
source = source.replace('''        for (BrowseSection section : mErrorSections) {
            getView().addSection(index++, section);
        }''','''        // Do not show network or login error sections as alternative recommendations.''')
source = source.replace('''int selectedSectionIndex = findSectionIndex(mCurrentSection != null ? mCurrentSection.getId() : mBootstrapSectionId);''',
                        '''int selectedSectionIndex = findSectionIndex(MediaGroup.TYPE_HOME);''')
# Prevent a previous session's Music section from being recalled.
source = source.replace('''int selectedSectionIndex = findSectionIndex(mCurrentSection != null ? mCurrentSection.getId() : -1);''',
                        '''int selectedSectionIndex = findSectionIndex(MediaGroup.TYPE_HOME);''')

# Give the customized app a different package and visible label so it cannot
# be confused with (or replace) the official SmartTube installation.
gradle_path = Path("SmartTube/smarttubetv/build.gradle")
gradle = gradle_path.read_text(encoding="utf-8")
flavor_needle = 'ststable {\n            applicationId "org.smarttube.stable"'
assert gradle.count(flavor_needle) == 1, "Stable flavor changed upstream"
gradle = gradle.replace(flavor_needle,
                        'ststable {\n            applicationId "org.smarttube.curated"')
# The original Google Services JSON only has the official package IDs. Do not
# reuse official Firebase credentials for the customized package.
firebase_needle = 'if (new File("${projectDir}/google-services.json").exists() &&'
assert gradle.count(firebase_needle) == 1, "Google Services configuration changed upstream"
gradle = gradle.replace(firebase_needle,
                        'if (false && new File("${projectDir}/google-services.json").exists() &&')
gradle_path.write_text(gradle, encoding="utf-8")

manifest_path = Path("SmartTube/smarttubetv/src/main/AndroidManifest.xml")
manifest = manifest_path.read_text(encoding="utf-8")
label_needle = 'android:label="@string/app_name"'
assert manifest.count(label_needle) == 2, "Manifest launcher labels changed upstream"
manifest = manifest.replace(label_needle, 'android:label="قنواتي SmartTube"')
merge_needle = 'tools:replace="android:theme,android:allowBackup"'
assert manifest.count(merge_needle) == 1, "Main manifest merge config changed"
manifest = manifest.replace(merge_needle,
                            'tools:replace="android:theme,android:allowBackup,android:label"')
manifest_path.write_text(manifest, encoding="utf-8")

# Install the user's YouTube-like HTML and bind it to native SmartTube playback.
assets_dir = Path("SmartTube/smarttubetv/src/main/assets")
assets_dir.mkdir(parents=True, exist_ok=True)
(assets_dir / "curated_home.html").write_bytes(
    Path("smarttube-custom/youtube_ui.html").read_bytes())
java_dir = Path("SmartTube/smarttubetv/src/main/java/com/liskovsoft/smartyoutubetv2/tv/ui/main")
(java_dir / "CuratedActivity.java").write_bytes(
    Path("smarttube-custom/CuratedActivity.java").read_bytes())

# On normal launcher start, open the user's WebView interface, not original
# SmartTube browse. Keep all YouTube deep-link handling in original Splash.
splash_path = java_dir / "SplashActivity.java"
splash = splash_path.read_text(encoding="utf-8")
start_marker = "        super.onCreate(savedInstanceState);"
assert splash.count(start_marker) == 1, "SmartTube splash changed"
splash = splash.replace(start_marker, start_marker + '''
        if (getIntent() != null && Intent.ACTION_MAIN.equals(getIntent().getAction())) {
            startActivity(new Intent(this, CuratedActivity.class));
            finish();
            return;
        }''')
splash = splash.replace('        mPresenter.onViewDestroyed();',
                        '        if (mPresenter != null) mPresenter.onViewDestroyed();')
splash_path.write_text(splash, encoding="utf-8")

activity_marker = '        <activity\\n            android:name="com.liskovsoft.smartyoutubetv2.tv.ui.main.SplashActivity"'
assert manifest.count(activity_marker) == 1, "Splash manifest entry changed"
new_activity = '''        <activity
            android:name="com.liskovsoft.smartyoutubetv2.tv.ui.main.CuratedActivity"
            android:exported="false"
            android:screenOrientation="landscape"
            android:theme="@style/App.Theme.Leanback.Browse" />

'''
manifest = manifest.replace(activity_marker, new_activity + activity_marker, 1)
manifest_path.write_text(manifest, encoding="utf-8")

p.write_text(source, encoding="utf-8")
print("Patched official SmartTube Home mapping; all other sections and playback untouched.")
