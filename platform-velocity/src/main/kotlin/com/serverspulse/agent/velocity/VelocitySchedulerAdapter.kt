package com.serverspulse.agent.velocity

import com.serverspulse.agent.api.SchedulerAdapter
import com.serverspulse.agent.api.TaskHandle
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.scheduler.ScheduledTask
import java.util.concurrent.TimeUnit

/**
 * Scheduler adapter for Velocity.
 *
 * A proxy has no tick loop, so the interval the core runtime expresses in ticks
 * is converted to wall-clock time at the usual 50ms per tick. There is likewise
 * no main thread to protect: every Velocity task already runs on the proxy's
 * pool, so the repeating and async paths differ only in their schedule.
 */
class VelocitySchedulerAdapter(
    private val proxy: ProxyServer,
    private val plugin: Any
) : SchedulerAdapter {

    override fun scheduleRepeating(intervalTicks: Long, task: Runnable): TaskHandle {
        val intervalMs = intervalTicks * 50L
        val scheduled = proxy.scheduler
            .buildTask(plugin, task)
            .delay(intervalMs, TimeUnit.MILLISECONDS)
            .repeat(intervalMs, TimeUnit.MILLISECONDS)
            .schedule()
        return VelocityTaskHandle(scheduled)
    }

    override fun runAsync(task: Runnable) {
        proxy.scheduler.buildTask(plugin, task).schedule()
    }

    fun runLater(delayMillis: Long, task: Runnable) {
        proxy.scheduler
            .buildTask(plugin, task)
            .delay(delayMillis, TimeUnit.MILLISECONDS)
            .schedule()
    }

    private class VelocityTaskHandle(private val task: ScheduledTask) : TaskHandle {
        override fun cancel() {
            task.cancel()
        }
    }
}
