package com.mithun.latchscan.scanner

import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.mithun.latchscan.data.Pt
import com.mithun.latchscan.data.Quad
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat

/** Result of analysing one preview frame. Coordinates are normalised to the upright frame. */
data class Detection(
    val quad: Quad?,
    val frameWidth: Int,
    val frameHeight: Int,
    val timeMs: Long,
)

/**
 * CameraX analyzer: takes each colour preview frame, finds the page outline
 * and publishes it. Runs on a background executor at ~12 fps.
 */
class FrameAnalyzer(private val enabled: () -> Boolean) : ImageAnalysis.Analyzer {

    private val _detections = MutableStateFlow<Detection?>(null)
    val detections: StateFlow<Detection?> = _detections.asStateFlow()

    private var buffer = ByteArray(0)
    private var mat: Mat? = null
    private var uprightMat: Mat? = null
    private var lastRun = 0L

    override fun analyze(image: ImageProxy) {
        try {
            val now = SystemClock.elapsedRealtime()
            if (!enabled() || now - lastRun < 70) return
            lastRun = now

            // RGBA_8888 frames: copy row by row (rows may be padded).
            val w = image.width
            val h = image.height
            val plane = image.planes[0]
            val buf = plane.buffer
            val rowStride = plane.rowStride
            val rowBytes = w * 4
            if (buffer.size != rowBytes * h) buffer = ByteArray(rowBytes * h)
            buf.rewind()
            if (rowStride == rowBytes) {
                buf.get(buffer, 0, rowBytes * h)
            } else {
                for (row in 0 until h) {
                    buf.position(row * rowStride)
                    buf.get(buffer, row * rowBytes, rowBytes)
                }
            }
            val m = mat?.takeIf { it.cols() == w && it.rows() == h } ?: run {
                mat?.release()
                Mat(h, w, CvType.CV_8UC4).also { mat = it }
            }
            m.put(0, 0, buffer)

            // Turn the frame upright so the corner model sees the page the way the user does.
            val rot = image.imageInfo.rotationDegrees
            val upright = if (rot == 0) m else {
                val r = uprightMat ?: Mat().also { uprightMat = it }
                Core.rotate(
                    m, r,
                    when (rot) {
                        90 -> Core.ROTATE_90_CLOCKWISE
                        180 -> Core.ROTATE_180
                        else -> Core.ROTATE_90_COUNTERCLOCKWISE
                    },
                )
                r
            }
            val quad = EdgeDetector.detectColor(upright)
            _detections.value = Detection(quad, upright.cols(), upright.rows(), now)
        } catch (t: Throwable) {
            // A bad frame must never crash the camera.
        } finally {
            image.close()
        }
    }

    fun release() {
        mat?.release()
        mat = null
        uprightMat?.release()
        uprightMat = null
    }

    companion object {
        /** Maps normalised sensor coordinates to the upright (display) orientation. */
        fun rotateQuad(q: Quad, degrees: Int): Quad {
            val pts = q.points.map { p ->
                when (degrees) {
                    90 -> Pt(1f - p.y, p.x)
                    180 -> Pt(1f - p.x, 1f - p.y)
                    270 -> Pt(p.y, 1f - p.x)
                    else -> p
                }
            }
            return Quad.ordered(pts)
        }
    }
}
