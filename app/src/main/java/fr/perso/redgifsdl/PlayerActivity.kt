package fr.perso.redgifsdl

import android.app.Activity
import android.content.Context
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.ImageView
import android.widget.MediaController
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import java.io.File
import kotlin.math.abs

/**
 * Lecteur plein écran d'un dossier de la bibliothèque.
 * Lecture en boucle ; glisser à gauche/droite (ou ⏮ ⏭) pour changer de vidéo.
 */
class PlayerActivity : Activity() {

    private lateinit var videoView: VideoView
    private lateinit var imageView: ImageView
    private lateinit var controller: MediaController
    private lateinit var gestures: GestureDetector
    private var files: List<File> = emptyList()
    private var index = 0
    private var resumePosition = 0
    private lateinit var topBar: View
    private lateinit var fileName: TextView
    private lateinit var volumeLabel: TextView
    private var player: MediaPlayer? = null
    /** Volume de lecture propre à l'appli, 0..100 (%), réglé par pas de 1 avec les boutons de volume. */
    private var volume = 100
    private val hideVolume = Runnable { volumeLabel.visibility = View.GONE }
    private val hideBar = Runnable { topBar.visibility = View.GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyPrivacy()
        setContentView(R.layout.activity_player)
        hideSystemBars()

        videoView = findViewById(R.id.video)
        imageView = findViewById(R.id.image)
        topBar = findViewById(R.id.topBar)
        fileName = findViewById(R.id.fileName)
        volumeLabel = findViewById(R.id.volumeLabel)
        volume = getSharedPreferences("app", MODE_PRIVATE).getInt(PREF_VOLUME, 100).coerceIn(0, 100)
        volumeControlStream = AudioManager.STREAM_MUSIC
        findViewById<View>(R.id.deleteButton).setOnClickListener {
            current()?.let { GalleryExport.confirmDelete(this, it) { removeCurrent() } }
        }
        findViewById<View>(R.id.censorButton).setOnClickListener {
            current()?.let { GalleryExport.confirmCensor(this, it) }
        }
        findViewById<View>(R.id.moveButton).setOnClickListener {
            current()?.let { GalleryExport.confirmMove(this, it) { removeCurrent() } }
        }
        val site = Site.fromLabel(intent.getStringExtra(LibraryActivity.EXTRA_SITE)) ?: Site.REDGIFS
        val folder = intent.getStringExtra(LibraryActivity.EXTRA_FOLDER) ?: ""
        files = Library.media(Library.dir(this, site, folder))
        if (files.isEmpty()) {
            finish()
            return
        }
        index = (savedInstanceState?.getInt(STATE_INDEX) ?: intent.getIntExtra(EXTRA_INDEX, 0))
            .coerceIn(0, files.size - 1)

        controller = VolumeAwareController(this) { handleVolumeKey(it) }
        controller.setAnchorView(findViewById(R.id.root))
        controller.setPrevNextListeners({ show(index + 1) }, { show(index - 1) })
        videoView.setMediaController(controller)
        videoView.setOnPreparedListener { mp ->
            player = mp
            applyVolume()
            mp.isLooping = true
            if (resumePosition > 0) {
                videoView.seekTo(resumePosition)
                resumePosition = 0
            }
            videoView.start()
        }
        videoView.setOnErrorListener { _, _, _ ->
            Toast.makeText(this, "Lecture impossible : ${files[index].name}", Toast.LENGTH_SHORT).show()
            true
        }

        gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            // false : on ne consomme pas le toucher (les boutons et les contrôles de lecture le reçoivent aussi)
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                showBar()
                return false
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                val dx = e2.x - (e1?.x ?: e2.x)
                if (abs(dx) > 150 && abs(vx) > abs(vy)) {
                    show(if (dx < 0) index + 1 else index - 1)
                    return true
                }
                return false
            }
        })

        show(index)
    }

    // ------------------------------------------------------------------ volume par pas de 1 %

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        handleVolumeKey(event) || super.dispatchKeyEvent(event)

    /** Boutons de volume : ±1 % sur le volume de lecture (maintenir appuyé = défilement continu). */
    private fun handleVolumeKey(event: KeyEvent): Boolean {
        val delta = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> 1
            KeyEvent.KEYCODE_VOLUME_DOWN -> -1
            else -> return false
        }
        if (event.action != KeyEvent.ACTION_DOWN) return true // relâchement : rien, mais consommé
        if (delta > 0 && volume >= 100) {
            // Déjà à 100 % : on monte d'un cran le volume du téléphone
            getSystemService(AudioManager::class.java)
                ?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            showVolume(" · téléphone +1")
            return true
        }
        volume = (volume + delta).coerceIn(0, 100)
        getSharedPreferences("app", MODE_PRIVATE).edit().putInt(PREF_VOLUME, volume).apply()
        applyVolume()
        showVolume("")
        return true
    }

    /**
     * Courbe adaptée à l'oreille (puissance 2) : chaque pour cent s'entend à peu près pareil,
     * à bas comme à haut volume. 100 % = volume du téléphone tel quel.
     */
    private fun applyVolume() {
        val gain = (volume / 100f).let { it * it }
        runCatching { player?.setVolume(gain, gain) }
    }

    private fun showVolume(extra: String) {
        volumeLabel.text = (if (volume == 0) "🔇 " else "🔊 ") + "$volume %$extra"
        volumeLabel.visibility = View.VISIBLE
        volumeLabel.removeCallbacks(hideVolume)
        volumeLabel.postDelayed(hideVolume, 1200)
    }

    /** Les contrôles de lecture ont leur propre fenêtre : ils transmettent les boutons de volume au lecteur. */
    private class VolumeAwareController(context: Context, val onVolumeKey: (KeyEvent) -> Boolean) : MediaController(context) {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean = onVolumeKey(event) || super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (::gestures.isInitialized && gestures.onTouchEvent(ev)) return true
        return super.dispatchTouchEvent(ev)
    }

    private fun show(i: Int) {
        if (files.isEmpty()) return
        index = ((i % files.size) + files.size) % files.size
        val file = files[index]
        player = null // l'ancien lecteur est libéré par VideoView ; le nouveau arrive dans onPrepared
        fileName.text = "${index + 1}/${files.size} · ${file.name}"
        showBar()
        if (Library.isImage(file)) {
            videoView.stopPlayback()
            videoView.visibility = View.GONE
            imageView.visibility = View.VISIBLE
            imageView.setImageBitmap(decodeForScreen(file))
        } else {
            imageView.setImageDrawable(null)
            imageView.visibility = View.GONE
            videoView.visibility = View.VISIBLE
            videoView.setVideoPath(file.path)
        }
    }

    private fun current(): File? = files.getOrNull(index)

    /** Le fichier affiché a été supprimé ou déplacé : on passe au suivant (ou on quitte si c'était le dernier). */
    private fun removeCurrent() {
        val gone = current() ?: return
        files = files.filter { it != gone }
        if (files.isEmpty()) {
            finish()
        } else {
            show(index.coerceAtMost(files.size - 1))
        }
    }

    private fun showBar() {
        topBar.visibility = View.VISIBLE
        topBar.removeCallbacks(hideBar)
        topBar.postDelayed(hideBar, 3500)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        GalleryExport.onPermissionResult(this, requestCode, grantResults)
    }

    private fun decodeForScreen(file: File) = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val max = maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= max) sample *= 2
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    override fun onPause() {
        super.onPause()
        if (videoView.visibility == View.VISIBLE) {
            resumePosition = videoView.currentPosition
            videoView.pause()
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_INDEX, index)
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Build.VERSION.SDK_INT < 35) {
                @Suppress("DEPRECATION")
                window.setDecorFitsSystemWindows(false) // bord à bord déjà imposé à partir d'Android 15
            }
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        }
    }

    companion object {
        const val EXTRA_INDEX = "index"
        private const val PREF_VOLUME = "player_volume"
        private const val STATE_INDEX = "index"
    }
}
