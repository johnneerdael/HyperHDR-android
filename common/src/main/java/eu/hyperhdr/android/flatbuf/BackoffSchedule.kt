package eu.hyperhdr.android.flatbuf

class BackoffSchedule(
    private val schedule: LongArray,
    private val capMillis: Long,
) {
    private var index = 0

    fun nextDelayMillis(): Long {
        val v = if (index < schedule.size) schedule[index] else capMillis
        if (index < schedule.size) index++
        return v.coerceAtMost(capMillis)
    }

    fun reset() {
        index = 0
    }

    companion object {
        fun default(): BackoffSchedule = BackoffSchedule(
            longArrayOf(250L, 500L, 1000L, 2000L, 4000L),
            capMillis = 4000L,
        )
    }
}
