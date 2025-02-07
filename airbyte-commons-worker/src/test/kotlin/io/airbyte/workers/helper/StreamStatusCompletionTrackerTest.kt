package io.airbyte.workers.helper

import io.airbyte.config.AirbyteStream
import io.airbyte.config.ConfiguredAirbyteCatalog
import io.airbyte.config.ConfiguredAirbyteStream
import io.airbyte.config.StreamDescriptor
import io.airbyte.protocol.models.AirbyteMessage
import io.airbyte.protocol.models.AirbyteStreamStatusTraceMessage
import io.airbyte.protocol.models.AirbyteTraceMessage
import io.airbyte.workers.context.ReplicationContext
import io.airbyte.workers.internal.AirbyteMapper
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.util.UUID

internal class StreamStatusCompletionTrackerTest {
  private val clock: Clock = mockk()
  private val mapper: AirbyteMapper = mockk()

  private val streamStatusCompletionTracker = StreamStatusCompletionTracker(clock)

  private val catalog =
    ConfiguredAirbyteCatalog()
      .withStreams(
        listOf(
          ConfiguredAirbyteStream().withStream(AirbyteStream().withName("name1")),
          ConfiguredAirbyteStream().withStream(AirbyteStream().withName("name2").withNamespace("namespace2")),
        ),
      )

  private val connectionId = UUID.randomUUID()
  private val workspaceId = UUID.randomUUID()
  private val sourceDefinitionId = UUID.randomUUID()
  private val destinationDefinitionId = UUID.randomUUID()

  private val replicationContext =
    ReplicationContext(
      false,
      connectionId,
      UUID.randomUUID(),
      UUID.randomUUID(),
      0,
      0,
      workspaceId,
      "",
      "",
      sourceDefinitionId,
      destinationDefinitionId,
    )

  @BeforeEach
  fun init() {
    every { clock.millis() } returns 1
    every { mapper.mapMessage(any()) } returnsArgument 0
  }

  @Test
  fun `test that we get all the streams if the exit code is 0 and no stream status is send`() {
    streamStatusCompletionTracker.startTracking(catalog, true)
    val result = streamStatusCompletionTracker.finalize(0, mapper)

    assertEquals(
      listOf(
        getStreamStatusCompletedMessage("name1"),
        getStreamStatusCompletedMessage("name2", "namespace2"),
      ),
      result,
    )
  }

  @Test
  fun `test that we get all the streams if the exit code is 0 and some stream status is send`() {
    streamStatusCompletionTracker.startTracking(catalog, true)
    streamStatusCompletionTracker.track(getStreamStatusCompletedMessage("name1").trace.streamStatus)
    val result = streamStatusCompletionTracker.finalize(0, mapper)

    assertEquals(
      listOf(
        getStreamStatusCompletedMessage("name1"),
        getStreamStatusCompletedMessage("name2", "namespace2"),
      ),
      result,
    )
  }

  @Test
  fun `test that we support multiple completed status`() {
    streamStatusCompletionTracker.startTracking(catalog, true)
    streamStatusCompletionTracker.track(getStreamStatusCompletedMessage("name1").trace.streamStatus)
    streamStatusCompletionTracker.track(getStreamStatusCompletedMessage("name1").trace.streamStatus)
    val result = streamStatusCompletionTracker.finalize(0, mapper)

    assertEquals(
      listOf(
        getStreamStatusCompletedMessage("name1"),
        getStreamStatusCompletedMessage("name2", "namespace2"),
      ),
      result,
    )
  }

  @Test
  fun `test that we get no streams if the exit code is 1 and no stream status is send`() {
    streamStatusCompletionTracker.startTracking(catalog, true)
    val result = streamStatusCompletionTracker.finalize(1, mapper)

    assertEquals(listOf<AirbyteMessage>(), result)
  }

  @Test
  fun `test that we get not stream status if the exit code is 1 even the source emitted some stream status`() {
    streamStatusCompletionTracker.startTracking(catalog, true)
    streamStatusCompletionTracker.track(getStreamStatusCompletedMessage("name1").trace.streamStatus)
    val result = streamStatusCompletionTracker.finalize(1, mapper)

    assertEquals(listOf<AirbyteMessage>(), result)
  }

  @Test
  fun `test that no message is send if the destination doesn't support refreshes`() {
    streamStatusCompletionTracker.startTracking(catalog, false)
    streamStatusCompletionTracker.track(getStreamStatusCompletedMessage("name1").trace.streamStatus)
    val result = streamStatusCompletionTracker.finalize(0, mapper)

    assertEquals(listOf<AirbyteMessage>(), result)
  }

  private fun getStreamStatusCompletedMessage(
    name: String,
    namespace: String? = null,
  ): AirbyteMessage {
    return AirbyteMessage()
      .withType(AirbyteMessage.Type.TRACE)
      .withTrace(
        AirbyteTraceMessage()
          .withType(AirbyteTraceMessage.Type.STREAM_STATUS)
          .withEmittedAt(1.0)
          .withStreamStatus(
            AirbyteStreamStatusTraceMessage()
              .withStatus(AirbyteStreamStatusTraceMessage.AirbyteStreamStatus.COMPLETE)
              .withStreamDescriptor(
                io.airbyte.protocol.models.StreamDescriptor()
                  .withName(name)
                  .withNamespace(namespace),
              ),
          ),
      )
  }
}
