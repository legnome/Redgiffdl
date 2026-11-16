package fr.perso.redgifsdl

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Équivalent Android de legnome detect + censor (mode "black"), avec les outils du système :
 * - décodage matériel (MediaCodec) vers une texture OpenGL ;
 * - détection : image réduite en 320x320 avec bandes noires par OpenGL (comme cv2 dans NudeNet),
 *   relue puis passée au modèle (1 image sur `step`) ;
 * - censure : image redessinée vers l'encodeur H.264 avec des carrés noirs (glScissor + glClear),
 *   son d'origine recopié sans réencodage. Remplace OpenCV + ffmpeg.
 */
class VideoCensor(private val detector: NudeDetector) {

    class VideoInfo(val width: Int, val height: Int, val fps: Double, val durationUs: Long, val rotation: Int, val bitrate: Int?)

    /** Étape 1 : zones détectées (format JSON de legnome). [onProgress] : 0..1 */
    fun detect(input: File, opts: Nudity.Options, onProgress: (Float) -> Unit): Nudity.Zones {
        val step = maxOf(1, opts.step)
        val frames = LinkedHashMap<Int, List<Nudity.Box>>()
        var info: VideoInfo? = null
        var count = 0
        Gl(null).use { gl ->
            val fbo = Fbo(Nudity.INPUT)
            val pixels = ByteBuffer.allocateDirect(Nudity.INPUT * Nudity.INPUT * 4).order(ByteOrder.nativeOrder())
            val bytes = ByteArray(Nudity.INPUT * Nudity.INPUT * 4)
            decode(input, gl, { info = it }) { index, ptsUs ->
                count = index + 1
                val vi = info!!
                if (index % step == 0) {
                    // Image réduite dans le coin haut-gauche du carré 320x320, reste noir (comme NudeNet)
                    val (sw, sh) = Nudity.letterboxSize(vi.width, vi.height)
                    fbo.bind()
                    GLES20.glViewport(0, 0, Nudity.INPUT, Nudity.INPUT)
                    GLES20.glClearColor(0f, 0f, 0f, 1f)
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                    GLES20.glViewport(0, Nudity.INPUT - sh, sw, sh)
                    gl.drawFrame()
                    pixels.clear()
                    GLES20.glReadPixels(0, 0, Nudity.INPUT, Nudity.INPUT, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
                    fbo.unbind()
                    pixels.get(bytes)
                    val tensor = Nudity.tensorFromRgba(bytes, bottomUp = true)
                    val boxes = Nudity.select(detector.detect(tensor, vi.width, vi.height), opts, vi.width, vi.height)
                    if (boxes.isNotEmpty()) frames[index] = boxes
                }
                if (vi.durationUs > 0) onProgress((ptsUs.toFloat() / vi.durationUs).coerceIn(0f, 1f))
            }
            fbo.release()
        }
        val vi = info ?: throw IOException("Aucune piste vidéo")
        return Nudity.Zones(
            input.name, vi.width, vi.height, vi.fps, count, step, opts.threshold, opts.padding, opts.square, opts.labels, frames,
        )
    }

    /** Étape 2 : copie H.264 avec carrés noirs + son d'origine. */
    /** @param before / [after] : nombre d'images avant / après chaque détection où la zone est appliquée. */
    fun censor(input: File, zones: Nudity.Zones, output: File, before: Int, after: Int, onProgress: (Float) -> Unit) {
        val vi = probe(input)
        if (vi.width != zones.width || vi.height != zones.height) {
            throw IOException("Les zones ne correspondent pas à cette vidéo (dimensions différentes)")
        }
        val encW = Nudity.even(vi.width)
        val encH = Nudity.even(vi.height)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, encW, encH).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            val auto = (encW.toLong() * encH * vi.fps * 0.15).toInt()
            setInteger(MediaFormat.KEY_BIT_RATE, (vi.bitrate ?: auto).coerceIn(1_000_000, 25_000_000))
            setInteger(MediaFormat.KEY_FRAME_RATE, Math.round(vi.fps).toInt().coerceIn(1, 120))
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = encoder.createInputSurface()
        encoder.start()

        val muxer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        if (vi.rotation != 0) muxer.setOrientationHint(vi.rotation)
        val audio = AudioCopier(input)
        val writer = EncoderDrain(encoder, muxer, audio)
        try {
            Gl(inputSurface).use { gl ->
                decode(input, gl, {}) { index, ptsUs ->
                    GLES20.glViewport(0, 0, encW, encH)
                    gl.drawFrame()
                    val boxes = Nudity.activeBoxes(index, zones.frames, zones.step, before, after)
                    if (boxes.isNotEmpty()) {
                        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
                        GLES20.glClearColor(0f, 0f, 0f, 1f)
                        for (b in boxes) {
                            // OpenGL : origine en bas à gauche
                            GLES20.glScissor(b.x, encH - (b.y + b.h), b.w, b.h)
                            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                        }
                        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
                    }
                    gl.present(ptsUs * 1000)
                    writer.drain(false)
                    if (vi.durationUs > 0) onProgress((ptsUs.toFloat() / vi.durationUs).coerceIn(0f, 1f))
                }
                encoder.signalEndOfInputStream()
                writer.drain(true)
            }
            audio.writeUpTo(muxer, Long.MAX_VALUE)
            if (!writer.started) throw IOException("L'encodeur n'a produit aucune image")
            muxer.stop()
        } finally {
            runCatching { encoder.stop() }
            encoder.release()
            inputSurface.release()
            audio.release()
            runCatching { muxer.release() }
        }
    }

    // ------------------------------------------------------------------ décodage

    private fun videoTrack(ex: MediaExtractor): Int =
        (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            ?: throw IOException("Aucune piste vidéo")

    fun probe(input: File): VideoInfo {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(input.path)
            return infoOf(ex.getTrackFormat(videoTrack(ex)))
        } finally {
            ex.release()
        }
    }

    private fun infoOf(f: MediaFormat): VideoInfo {
        fun int(key: String) = if (f.containsKey(key)) runCatching { f.getInteger(key) }.getOrNull() else null
        val fps = (if (f.containsKey(MediaFormat.KEY_FRAME_RATE)) {
            runCatching { f.getInteger(MediaFormat.KEY_FRAME_RATE).toDouble() }
                .getOrElse { runCatching { f.getFloat(MediaFormat.KEY_FRAME_RATE).toDouble() }.getOrNull() }
        } else null)?.takeIf { it > 0 } ?: 25.0
        return VideoInfo(
            f.getInteger(MediaFormat.KEY_WIDTH),
            f.getInteger(MediaFormat.KEY_HEIGHT),
            fps,
            if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) else 0,
            int(MediaFormat.KEY_ROTATION) ?: 0,
            int(MediaFormat.KEY_BIT_RATE),
        )
    }

    /**
     * Décode toutes les images dans l'ordre d'affichage ; pour chacune, la texture OpenGL est à jour
     * quand [onFrame](index, pts) est appelé.
     */
    private fun decode(input: File, gl: Gl, onInfo: (VideoInfo) -> Unit, onFrame: (Int, Long) -> Unit) {
        val ex = MediaExtractor()
        ex.setDataSource(input.path)
        val track = videoTrack(ex)
        ex.selectTrack(track)
        val format = ex.getTrackFormat(track)
        onInfo(infoOf(format))
        val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        try {
            decoder.configure(format, gl.decoderSurface, null, 0)
            decoder.start()
            val bi = MediaCodec.BufferInfo()
            var inputDone = false
            var index = 0
            while (true) {
                if (!inputDone) {
                    val inIdx = decoder.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = decoder.getInputBuffer(inIdx)!!
                        val size = ex.readSampleData(buf, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, size, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val outIdx = decoder.dequeueOutputBuffer(bi, 10_000)
                if (outIdx >= 0) {
                    val eos = bi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    val render = bi.size != 0
                    decoder.releaseOutputBuffer(outIdx, render)
                    if (render) {
                        gl.awaitFrame()
                        onFrame(index++, bi.presentationTimeUs)
                    }
                    if (eos) break
                }
            }
        } finally {
            runCatching { decoder.stop() }
            decoder.release()
            ex.release()
        }
    }

    // ------------------------------------------------------------------ encodeur -> muxer

    /** Recopie la piste audio d'origine (sans réencodage), entrelacée avec la vidéo. */
    private class AudioCopier(input: File) {
        private val ex = MediaExtractor()
        val format: MediaFormat?
        var track = -1
        private val buf = ByteBuffer.allocate(1 shl 20)
        private val bi = MediaCodec.BufferInfo()

        init {
            ex.setDataSource(input.path)
            val t = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            format = t?.let { ex.selectTrack(it); ex.getTrackFormat(it) }
        }

        fun writeUpTo(muxer: MediaMuxer, ptsUs: Long) {
            if (track < 0) return
            while (true) {
                val time = ex.sampleTime
                if (time < 0 || time > ptsUs) return
                buf.clear()
                val size = ex.readSampleData(buf, 0)
                if (size < 0) return
                val flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                bi.set(0, size, time, flags)
                muxer.writeSampleData(track, buf, bi)
                ex.advance()
            }
        }

        fun release() = ex.release()
    }

    private class EncoderDrain(val encoder: MediaCodec, val muxer: MediaMuxer, val audio: AudioCopier) {
        private val bi = MediaCodec.BufferInfo()
        private var videoTrack = -1
        var started = false

        fun drain(endOfStream: Boolean) {
            while (true) {
                val idx = encoder.dequeueOutputBuffer(bi, if (endOfStream) 10_000 else 0)
                when {
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        videoTrack = muxer.addTrack(encoder.outputFormat)
                        // Son non accepté en MP4 (ex. Opus d'un WebM) : copie sans le son plutôt qu'un échec
                        audio.format?.let { f -> audio.track = runCatching { muxer.addTrack(f) }.getOrDefault(-1) }
                        muxer.start()
                        started = true
                    }
                    idx >= 0 -> {
                        val data = encoder.getOutputBuffer(idx)!!
                        if (bi.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) bi.size = 0
                        if (bi.size > 0 && started) {
                            data.position(bi.offset)
                            data.limit(bi.offset + bi.size)
                            muxer.writeSampleData(videoTrack, data, bi)
                            audio.writeUpTo(muxer, bi.presentationTimeUs)
                        }
                        encoder.releaseOutputBuffer(idx, false)
                        if (bi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ OpenGL

    /** Rendu hors écran (FBO) en 320x320 pour la détection. */
    private class Fbo(size: Int) {
        private val ids = IntArray(1)
        private val tex = IntArray(1)

        init {
            GLES20.glGenTextures(1, tex, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, size, size, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glGenFramebuffers(1, ids, 0)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, ids[0])
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tex[0], 0)
            val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) throw IOException("Framebuffer OpenGL incomplet ($status)")
        }

        fun bind() = GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, ids[0])
        fun unbind() = GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        fun release() {
            GLES20.glDeleteFramebuffers(1, ids, 0)
            GLES20.glDeleteTextures(1, tex, 0)
        }
    }

    /**
     * Contexte EGL + texture externe alimentée par le décodeur.
     * @param output surface de l'encodeur, ou null (rendu hors écran pour la détection).
     */
    private class Gl(output: Surface?) : AutoCloseable {
        private val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        private val context: EGLContext
        private val surface: EGLSurface
        private val oesTex: Int
        private val program: Int
        private val surfaceTexture: SurfaceTexture
        val decoderSurface: Surface
        private val callbacks = HandlerThread("redDL-gl").apply { start() }
        private val lock = Object()
        private var frameAvailable = false
        private val stMatrix = FloatArray(16)
        private val quad: FloatBuffer = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            // x, y, u, v (bande de triangles plein écran)
            put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f))
            position(0)
        }

        init {
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) throw IOException("EGL indisponible")
            val attribs = mutableListOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, if (output != null) EGL14.EGL_WINDOW_BIT else EGL14.EGL_PBUFFER_BIT,
            ).apply {
                if (output != null) addAll(listOf(EGL_RECORDABLE_ANDROID, 1)) // surface d'encodeur
                add(EGL14.EGL_NONE)
            }.toIntArray()
            val configs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) || num[0] == 0) {
                throw IOException("Configuration EGL introuvable")
            }
            val config = configs[0]!!
            context = EGL14.eglCreateContext(
                display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
            )
            surface = if (output != null) {
                EGL14.eglCreateWindowSurface(display, config, output, intArrayOf(EGL14.EGL_NONE), 0)
            } else {
                EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            }
            if (surface == EGL14.EGL_NO_SURFACE) throw IOException("Surface EGL impossible")
            EGL14.eglMakeCurrent(display, surface, surface, context)

            program = buildProgram()
            val t = IntArray(1)
            GLES20.glGenTextures(1, t, 0)
            oesTex = t[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            surfaceTexture = SurfaceTexture(oesTex)
            surfaceTexture.setOnFrameAvailableListener({
                synchronized(lock) {
                    frameAvailable = true
                    lock.notifyAll()
                }
            }, Handler(callbacks.looper))
            decoderSurface = Surface(surfaceTexture)
        }

        /** Attend la prochaine image du décodeur et met la texture à jour. */
        fun awaitFrame() {
            synchronized(lock) {
                val deadline = System.currentTimeMillis() + 5_000
                while (!frameAvailable) {
                    val left = deadline - System.currentTimeMillis()
                    if (left <= 0) throw IOException("Le décodeur vidéo ne répond plus")
                    lock.wait(left)
                }
                frameAvailable = false
            }
            surfaceTexture.updateTexImage()
            surfaceTexture.getTransformMatrix(stMatrix)
        }

        /** Dessine l'image courante dans la zone d'affichage (viewport) active. */
        fun drawFrame() {
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
            val aPos = GLES20.glGetAttribLocation(program, "aPosition")
            val aTex = GLES20.glGetAttribLocation(program, "aTexCoord")
            quad.position(0)
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad)
            GLES20.glEnableVertexAttribArray(aPos)
            quad.position(2)
            GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, quad)
            GLES20.glEnableVertexAttribArray(aTex)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uSTMatrix"), 1, false, stMatrix, 0)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "sTexture"), 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(aPos)
            GLES20.glDisableVertexAttribArray(aTex)
        }

        /** Envoie l'image dessinée à l'encodeur avec son horodatage. */
        fun present(ptsNs: Long) {
            EGLExt.eglPresentationTimeANDROID(display, surface, ptsNs)
            EGL14.eglSwapBuffers(display, surface)
        }

        private fun buildProgram(): Int {
            fun shader(type: Int, src: String): Int {
                val s = GLES20.glCreateShader(type)
                GLES20.glShaderSource(s, src)
                GLES20.glCompileShader(s)
                val ok = IntArray(1)
                GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
                if (ok[0] == 0) throw IOException("Shader : " + GLES20.glGetShaderInfoLog(s))
                return s
            }
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, VERTEX))
            GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT))
            GLES20.glLinkProgram(p)
            val ok = IntArray(1)
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
            if (ok[0] == 0) throw IOException("Programme OpenGL : " + GLES20.glGetProgramInfoLog(p))
            return p
        }

        override fun close() {
            decoderSurface.release()
            surfaceTexture.release()
            GLES20.glDeleteProgram(program)
            GLES20.glDeleteTextures(1, intArrayOf(oesTex), 0)
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
            // Pas d'eglTerminate : l'affichage EGL est partagé avec le rendu de l'interface de l'appli
            callbacks.quitSafely()
        }

        companion object {
            private const val EGL_RECORDABLE_ANDROID = 0x3142

            private const val VERTEX = """
                uniform mat4 uSTMatrix;
                attribute vec4 aPosition;
                attribute vec4 aTexCoord;
                varying vec2 vTexCoord;
                void main() {
                    gl_Position = aPosition;
                    vTexCoord = (uSTMatrix * aTexCoord).xy;
                }"""

            private const val FRAGMENT = """
                #extension GL_OES_EGL_image_external : require
                precision mediump float;
                varying vec2 vTexCoord;
                uniform samplerExternalOES sTexture;
                void main() {
                    gl_FragColor = texture2D(sTexture, vTexCoord);
                }"""
        }
    }
}
