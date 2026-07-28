package com.serverspulse.agent.api

/**
 * Abstraction over the platform's task scheduler.
 */
interface SchedulerAdapter {
    /**
     * Schedules a repeating task on the server's primary execution context.
     *
     * On traditional single-threaded servers this is the main thread.
     * On Folia this maps to the global region scheduler.
     * @param intervalTicks interval between executions in server ticks (20 ticks = 1 second)
     * @param task the work to run each interval
     * @return a cancellable handle
     */
    fun scheduleRepeating(intervalTicks: Long, task: Runnable): TaskHandle

    /**
     * Runs a task asynchronously off the main thread.
     */
    fun runAsync(task: Runnable)
}

/**
 * A handle to a scheduled task that can be cancelled.
 */
interface TaskHandle {
    fun cancel()
}
