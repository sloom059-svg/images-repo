package com.sloom.curatedtv

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.content.Intent
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import java.util.Collections
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import android.util.Xml
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : Activity() {
    data class Video(val id: String, val title: String, val channel: String, val published: String)
    private val io = Executors.newFixedThreadPool(4)
    private val videos = mutableListOf<Video>()
    private lateinit var grid: GridView
    private lateinit var message: TextView
    private lateinit var heading: TextView
    private lateinit var adapter: BaseAdapter
    private var thumbnails = true
    private var searchMode = false
    private var searchSerial = 0
    private lateinit var searchInput: EditText
    private var player: ExoPlayer? = null
    private var playerScreen: View? = null
    private var homeScreen: View? = null
    private val bg = Color.rgb(9, 20, 27)
    private val panel = Color.rgb(23, 40, 49)
    private val accent = Color.rgb(68, 231, 186)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        NewPipe.init(object : Downloader() {
            override fun execute(request: Request): Response {
                val conn = URL(request.url()).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000; conn.readTimeout = 15000
                conn.requestMethod = request.httpMethod()
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/124.0 Safari/537.36")
                for ((k, vals) in request.headers()) if (vals.isNotEmpty()) conn.setRequestProperty(k, vals.joinToString(", "))
                request.dataToSend()?.let { bytes -> conn.doOutput = true; conn.outputStream.use { it.write(bytes) } }
                try {
                    val status = conn.responseCode
                    val body = (if (status >= 400) conn.errorStream else conn.inputStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                    return Response(status, conn.responseMessage ?: "", conn.headerFields.filterKeys { it != null }, body, conn.url.toString())
                } finally { conn.disconnect() }
            }
        })
        buildUi()
        refresh()
    }

    private fun dp(x: Int) = (x * resources.displayMetrics.density + .5f).toInt()
    private fun rounded(color: Int, radius: Int = 14) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun text(s: String, size: Float, color: Int = Color.WHITE) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); gravity = Gravity.CENTER_VERTICAL
    }
    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(40), dp(22), dp(40), dp(20))
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        heading = text("قنواتي  ●", 29f, accent).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL }
        top.addView(heading, LinearLayout.LayoutParams(0, dp(58), 1f))
        searchInput = EditText(this).apply {
            hint = "ابحث في جميع فيديوهات يوتيوب"
            setSingleLine(true)
            textSize = 17f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(164, 183, 189))
            setPadding(dp(14), 0, dp(14), 0)
            background = rounded(panel)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, _, _ -> search(searchInput.text.toString()); true }
        }
        top.addView(searchInput, LinearLayout.LayoutParams(0, dp(52), 1.3f).apply { marginEnd = dp(12) })
        val searchButton = Button(this).apply {
            text = "بحث"
            isAllCaps = false
            textSize = 15f
            setOnClickListener { search(searchInput.text.toString()) }
        }
        top.addView(searchButton, LinearLayout.LayoutParams(dp(95), dp(52)))
        val homeButton = Button(this).apply {
            text = "الرئيسية"
            isAllCaps = false
            textSize = 15f
            setOnClickListener { searchSerial++; searchMode = false; searchInput.setText(""); refresh() }
        }
        top.addView(homeButton, LinearLayout.LayoutParams(dp(120), dp(52)))
        val refresh = Button(this).apply {
            text = "↻ تحديث"
            textSize = 16f
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = rounded(panel)
            setOnClickListener { if (searchMode) search(searchInput.text.toString()) else refresh() }
        }
        top.addView(refresh, LinearLayout.LayoutParams(dp(135), dp(52)))
        root.addView(top)
        message = text("جاري تحميل قنواتك…", 16f, Color.rgb(169, 191, 198))
        root.addView(message, LinearLayout.LayoutParams(-1, dp(46)))
        grid = GridView(this).apply {
            numColumns = 4
            horizontalSpacing = dp(18)
            verticalSpacing = dp(20)
            clipToPadding = false
            setPadding(0, dp(12), 0, dp(12))
            isFocusable = true
        }
        adapter = object : BaseAdapter() {
            override fun getCount() = videos.size
            override fun getItem(position: Int) = videos[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val item = videos[position]
                val box = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    background = rounded(panel)
                    isFocusable = true
                    isClickable = true
                    setOnClickListener { play(item.id) }
                    setOnFocusChangeListener { _, focused ->
                        background = rounded(if (focused) Color.rgb(34, 83, 82) else panel)
                        elevation = if (focused) dp(12).toFloat() else 0f
                    }
                }
                val thumb = FrameLayout(this@MainActivity).apply { background = rounded(Color.rgb(16, 54, 61), 10) }
                val playMark = text("▶", 39f, accent).apply { gravity = Gravity.CENTER }
                thumb.addView(playMark, FrameLayout.LayoutParams(-1, -1))
                if (thumbnails) {
                    val img = ImageView(this@MainActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                    thumb.addView(img, 0, FrameLayout.LayoutParams(-1, -1))
                    io.execute {
                        try {
                            val bitmap = BitmapFactory.decodeStream(URL("https://i.ytimg.com/vi/${item.id}/hqdefault.jpg").openStream())
                            runOnUiThread { if (!isFinishing) img.setImageBitmap(bitmap) }
                        } catch (_: Exception) {}
                    }
                }
                box.addView(thumb, LinearLayout.LayoutParams(-1, dp(126)))
                box.addView(text(item.title, 16f).apply { maxLines = 2; setPadding(0, dp(8), 0, 0) }, LinearLayout.LayoutParams(-1, dp(57)))
                box.addView(text(item.channel, 13f, accent).apply { maxLines = 1 }, LinearLayout.LayoutParams(-1, dp(24)))
                return box
            }
        }
        grid.adapter = adapter
        root.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        homeScreen = root
        setContentView(root)
    }

    private fun search(queryRaw: String) {
        val query = queryRaw.trim()
        if (query.isEmpty()) {
            message.text = "اكتب عبارة للبحث"
            return
        }
        searchMode = true
        val serial = ++searchSerial
        message.text = "جاري البحث في يوتيوب عن: $query"
        videos.clear()
        adapter.notifyDataSetChanged()
        io.execute {
            try {
                val service = NewPipe.getService(0)
                val handler = service.searchQHFactory.fromQuery(query, emptyList<String>(), "")
                val results = SearchInfo.getInfo(service, handler)
                val matches = results.relatedItems.filterIsInstance<StreamInfoItem>().mapNotNull { item ->
                    val id = Regex("(?:v=|youtu\\.be/|shorts/|live/)([A-Za-z0-9_-]{11})")
                        .find(item.url)?.groupValues?.get(1)
                    if (id != null) Video(id, item.name, item.uploaderName ?: "يوتيوب", "") else null
                }
                runOnUiThread {
                    if (isFinishing || serial != searchSerial || !searchMode) return@runOnUiThread
                    videos.clear()
                    videos.addAll(matches.distinctBy { it.id }.take(80))
                    adapter.notifyDataSetChanged()
                    heading.text = "نتائج البحث"
                    message.text = if (videos.isEmpty()) "لم يتم العثور على فيديوهات" else "${videos.size} نتيجة بحث من يوتيوب • المقترحات في الرئيسية من قنواتك فقط"
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!isFinishing && serial == searchSerial) message.text = "تعذر البحث في يوتيوب: " + (e.message ?: "خطأ غير معروف")
                }
            }
        }
    }

    private fun fetch(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 9000; conn.readTimeout = 9000
        conn.setRequestProperty("User-Agent", "CuratedTV/1.0")
        conn.setRequestProperty("Cache-Control", "no-cache")
        try {
            if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally { conn.disconnect() }
    }
    private fun refresh() {
        searchMode = false
        val serial = ++searchSerial
        message.text = "جاري تحديث القنوات…"
        io.execute {
            val prefs = getSharedPreferences("offline", MODE_PRIVATE)
            var config: JSONObject? = null
            var offline = false
            try {
                val body = fetch(BuildConfig.CONFIG_URL + "?t=" + (System.currentTimeMillis() / 60000))
                config = JSONObject(body)
                prefs.edit().putString("channels", body).apply()
            } catch (_: Exception) {
                offline = true
                prefs.getString("channels", null)?.let { try { config = JSONObject(it) } catch (_: Exception) {} }
            }
            val data = config
            if (data == null) {
                runOnUiThread { message.text = "تعذّر جلب ملف القنوات. تحقق من اتصال الإنترنت." }
                return@execute
            }
            val newVideos = mutableListOf<Video>()
            val channels = data.optJSONArray("channels")
            thumbnails = true
            val title = data.optString("title", "قنواتي")
            if (channels != null) {
                for (n in 0 until channels.length()) {
                    val ch = channels.optJSONObject(n) ?: continue
                    val id = ch.optString("channelId")
                    if (!Regex("^UC[A-Za-z0-9_-]{22}$").matches(id)) continue
                    val name = ch.optString("name", "قناة")
                    try {
                        newVideos.addAll(parseFeed(fetch("https://www.youtube.com/feeds/videos.xml?channel_id=" + id), name))
                    } catch (_: Exception) {}
                }
            }
            newVideos.sortByDescending { it.published }
            runOnUiThread {
                if (isFinishing || searchMode || serial != searchSerial) return@runOnUiThread
                heading.text = "$title  ●"
                videos.clear(); videos.addAll(newVideos.distinctBy { it.id }.take(120))
                adapter.notifyDataSetChanged()
                message.text = when {
                    channels == null || channels.length() == 0 -> "لا توجد قنوات بعد. أضفها في ملف channels.json على GitHub."
                    videos.isEmpty() -> "لم تظهر فيديوهات؛ تحقق من معرفات القنوات أو اتصال يوتيوب."
                    else -> "${videos.size} فيديو من قنواتك المحددة فقط" + if (offline) " • ملف القنوات المحفوظ" else ""
                }
            }
        }
    }
    private fun parseFeed(xml: String, channel: String): List<Video> {
        val result = mutableListOf<Video>()
        val parser = Xml.newPullParser()
        parser.setInput(xml.reader())
        var inside = false
        var title = ""; var id = ""; var date = ""
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "entry" -> { inside = true; title = ""; id = ""; date = "" }
                    "title" -> if (inside) title = parser.nextText()
                    "videoId" -> if (inside) id = parser.nextText()
                    "published" -> if (inside) date = parser.nextText()
                }
            } else if (parser.eventType == XmlPullParser.END_TAG && parser.name == "entry") {
                if (Regex("^[A-Za-z0-9_-]{11}$").matches(id)) result.add(Video(id, title, channel, date))
                inside = false
            }
            parser.next()
        }
        return result
    }
    private fun play(id: String) {
        message.text = "جاري تجهيز تشغيل الفيديو داخل التطبيق…"
        io.execute {
            try {
                val info = StreamInfo.getInfo("https://www.youtube.com/watch?v=$id")
                val selected = info.videoStreams
                    .filter { it.isUrl && !it.isVideoOnly }
                    .maxByOrNull { it.height }
                val mediaUrl = selected?.content ?: info.hlsUrl?.takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException("لم يتوفر رابط فيديو متوافق")
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    val container = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
                    val view = PlayerView(this).apply {
                        useController = true
                        setShowNextButton(false)
                        setShowPreviousButton(false)
                    }
                    container.addView(view, FrameLayout.LayoutParams(-1,-1))
                    player = ExoPlayer.Builder(this).build().also {
                        view.player = it
                        it.setMediaItem(MediaItem.fromUri(mediaUrl))
                        it.prepare()
                        it.playWhenReady = true
                    }
                    playerScreen = container
                    setContentView(container)
                    view.requestFocus()
                }
            } catch (ex: Exception) {
                runOnUiThread {
                    message.text = "تعذر تشغيل الفيديو"
                    AlertDialog.Builder(this).setTitle("تعذر تشغيل الفيديو")
                        .setMessage("لم يتمكن المشغل المدمج من الحصول على هذا الفيديو. قد يكون بسبب قيود يوتيوب أو تغييرات في الخدمة.\n\n" + (ex.message ?: "خطأ غير معروف"))
                        .setPositiveButton("حسنًا", null).show()
                }
            }
        }
    }
    override fun onBackPressed() {
        if (playerScreen != null) {
            player?.release()
            player = null
            playerScreen = null
            homeScreen?.let { setContentView(it) }
        } else super.onBackPressed()
    }
        override fun onDestroy() { player?.release(); io.shutdownNow(); super.onDestroy() }
}
