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
        col.addView(status); col.addView(info); refresh()
    }

    fun generate() {
        if (voMs == 0L) { status.text = "Select a voiceover first"; return }
        val names = seqIn.text.split(",", "\n").map { it.trim() }.filter { it.isNotEmpty() }
        if (names.isEmpty()) { status.text = "Type your emotions first"; return }
        val keys = names.map { n -> prefs.all.keys.firstOrNull { it.equals(n, true) } ?: run { status.text = "No folder registered for \"$n\""; return } }
        val d = voMs / 1000.0
        val count = if (gifSec == 0) keys.size else Math.ceil(d / gifSec).toInt()
        val out = ArrayList<Seg>()
        for (i in 0 until count) {
            val key = keys[i % keys.size]
            val bag = bags.getOrPut(key) { ArrayDeque() }
            if (bag.isEmpty()) bag.addAll(gifs(key).shuffled())
            if (bag.isEmpty()) { status.text = "Folder \"$key\" has no GIFs / needs re-adding"; return }
            val sg = Seg(key, bag.removeFirst())
            if (gifSec == 0) { sg.start = d * i / count; sg.end = d * (i + 1) / count }
            else { sg.start = i * gifSec.toDouble(); sg.end = minOf((i + 1) * gifSec.toDouble(), d) }
            out.add(sg)
        }
        segs = out; status.text = "Generated ${out.size} segments for ${d.toInt()}s"; refresh()
    }

    fun export() {
        val v = vo; val b = br
        if (v == null || b == null || segs.isEmpty() || brMs <= 0) { status.text = "Need voiceover, B-roll and a generated sequence"; return }
        try { segs.forEach { sg -> val bytes = contentResolver.openInputStream(sg.uri)!!.use { it.readBytes() }; sg.movie = Movie.decodeByteArray(bytes, 0, bytes.size) }
            val bad = segs.firstOrNull { it.movie == null }; if (bad != null) { status.text = "Could not decode GIF: ${DocumentFile.fromSingleUri(this, bad.uri)?.name}"; return } }
        catch (e: Exception) { status.text = "GIF load error: ${e.message}"; return }
        val W = 540; val H = 960
        val blank = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        fun mk(off: Double) = object : BitmapOverlay() {
            override fun getBitmap(presentationTimeUs: Long): Bitmap {
                calls++
                val t = presentationTimeUs / 1e6 + off
                val sg = segs.firstOrNull { t >= it.start && t < it.end } ?: return blank
                val m = sg.movie ?: return blank
                if (m.width() == 0 || m.height() == 0) return blank
                val d = m.duration().coerceAtLeast(1)
                m.setTime((((t - sg.start) * 1000).toLong() % d).toInt())
                val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
                val c = Canvas(bmp)
                val w = W * sizePct / 100f; val sc = w / m.width(); val h = m.height() * sc
                val pr = presets[pos]
                val x = (W * pr.second - w / 2).coerceIn(0f, maxOf(0f, W - w)); val y = (H * pr.third - h / 2).coerceIn(0f, maxOf(0f, H - h))
                c.translate(x, y); c.scale(sc, sc)
                m.draw(c, 0f, 0f)
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
                status.text = "Exporting… ${ph.progress}% (overlay calls: $calls)"; h.postDelayed(this, 500)
            }
        } })
    }
}
