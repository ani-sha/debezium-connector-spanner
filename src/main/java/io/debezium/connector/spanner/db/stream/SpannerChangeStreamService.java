/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.spanner.db.stream;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.debezium.connector.spanner.db.dao.ChangeStreamDao;
import io.debezium.connector.spanner.db.dao.ChangeStreamResultSet;
import io.debezium.connector.spanner.db.mapper.ChangeStreamRecordMapper;
import io.debezium.connector.spanner.db.model.Partition;
import io.debezium.connector.spanner.db.model.event.ChangeStreamEvent;
import io.debezium.connector.spanner.db.model.event.ChildPartitionsEvent;
import io.debezium.connector.spanner.db.model.event.FinishPartitionEvent;
import io.debezium.connector.spanner.db.model.event.HeartbeatEvent;
import io.debezium.connector.spanner.metrics.MetricsEventPublisher;
import io.debezium.connector.spanner.metrics.event.DelayChangeStreamEventsMetricEvent;

/**
 * This class queries the change stream, sends child partitions to SynchronizedPartitionManager,
 * and updates the last commit timestamp for each partition.
 */
public class SpannerChangeStreamService {

    private static final Logger LOGGER = LoggerFactory.getLogger(SpannerChangeStreamService.class);

    private final ChangeStreamDao changeStreamDao;
    private final ChangeStreamRecordMapper changeStreamRecordMapper;

    private final Duration heartbeatMillis;
    private final MetricsEventPublisher metricsEventPublisher;

    public SpannerChangeStreamService(ChangeStreamDao changeStreamDao, ChangeStreamRecordMapper changeStreamRecordMapper,
                                      Duration heartbeatMillis, MetricsEventPublisher metricsEventPublisher) {
        this.changeStreamDao = changeStreamDao;
        this.changeStreamRecordMapper = changeStreamRecordMapper;
        this.heartbeatMillis = heartbeatMillis;
        this.metricsEventPublisher = metricsEventPublisher;
    }

    public void getEvents(Partition partition, ChangeStreamEventConsumer changeStreamEventConsumer,
                          PartitionEventListener partitionEventListener)
            throws InterruptedException {
        final String token = partition.getToken();

        partitionEventListener.onRun(partition);

        LOGGER.info("Streaming {} from {} to {}", token, partition.getStartTimestamp(), partition.getEndTimestamp());
        try (ChangeStreamResultSet resultSet = changeStreamDao.streamQuery(token, partition.getStartTimestamp(),
                partition.getEndTimestamp(), heartbeatMillis.toMillis())) {

            long start = now();
            while (resultSet.next()) {
                long delay = now() - start;

                List<ChangeStreamEvent> events = changeStreamRecordMapper.toChangeStreamEvents(partition,
                        resultSet.getCurrentRowAsStruct(), resultSet.getMetadata());
                LOGGER.debug("Events receive from stream: {}", events);

                if (!events.isEmpty() && (events.get(0) instanceof HeartbeatEvent)) {
                    var heartbeatEvent = (HeartbeatEvent) events.get(0);
                    long heartbeatLag = System.currentTimeMillis() - heartbeatEvent.getRecordTimestamp().toSqlTimestamp().toInstant().toEpochMilli();
                    if (heartbeatLag > 60_000) {
                        LOGGER.warn("heartbeat has very old timestamp, lag: {}, token: {}, event: {}", heartbeatLag,
                                heartbeatEvent.getMetadata().getPartitionToken(),
                                heartbeatEvent);
                    }
                }

                processEvents(partition, events, changeStreamEventConsumer);

                if (!events.isEmpty() && !(events.get(0) instanceof HeartbeatEvent)) {
                    metricsEventPublisher.publishMetricEvent(new DelayChangeStreamEventsMetricEvent((int) delay));
                }

                start = now();
            }
        }

        partitionEventListener.onFinish(partition);
        LOGGER.info("Finished consuming partition {}", partition);

        changeStreamEventConsumer.acceptChangeStreamEvent(new FinishPartitionEvent(partition));
    }

    private long now() {
        return Instant.now().toEpochMilli();
    }

    private void processEvents(Partition partition, List<ChangeStreamEvent> events,
                               ChangeStreamEventConsumer changeStreamEventConsumer)
            throws InterruptedException {
        for (final ChangeStreamEvent changeStreamEvent : events) {
            if (changeStreamEvent instanceof ChildPartitionsEvent) {
                ChildPartitionsEvent childPartitionsEvent = (ChildPartitionsEvent) changeStreamEvent;
                LOGGER.info("Received child partition from partition {}:{}", partition.getToken(), childPartitionsEvent);
            }
            LOGGER.debug("Received record from partition {}: {}", partition.getToken(), changeStreamEvent);

            changeStreamEventConsumer.acceptChangeStreamEvent(changeStreamEvent);
        }
    }

}
