package com.serverspulse.agent.minestom

import com.serverspulse.agent.core.collector.SnapshotAssembler
import net.minestom.server.MinecraftServer
import net.minestom.server.event.server.ServerTickMonitorEvent
import net.minestom.server.monitoring.TickMonitor
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlin.test.*

class MinestomSnapshotTest {
    @Test fun assemblesAnInitializedMinestomServer() {
        MinecraftServer.init()
        val executor = Executors.newSingleThreadExecutor()
        val folder = Files.createTempDirectory("sp-minestom").toFile()
        try {
            val instance = MinecraftServer.getInstanceManager().createInstanceContainer()
            instance.loadChunk(0, 0).join()
            val adapter = MinestomPlatformAdapter(ServersPulseMinestom.Options(folder, 100), executor)
            adapter.timing.recordTickDurationNanos(2_500_000)
            val snapshot = SnapshotAssembler(adapter).assemble()
            assertEquals("minestom", snapshot.platform)
            assertNull(snapshot.tps)
            assertEquals(2.5, snapshot.mspt)
            assertEquals(0, snapshot.onlinePlayers)
            assertEquals(100, snapshot.maxPlayers)
            assertTrue(snapshot.memoryMaxMB > 0)
            assertEquals(instance.uuid.toString(), snapshot.worlds.single().name)
            assertEquals(1, snapshot.worlds.single().loadedChunks)
            assertEquals(0, snapshot.worlds.single().entityCount)
            assertEquals(0, snapshot.worlds.single().playerCount)
            // Exercise lifecycle registration, optional console hook, removal and idempotent stop without a key.
            val agent = ServersPulseMinestom.start(ServersPulseMinestom.Options(folder))
            MinecraftServer.getGlobalEventHandler().call(ServerTickMonitorEvent(TickMonitor(3.0, 0.0)))
            assertTrue(agent.executeConsoleCommand(arrayOf("status")))
            agent.stop()
            agent.stop()
        } finally {
            executor.shutdownNow()
            MinecraftServer.stopCleanly()
            folder.deleteRecursively()
        }
    }
}
