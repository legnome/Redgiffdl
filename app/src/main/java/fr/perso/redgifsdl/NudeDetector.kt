package fr.perso.redgifsdl

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

/**
 * Modèle NudeNet 320n (ONNX) exécuté avec ONNX Runtime, comme NudeDetector dans NudeNet / legnome.
 * Même API sur Android et sur PC (tests).
 */
class NudeDetector(modelBytes: ByteArray, threads: Int = 4) : AutoCloseable {

    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(
        modelBytes,
        OrtSession.SessionOptions().apply { setIntraOpNumThreads(threads) },
    )
    private val inputName = session.inputNames.first()

    /** Sortie brute du modèle pour un tenseur [1,3,size,size]. */
    fun run(tensor: FloatArray, size: Int = Nudity.INPUT): FloatArray {
        OnnxTensor.createTensor(env, FloatBuffer.wrap(tensor), longArrayOf(1, 3, size.toLong(), size.toLong())).use { input ->
            session.run(mapOf(inputName to input)).use { result ->
                val fb = (result.get(0) as OnnxTensor).floatBuffer
                return FloatArray(fb.remaining()).also { fb.get(it) }
            }
        }
    }

    /** Détections (toutes classes) d'une image de [width]x[height] déjà mise au format du modèle. */
    fun detect(tensor: FloatArray, width: Int, height: Int): List<Nudity.Detection> =
        Nudity.postprocess(run(tensor), width, height)

    override fun close() {
        session.close()
    }

    companion object {
        const val ASSET = "nudenet_320n.onnx"
    }
}
