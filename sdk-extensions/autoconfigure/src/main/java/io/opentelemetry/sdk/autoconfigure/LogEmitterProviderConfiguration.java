/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.sdk.autoconfigure;

import static io.opentelemetry.sdk.autoconfigure.LogExporterConfiguration.configureLogExporters;

import io.opentelemetry.api.metrics.MeterProvider;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.logs.LogProcessor;
import io.opentelemetry.sdk.logs.SdkLogEmitterProvider;
import io.opentelemetry.sdk.logs.SdkLogEmitterProviderBuilder;
import io.opentelemetry.sdk.logs.export.BatchLogProcessor;
import io.opentelemetry.sdk.logs.export.LogExporter;
import io.opentelemetry.sdk.logs.export.SimpleLogProcessor;
import io.opentelemetry.sdk.resources.Resource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class LogEmitterProviderConfiguration {

  static SdkLogEmitterProvider configureLogEmitterProvider(
      Resource resource, ConfigProperties config, MeterProvider meterProvider) {
    SdkLogEmitterProviderBuilder builder = SdkLogEmitterProvider.builder().setResource(resource);

    Map<String, LogExporter> exportersByName = configureLogExporters(config, meterProvider);

    configureLogProcessors(exportersByName, meterProvider).forEach(builder::addLogProcessor);

    return builder.build();
  }

  // Visible for testing
  static List<LogProcessor> configureLogProcessors(
      Map<String, LogExporter> exportersByName, MeterProvider meterProvider) {
    Map<String, LogExporter> exportersByNameCopy = new HashMap<>(exportersByName);
    List<LogProcessor> logProcessors = new ArrayList<>();

    LogExporter exporter = exportersByNameCopy.remove("logging");
    if (exporter != null) {
      logProcessors.add(SimpleLogProcessor.create(exporter));
    }

    if (!exportersByNameCopy.isEmpty()) {
      LogExporter compositeLogExporter = LogExporter.composite(exportersByNameCopy.values());
      logProcessors.add(
          BatchLogProcessor.builder(compositeLogExporter).setMeterProvider(meterProvider).build());
    }

    return logProcessors;
  }

  private LogEmitterProviderConfiguration() {}
}
