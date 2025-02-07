/*
 * Copyright (c) 2020-2024 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.commons.server.converters;

import io.airbyte.api.model.generated.AttemptFailureSummary;
import io.airbyte.api.model.generated.AttemptInfoRead;
import io.airbyte.api.model.generated.AttemptRead;
import io.airbyte.api.model.generated.AttemptStats;
import io.airbyte.api.model.generated.AttemptStatus;
import io.airbyte.api.model.generated.AttemptStreamStats;
import io.airbyte.api.model.generated.DestinationDefinitionRead;
import io.airbyte.api.model.generated.FailureOrigin;
import io.airbyte.api.model.generated.FailureReason;
import io.airbyte.api.model.generated.FailureType;
import io.airbyte.api.model.generated.JobConfigType;
import io.airbyte.api.model.generated.JobDebugRead;
import io.airbyte.api.model.generated.JobInfoLightRead;
import io.airbyte.api.model.generated.JobInfoRead;
import io.airbyte.api.model.generated.JobOptionalRead;
import io.airbyte.api.model.generated.JobRead;
import io.airbyte.api.model.generated.JobRefreshConfig;
import io.airbyte.api.model.generated.JobStatus;
import io.airbyte.api.model.generated.JobWithAttemptsRead;
import io.airbyte.api.model.generated.LogRead;
import io.airbyte.api.model.generated.ResetConfig;
import io.airbyte.api.model.generated.SourceDefinitionRead;
import io.airbyte.api.model.generated.StreamDescriptor;
import io.airbyte.api.model.generated.SynchronousJobRead;
import io.airbyte.commons.converters.ApiConverters;
import io.airbyte.commons.enums.Enums;
import io.airbyte.commons.server.scheduler.SynchronousJobMetadata;
import io.airbyte.commons.server.scheduler.SynchronousResponse;
import io.airbyte.commons.version.AirbyteVersion;
import io.airbyte.config.Configs.WorkerEnvironment;
import io.airbyte.config.JobConfig.ConfigType;
import io.airbyte.config.JobConfigProxy;
import io.airbyte.config.JobOutput;
import io.airbyte.config.ResetSourceConfiguration;
import io.airbyte.config.StandardSyncOutput;
import io.airbyte.config.StandardSyncSummary;
import io.airbyte.config.StreamSyncStats;
import io.airbyte.config.SyncStats;
import io.airbyte.config.helpers.LogClientSingleton;
import io.airbyte.config.helpers.LogConfigs;
import io.airbyte.featureflag.FeatureFlagClient;
import io.airbyte.persistence.job.models.Attempt;
import io.airbyte.persistence.job.models.Job;
import jakarta.annotation.Nullable;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Convert between API and internal versions of job models.
 */
@Singleton
public class JobConverter {

  private final WorkerEnvironment workerEnvironment;
  private final LogConfigs logConfigs;
  private final FeatureFlagClient featureFlagClient;

  public JobConverter(final WorkerEnvironment workerEnvironment,
                      final LogConfigs logConfigs,
                      final FeatureFlagClient featureFlagClient) {
    this.workerEnvironment = workerEnvironment;
    this.logConfigs = logConfigs;
    this.featureFlagClient = featureFlagClient;
  }

  public JobInfoRead getJobInfoRead(final Job job) {
    return new JobInfoRead()
        .job(getJobWithAttemptsRead(job).getJob())
        .attempts(job.getAttempts().stream().map(this::getAttemptInfoRead).collect(Collectors.toList()));
  }

  public JobInfoLightRead getJobInfoLightRead(final Job job) {
    return new JobInfoLightRead().job(getJobRead(job));
  }

  public JobOptionalRead getJobOptionalRead(final Job job) {
    return new JobOptionalRead().job(getJobRead(job));
  }

  public static JobDebugRead getDebugJobInfoRead(final JobInfoRead jobInfoRead,
                                                 final SourceDefinitionRead sourceDefinitionRead,
                                                 final DestinationDefinitionRead destinationDefinitionRead,
                                                 final AirbyteVersion airbyteVersion) {
    return new JobDebugRead()
        .id(jobInfoRead.getJob().getId())
        .configId(jobInfoRead.getJob().getConfigId())
        .configType(jobInfoRead.getJob().getConfigType())
        .status(jobInfoRead.getJob().getStatus())
        .airbyteVersion(airbyteVersion.serialize())
        .sourceDefinition(sourceDefinitionRead)
        .destinationDefinition(destinationDefinitionRead);
  }

  public static JobWithAttemptsRead getJobWithAttemptsRead(final Job job) {
    return new JobWithAttemptsRead()
        .job(getJobRead(job))
        .attempts(job.getAttempts().stream()
            .sorted(Comparator.comparingInt(Attempt::getAttemptNumber))
            .map(JobConverter::getAttemptRead)
            .toList());
  }

  public static JobRead getJobRead(final Job job) {
    final String configId = job.getScope();
    final JobConfigType configType = Enums.convertTo(job.getConfigType(), JobConfigType.class);

    return new JobRead()
        .id(job.getId())
        .configId(configId)
        .configType(configType)
        .enabledStreams(extractEnabledStreams(job))
        .resetConfig(extractResetConfigIfReset(job).orElse(null))
        .refreshConfig(extractRefreshConfigIfNeeded(job).orElse(null))
        .createdAt(job.getCreatedAtInSecond())
        .updatedAt(job.getUpdatedAtInSecond())
        .startedAt(job.getStartedAtInSecond().isPresent() ? job.getStartedAtInSecond().get() : null)
        .status(Enums.convertTo(job.getStatus(), JobStatus.class));
  }

  /**
   * If the job type is REFRESH or CLEAR/RESET, extracts the streams from the job config. Otherwise,
   * returns null.
   *
   * @param job - job
   * @return List of the streams associated with the job
   */
  public static List<io.airbyte.protocol.models.StreamDescriptor> getStreamsAssociatedWithJob(final Job job) {
    final JobRead jobRead = getJobRead(job);
    switch (job.getConfigType()) {
      case REFRESH -> {
        return jobRead.getRefreshConfig().getStreamsToRefresh().stream().map(streamDescriptor -> new io.airbyte.protocol.models.StreamDescriptor()
            .withName(streamDescriptor.getName())
            .withNamespace(streamDescriptor.getNamespace())).collect(Collectors.toList());
      }
      case CLEAR, RESET_CONNECTION -> {
        return jobRead.getResetConfig().getStreamsToReset().stream().map(streamDescriptor -> new io.airbyte.protocol.models.StreamDescriptor()
            .withName(streamDescriptor.getName())
            .withNamespace(streamDescriptor.getNamespace())).collect(Collectors.toList());
      }
      default -> {
        return null;
      }
    }
  }

  /**
   * If the job is of type RESET, extracts the part of the reset config that we expose in the API.
   * Otherwise, returns empty optional.
   *
   * @param job - job
   * @return api representation of reset config
   */
  private static Optional<ResetConfig> extractResetConfigIfReset(final Job job) {
    if (job.getConfigType() == ConfigType.RESET_CONNECTION) {
      final ResetSourceConfiguration resetSourceConfiguration = job.getConfig().getResetConnection().getResetSourceConfiguration();
      if (resetSourceConfiguration == null) {
        return Optional.empty();
      }
      return Optional.ofNullable(
          new ResetConfig().streamsToReset(job.getConfig().getResetConnection().getResetSourceConfiguration().getStreamsToReset()
              .stream()
              .map(ApiConverters::toApi)
              .toList()));
    } else {
      return Optional.empty();
    }
  }

  /**
   * If the job is of type RESET, extracts the part of the reset config that we expose in the API.
   * Otherwise, returns empty optional.
   *
   * @param job - job
   * @return api representation of refresh config
   */
  public static Optional<JobRefreshConfig> extractRefreshConfigIfNeeded(final Job job) {
    if (job.getConfigType() == ConfigType.REFRESH) {
      final List<StreamDescriptor> refreshedStreams = job.getConfig().getRefresh().getStreamsToRefresh()
          .stream().flatMap(refreshStream -> Stream.ofNullable(refreshStream.getStreamDescriptor()))
          .map(ApiConverters::toApi)
          .toList();
      if (refreshedStreams == null || refreshedStreams.isEmpty()) {
        return Optional.empty();
      }
      return Optional.ofNullable(new JobRefreshConfig().streamsToRefresh(refreshedStreams));
    } else {
      return Optional.empty();
    }
  }

  public AttemptInfoRead getAttemptInfoRead(final Attempt attempt) {
    return new AttemptInfoRead()
        .attempt(getAttemptRead(attempt))
        .logs(getLogRead(attempt.getLogPath()));
  }

  public static AttemptInfoRead getAttemptInfoWithoutLogsRead(final Attempt attempt) {
    return new AttemptInfoRead()
        .attempt(getAttemptRead(attempt));
  }

  public static AttemptRead getAttemptRead(final Attempt attempt) {
    return new AttemptRead()
        .id((long) attempt.getAttemptNumber())
        .status(Enums.convertTo(attempt.getStatus(), AttemptStatus.class))
        .bytesSynced(attempt.getOutput() // TODO (parker) remove after frontend switches to totalStats
            .map(JobOutput::getSync)
            .map(StandardSyncOutput::getStandardSyncSummary)
            .map(StandardSyncSummary::getBytesSynced)
            .orElse(null))
        .recordsSynced(attempt.getOutput() // TODO (parker) remove after frontend switches to totalStats
            .map(JobOutput::getSync)
            .map(StandardSyncOutput::getStandardSyncSummary)
            .map(StandardSyncSummary::getRecordsSynced)
            .orElse(null))
        .totalStats(getTotalAttemptStats(attempt))
        .streamStats(getAttemptStreamStats(attempt))
        .createdAt(attempt.getCreatedAtInSecond())
        .updatedAt(attempt.getUpdatedAtInSecond())
        .endedAt(attempt.getEndedAtInSecond().orElse(null))
        .failureSummary(getAttemptFailureSummary(attempt));
  }

  private static AttemptStats getTotalAttemptStats(final Attempt attempt) {
    final SyncStats totalStats = attempt.getOutput()
        .map(JobOutput::getSync)
        .map(StandardSyncOutput::getStandardSyncSummary)
        .map(StandardSyncSummary::getTotalStats)
        .orElse(null);

    if (totalStats == null) {
      return null;
    }

    return new AttemptStats()
        .bytesEmitted(totalStats.getBytesEmitted())
        .recordsEmitted(totalStats.getRecordsEmitted())
        .stateMessagesEmitted(totalStats.getSourceStateMessagesEmitted())
        .recordsCommitted(totalStats.getRecordsCommitted());
  }

  private static List<AttemptStreamStats> getAttemptStreamStats(final Attempt attempt) {
    final List<StreamSyncStats> streamStats = attempt.getOutput()
        .map(JobOutput::getSync)
        .map(StandardSyncOutput::getStandardSyncSummary)
        .map(StandardSyncSummary::getStreamStats)
        .orElse(null);

    if (streamStats == null) {
      return null;
    }

    return streamStats.stream()
        .map(streamStat -> new AttemptStreamStats()
            .streamName(streamStat.getStreamName())
            .stats(new AttemptStats()
                .bytesEmitted(streamStat.getStats().getBytesEmitted())
                .recordsEmitted(streamStat.getStats().getRecordsEmitted())
                .stateMessagesEmitted(streamStat.getStats().getSourceStateMessagesEmitted())
                .recordsCommitted(streamStat.getStats().getRecordsCommitted())))
        .collect(Collectors.toList());
  }

  private static AttemptFailureSummary getAttemptFailureSummary(final Attempt attempt) {
    final io.airbyte.config.AttemptFailureSummary failureSummary = attempt.getFailureSummary().orElse(null);

    if (failureSummary == null) {
      return null;
    }
    return new AttemptFailureSummary()
        .failures(failureSummary.getFailures().stream()
            .map(failureReason -> getFailureReason(failureReason, TimeUnit.SECONDS.toMillis(attempt.getUpdatedAtInSecond())))
            .toList())
        .partialSuccess(failureSummary.getPartialSuccess());
  }

  public LogRead getLogRead(final Path logPath) {
    try {
      return new LogRead().logLines(LogClientSingleton.getInstance().getJobLogFile(workerEnvironment, logConfigs, logPath, featureFlagClient));
    } catch (final IOException e) {
      throw new RuntimeException(e);
    }
  }

  private static FailureReason getFailureReason(final @Nullable io.airbyte.config.FailureReason failureReason, final long defaultTimestamp) {
    if (failureReason == null) {
      return null;
    }
    return new FailureReason()
        .failureOrigin(Enums.convertTo(failureReason.getFailureOrigin(), FailureOrigin.class))
        .failureType(Enums.convertTo(failureReason.getFailureType(), FailureType.class))
        .externalMessage(failureReason.getExternalMessage())
        .internalMessage(failureReason.getInternalMessage())
        .stacktrace(failureReason.getStacktrace())
        .timestamp(failureReason.getTimestamp() != null ? failureReason.getTimestamp() : defaultTimestamp)
        .retryable(failureReason.getRetryable());
  }

  public SynchronousJobRead getSynchronousJobRead(final SynchronousResponse<?> response) {
    return getSynchronousJobRead(response.getMetadata());
  }

  public SynchronousJobRead getSynchronousJobRead(final SynchronousJobMetadata metadata) {
    final JobConfigType configType = Enums.convertTo(metadata.getConfigType(), JobConfigType.class);

    return new SynchronousJobRead()
        .id(metadata.getId())
        .configType(configType)
        .configId(String.valueOf(metadata.getConfigId()))
        .createdAt(metadata.getCreatedAt())
        .endedAt(metadata.getEndedAt())
        .succeeded(metadata.isSucceeded())
        .connectorConfigurationUpdated(metadata.isConnectorConfigurationUpdated())
        .logs(getLogRead(metadata.getLogPath()))
        .failureReason(getFailureReason(metadata.getFailureReason(), TimeUnit.SECONDS.toMillis(metadata.getEndedAt())));
  }

  private static List<StreamDescriptor> extractEnabledStreams(final Job job) {
    final var configuredCatalog = new JobConfigProxy(job.getConfig()).getConfiguredCatalog();
    return configuredCatalog != null
        ? configuredCatalog.getStreams().stream()
            .map(s -> new StreamDescriptor().name(s.getStream().getName()).namespace(s.getStream().getNamespace())).collect(Collectors.toList())
        : List.of();
  }

}
