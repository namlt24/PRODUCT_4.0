package com.telecom.bccs.catalog.kafka;

import com.telecom.bccs.catalog.model.CatalogChangeEvent;
import com.telecom.bccs.catalog.service.TwoLevelCacheService;
import com.telecom.bccs.common.tracing.MdcConstants;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Listens to {@code catalog.changes} and performs targeted cache invalidation so a write in
 * product-management-service is reflected here within the event latency, not the cache TTL.
 * The trace id carried in the Kafka record header is restored into the MDC so the eviction
 * log line is correlated with the originating write request on Kibana.
 */
@Component
public class CatalogChangeConsumer {

    private static final Logger log = LoggerFactory.getLogger(CatalogChangeConsumer.class);

    private final TwoLevelCacheService cache;

    public CatalogChangeConsumer(TwoLevelCacheService cache) {
        this.cache = cache;
    }

    @KafkaListener(topics = "${app.kafka.topic.catalog-changes:catalog.changes}",
            containerFactory = "kafkaListenerContainerFactory")
    public void onCatalogChange(ConsumerRecord<String, CatalogChangeEvent> record, Acknowledgment ack) {
        restoreTraceContext(record);
        try {
            CatalogChangeEvent event = record.value();
            if (event == null) {
                ack.acknowledge();
                return;
            }
            String namespace = switch (event.entityType()) {
                case "PRODUCT" -> "product";
                case "OFFER" -> "offer";
                default -> null;
            };
            if (namespace == null) {
                log.warn("Unknown entityType in catalog change event: {}", event.entityType());
            } else {
                cache.evict(namespace, event.entityId());
                log.info("Evicted cache {}:{} due to {} event", namespace, event.entityId(), event.changeType());
            }
            ack.acknowledge();   // commit only after successful handling
        } finally {
            MDC.clear();
        }
    }

    private void restoreTraceContext(ConsumerRecord<String, CatalogChangeEvent> record) {
        var traceHeader = record.headers().lastHeader(MdcConstants.KAFKA_HEADER_TRACE_ID);
        var clientHeader = record.headers().lastHeader(MdcConstants.KAFKA_HEADER_CLIENT_ID);
        if (traceHeader != null) {
            MDC.put(MdcConstants.MDC_TRACE_ID, new String(traceHeader.value(), StandardCharsets.UTF_8));
        }
        if (clientHeader != null) {
            MDC.put(MdcConstants.MDC_CLIENT_ID, new String(clientHeader.value(), StandardCharsets.UTF_8));
        }
        MDC.put(MdcConstants.MDC_SERVICE_NAME, "product-catalog-service");
    }
}
