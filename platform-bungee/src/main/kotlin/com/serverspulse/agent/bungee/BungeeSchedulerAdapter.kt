package com.serverspulse.agent.bungee

import com.serverspulse.agent.api.SchedulerAdapter
import com.serverspulse.agent.api.TaskHandle
import net.md_5.bungee.api.plugin.Plugin
import net.md_5.bungee.api.scheduler.ScheduledTask
import java.util.concurrent.TimeUnit

/**
 * Scheduler adapter for BungeeCord and Waterfall.
 *
 * A proxy has no tick loop, so the interval the core runtime expresses in ticks
 * is converted to wall-clock time at the usual 50ms per tick. Bungee runs every
 * scheduled task off the network threads already, so there is no main thread to
 * hop back onto and the repeating and async paths differ only in their schedule.
 */
class BungeeSchedulerAdapter(private val plugin: Plugin) : SchedulerAdapter {

    override fun scheduleRepeating(intervalTicks: Long, task: Runnable): TaskHandle {
        val intervalMs = intervalTicks * 50L
        val scheduled = plugin.proxy.scheduler.schedule(
            plugin, task, intervalMs, intervalMs, TimeUnit.MILLISECONDS
        )
        return BungeeTaskHandle(scheduled)
    }

    override fun runAsync(task: Runnable) {
        plugin.proxy.scheduler.runAsync(plugin, task)
    }

    fun runLater(delayMillis: Long, task: Runnable) {
        plugin.proxy.scheduler.schedule(plugin, task, delayMillis, TimeUnit.MILLISECONDS)
    }

    private class BungeeTaskHandle(private val task: ScheduledTask) : TaskHandle {
        override fun cancel() {
            task.cancel()
        }
    }
}
