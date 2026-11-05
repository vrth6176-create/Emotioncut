package com.emotioncut

import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.graphics.*
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.view.View
import android.view.WindowManager
import java.util.concurrent.ConcurrentHashMap
import android.view.ViewGroup
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.*
import androidx.media3.effect.*
import androidx.media3.transformer.*
import com.google.common.collect.ImmutableList
import java.io.File

class MainActivity : ComponentActivity() {
    class Seg(val emo: String, val uri: Uri, var start: Double = 0.0, var end: Double = 0.0, var movie: Movie? = null)

    lateinit var info: TextView
    lateinit var status: TextView
    lateinit var seqIn: EditText
    var vo: Uri? = null; var br: Uri? = null; var voMs = 0L; var brMs = 0L
    var segs = listOf<Seg>()
    var curSeg: Seg? = null; var lastKey = -1L; var lastBmp: Bitmap? = null; var fails = 0
    var gifSec = 3; var pos = 2; var sizePct = 60; var calls = 0
    val presets = listOf(Triple("Top Center", .5f, .12f), Triple("Upper Third", .5f, .25f), Triple("Center Focus", .5f, .5f),
        Triple("Lower Third", .5f, .72f), Triple("Bottom Center", .5f, .88f), Triple("Left Focus", .25f, .5f), Triple("Right Focus", .75f, .5f))
    val bags = HashMap<String, ArrayDeque<Uri>>()
    val prefs by lazy { getSharedPreferences("folders", 0) }

    val pickVo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) { vo = u; voMs = dur(u); refresh() } }
    val pickBr = registerForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) { br = u; brMs = dur(u); refresh() } }
    val pickDir = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u ->
        if (u != null) {
            contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val et = EditText(this); et.setText(DocumentFile.fromTreeUri(this, u)?.name ?: "")
            AlertDialog.Builder(this).setTitle("Emotion name").setView(et)
                .setPositiveButton("Save") { _, _ -> prefs.edit().putString(et.text.toString().trim(), u.toString()).apply(); scanAll() }.show()
        }
    }

    val pickParent = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u ->
        if (u != null) {
            contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val ed = prefs.edit(); var n = 0
            DocumentFile.fromTreeUri(this, u)?.listFiles()?.filter { it.isDirectory }?.forEach { ed.putString(it.name, u.toString() + "||" + it.name); n++ }
            ed.apply(); status.text = "Added $n emotion folders"; scanAll()
        }
    }

    fun dur(u: Uri): Long { val r = MediaMetadataRetriever(); r.setDataSource(this, u); return r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() }
    val cache = ConcurrentHashMap<String, List<Uri>>()
    fun folderOf(key: String): DocumentFile? {
        val p = (prefs.getString(key, "") ?: "").split("||")
        val root = DocumentFile.fromTreeUri(this, Uri.parse(p[0])) ?: return null
        return if (p.size > 1) root.listFiles().firstOrNull { it.name == p[1] } else root
    }
    fun scanOne(k: String): List<Uri> = try {
        folderOf(k)?.listFiles()?.filter { it.name?.endsWith(".gif", true) == true }?.map { it.uri } ?: emptyList()
    } catch (e: Exception) { emptyList() }
    fun gifs(key: String): List<Uri> = cache.getOrPut(key) { scanOne(key) }
    fun scanAll() {
        cache.clear(); refresh()
        Thread { prefs.all.keys.toList().forEach { k -> cache[k] = scanOne(k) }; runOnUiThread { refresh() } }.start()
    }

    fun refresh() {
        val f = prefs.all.keys.joinToString("\n") { "• $it: ${cache[it]?.size?.toString() ?: "scanning"} GIFs" }
        info.text = "Voiceover: ${vo?.lastPathSegment ?: "-"} (${voMs / 1000}s)\nB-roll: ${br?.lastPathSegment ?: "-"} (${brMs / 1000}s)\n\nEmotion folders:\n$f\n\nSequence: " +
            segs.take(30).joinToString { it.emo } + (if (segs.size > 30) " … (${segs.size} total)" else "")
    }

    fun btn(t: String, l: LinearLayout, f: () -> Unit) { val b = Button(this); b.text = t; b.setOnClickListener { f() }; l.addView(b) }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL; col.setPadding(32, 64, 32, 32)
        val sv = ScrollView(this); sv.setBackgroundColor(Color.parseColor("#141418")); sv.addView(col); setContentView(sv)
        info = TextView(this); info.setTextColor(Color.WHITE); status = TextView(this); status.setTextColor(Color.parseColor("#a78bfa"))
        seqIn = EditText(this); seqIn.hint = "sad:2, happy:4, shocked  (:seconds is optional)"; seqIn.setTextColor(Color.WHITE); seqIn.setHintTextColor(Color.GRAY)
        val t = TextView(this); t.text = "EmotionCut Studio"; t.textSize = 24f; t.setTextColor(Color.WHITE); col.addView(t)
        btn("1. Select voiceover", col) { pickVo.launch(arrayOf("audio/*")) }
        btn("2. Select B-roll video", col) { pickBr.launch(arrayOf("video/*")) }
        btn("3. + Add Emotion Folder", col) { pickDir.launch(null) }
        btn("3b. + Add ALL emotion folders (pick the parent folder)", col) { pickParent.launch(null) }
        btn("Remove all folders", col) { prefs.edit().clear().apply(); scanAll() }
        col.addView(seqIn)
        val lbl = TextView(this); lbl.setTextColor(Color.WHITE); lbl.text = "GIF position:"; col.addView(lbl)
        val sp = Spinner(this); sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, presets.map { it.first }); sp.setSelection(2)
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, i: Int, id: Long) { pos = i }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        col.addView(sp)
        val sl = TextView(this); sl.setTextColor(Color.WHITE); sl.text = "GIF size: 60%"; col.addView(sl)
        val sb = SeekBar(this); sb.max = 90; sb.progress = 50
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(b: SeekBar?, p: Int, u: Boolean) { sizePct = p + 10; sl.text = "GIF size: $sizePct%" }
            override fun onStartTrackingTouch(b: SeekBar?) {}
            override fun onStopTrackingTouch(b: SeekBar?) {}
        })
        col.addView(sb)
        val dl = TextView(this); dl.setTextColor(Color.WHITE); dl.text = "Seconds per GIF: 3 (0 = split equally, no loop)"; col.addView(dl)
        val db = SeekBar(this); db.max = 10; db.progress = 3
        db.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(b: SeekBar?, p: Int, u: Boolean) { gifSec = p; dl.text = if (p == 0) "Seconds per GIF: 0 (split equally, no loop)" else "Seconds per GIF: $p (sequence loops until audio ends)" }
            override fun onStartTrackingTouch(b: SeekBar?) {}
            override fun onStopTrackingTouch(b: SeekBar?) {}
        })
        col.addView(db)
        btn("4. Generate sequence", col) { generate() }
        btn("5. Export MP4", col) { export() }
        col.addView(status); col.addView(info); scanAll()
    }

    fun generate() {
        if (voMs == 0L) { status.text = "Select a voiceover first"; return }
        val raw = seqIn.text.split(",", "\n").map { it.trim() }.filter { it.isNotEmpty() }
        if (raw.isEmpty()) { status.text = "Type your emotions first"; return }
        val items = raw.map { r ->
            val n = r.substringBefore(":").trim()
            val key = prefs.all.keys.firstOrNull { it.equals(n, true) } ?: run { status.text = "No folder registered for \"$n\""; return }
            key to r.substringAfter(":", "").trim().toDoubleOrNull()
        }
        val d = voMs / 1000.0
        val equal = gifSec == 0 && items.all { it.second == null }
        val out = ArrayList<Seg>(); var t = 0.0; var i = 0
        while (t < d - 0.001 && out.size < 3000) {
            val (key, own) = items[i % items.size]
            val bag = bags.getOrPut(key) { ArrayDeque() }
            if (bag.isEmpty()) bag.addAll(gifs(key).shuffled())
            if (bag.isEmpty()) { status.text = "Folder \"$key\" has no GIFs / needs re-adding"; return }
            val len = if (equal) d / items.size else own ?: (if (gifSec > 0) gifSec.toDouble() else 2.0)
            val sg = Seg(key, bag.removeFirst()); sg.start = t; sg.end = minOf(t + len, d)
            out.add(sg); t += len; i++
            if (equal && i >= items.size) break
        }
        segs = out; status.text = "Generated ${out.size} segments for ${d.toInt()}s"; refresh()
    }

    fun export() {
        val v = vo; val b = br
        if (v == null || b == null || segs.isEmpty() || brMs <= 0) { status.text = "Need voiceover, B-roll and a generated sequence"; return }
        calls = 0; fails = 0; curSeg = null; lastBmp = null
        val W = 540; val H = 960
        val blank = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val ring = Array(3) { Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888) }; var ri = 0
        fun mk(off: Double) = object : BitmapOverlay() {
            override fun getBitmap(presentationTimeUs: Long): Bitmap {
                calls++
                val t = presentationTimeUs / 1e6 + off
                val sg = segs.firstOrNull { t >= it.start && t < it.end } ?: return blank
                if (sg !== curSeg) {
                    curSeg?.movie = null; curSeg = sg; lastBmp = null
                    try { val bytes = contentResolver.openInputStream(sg.uri)!!.use { it.readBytes() }; sg.movie = Movie.decodeByteArray(bytes, 0, bytes.size) } catch (e: Throwable) { fails++ }
                }
                val m = sg.movie ?: return blank
                if (m.width() == 0 || m.height() == 0) return blank
                val d = m.duration().coerceAtLeast(1)
                val mt = (((t - sg.start) * 1000).toLong() % d)
                val key = mt / 50
                if (lastBmp != null && lastKey == key) return lastBmp!!
                m.setTime(mt.toInt())
                ri = (ri + 1) % 3; val bmp = ring[ri]; bmp.eraseColor(Color.TRANSPARENT)
                val c = Canvas(bmp)
                val w = W * sizePct / 100f; val sc = w / m.width(); val h = m.height() * sc
                val pr = presets[pos]
                val x = (W * pr.second - w / 2).coerceIn(0f, maxOf(0f, W - w)); val y = (H * pr.third - h / 2).coerceIn(0f, maxOf(0f, H - h))
                c.translate(x, y); c.scale(sc, sc)
                m.draw(c, 0f, 0f)
                lastBmp = bmp; lastKey = key
                return bmp
            }
        }
        val items = ArrayList<EditedMediaItem>(); var acc = 0L
        while (acc < voMs) {
            val take = minOf(brMs, voMs - acc)
            val mi = MediaItem.Builder().setUri(b).setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setEndPositionMs(take).build()).build()
            val fx = Effects(listOf<androidx.media3.common.audio.AudioProcessor>(), listOf<Effect>(
                Presentation.createForWidthAndHeight(720, 1280, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP),
                OverlayEffect(ImmutableList.of<TextureOverlay>(mk(acc / 1000.0)))))
            items.add(EditedMediaItem.Builder(mi).setRemoveAudio(true).setEffects(fx).build()); acc += take
        }
        val audio = EditedMediaItem.Builder(MediaItem.fromUri(v)).setRemoveVideo(true).build()
        val comp = Composition.Builder(listOf(EditedMediaItemSequence(items), EditedMediaItemSequence(listOf(audio)))).build()
        val outFile = File(cacheDir, "out.mp4"); outFile.delete()
        val tr = Transformer.Builder(this).setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, r: ExportResult) {
                    val cv = ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, "emotioncut_${System.currentTimeMillis()}.mp4")
                        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                        put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/EmotionCut")
                    }
                    val u = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv)!!
                    contentResolver.openOutputStream(u)!!.use { o -> outFile.inputStream().use { it.copyTo(o) } }
                    outFile.delete(); segs.forEach { it.movie = null }
                    status.text = "Done! Saved in Movies/EmotionCut"
                }
                override fun onError(composition: Composition, r: ExportResult, e: ExportException) {
                    outFile.delete(); status.text = "Export failed: ${e.message}"
                }
            }).build()
        tr.start(comp, outFile.absolutePath)
        val h = Handler(Looper.getMainLooper()); val ph = ProgressHolder()
        h.post(object : Runnable { override fun run() {
            if (tr.getProgress(ph) != Transformer.PROGRESS_STATE_NOT_STARTED && status.text.startsWith("Export").not() && !status.text.startsWith("Done")) { }
            if (!status.text.startsWith("Done") && !status.text.startsWith("Export failed")) {
                status.text = "Exporting… ${ph.progress}% (overlay calls: $calls, GIF load fails: $fails)"; h.postDelayed(this, 500)
            }
        } })
    }
}
