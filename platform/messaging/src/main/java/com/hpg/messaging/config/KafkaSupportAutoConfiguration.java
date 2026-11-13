package com.hpg.messaging.config;

import com.hpg.messaging.consumer.ConsumerProperties;
import com.hpg.messaging.consumer.DeadLetterRecoverer;
import com.hpg.messaging.consumer.JitteredExponentialBackOff;
import com.hpg.messaging.consumer.ProcessedEvents;
import com.hpg.messaging.consumer.ReceivedEvent;
import com.hpg.messaging.consumer.ReceivedEventDeserializer;
import com.hpg.common.correlation.CorrelationId;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaConnectionDetails;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.lang.NonNull;

import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Kafka producer and consumer setup shared by every service (docs/06-kafka-events.md §8).
 *
 * <p>Producer: {@code acks=all} with the idempotent producer, so an acknowledged event is on every
 * in-sync replica and the producer's own retries cannot duplicate or reorder it. {@code max.block.ms} is
 * short because the outbox relay holds a database transaction while it publishes.
 *
 * <p>Consumer: offsets are committed only after a record has been processed, so a crash replays it rather
 * than losing it; every consumer therefore deduplicates (see {@link ProcessedEvents}). A record that
 * keeps failing is retried with jittered backoff and then dead-lettered, instead of blocking its
 * partition for ever.
 */
@AutoConfiguration(before = KafkaAutoConfiguration.class)
@ConditionalOnClass(KafkaTemplate.class)
@EnableKafka
@EnableConfigurationProperties(ConsumerProperties.class)
public class KafkaSupportAutoConfiguration {

    /**
     * Where the brokers are. {@link KafkaProperties} holds what configuration says, but a
     * {@link KafkaConnectionDetails} bean may override it — that is how Testcontainers points a test at
     * the broker it just started, and how a platform can inject real endpoints. Building a client
     * straight from the properties ignores that, so both are consulted here.
     */
    private static Map<String, Object> withBrokers(Map<String, Object> config, List<String> bootstrapServers) {
        if (bootstrapServers != null && !bootstrapServers.isEmpty()) {
            config.put(org.apache.kafka.clients.CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        }
        return config;
    }

    @Bean
    @Primary
    public ProducerFactory<String, byte[]> hpgProducerFactory(KafkaProperties properties,
            ObjectProvider<KafkaConnectionDetails> connectionDetails) {
        Map<String, Object> config = withBrokers(new HashMap<>(properties.buildProducerProperties(null)),
                connectionDetails.getIfAvailable() == null ? null
                        : connectionDetails.getObject().getBootstrapServers());
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        config.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
        config.put(ProducerConfig.LINGER_MS_CONFIG, 5);
        // Fail fast rather than block the relay (and its database transaction) on an unreachable broker.
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5_000);
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5_000);
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10_000);
        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    @Primary
    public KafkaTemplate<String, byte[]> hpgKafkaTemplate(ProducerFactory<String, byte[]> factory) {
        return new KafkaTemplate<>(factory);
    }

    @Bean
    @ConditionalOnMissingBean
    public ConsumerFactory<String, ReceivedEvent> hpgConsumerFactory(KafkaProperties properties,
            ObjectProvider<KafkaConnectionDetails> connectionDetails) {
        Map<String, Object> config = withBrokers(new HashMap<>(properties.buildConsumerProperties(null)),
                connectionDetails.getIfAvailable() == null ? null
                        : connectionDetails.getObject().getConsumerBootstrapServers());
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        // ErrorHandlingDeserializer turns a parse failure into a record the error handler can dead-letter,
        // instead of an exception that stops the container and leaves the partition stuck.
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        config.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, ReceivedEventDeserializer.class);
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 50);
        return new DefaultKafkaConsumerFactory<>(config);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, ReceivedEvent> kafkaListenerContainerFactory(
            ConsumerFactory<String, ReceivedEvent> consumerFactory, KafkaProperties kafkaProperties,
            ConsumerProperties consumerProperties, KafkaTemplate<String, byte[]> template, MeterRegistry meters,
            @Value("${spring.application.name:hpg}") String applicationName) {
        ConcurrentKafkaListenerContainerFactory<String, ReceivedEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        // One thread per listener: these consumers maintain ordered state, and parallel threads within a
        // group would process a key's events out of order. Scale by adding partitions and replicas instead.
        factory.setConcurrency(1);
        factory.setAutoStartup(kafkaProperties.getListener().isAutoStartup());
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.getContainerProperties().setObservationEnabled(true);
        factory.setRecordInterceptor(correlationIdInterceptor());
        factory.setCommonErrorHandler(errorHandler(consumerProperties, template, meters, applicationName));
        return factory;
    }

    private DefaultErrorHandler errorHandler(ConsumerProperties properties, KafkaTemplate<String, byte[]> template,
            MeterRegistry meters, String applicationName) {
        DefaultErrorHandler handler = new DefaultErrorHandler(
                new DeadLetterRecoverer(template, meters, applicationName),
                new JitteredExponentialBackOff(properties.retryInitial().toMillis(), 2.0,
                        properties.retryMax().toMillis(), properties.retryAttempts()));
        // Nothing to gain from retrying a message that is simply wrong: dead-letter it immediately.
        handler.addNotRetryableExceptions(DeserializationException.class,
                ReceivedEventDeserializer.MalformedEventException.class,
                com.hpg.messaging.consumer.NonRetryableEventException.class);
        handler.setLogLevel(org.springframework.kafka.KafkaException.Level.WARN);
        return handler;
    }

    /** Carries the originating request's correlation id from the event into this service's logs. */
    private RecordInterceptor<String, ReceivedEvent> correlationIdInterceptor() {
        return new RecordInterceptor<>() {
            @Override
            public ConsumerRecord<String, ReceivedEvent> intercept(@NonNull ConsumerRecord<String, ReceivedEvent> record,
                    @NonNull Consumer<String, ReceivedEvent> consumer) {
                ReceivedEvent event = record.value();
                if (event != null && event.correlationId() != null) {
                    MDC.put(CorrelationId.MDC_KEY, event.correlationId());
                }
                return record;
            }

            @Override
            public void afterRecord(@NonNull ConsumerRecord<String, ReceivedEvent> record,
                    @NonNull Consumer<String, ReceivedEvent> consumer) {
                MDC.remove(CorrelationId.MDC_KEY);
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean
    public ProcessedEvents processedEvents(JdbcTemplate jdbcTemplate, Clock clock) {
        return new ProcessedEvents(jdbcTemplate, clock);
    }
}
