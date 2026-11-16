package fr.perso.redgifsdl

import android.app.Activity
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.ImageView
import android.widget.MediaController
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyPrivacy()
        setContentView(R.layout.activity_player)
        hideSystemBars()

        videoView = findViewById(R.id.video)
        imageView = findViewById(R.id.image)
        val folder = intent.getStringExtra(LibraryActivity.EXTRA_FOLDER) ?: ""
        files = Library.media(Library.dir(this, folder))
        if (files.isEmpty()) {
            finish()
            return
        }
        index = (savedInstanceState?.getInt(STATE_INDEX) ?: intent.getIntExtra(EXTRA_INDEX, 0))
            .coerceIn(0, files.size - 1)

        controller = MediaController(this)
        controller.setAnchorView(findViewById(R.id.root))
        controller.setPrevNextListeners({ show(index + 1) }, { show(index - 1) })
        videoView.setMediaController(controller)
        videoView.setOnPreparedListener { mp ->
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

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (::gestures.isInitialized && gestures.onTouchEvent(ev)) return true
        return super.dispatchTouchEvent(ev)
    }

    private fun show(i: Int) {
        if (files.isEmpty()) return
        index = ((i % files.size) + files.size) % files.size
        val file = files[index]
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
            window.setDecorFitsSystemWindows(false)
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
        private const val STATE_INDEX = "index"
    }
}
