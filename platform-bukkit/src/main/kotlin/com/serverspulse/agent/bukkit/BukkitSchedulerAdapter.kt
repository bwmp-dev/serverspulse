package com.serverspulse.agent.bukkit

import com.serverspulse.agent.api.SchedulerAdapter
import com.serverspulse.agent.api.TaskHandle
import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import java.lang.reflect.Method
import java.util.function.Consumer

/**
 * Bukkit scheduler adapter using Bukkit's built-in scheduler.
 */
class BukkitSchedulerAdapter(private val plugin: JavaPlugin) : SchedulerAdapter {

    private val foliaBridge = FoliaSchedulerBridge(plugin)

    override fun scheduleRepeating(intervalTicks: Long, task: Runnable): TaskHandle {
        foliaBridge.scheduleRepeating(intervalTicks, task)?.let { return it }
        val bukkitTask = plugin.server.scheduler.runTaskTimer(plugin, task, intervalTicks, intervalTicks)
        return BukkitTaskHandle(bukkitTask)
    }

    override fun runAsync(task: Runnable) {
        if (foliaBridge.runAsync(task)) {
            return
        }
        plugin.server.scheduler.runTaskAsynchronously(plugin, task)
    }

    /**
     * Runs work on the primary server execution context.
     *
     * On Folia this uses the global region scheduler. On legacy Bukkit/Paper
     * this falls back to Bukkit's main-thread scheduler.
     */
    fun runPrimary(task: Runnable) {
        if (foliaBridge.runPrimary(task)) {
            return
        }
        plugin.server.scheduler.runTask(plugin, task)
    }

    private class BukkitTaskHandle(private val task: BukkitTask) : TaskHandle {
        override fun cancel() {
            task.cancel()
        }
    }

    private class FoliaSchedulerBridge(private val plugin: Plugin) {

        private val globalScheduler: Any? = resolveBukkitScheduler("getGlobalRegionScheduler")
        private val asyncScheduler: Any? = resolveBukkitScheduler("getAsyncScheduler")

        private val globalRunAtFixedRateConsumer: Method? = resolveMethod(
            globalScheduler,
            "runAtFixedRate",
            Plugin::class.java,
            Consumer::class.java,
            java.lang.Long.TYPE,
            java.lang.Long.TYPE
        )
        private val globalRunAtFixedRateRunnable: Method? = resolveMethod(
            globalScheduler,
            "runAtFixedRate",
            Plugin::class.java,
            Runnable::class.java,
            java.lang.Long.TYPE,
            java.lang.Long.TYPE
        )
        private val globalRunConsumer: Method? = resolveMethod(
            globalScheduler,
            "run",
            Plugin::class.java,
            Consumer::class.java
        )
        private val globalExecuteRunnable: Method? = resolveMethod(
            globalScheduler,
            "execute",
            Plugin::class.java,
            Runnable::class.java
        )
        private val asyncRunNowConsumer: Method? = resolveMethod(
            asyncScheduler,
            "runNow",
            Plugin::class.java,
            Consumer::class.java
        )
        private val asyncRunNowRunnable: Method? = resolveMethod(
            asyncScheduler,
            "runNow",
            Plugin::class.java,
            Runnable::class.java
        )
        private val asyncExecuteRunnable: Method? = resolveMethod(
            asyncScheduler,
            "execute",
            Plugin::class.java,
            Runnable::class.java
        )

        fun scheduleRepeating(intervalTicks: Long, task: Runnable): TaskHandle? {
            val scheduler = globalScheduler ?: return null

            invoke(globalRunAtFixedRateConsumer, scheduler, plugin, Consumer<Any> { task.run() }, intervalTicks, intervalTicks)?.let {
                return FoliaTaskHandle(it)
            }

            invoke(globalRunAtFixedRateRunnable, scheduler, plugin, task, intervalTicks, intervalTicks)?.let {
                return FoliaTaskHandle(it)
            }

            return null
        }

        fun runAsync(task: Runnable): Boolean {
            val scheduler = asyncScheduler ?: return false

            if (invokeSuccessfully(asyncRunNowConsumer, scheduler, plugin, Consumer<Any> { task.run() })) {
                return true
            }
            if (invokeSuccessfully(asyncRunNowRunnable, scheduler, plugin, task)) {
                return true
            }

            return invokeSuccessfully(asyncExecuteRunnable, scheduler, plugin, task)
        }

        fun runPrimary(task: Runnable): Boolean {
            val scheduler = globalScheduler ?: return false

            if (invokeSuccessfully(globalRunConsumer, scheduler, plugin, Consumer<Any> { task.run() })) {
                return true
            }

            return invokeSuccessfully(globalExecuteRunnable, scheduler, plugin, task)
        }

        private fun resolveBukkitScheduler(methodName: String): Any? {
            return try {
                Bukkit::class.java.getMethod(methodName).invoke(null)
            } catch (_: Throwable) {
                null
            }
        }

        private fun resolveMethod(target: Any?, name: String, vararg args: Class<*>): Method? {
            if (target == null) return null
            return try {
                target::class.java.getMethod(name, *args)
            } catch (_: Throwable) {
                null
            }
        }

        private fun invoke(method: Method?, target: Any, vararg args: Any?): Any? {
            if (method == null) return null
            return try {
                method.invoke(target, *args)
            } catch (_: Throwable) {
                null
            }
        }

        private fun invokeSuccessfully(method: Method?, target: Any, vararg args: Any?): Boolean {
            if (method == null) return false
            return try {
                method.invoke(target, *args)
                true
            } catch (_: Throwable) {
                false
            }
        }
    }

    private class FoliaTaskHandle(private val task: Any) : TaskHandle {
        override fun cancel() {
            try {
                task::class.java.getMethod("cancel").invoke(task)
            } catch (_: Throwable) {
                // Ignore cancellation failures.
            }
        }
    }
}
