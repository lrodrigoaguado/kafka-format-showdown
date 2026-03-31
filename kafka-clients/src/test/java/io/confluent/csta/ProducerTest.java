package io.confluent.csta;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.Test;

public class ProducerTest {

    @Test
    public void testProduceRecords() {
        // Use Kafka's MockProducer
        MockProducer<String, String> mockProducer = new MockProducer<>(true, null, new StringSerializer(),
                new StringSerializer());

        String topic = "test-queues";
        // produced 10 records, NO DELAY
        Producer.produceRecords(mockProducer, topic, 10, () -> {
        });

        // Verify history
        assertEquals(10, mockProducer.history().size());
        assertEquals(topic, mockProducer.history().get(0).topic());

        // Verify value content (length between 5 and 20)
        String value = mockProducer.history().get(0).value();
        assertTrue("Value should be alphanumeric and length between 5 and 20. Actual: " + value,
                value.length() >= 5 && value.length() <= 20 && value.matches("[a-zA-Z0-9]+"));
    }
}
