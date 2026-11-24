package me.d3sox.nevumi.diagnostics

/**
 * Notices when a thread that should loop continuously stops for [limitMs]. It reports the stall while the thread is still
 * stuck, so what holds the thread can be read, and again once the thread moves on, with [Sample]s taken before and after.
 * The watched thread calls [progress]; another thread calls [check] regularly.
 */
class StallWatchdog<Sample : Any>(private val limitMs: Long, private val now: () -> Long, private val sample: (Thread) -> Sample?,
    private val stalled: (thread: Thread, stalledMs: Long) -> Unit,
    private val ended: (thread: Thread, stalledMs: Long, resumed: Boolean, before: Sample?, after: Sample?) -> Unit) {
    @Volatile private var thread: Thread? = null
    @Volatile private var progressedAt = 0L
    @Volatile private var running = false
    private var stallStartedAt: Long? = null
    private var healthy: Sample? = null

    fun progress() {
        thread = Thread.currentThread()
        progressedAt = now()
    }

    @Synchronized fun start() {
        progressedAt = now()
        running = true
    }

    /** A thread that stops on purpose, as when playback pauses, ends a stall without having resumed. */
    @Synchronized fun stop() {
        running = false
        val watched = thread ?: return
        stallStartedAt?.let { ended(watched, now() - it, false, healthy, sample(watched)) }
        stallStartedAt = null
        healthy = null
    }

    @Synchronized fun check() {
        val watched = thread ?: return
        if (!running) return
        val last = progressedAt
        val started = stallStartedAt
        if (started != null) {
            if (last > started) {
                ended(watched, last - started, true, healthy, sample(watched))
                stallStartedAt = null
                healthy = sample(watched)
            }
        } else if (now() - last >= limitMs) {
            stallStartedAt = last
            stalled(watched, now() - last)
        } else healthy = sample(watched)
    }
}
