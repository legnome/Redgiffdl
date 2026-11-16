package fr.perso.redgifsdl

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Réassemblage en MP4 avec les outils d'Android (MediaExtractor + MediaMuxer), sans réencodage :
 * - MPEG-TS (segments HLS) -> MP4
 * - vidéo seule + audio seul (Reddit) -> un MP4 avec le son
 */
object Remux {

    private class Track(val extractor: MediaExtractor, val muxIndex: Int)

    /** Prend la première piste vidéo et la première piste audio trouvées dans [inputs]. */
    fun mux(inputs: List<File>, output: File, webm: Boolean = false) {
        val muxer = MediaMuxer(
            output.path,
            if (webm) MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
        )
        val tracks = ArrayList<Track>()
        var started = false
        try {
            var hasVideo = false
            var hasAudio = false
            var bufferSize = 1 shl 20
            for (f in inputs) {
                val probe = MediaExtractor()
                try {
                    probe.setDataSource(f.path)
                    for (i in 0 until probe.trackCount) {
                        val format = probe.getTrackFormat(i)
                        val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                        val isVideo = mime.startsWith("video/")
                        val isAudio = mime.startsWith("audio/")
                        if (!(isVideo && !hasVideo) && !(isAudio && !hasAudio)) continue
                        val ex = MediaExtractor()
                        ex.setDataSource(f.path)
                        ex.selectTrack(i)
                        val index = muxer.addTrack(format)
                        if (isVideo) {
                            hasVideo = true
                            if (format.containsKey(MediaFormat.KEY_ROTATION)) {
                                muxer.setOrientationHint(format.getInteger(MediaFormat.KEY_ROTATION))
                            }
                        } else {
                            hasAudio = true
                        }
                        if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                            bufferSize = maxOf(bufferSize, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                        }
                        tracks += Track(ex, index)
                    }
                } finally {
                    probe.release()
                }
            }
            if (!hasVideo) throw IOException("aucune piste vidéo")

            muxer.start()
            started = true
            val buffer = ByteBuffer.allocate(bufferSize)
            val info = MediaCodec.BufferInfo()
            val active = tracks.toMutableList()
            // Écriture entrelacée : toujours l'échantillon le plus ancien parmi les pistes
            while (active.isNotEmpty()) {
                val next = active.minByOrNull { it.extractor.sampleTime } ?: break
                val time = next.extractor.sampleTime
                if (time < 0) {
                    active.remove(next)
                    continue
                }
                buffer.clear()
                val size = next.extractor.readSampleData(buffer, 0)
                if (size < 0) {
                    active.remove(next)
                    continue
                }
                val flags = if (next.extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                    MediaCodec.BUFFER_FLAG_KEY_FRAME
                } else {
                    0
                }
                info.set(0, size, time, flags)
                muxer.writeSampleData(next.muxIndex, buffer, info)
                next.extractor.advance()
            }
            started = false
            muxer.stop() // une erreur ici = fichier invalide : on la laisse remonter
        } finally {
            tracks.forEach { runCatching { it.extractor.release() } }
            runCatching { if (started) muxer.stop() }
            runCatching { muxer.release() }
        }
    }
}
