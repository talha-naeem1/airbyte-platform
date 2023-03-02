/*
 * Copyright (c) 2023 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.commons.temporal;

import io.airbyte.api.client.invoker.generated.ApiException;
import io.airbyte.commons.temporal.scheduling.ConnectionNotificationWorkflow;
import io.airbyte.config.persistence.ConfigNotFoundException;
import io.airbyte.validation.json.JsonValidationException;
import io.temporal.client.WorkflowClient;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

/**
 * Utility functions for triggering a notification in temporal.
 */
@Singleton
@Slf4j
public class NotificationUtils {

  public NotificationUtils() {}

  /**
   * Trigger notification to the user using whatever notification settings they provided after
   * detecting a schema change.
   *
   * @param client workflow client
   * @param connectionId connection id
   * @param url url to the connection in the airbyte web app
   */
  public void sendSchemaChangeNotification(final WorkflowClient client, final UUID connectionId, final String url) {
    final ConnectionNotificationWorkflow notificationWorkflow =
        client.newWorkflowStub(ConnectionNotificationWorkflow.class, TemporalWorkflowUtils.buildWorkflowOptions(TemporalJobType.NOTIFY));
    try {
      notificationWorkflow.sendSchemaChangeNotification(connectionId, url);
    } catch (IOException | RuntimeException | InterruptedException | ApiException | ConfigNotFoundException | JsonValidationException e) {
      log.error("There was an error while sending a Schema Change Notification", e);
    }
  }

}
