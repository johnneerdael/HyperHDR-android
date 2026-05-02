package eu.hyperhdr.android.flatbuf

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BackoffScheduleTest {

    @Test
    fun `default schedule produces canonical sequence then caps at 4s`() {
        val schedule = BackoffSchedule.default()
        val first10 = (1..10).map { schedule.nextDelayMillis() }
        assertThat(first10).containsExactly(
            250L, 500L, 1000L, 2000L, 4000L,
            4000L, 4000L, 4000L, 4000L, 4000L,
        ).inOrder()
    }

    @Test
    fun `reset returns to the start of the sequence`() {
        val schedule = BackoffSchedule.default()
        repeat(3) { schedule.nextDelayMillis() } // advance to 1000
        schedule.reset()
        assertThat(schedule.nextDelayMillis()).isEqualTo(250L)
    }

    @Test
    fun `custom schedule honours its own delays`() {
        val schedule = BackoffSchedule(longArrayOf(100L, 200L), capMillis = 200L)
        assertThat(schedule.nextDelayMillis()).isEqualTo(100L)
        assertThat(schedule.nextDelayMillis()).isEqualTo(200L)
        assertThat(schedule.nextDelayMillis()).isEqualTo(200L) // cap
    }
}
