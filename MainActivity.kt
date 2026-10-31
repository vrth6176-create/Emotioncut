package com.emotioncut

import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.graphics.*
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.*
import android.provider.MediaStore
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
    val bags = HashMap<String, ArrayDeque<Uri>>()
    val prefs by lazy { getSharedPreferences("folders", 0) }

    val pickVo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) { vo = u; voMs = dur(u); refresh() } }
    val pickBr = registerForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) { br = u; brMs = dur(u); refresh() } }
    val pickDir = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u ->
        if (u != null) {
            contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val et = EditText(this); et.setText(DocumentFile.fromTreeUri(this, u)?.name ?: "")
            AlertDialog.Builder(this).setTitle("Emotion name").setView(et)
                .setPositiveButton("Save") { _, _ -> prefs.edit().putString(et.text.toString().trim(), u.toString()).apply(); refresh() }.show()
        }
    }

    fun dur(u: Uri): Long { val r = MediaMetadataRetriever(); r.setDataSource(this, u); return r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() }
    fun gifs(key: String): List<Uri> = try {
        DocumentFile.fromTreeUri(this, Uri.parse(prefs.getString(key, "")))!!.listFiles().filter { it.name?.endsWith(".gif", true) == true }.map { it.uri }
    } catch (e: Exception) { emptyList() }

    fun refresh() {
        val f = prefs.all.keys.joinToString("\n") { "• $it: ${gifs(it).size} GIFs" }
        info.text = "Voiceover: ${vo?.lastPathSegment ?: "-"} (${voMs / 1000}s)\nB-roll: ${br?.lastPathSegment ?: "-"} (${brMs / 1000}s)\n\nEmotion folders:\n$f\n\nSequence: " +
            segs.joinToString { it.emo }
    }

    fun btn(t: String, l: LinearLayout, f: () -> Unit) { val b = Button(this); b.text = t; b.setOnClickListener { f() }; l.addView(b) }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL; col.setPadding(32, 64, 32, 32)
        val sv = ScrollView(this); sv.setBackgroundColor(Color.parseColor("#141418")); sv.addView(col); setContentView(sv)
        info = TextView(this); info.setTextColor(Color.WHITE); status = TextView(this); status.setTextColor(Color.parseColor("#a78bfa"))
        seqIn = EditText(this); seqIn.hint = "sad, happy, curious, shocked"; seqIn.setTextColor(Color.WHITE); seqIn.setHintTextColor(Color.GRAY)
        val t = TextView(this); t.text = "EmotionCut Studio"; t.textSize = 24f; t.setTextColor(Color.WHITE); col.addView(t)
        btn("1. Select voiceover", col) { pickVo.launch(arrayOf("audio/*")) }
        btn("2. Select B-roll video", col) { pickBr.launch(arrayOf("video/*")) }
        btn("3. + Add Emotion Folder", col) { pickDir.launch(null) }
        btn("Remove all folders", col) { prefs.edit().clear().apply(); refresh() }
        col.addView(seqIn)
        btn("4. Generate sequence", col) { generate() }
        btn("5. Export MP4", col) { export() }
        col.addView(status); col.addView(info); refresh()
    }

    fun generate() {
        if (voMs == 0L) { status.text = "Select a voiceover first"; return }
        val names = seqIn.text.split(",", "\n").map { it.trim() }.filter { it.isNotEmpty() }
        val out = ArrayList<Seg>()
        for (n in names) {
            val key = prefs.all.keys.firstOrNull { it.equals(n, true) }
            if (key == null) { status.text = "No folder registered for \"$n\""; return }
            val bag = bags.getOrPut(key) { ArrayDeque() }
            if (bag.isEmpty()) bag.addAll(gifs(key).shuffled())
            if (bag.isEmpty()) { status.text = "Folder \"$key\" has no GIFs / needs re-adding"; return }
            out.add(Seg(key, bag.removeFirst()))
        }
        val d = voMs / 1000.0
        out.forEachIndexed { i, s -> s.start = d * i / out.size; s.end = d * (i + 1) / out.size }
        segs = out; status.text = "Generated ${out.size} segments"; refresh()
    }

    fun export() {
        val v = vo; val b = br
        if (v == null || b == null || segs.isEmpty()) { status.text = "Need voiceover, B-roll and a generated sequence"; return }
        try { segs.forEach { sg -> contentResolver.openInputStream(sg.uri)!!.use { sg.movie = Movie.decodeStream(it) } } }
        catch (e: Exception) { status.text = "GIF load error: ${e.message}"; return }
        val W = 540; val H = 960
        val blank = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val overlay = object : BitmapOverlay() {
            override fun getBitmap(presentationTimeUs: Long): Bitmap {
                val t = presentationTimeUs / 1e6
                val sg = segs.firstOrNull { t >= it.start && t < it.end } ?: return blank
                val m = sg.movie ?: return blank
                val d = m.duration().coerceAtLeast(1)
                m.setTime((((t - sg.start) * 1000).toLong() % d).toInt())
                val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
                val c = Canvas(bmp)
                val w = W * 0.6f; val sc = w / m.width(); val h = m.height() * sc
                c.translate((W - w) / 2, (H - h) / 2); c.scale(sc, sc)
                m.draw(c, 0f, 0f)
                return bmp
            }
        }
        val items = ArrayList<EditedMediaItem>(); var acc = 0L
        while (acc < voMs) {
            val take = minOf(brMs, voMs - acc)
            val mi = MediaItem.Builder().setUri(b).setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setEndPositionMs(take).build()).build()
            items.add(EditedMediaItem.Builder(mi).setRemoveAudio(true).build()); acc += take
        }
        val audio = EditedMediaItem.Builder(MediaItem.fromUri(v)).setRemoveVideo(true).build()
        val fx = Effects(listOf<androidx.media3.common.audio.AudioProcessor>(), listOf<Effect>(
            Presentation.createForWidthAndHeight(720, 1280, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP),
            OverlayEffect(ImmutableList.of<TextureOverlay>(overlay))))
        val comp = Composition.Builder(listOf(EditedMediaItemSequence(items), EditedMediaItemSequence(listOf(audio)))).setEffects(fx).build()
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
                status.text = "Exporting… ${ph.progress}%"; h.postDelayed(this, 500)
            }
        } })
    }
}
