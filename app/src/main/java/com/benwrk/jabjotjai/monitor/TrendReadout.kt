package com.benwrk.jabjotjai.monitor

/** A value read off a heart-rate or RR graph: [value] at [tNs] on the phone's monotonic clock. */
data class TrendPoint(val tNs: Long, val value: Int)

/**
 * The time a heart-rate or RR [graph] shows [fraction] of the way across it, when its newest time is
 * [nowNs]: the inverse of where it draws a value, sweeping or scrolling, and folded across a
 * cascade the same way (see `TrendTrace`). In a sweep, the part right of the cursor shows the
 * sweep before, so its times are older.
 */
fun trendTimeAt(fraction: Float, nowNs: Long, graph: ChartGraph): Long {
    val rowNs = graph.span.ns
    val stripNs = rowNs * graph.cascadeSize
    val alongNs = ((graph.cascadeIndex + fraction.coerceIn(0f, 1f)).toDouble() * rowNs).toLong()
    return if (graph.motion == TrendMotion.SWEEP) {
        nowNs - Math.floorMod(Math.floorMod(nowNs, stripNs) - alongNs, stripNs)
    } else {
        nowNs - stripNs + alongNs
    }
}

/**
 * The value of [trends] nearest [tNs], no further from it than [withinNs] and later than [fromNs] up
 * to [toNs], the stretch the graph shows; null if there is none. Breaks are not values, and nor are
 * values [filter] does not draw.
 */
fun nearestTrendPoint(
    trends: List<TrendSnapshot>,
    tNs: Long,
    fromNs: Long,
    toNs: Long,
    withinNs: Long,
    filter: TrendFilter = TrendFilter(),
): TrendPoint? {
    var nearest: TrendPoint? = null
    var nearestDistance = Long.MAX_VALUE
    for (trend in trends) {
        for (i in 0 until trend.size) {
            val t = trend.tNs[i]
            val value = trend.values[i]
            if (!filter.shows(trend, i) || t <= fromNs || t > toNs) continue
            val distance = Math.abs(t - tNs)
            if (distance <= withinNs && distance < nearestDistance) {
                nearest = TrendPoint(t, value)
                nearestDistance = distance
            }
        }
    }
    return nearest
}
