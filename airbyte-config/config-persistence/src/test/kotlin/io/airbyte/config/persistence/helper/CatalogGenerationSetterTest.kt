package io.airbyte.config.persistence.helper

import io.airbyte.config.AirbyteStream
import io.airbyte.config.ConfiguredAirbyteCatalog
import io.airbyte.config.ConfiguredAirbyteStream
import io.airbyte.config.DestinationSyncMode
import io.airbyte.config.RefreshStream
import io.airbyte.config.StreamDescriptor
import io.airbyte.config.SyncMode
import io.airbyte.config.persistence.domain.Generation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class CatalogGenerationSetterTest {
  private val catalogGenerationSetter = CatalogGenerationSetter()

  private val catalog =
    ConfiguredAirbyteCatalog().withStreams(
      listOf(
        ConfiguredAirbyteStream().withStream(
          AirbyteStream()
            .withName("name1")
            .withNamespace("namespace1"),
        ),
        ConfiguredAirbyteStream().withStream(
          AirbyteStream()
            .withName("name2")
            .withNamespace("namespace2"),
        ),
      ),
    )

  private val generations =
    listOf(
      Generation(
        streamName = "name1",
        streamNamespace = "namespace1",
        generationId = 1L,
      ),
      Generation(
        streamName = "name2",
        streamNamespace = "namespace1",
        generationId = 3L,
      ),
      Generation(
        streamName = "name2",
        streamNamespace = "namespace2",
        generationId = 2L,
      ),
    )

  val jobId = 3L
  val connectionId = UUID.randomUUID()

  @BeforeEach
  fun init() {
  }

  @Test
  fun `test that no refresh truncation is performed if there is no refresh`() {
    val updatedCatalog =
      catalogGenerationSetter.updateCatalogWithGenerationAndSyncInformation(
        catalog = catalog,
        jobId = jobId,
        streamRefreshes = listOf(),
        generations = generations,
      )

    updatedCatalog.streams.forEach {
      assertEquals(0L, it.minimumGenerationId)
      assertEquals(jobId, it.syncId)
    }
  }

  @Test
  fun `test that truncation are properly requested`() {
    val updatedCatalog =
      catalogGenerationSetter.updateCatalogWithGenerationAndSyncInformation(
        catalog = catalog,
        jobId = jobId,
        streamRefreshes =
          listOf(
            RefreshStream()
              .withRefreshType(RefreshStream.RefreshType.TRUNCATE)
              .withStreamDescriptor(StreamDescriptor().withName("name1").withNamespace("namespace1")),
            RefreshStream()
              .withRefreshType(RefreshStream.RefreshType.TRUNCATE)
              .withStreamDescriptor(StreamDescriptor().withName("name2").withNamespace("namespace2")),
          ),
        generations = generations,
      )

    updatedCatalog.streams.forEach {
      assertEquals(it.generationId, it.minimumGenerationId)
      assertEquals(jobId, it.syncId)
    }
  }

  @Test
  fun `test that truncation are properly requested when partial`() {
    val updatedCatalog =
      catalogGenerationSetter.updateCatalogWithGenerationAndSyncInformation(
        catalog = catalog,
        jobId = jobId,
        streamRefreshes =
          listOf(
            RefreshStream()
              .withRefreshType(RefreshStream.RefreshType.TRUNCATE)
              .withStreamDescriptor(StreamDescriptor().withName("name1").withNamespace("namespace1")),
          ),
        generations = generations,
      )

    updatedCatalog.streams.forEach {
      if (it.stream.name == "name1" && it.stream.namespace == "namespace1") {
        assertEquals(it.generationId, it.minimumGenerationId)
        assertEquals(jobId, it.syncId)
        assertEquals(1L, it.generationId)
      } else {
        assertEquals(0L, it.minimumGenerationId)
        assertEquals(jobId, it.syncId)
        assertEquals(2L, it.generationId)
      }
    }
  }

  @Test
  fun `test that min gen is 0 for merge`() {
    val updatedCatalog =
      catalogGenerationSetter.updateCatalogWithGenerationAndSyncInformation(
        catalog = catalog,
        jobId = jobId,
        streamRefreshes =
          listOf(
            RefreshStream()
              .withRefreshType(RefreshStream.RefreshType.MERGE)
              .withStreamDescriptor(StreamDescriptor().withName("name1").withNamespace("namespace1")),
          ),
        generations = generations,
      )

    updatedCatalog.streams.forEach {
      if (it.stream.name == "name1" && it.stream.namespace == "namespace1") {
        assertEquals(0L, it.minimumGenerationId)
        assertEquals(jobId, it.syncId)
        assertEquals(1L, it.generationId)
      } else {
        assertEquals(0L, it.minimumGenerationId)
        assertEquals(jobId, it.syncId)
        assertEquals(2L, it.generationId)
      }
    }
  }

  @Test
  fun `test that min gen is 0 for full refresh overwrite and overwrite dedup`() {
    val catalog =
      ConfiguredAirbyteCatalog().withStreams(
        listOf(
          ConfiguredAirbyteStream().withStream(
            AirbyteStream()
              .withName("name1")
              .withNamespace("namespace1"),
          )
            .withSyncMode(SyncMode.FULL_REFRESH)
            .withDestinationSyncMode(DestinationSyncMode.OVERWRITE),
          ConfiguredAirbyteStream().withStream(
            AirbyteStream()
              .withName("name2")
              .withNamespace("namespace2"),
          )
            .withSyncMode(SyncMode.FULL_REFRESH)
            .withDestinationSyncMode(DestinationSyncMode.APPEND),
          ConfiguredAirbyteStream().withStream(
            AirbyteStream()
              .withName("name2")
              .withNamespace("namespace1"),
          )
            .withSyncMode(SyncMode.FULL_REFRESH)
            .withDestinationSyncMode(DestinationSyncMode.OVERWRITE_DEDUP),
        ),
      )

    val updatedCatalog =
      catalogGenerationSetter.updateCatalogWithGenerationAndSyncInformation(
        catalog = catalog,
        jobId = jobId,
        streamRefreshes = listOf(),
        generations = generations,
      )

    assertEquals(3, updatedCatalog.streams.size)
    updatedCatalog.streams.forEach {
      if (it.stream.name == "name1" && it.stream.namespace == "namespace1") {
        assertEquals(1L, it.minimumGenerationId)
        assertEquals(jobId, it.syncId)
        assertEquals(1L, it.generationId)
      } else if (it.stream.name == "name2" && it.stream.namespace == "namespace1") {
        assertEquals(3L, it.minimumGenerationId)
        assertEquals(jobId, it.syncId)
        assertEquals(3L, it.generationId)
      } else {
        assertEquals(0L, it.minimumGenerationId)
        assertEquals(jobId, it.syncId)
        assertEquals(2L, it.generationId)
      }
    }
  }

  @Test
  fun `test that min gen is current gen for clear`() {
    val catalog =
      ConfiguredAirbyteCatalog().withStreams(
        listOf(
          ConfiguredAirbyteStream().withStream(
            AirbyteStream()
              .withName("name1")
              .withNamespace("namespace1"),
          )
            .withSyncMode(SyncMode.INCREMENTAL)
            .withDestinationSyncMode(DestinationSyncMode.APPEND),
          ConfiguredAirbyteStream().withStream(
            AirbyteStream()
              .withName("name2")
              .withNamespace("namespace2"),
          )
            .withSyncMode(SyncMode.FULL_REFRESH)
            .withDestinationSyncMode(DestinationSyncMode.OVERWRITE),
        ),
      )

    val updatedCatalog =
      catalogGenerationSetter.updateCatalogWithGenerationAndSyncInformationForClear(
        catalog = catalog,
        jobId = jobId,
        clearedStream =
          setOf(
            StreamDescriptor().withName("name1").withNamespace("namespace1"),
          ),
        generations = generations,
      )

    updatedCatalog.streams.forEach {
      if (it.stream.name == "name1" && it.stream.namespace == "namespace1") {
        assertEquals(1L, it.minimumGenerationId)
        assertEquals(jobId, it.syncId)
        assertEquals(1L, it.generationId)
      } else {
        assertEquals(0L, it.minimumGenerationId)
        assertEquals(jobId, it.syncId)
        assertEquals(2L, it.generationId)
      }
    }
  }
}
