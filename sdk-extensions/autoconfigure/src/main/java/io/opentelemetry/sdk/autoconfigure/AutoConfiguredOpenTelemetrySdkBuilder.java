/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.sdk.autoconfigure;

import static java.util.Objects.requireNonNull;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.OpenTelemetrySdkBuilder;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.autoconfigure.spi.traces.SdkTracerProviderConfigurer;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.logs.SdkLogEmitterProvider;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/**
 * A builder for configuring auto-configuration of the OpenTelemetry SDK. Notably, auto-configured
 * components can be customized, for example by delegating to them from a wrapper that tweaks
 * behavior such as filtering out telemetry attributes.
 */
public final class AutoConfiguredOpenTelemetrySdkBuilder implements AutoConfigurationCustomizer {

  private static final Logger logger =
      Logger.getLogger(AutoConfiguredOpenTelemetrySdkBuilder.class.getName());

  @Nullable private ConfigProperties config;

  private BiFunction<SdkTracerProviderBuilder, ConfigProperties, SdkTracerProviderBuilder>
      tracerProviderCustomizer = (a, unused) -> a;
  private BiFunction<? super TextMapPropagator, ConfigProperties, ? extends TextMapPropagator>
      propagatorCustomizer = (a, unused) -> a;
  private BiFunction<? super SpanExporter, ConfigProperties, ? extends SpanExporter>
      spanExporterCustomizer = (a, unused) -> a;
  private BiFunction<? super Resource, ConfigProperties, ? extends Resource> resourceCustomizer =
      (a, unused) -> a;
  private BiFunction<? super Sampler, ConfigProperties, ? extends Sampler> samplerCustomizer =
      (a, unused) -> a;

  private Supplier<Map<String, String>> propertiesSupplier = Collections::emptyMap;

  private ClassLoader serviceClassLoader =
      AutoConfiguredOpenTelemetrySdkBuilder.class.getClassLoader();

  private boolean registerShutdownHook = true;

  private boolean setResultAsGlobal = true;

  private boolean customized;

  AutoConfiguredOpenTelemetrySdkBuilder() {}

  /**
   * Sets the {@link ConfigProperties} to use when resolving properties for auto-configuration.
   * {@link #addPropertiesSupplier(Supplier)} will have no effect if this method is used.
   */
  AutoConfiguredOpenTelemetrySdkBuilder setConfig(ConfigProperties config) {
    requireNonNull(config, "config");
    this.config = config;
    return this;
  }

  /**
   * Adds a {@link BiFunction} to invoke the with the {@link SdkTracerProviderBuilder} to allow
   * customization. The return value of the {@link BiFunction} will replace the passed-in argument.
   *
   * <p>Multiple calls will execute the customizers in order.
   */
  @Override
  public AutoConfiguredOpenTelemetrySdkBuilder addTracerProviderCustomizer(
      BiFunction<SdkTracerProviderBuilder, ConfigProperties, SdkTracerProviderBuilder>
          tracerProviderCustomizer) {
    requireNonNull(tracerProviderCustomizer, "tracerProviderCustomizer");
    this.tracerProviderCustomizer =
        mergeCustomizer(this.tracerProviderCustomizer, tracerProviderCustomizer);
    return this;
  }

  /**
   * Adds a {@link BiFunction} to invoke with the default autoconfigured {@link TextMapPropagator}
   * to allow customization. The return value of the {@link BiFunction} will replace the passed-in
   * argument.
   *
   * <p>Multiple calls will execute the customizers in order.
   */
  @Override
  public AutoConfiguredOpenTelemetrySdkBuilder addPropagatorCustomizer(
      BiFunction<? super TextMapPropagator, ConfigProperties, ? extends TextMapPropagator>
          propagatorCustomizer) {
    requireNonNull(propagatorCustomizer, "propagatorCustomizer");
    this.propagatorCustomizer = mergeCustomizer(this.propagatorCustomizer, propagatorCustomizer);
    return this;
  }

  /**
   * Adds a {@link BiFunction} to invoke with the default autoconfigured {@link Resource} to allow
   * customization. The return value of the {@link BiFunction} will replace the passed-in argument.
   *
   * <p>Multiple calls will execute the customizers in order.
   */
  @Override
  public AutoConfiguredOpenTelemetrySdkBuilder addResourceCustomizer(
      BiFunction<? super Resource, ConfigProperties, ? extends Resource> resourceCustomizer) {
    requireNonNull(resourceCustomizer, "resourceCustomizer");
    this.resourceCustomizer = mergeCustomizer(this.resourceCustomizer, resourceCustomizer);
    return this;
  }

  /**
   * Adds a {@link BiFunction} to invoke with the default autoconfigured {@link Sampler} to allow
   * customization. The return value of the {@link BiFunction} will replace the passed-in argument.
   *
   * <p>Multiple calls will execute the customizers in order.
   */
  @Override
  public AutoConfiguredOpenTelemetrySdkBuilder addSamplerCustomizer(
      BiFunction<? super Sampler, ConfigProperties, ? extends Sampler> samplerCustomizer) {
    requireNonNull(samplerCustomizer, "samplerCustomizer");
    this.samplerCustomizer = mergeCustomizer(this.samplerCustomizer, samplerCustomizer);
    return this;
  }

  /**
   * Adds a {@link BiFunction} to invoke with the default autoconfigured {@link SpanExporter} to
   * allow customization. The return value of the {@link BiFunction} will replace the passed-in
   * argument.
   *
   * <p>Multiple calls will execute the customizers in order.
   */
  @Override
  public AutoConfiguredOpenTelemetrySdkBuilder addSpanExporterCustomizer(
      BiFunction<? super SpanExporter, ConfigProperties, ? extends SpanExporter>
          spanExporterCustomizer) {
    requireNonNull(spanExporterCustomizer, "spanExporterCustomizer");
    this.spanExporterCustomizer =
        mergeCustomizer(this.spanExporterCustomizer, spanExporterCustomizer);
    return this;
  }

  /**
   * Adds a {@link Supplier} of a map of property names and values to use as defaults for the {@link
   * ConfigProperties} used during auto-configuration. The order of precedence of properties is
   * system properties > environment variables > the suppliers registered with this method.
   *
   * <p>Multiple calls will cause properties to be merged in order, with later ones overwriting
   * duplicate keys in earlier ones.
   */
  @Override
  public AutoConfiguredOpenTelemetrySdkBuilder addPropertiesSupplier(
      Supplier<Map<String, String>> propertiesSupplier) {
    requireNonNull(propertiesSupplier, "propertiesSupplier");
    this.propertiesSupplier = mergeProperties(this.propertiesSupplier, propertiesSupplier);
    return this;
  }

  /**
   * Control the registration of a shutdown hook to shut down the SDK when appropriate. By default,
   * the shutdown hook is registered.
   *
   * <p>Skipping the registration of the shutdown hook may cause unexpected behavior. This
   * configuration is for SDK consumers that require control over the SDK lifecycle. In this case,
   * alternatives must be provided by the SDK consumer to shut down the SDK.
   *
   * @param registerShutdownHook a boolean <code>true</code> will register the hook, otherwise
   *     <code>false</code> will skip registration.
   */
  public AutoConfiguredOpenTelemetrySdkBuilder registerShutdownHook(boolean registerShutdownHook) {
    this.registerShutdownHook = registerShutdownHook;
    return this;
  }

  /**
   * Sets whether the configured {@link OpenTelemetrySdk} should be set as the application's
   * {@linkplain io.opentelemetry.api.GlobalOpenTelemetry global} instance.
   */
  public AutoConfiguredOpenTelemetrySdkBuilder setResultAsGlobal(boolean setResultAsGlobal) {
    this.setResultAsGlobal = setResultAsGlobal;
    return this;
  }

  /** Sets the {@link ClassLoader} to be used to load SPI implementations. */
  public AutoConfiguredOpenTelemetrySdkBuilder setServiceClassLoader(
      ClassLoader serviceClassLoader) {
    requireNonNull(serviceClassLoader, "serviceClassLoader");
    this.serviceClassLoader = serviceClassLoader;
    return this;
  }

  /**
   * Returns a new {@link AutoConfiguredOpenTelemetrySdk} holding components auto-configured using
   * the settings of this {@link AutoConfiguredOpenTelemetrySdkBuilder}.
   */
  public AutoConfiguredOpenTelemetrySdk build() {
    if (!customized) {
      customized = true;
      mergeSdkTracerProviderConfigurer();
      for (AutoConfigurationCustomizerProvider customizer :
          ServiceLoader.load(AutoConfigurationCustomizerProvider.class, serviceClassLoader)) {
        customizer.customize(this);
      }
    }

    SdkTracerProviderBuilder tracerProviderBuilder = SdkTracerProvider.builder();
    ConfigProperties config = getConfig();

    Resource resource =
        ResourceConfiguration.configureResource(config, serviceClassLoader, resourceCustomizer);

    SdkMeterProvider meterProvider =
        MeterProviderConfiguration.configureMeterProvider(resource, config, serviceClassLoader);

    tracerProviderBuilder.setResource(resource);
    TracerProviderConfiguration.configureTracerProvider(
        tracerProviderBuilder,
        config,
        serviceClassLoader,
        meterProvider,
        spanExporterCustomizer,
        samplerCustomizer);
    tracerProviderBuilder = tracerProviderCustomizer.apply(tracerProviderBuilder, config);
    SdkTracerProvider tracerProvider = tracerProviderBuilder.build();

    SdkLogEmitterProvider logEmitterProvider =
        LogEmitterProviderConfiguration.configureLogEmitterProvider(
            resource, config, meterProvider);

    if (registerShutdownHook) {
      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(
                  () -> {
                    List<CompletableResultCode> shutdown = new ArrayList<>();
                    shutdown.add(tracerProvider.shutdown());
                    shutdown.add(meterProvider.shutdown());
                    shutdown.add(logEmitterProvider.shutdown());
                    CompletableResultCode.ofAll(shutdown).join(10, TimeUnit.SECONDS);
                  }));
    }

    ContextPropagators propagators =
        PropagatorConfiguration.configurePropagators(
            config, serviceClassLoader, propagatorCustomizer);

    OpenTelemetrySdkBuilder sdkBuilder =
        OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .setLogEmitterProvider(logEmitterProvider)
            .setMeterProvider(meterProvider)
            .setPropagators(propagators);

    OpenTelemetrySdk openTelemetrySdk = sdkBuilder.build();

    if (setResultAsGlobal) {
      GlobalOpenTelemetry.set(openTelemetrySdk);
      logger.log(
          Level.FINE, "Global OpenTelemetrySdk set to {0} by autoconfiguration", openTelemetrySdk);
    }

    return AutoConfiguredOpenTelemetrySdk.create(openTelemetrySdk, resource, config);
  }

  private void mergeSdkTracerProviderConfigurer() {
    for (SdkTracerProviderConfigurer configurer :
        ServiceLoader.load(SdkTracerProviderConfigurer.class, serviceClassLoader)) {
      addTracerProviderCustomizer(
          (builder, config) -> {
            configurer.configure(builder, config);
            return builder;
          });
    }
  }

  private ConfigProperties getConfig() {
    ConfigProperties config = this.config;
    if (config == null) {
      config = DefaultConfigProperties.get(propertiesSupplier.get());
    }
    return config;
  }

  private static <I, O1, O2> BiFunction<I, ConfigProperties, O2> mergeCustomizer(
      BiFunction<? super I, ConfigProperties, ? extends O1> first,
      BiFunction<? super O1, ConfigProperties, ? extends O2> second) {
    return (I configured, ConfigProperties config) -> {
      O1 firstResult = first.apply(configured, config);
      return second.apply(firstResult, config);
    };
  }

  private static Supplier<Map<String, String>> mergeProperties(
      Supplier<Map<String, String>> first, Supplier<Map<String, String>> second) {
    return () -> {
      Map<String, String> merged = new HashMap<>();
      merged.putAll(first.get());
      merged.putAll(second.get());
      return merged;
    };
  }
}
