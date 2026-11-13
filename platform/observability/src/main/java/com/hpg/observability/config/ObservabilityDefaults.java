package com.hpg.observability.config;

import org.springframework.boot.DefaultPropertiesPropertySource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Observability settings shared by every service, as <em>default</em> properties: a service's own
 * configuration or an environment variable always wins.
 *
 * <ul>
 *   <li>Tracing on for every request (trace and span ids appear in every log line); spans are exported to
 *       Jaeger only when {@code HPG_TRACE_EXPORT=true} (the {@code obs} Compose profile).</li>
 *   <li>Kafka listeners continue the trace carried in the {@code traceparent} header.</li>
 *   <li>HTTP latency histogram with SLO buckets 100/250/500/1000 ms.</li>
 *   <li>Structured JSON logs pass through the PII masking customizer.</li>
 * </ul>
 */
public class ObservabilityDefaults implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("management.tracing.sampling.probability", "${HPG_TRACE_SAMPLING:1.0}");
        defaults.put("management.otlp.tracing.endpoint", "${OTEL_EXPORTER_OTLP_TRACES_ENDPOINT:http://localhost:4318/v1/traces}");
        defaults.put("management.otlp.tracing.export.enabled", "${HPG_TRACE_EXPORT:false}");
        defaults.put("spring.kafka.listener.observation-enabled", "true");
        defaults.put("management.metrics.distribution.percentiles-histogram.http.server.requests", "true");
        defaults.put("management.metrics.distribution.slo.http.server.requests", "100ms,250ms,500ms,1s");
        defaults.put("logging.structured.json.customizer", "com.hpg.observability.logging.PiiMaskingJsonCustomizer");
        DefaultPropertiesPropertySource.addOrMerge(defaults, environment.getPropertySources());
    }
}
