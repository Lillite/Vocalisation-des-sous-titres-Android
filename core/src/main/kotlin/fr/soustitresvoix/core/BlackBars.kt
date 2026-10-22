package fr.soustitresvoix.core

/** Evidence only. Never crops by itself; callers must display a preview and obtain consent. */
object BlackBars {
    data class Margins(val left: Float = 0f, val top: Float = 0f, val right: Float = 0f, val bottom: Float = 0f) {
        fun safe() = listOf(left, top, right, bottom).all { it.isFinite() && it in 0f..0.24f } && left + right < .48f && top + bottom < .48f
        fun nonzero() = left + top + right + bottom > .005f
    }
    fun inspect(pixels: IntArray, width: Int, height: Int): Margins? {
        require(width > 0 && height > 0 && width.toLong() * height == pixels.size.toLong())
        fun luma(x: Int, y: Int): Int { val c = pixels[y * width + x]; return (54 * (c shr 16 and 255) + 183 * (c shr 8 and 255) + 19 * (c and 255)) / 256 }
        var light = 0; var samples = 0
        for (y in height / 4 until height * 3 / 4 step 3) for (x in width / 4 until width * 3 / 4 step 3) { samples++; if (luma(x,y) > 35) light++ }
        if (samples == 0 || light.toDouble() / samples < .30) return null // dark scenes are not evidence
        fun row(y: Int) = (0 until width).all { luma(it,y) <= 18 }
        fun column(x: Int) = (0 until height).all { luma(x,it) <= 18 }
        var t=0; var b=0; var l=0; var r=0
        while (t < height / 4 && row(t)) t++
        while (b < height / 4 && row(height-1-b)) b++
        while (l < width / 4 && column(l)) l++
        while (r < width / 4 && column(width-1-r)) r++
        // One inset pixel protects border details and antialiasing. Reject max-bound scans.
        if (t >= height/4 || b >= height/4 || l >= width/4 || r >= width/4) return null
        return Margins((l-1).coerceAtLeast(0).toFloat()/width, (t-1).coerceAtLeast(0).toFloat()/height,
            (r-1).coerceAtLeast(0).toFloat()/width, (b-1).coerceAtLeast(0).toFloat()/height)
    }
    fun consensus(samples: List<Margins?>): Margins? {
        if (samples.size < 5 || samples.any { it == null }) return null
        val values = samples.filterNotNull()
        val result = Margins(values.minOf { it.left }, values.minOf { it.top }, values.minOf { it.right }, values.minOf { it.bottom })
        return result.takeIf { it.safe() && it.nonzero() }
    }
}
