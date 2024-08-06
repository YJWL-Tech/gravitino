/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.audit;

import com.datastrato.gravitino.exceptions.GravitinoRuntimeException;
import com.datastrato.gravitino.listener.EventListenerManager;
import com.datastrato.gravitino.listener.api.EventListenerPlugin;
import com.datastrato.gravitino.listener.api.event.Event;
import com.google.common.annotations.VisibleForTesting;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AuditLogManager {

  private static final Logger LOG = LoggerFactory.getLogger(AuditLogManager.class);

  @VisibleForTesting private AuditLogWriter auditLogWriter;

  public void init(Map<String, String> properties, EventListenerManager eventBusManager) {
    LOG.info("Audit log properties {}", properties);
    AuditLogConfig auditLogConfig = new AuditLogConfig(properties);
    if (!auditLogConfig.isAuditEnabled()) {
      LOG.warn("Audit log is not enabled");
      return;
    }

    LOG.info("Audit log config {}", auditLogConfig);
    String writerClassName = auditLogConfig.getWriterClassName();
    String formatterClassName = auditLogConfig.getAuditLogFormatterClassName();
    Formatter formatter = loadFormatter(formatterClassName);
    LOG.info("Audit log writer class name {}", writerClassName);
    if (StringUtils.isEmpty(writerClassName)) {
      throw new GravitinoRuntimeException("Audit log writer class is not configured");
    }

    auditLogWriter =
        loadAuditLogWriter(
            writerClassName, auditLogConfig.getWriterProperties(properties), formatter);

    eventBusManager.addEventListener(
        "audit-log",
        new EventListenerPlugin() {

          @Override
          public void init(Map<String, String> properties) throws RuntimeException {}

          @Override
          public void start() throws RuntimeException {}

          @Override
          public void stop() throws RuntimeException {
            auditLogWriter.close();
          }

          @Override
          public void onPostEvent(Event event) throws RuntimeException {
            try {
              auditLogWriter.write(event);
            } catch (Exception e) {
              throw new GravitinoRuntimeException(e, "Failed to write audit log");
            }
          }
        });
  }

  private AuditLogWriter loadAuditLogWriter(
      String className, Map<String, String> config, Formatter formatter) {
    try {
      AuditLogWriter auditLogWriter =
          (AuditLogWriter)
              Class.forName(className).getConstructor(Formatter.class).newInstance(formatter);
      auditLogWriter.init(config);
      return auditLogWriter;
    } catch (Exception e) {
      throw new GravitinoRuntimeException(e, "Failed to load audit log writer %s", className);
    }
  }

  private Formatter loadFormatter(String className) {
    try {
      return (Formatter) Class.forName(className).getDeclaredConstructor().newInstance();
    } catch (Exception e) {
      throw new GravitinoRuntimeException(e, "Failed to load formatter class name %s", className);
    }
  }

  AuditLogWriter getAuditLogWriter() {
    return auditLogWriter;
  }
}
