package io.confluent.csta;

import java.time.Duration;
import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;

import io.confluent.kafka.serializers.KafkaAvroDeserializer;

public class Consumer {

    private static final String BOOTSTRAP_SERVERS = "localhost:9094,localhost:9096,localhost:9098";
    private static final String SCHEMA_REGISTRY_URL = "http://localhost:8082";
    private static final String DEFAULT_GROUP_ID = "message-format-consumer";

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: Consumer <TOPIC>");
            System.err.println("  TOPIC: Topic name to consume from (format is auto-detected from topic name)");
            System.err.println("  Example: json-gzip-normal.medium-nested");
            System.exit(1);
        }

        String topic = args[0];

        // Extract format from topic name (format is the first part before the first dash)
        // Topic format: {FORMAT}-{COMPRESSION}-{TEMPLATE}
        String format = topic.split("-")[0]; // json, avro, string
        String groupId = DEFAULT_GROUP_ID + "-" + topic;

        Properties props = new Properties();
        props.setProperty(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        props.setProperty(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.setProperty(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.setProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.setProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        props.setProperty(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG, "1000");

        // Set value deserializer based on format
        switch (format.toLowerCase()) {
            case "avro":
                props.setProperty(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, KafkaAvroDeserializer.class.getName());
                props.setProperty("schema.registry.url", SCHEMA_REGISTRY_URL);
                props.setProperty("specific.avro.reader", "false");
                System.out.println("Using Avro deserializer with Schema Registry: " + SCHEMA_REGISTRY_URL);
                break;
            case "json":
            case "string":
            default:
                props.setProperty(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
                System.out.println("Using String deserializer (format: " + format + ")");
                break;
        }

        System.out.println("Starting Consumer - Topic: " + topic + ", Group: " + groupId);

        try (KafkaConsumer<String, Object> consumer = new KafkaConsumer<>(props)) {
            consumeRecords(consumer, topic, () -> !Thread.currentThread().isInterrupted());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static <V> void consumeRecords(org.apache.kafka.clients.consumer.Consumer<String, V> consumer,
            String topic, java.util.function.BooleanSupplier loopCondition) {
        consumer.subscribe(Arrays.asList(topic));
        System.out.printf("Consumer processing topic: %s%n", topic);

        AtomicLong messageCount = new AtomicLong(0);
        long startTime = System.currentTimeMillis();
        int emptyPollCount = 0;
        final int MAX_EMPTY_POLLS = 3;

        while (loopCondition.getAsBoolean()) {
            ConsumerRecords<String, V> records = consumer.poll(Duration.ofMillis(1000));

            if (records.isEmpty()) {
                if (++emptyPollCount >= MAX_EMPTY_POLLS) {
                    System.out.println("No more messages available. Consumer exiting.");
                    break;
                }
                continue;
            }
            emptyPollCount = 0;

            for (ConsumerRecord<String, V> record : records) {
                long count = messageCount.incrementAndGet();

                // Show first message with headers for verification
                if (count == 1 && record.headers().iterator().hasNext()) {
                    System.out.println("\n=== Sample Message with Headers ===");
                    System.out.println("Headers:");
                    record.headers().forEach(header -> {
                        System.out.printf("  %s: %s%n", header.key(), new String(header.value()));
                    });
                    System.out.printf("Key: %s%n", record.key());
                    System.out.printf("Value: %s%n", record.value());
                    System.out.println("===================================\n");
                }

                if (count % 1000 == 0) {
                    long elapsed = System.currentTimeMillis() - startTime;
                    double rate = (count * 1000.0) / elapsed;
                    System.out.printf("Progress: %d records consumed (%.2f records/sec) - partition %d, offset %d%n",
                            count, rate, record.partition(), record.offset());
                }
            }
        }

        long totalTime = System.currentTimeMillis() - startTime;
        long total = messageCount.get();
        if (total > 0) {
            System.out.printf("Final stats: %d records in %d ms (%.2f records/sec)%n",
                    total, totalTime, (total * 1000.0) / totalTime);
        }
    }
}
