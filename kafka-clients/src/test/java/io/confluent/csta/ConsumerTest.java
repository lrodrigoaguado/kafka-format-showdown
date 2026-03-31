package io.confluent.csta;

import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.TopicPartition;
import org.junit.Test;

public class ConsumerTest {

    @Test
    public void testConsumeRecords() {
        // Use Kafka's MockConsumer
        MockConsumer<String, String> mockConsumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST);

        String topic = "test-queues";
        TopicPartition tp = new TopicPartition(topic, 0);

        // Schedule records to be returned by poll
        mockConsumer.schedulePollTask(() -> {
            mockConsumer.rebalance(Collections.singletonList(tp));
            mockConsumer.updateBeginningOffsets(new HashMap<TopicPartition, Long>() {
                {
                    put(tp, 0L);
                }
            });

            ConsumerRecord<String, String> record = new ConsumerRecord<>(topic, 0, 0L, "key", "value");
            mockConsumer.addRecord(record);
        });

        // Control the loop: run 2 iterations
        AtomicInteger counter = new AtomicInteger(0);
        Consumer.consumeRecords(mockConsumer, topic, () -> counter.getAndIncrement() < 2);

        // Verify that we subscribed
        assertTrue(mockConsumer.subscription().contains(topic));

        // No threads needed! Test is deterministic and fast.
    }
}
