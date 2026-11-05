package com.serverspulse.agent.core

import com.serverspulse.agent.api.ServersPulse
import java.nio.file.Files
import kotlin.test.*

class CustomTelemetryTest {
 @Test fun aggregationValidationAndLifecycle() {
  ServersPulse.setRunning(false)
  assertFalse(ServersPulse.gauge("test", 1.0))
  ServersPulse.setRunning(true)
  try {
   assertTrue(ServersPulse.gauge("queue.size", 2.0))
   assertTrue(ServersPulse.gauge("queue.size", 3.0))
   assertTrue(ServersPulse.counter("games", 2.0))
   assertTrue(ServersPulse.counter("games", 4.0))
   assertFalse(ServersPulse.counter("games", -1.0))
   assertFalse(ServersPulse.gauge("invalid name", 0.0))
   assertFalse(ServersPulse.gauge("bad", Double.NaN))
   assertFalse(ServersPulse.event("game", mapOf("nested" to mapOf("x" to 1))))
   assertFalse(ServersPulse.annotate("x".repeat(257)))
   assertTrue(ServersPulse.annotate("Round started"))
   val batch = assertNotNull(ServersPulse.drain())
   assertEquals(3.0, batch.metrics.first { it.kind == "gauge" }.value)
   assertEquals(6.0, batch.metrics.first { it.kind == "counter" }.value)
   assertEquals("annotation", batch.events.single().type)
   assertNull(ServersPulse.drain())
   repeat(62) { assertTrue(ServersPulse.gauge("name$it", 0.0)) }
   assertFalse(ServersPulse.gauge("overflow", 0.0))
  } finally { ServersPulse.setRunning(false) }
 }
 @Test fun identitiesPersistAndAreServerScoped() {
  val one = Files.createTempDirectory("sp-identity").toFile()
  val two = Files.createTempDirectory("sp-identity").toFile()
  try {
   val raw = "069a79f4-44e9-4726-a5be-fca90e38aaf5"
   val first = PlayerIdentity(one)
   assertEquals(first.uuid(raw), PlayerIdentity(one).uuid(raw))
   assertNotEquals(raw, first.uuid(raw))
   assertNotEquals(first.uuid(raw), PlayerIdentity(two).uuid(raw))
   assertEquals(16,first.username(raw).length)
  } finally { one.deleteRecursively();two.deleteRecursively() }
 }
}
