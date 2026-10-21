package com.mithun.latchscan.scanner

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import com.mithun.latchscan.LatchApp
import com.mithun.latchscan.data.Pt
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.nio.FloatBuffer

/**
 * On-device document corner network: DocAligner "lcnet100" heatmap model by DocsaidLab
 * (Apache License 2.0, https://github.com/DocsaidLab/DocAligner), run with ONNX Runtime.
 *
 * Input: 256x256 BGR image scaled to 0..1 (NCHW). Output: four 128x128 heatmaps, one per
 * corner (top-left, top-right, bottom-right, bottom-left).
 */
class CornerModel private constructor(private val session: OrtSession, private val env: OrtEnvironment) {

    /** Corners found (normalised, in the network's order) and the weakest heatmap peak. */
    class Result(val corners: List<Pt>, val minConfidence: Float)

    private val inputName = session.inputNames.first()
    private val input = FloatArray(3 * SIZE * SIZE)
    private val lock = Any()

    /** [bgr] is an 8-bit 3-channel BGR image of any size. */
    fun predict(bgr: Mat): Result? {
        synchronized(lock) { return predictLocked(bgr) }
    }

    private fun predictLocked(bgr: Mat): Result? {
        val resized = Mat()
        val asFloat = Mat()
        try {
            Imgproc.resize(bgr, resized, Size(SIZE.toDouble(), SIZE.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            resized.convertTo(asFloat, CvType.CV_32FC3, 1.0 / 255.0)
            val hwc = FloatArray(SIZE * SIZE * 3)
            asFloat.get(0, 0, hwc)
            // HWC -> CHW
            val plane = SIZE * SIZE
            for (i in 0 until plane) {
                input[i] = hwc[i * 3]
                input[plane + i] = hwc[i * 3 + 1]
                input[2 * plane + i] = hwc[i * 3 + 2]
            }
            OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong())).use { tensor ->
                session.run(mapOf(inputName to tensor)).use { out ->
                    val heat = (out[0] as OnnxTensor)
                    val shape = heat.info.shape // [1, 4, H, W]
                    val hh = shape[2].toInt()
                    val ww = shape[3].toInt()
                    val data = FloatArray((shape[1] * shape[2] * shape[3]).toInt())
                    heat.floatBuffer.get(data)
                    return decode(data, shape[1].toInt(), hh, ww)
                }
            }
        } finally {
            resized.release(); asFloat.release()
        }
    }

    private fun decode(data: FloatArray, channels: Int, h: Int, w: Int): Result? {
        val corners = ArrayList<Pt>(4)
        var minConf = 1f
        val plane = h * w
        val mask = Mat(h, w, CvType.CV_8UC1)
        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        val maskBytes = ByteArray(plane)
        val labelBuf = IntArray(plane)
        try {
            for (c in 0 until minOf(4, channels)) {
                val off = c * plane
                for (i in 0 until plane) maskBytes[i] = if (data[off + i] >= THRESHOLD) 1 else 0
                mask.put(0, 0, maskBytes)
                val n = Imgproc.connectedComponentsWithStats(mask, labels, stats, centroids)
                if (n <= 1) continue
                var best = 1
                var bestArea = -1
                for (k in 1 until n) {
                    val area = stats.get(k, Imgproc.CC_STAT_AREA)[0].toInt()
                    if (area > bestArea) { bestArea = area; best = k }
                }
                labels.get(0, 0, labelBuf)
                var sx = 0.0
                var sy = 0.0
                var sw = 0.0
                var peak = 0f
                for (i in 0 until plane) {
                    if (labelBuf[i] != best) continue
                    val v = data[off + i]
                    sx += (i % w) * v.toDouble()
                    sy += (i / w) * v.toDouble()
                    sw += v
                    if (v > peak) peak = v
                }
                if (sw <= 0) continue
                corners.add(Pt(((sx / sw + 0.5) / w).toFloat(), ((sy / sw + 0.5) / h).toFloat()))
                minConf = minOf(minConf, peak)
            }
        } finally {
            mask.release(); labels.release(); stats.release(); centroids.release()
        }
        if (corners.isEmpty()) return null
        return Result(corners, minConf)
    }

    companion object {
        private const val TAG = "CornerModel"
        private const val SIZE = 256
        private const val THRESHOLD = 0.3f
        const val ASSET = "models/lcnet100.onnx"

        @Volatile private var instance: CornerModel? = null
        @Volatile private var failed = false

        /** Loads the model once (about 5 MB). Returns null if it can't be loaded. */
        fun get(): CornerModel? {
            instance?.let { return it }
            if (failed) return null
            synchronized(this) {
                instance?.let { return it }
                return try {
                    val bytes = LatchApp.instance.assets.open(ASSET).use { it.readBytes() }
                    val env = OrtEnvironment.getEnvironment()
                    val opts = OrtSession.SessionOptions().apply {
                        setIntraOpNumThreads(2)
                        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                        runCatching { addXnnpack(mapOf("intra_op_num_threads" to "2")) }
                    }
                    CornerModel(env.createSession(bytes, opts), env).also { instance = it }
                } catch (t: Throwable) {
                    Log.e(TAG, "Couldn't load the corner model; using edge detection only", t)
                    failed = true
                    null
                }
            }
        }
    }
}
