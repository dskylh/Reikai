package reikai.domain.novel.tts

/**
 * The sliding window over a novel's chapters in reading order: which to render around the one being read,
 * and which are far enough behind to lose their audio. Eviction ignores pins, since a chapter picked by
 * hand is worth keeping only until it has been read past.
 */
object TtsRenderWindow {

    data class Plan(
        /** The current chapter, then up to [ahead] after it, nearest first. */
        val render: List<Long>,
        /** Every chapter more than [keepBehind] before the current one. */
        val evict: List<Long>,
    )

    fun plan(readingOrder: List<Long>, current: Long, ahead: Int, keepBehind: Int): Plan {
        val at = readingOrder.indexOf(current)
        if (at < 0) return Plan(emptyList(), emptyList())
        val render = if (ahead <= 0) emptyList() else readingOrder.subList(at, minOf(at + 1 + ahead, readingOrder.size))
        val evict = readingOrder.subList(0, maxOf(0, at - keepBehind.coerceAtLeast(0)))
        return Plan(render.toList(), evict.toList())
    }
}
