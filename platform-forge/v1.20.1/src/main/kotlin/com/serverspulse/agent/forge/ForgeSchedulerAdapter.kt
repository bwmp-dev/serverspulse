package com.serverspulse.agent.forge

import com.serverspulse.agent.api.SchedulerAdapter
import com.serverspulse.agent.api.TaskHandle
import net.minecraft.server.MinecraftServer
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Scheduler adapter for Forge.
 *
 * Main-thread tasks are submitted via [MinecraftServer.execute].
 * Repeating tasks use a single-threaded [ScheduledExecutorService] that
 * dispatches work onto the server thread each interval.
 * Async tasks run on a cached thread pool.
 */
class ForgeSchedulerAdapter(private val server: MinecraftServer) : SchedulerAdapter {

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "ServersPulse-Scheduler").apply { isDaemon = true }
    }
    private val asyncPool = Executors.newCachedThreadPool { r ->
        Thread(r, "ServersPulse-Async").apply { isDaemon = true }
    }

    override fun scheduleRepeating(intervalTicks: Long, task: Runnable): TaskHandle {
        val intervalMs = intervalTicks * 50L
        val future = scheduler.scheduleAtFixedRate({
            server.execute(task)
        }, intervalMs, intervalMs, TimeUnit.MILLISECONDS)
        return ScheduledTaskHandle(future)
    }

    override fun runAsync(task: Runnable) {
        asyncPool.execute(task)
    }

    fun shutdown() {
        scheduler.shutdownNow()
        asyncPool.shutdownNow()
    }

    private class ScheduledTaskHandle(private val future: ScheduledFuture<*>) : TaskHandle {
        override fun cancel() {
            future.cancel(false)
        }
    }
}
