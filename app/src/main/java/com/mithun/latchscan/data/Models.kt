package com.mithun.latchscan.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/** A point in normalised image coordinates (0..1 on both axes). */
data class Pt(val x: Float, val y: Float) {
    fun dist(o: Pt): Float = hypot(x - o.x, y - o.y)
}

/** Four document corners, always ordered top-left, top-right, bottom-right, bottom-left. */
data class Quad(val tl: Pt, val tr: Pt, val br: Pt, val bl: Pt) {

    val points: List<Pt> get() = listOf(tl, tr, br, bl)

    fun with(index: Int, p: Pt): Quad = when (index) {
        0 -> copy(tl = p)
        1 -> copy(tr = p)
        2 -> copy(br = p)
        else -> copy(bl = p)
    }

    /** Largest distance any corner moved compared with [o]. */
    fun maxShift(o: Quad): Float = points.zip(o.points).maxOf { (a, b) -> a.dist(b) }

    /** Signed area of the polygon (shoelace). Positive for clockwise order in screen space. */
    fun area(): Float {
        val p = points
        var s = 0f
        for (i in p.indices) {
            val a = p[i]
            val b = p[(i + 1) % p.size]
            s += a.x * b.y - b.x * a.y
        }
        return s / 2f
    }

    /** True when the polygon is convex and not degenerate, i.e. it can be perspective-corrected. */
    fun isValid(): Boolean {
        val p = points
        var sign = 0
        for (i in p.indices) {
            val a = p[i]
            val b = p[(i + 1) % 4]
            val c = p[(i + 2) % 4]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            if (abs(cross) < 1e-6f) return false
            val s = if (cross > 0) 1 else -1
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return abs(area()) > 0.01f
    }

    fun toJson(): JSONArray = JSONArray().apply {
        points.forEach { put(it.x.toDouble()); put(it.y.toDouble()) }
    }

    companion object {
        val FULL = Quad(Pt(0f, 0f), Pt(1f, 0f), Pt(1f, 1f), Pt(0f, 1f))

        fun inset(f: Float) = Quad(Pt(f, f), Pt(1 - f, f), Pt(1 - f, 1 - f), Pt(f, 1 - f))

        fun fromJson(a: JSONArray?): Quad {
            if (a == null || a.length() < 8) return FULL
            fun p(i: Int) = Pt(a.getDouble(i * 2).toFloat(), a.getDouble(i * 2 + 1).toFloat())
            return Quad(p(0), p(1), p(2), p(3))
        }

        /** Orders four arbitrary points as TL, TR, BR, BL. */
        fun ordered(pts: List<Pt>): Quad {
            require(pts.size == 4)
            val cx = pts.sumOf { it.x.toDouble() } / 4
            val cy = pts.sumOf { it.y.toDouble() } / 4
            val sorted = pts.sortedBy { kotlin.math.atan2(it.y - cy, it.x - cx) }
            // atan2 order in screen space (y down) goes TL? -> start from the point with min x+y.
            val start = sorted.indices.minBy { sorted[it].x + sorted[it].y }
            val r = List(4) { sorted[(start + it) % 4] }
            return Quad(r[0], r[1], r[2], r[3])
        }
    }
}

enum class Filter(val label: String) {
    ORIGINAL("Original"),
    ENHANCED("Magic"),
    GRAY("Grayscale"),
    BW("B&W"),
}

enum class Side { NONE, FRONT, BACK }

data class Page(
    val id: String = UUID.randomUUID().toString().take(12),
    /** File name (inside the owning folder) of the untouched camera/gallery image. */
    val original: String,
    val quad: Quad = Quad.FULL,
    val rotation: Int = 0,
    val filter: Filter = Filter.ENHANCED,
    val side: Side = Side.NONE,
    /** Bumped every time the processed image is regenerated (cache key for thumbnails). */
    val rev: Long = System.currentTimeMillis(),
) {
    val processed: String get() = "${id}_out.jpg"

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("original", original)
        .put("quad", quad.toJson())
        .put("rotation", rotation)
        .put("filter", filter.name)
        .put("side", side.name)
        .put("rev", rev)

    companion object {
        fun fromJson(o: JSONObject) = Page(
            id = o.getString("id"),
            original = o.getString("original"),
            quad = Quad.fromJson(o.optJSONArray("quad")),
            rotation = o.optInt("rotation", 0),
            filter = runCatching { Filter.valueOf(o.optString("filter")) }.getOrDefault(Filter.ORIGINAL),
            side = runCatching { Side.valueOf(o.optString("side")) }.getOrDefault(Side.NONE),
            rev = o.optLong("rev", 0L),
        )
    }
}

data class ScanDocument(
    val id: String = UUID.randomUUID().toString().take(12),
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val pages: List<Page> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("createdAt", createdAt)
        .put("updatedAt", updatedAt)
        .put("pages", JSONArray().apply { pages.forEach { put(it.toJson()) } })

    companion object {
        fun fromJson(o: JSONObject): ScanDocument {
            val arr = o.optJSONArray("pages") ?: JSONArray()
            return ScanDocument(
                id = o.getString("id"),
                name = o.optString("name", "Scan"),
                createdAt = o.optLong("createdAt"),
                updatedAt = o.optLong("updatedAt"),
                pages = List(arr.length()) { Page.fromJson(arr.getJSONObject(it)) },
            )
        }
    }
}

fun Float.clamp01(): Float = max(0f, kotlin.math.min(1f, this))
