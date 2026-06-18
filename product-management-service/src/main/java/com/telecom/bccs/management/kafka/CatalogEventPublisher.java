package com.telecom.bccs.management.kafka;

import com.telecom.bccs.common.tracing.MdcConstants;
import com.telecom.bccs.management.model.CatalogChangeEvent;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Publishes catalog change events, propagating the current trace id / client id into the
 * Kafka record headers so the downstream cache-eviction is traceable end-to-end on Kibana.
 */
@Component
public class CatalogEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(CatalogEventPublisher.class);

    private final KafkaTemplate<String, CatalogChangeEvent> kafkaTemplate;
    private final String topic;

    public CatalogEventPublisher(KafkaTemplate<String, CatalogChangeEvent> kafkaTemplate,
                                 @Value("${app.kafka.topic.catalog-changes:catalog.changes}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    public void publish(CatalogChangeEvent event) {
        ProducerRecord<String, CatalogChangeEvent> record =
                new ProducerRecord<>(topic, event.entityId(), event);

        addHeader(record, MdcConstants.KAFKA_HEADER_TRACE_ID, MDC.get(MdcConstants.MDC_TRACE_ID));
        addHeader(record, MdcConstants.KAFKA_HEADER_CLIENT_ID, MDC.get(MdcConstants.MDC_CLIENT_ID));

        kafkaTemplate.send(record).whenComplete((result, ex) -> {
            if (ex != null) {
                // Event delivery failed — log loudly; catalog TTL still bounds staleness.
                log.error("Failed to publish catalog change event {} {}: {}",
                        event.entityType(), event.entityId(), ex.getMessage());
            } else {
                log.info("Published {} {} event for {}", event.changeType(),
                        event.entityType(), event.entityId());
            }
        });
    }

    private void addHeader(ProducerRecord<String, CatalogChangeEvent> record, String key, String value) {
        if (value != null) {
            record.headers().add(new RecordHeader(key, value.getBytes(StandardCharsets.UTF_8)));
        }
    }
}
