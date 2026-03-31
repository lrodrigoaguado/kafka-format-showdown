/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.confluent.csta;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Properties;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringSerializer;

import io.confluent.kafka.serializers.KafkaAvroSerializer;

public class Producer {

    private static final String BOOTSTRAP_SERVERS = "localhost:9094,localhost:9096,localhost:9098";
    private static final String SCHEMA_REGISTRY_URL = "http://localhost:8082";
    private static final String DEFAULT_CLIENT_ID = "message-format-producer";
    private static final int DEFAULT_RECORD_COUNT = 50000;
    private static final int DEFAULT_LINGER_MS = 100; // 100ms for better batching/compression in demo
    private static final int DEFAULT_BATCH_SIZE = 16384; // 16KB - Kafka default
    private static final int DEFAULT_BUFFER_MEMORY = 33554432; // 32MB
    private static final int DEFAULT_COMPRESSION_LEVEL = -1; // -1 = use each codec's built-in default

    public static void main(String[] args) throws Exception {
        // Parse arguments: <format> <compression> <template> [recordCount] [lingerMs] [batchSize] [compressionLevel]
        if (args.length < 3) {
            System.err.println("Usage: Producer <FORMAT> <COMPRESSION> <HEADER_TEMPLATE+VALUE_TEMPLATE> [NUM_MESSAGES] [LINGER_MS] [BATCH_SIZE] [COMPRESSION_LEVEL]");
            System.err.println("  FORMAT: string, json, or avro");
            System.err.println("  COMPRESSION: none, gzip, snappy, lz4, or zstd");
            System.err.println("  TEMPLATE: header+value format (e.g., normal+medium-nested)");
            System.err.println("  NUM_MESSAGES: optional, default " + DEFAULT_RECORD_COUNT);
            System.err.println("  LINGER_MS: optional, default " + DEFAULT_LINGER_MS);
            System.err.println("  BATCH_SIZE: optional, default " + DEFAULT_BATCH_SIZE);
            System.err.println("  COMPRESSION_LEVEL: optional, codec default if omitted");
            System.err.println("    gzip: 1 (fastest) to 9 (smallest)");
            System.err.println("    lz4:  1 (fastest) to 17 (smallest)");
            System.err.println("    zstd: 1 (fastest) to 22 (smallest)");
            System.exit(1);
        }

        String format = args[0]; // string, json, avro
        String compression = args[1]; // none, gzip, snappy, lz4, zstd
        String templateName = args[2]; // header+value format, e.g., normal+medium-nested
        int recordCount = args.length > 3 ? Integer.parseInt(args[3]) : DEFAULT_RECORD_COUNT;
        int lingerMs = args.length > 4 ? Integer.parseInt(args[4]) : DEFAULT_LINGER_MS;
        int batchSize = args.length > 5 ? Integer.parseInt(args[5]) : DEFAULT_BATCH_SIZE;
        int compressionLevel = args.length > 6 ? Integer.parseInt(args[6]) : DEFAULT_COMPRESSION_LEVEL;
        String topicSafeTemplateName = templateName.replace("+", "-");
        // Include compression level in topic name when specified so different levels appear as separate series in Grafana
        String compressionPart = compression +
            (compressionLevel != DEFAULT_COMPRESSION_LEVEL ? "-l" + compressionLevel : "");
        String topic = format + "-" + compressionPart + "-" + topicSafeTemplateName;

        // Load template (now required)
        MessageTemplate template = new MessageTemplate(templateName);
        System.out.println("Using message template: " + templateName);

        Properties props = new Properties();
        props.setProperty(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        props.setProperty(ProducerConfig.CLIENT_ID_CONFIG, DEFAULT_CLIENT_ID + "-" + format + "-" + compression);
        props.setProperty(ProducerConfig.ACKS_CONFIG, "all");
        props.setProperty(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

        // Production tuning parameters
        props.setProperty(ProducerConfig.LINGER_MS_CONFIG, String.valueOf(lingerMs));
        props.setProperty(ProducerConfig.BATCH_SIZE_CONFIG, String.valueOf(batchSize));
        props.setProperty(ProducerConfig.BUFFER_MEMORY_CONFIG, String.valueOf(DEFAULT_BUFFER_MEMORY));
        props.setProperty(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, "5");

        System.out.println("Producer configuration:");
        System.out.println("  Linger MS: " + lingerMs);
        System.out.println("  Batch Size: " + batchSize + " bytes");

        // Set compression type and optional level
        if (!compression.equals("none")) {
            props.setProperty(ProducerConfig.COMPRESSION_TYPE_CONFIG, compression);
            if (compressionLevel != DEFAULT_COMPRESSION_LEVEL) {
                props.setProperty("compression.level", String.valueOf(compressionLevel));
                System.out.println("  Compression: " + compression + " (level " + compressionLevel + ")");
            } else {
                System.out.println("  Compression: " + compression + " (default level)");
            }
        } else {
            System.out.println("  Compression: none");
        }

        // Set value serializer based on format
        switch (format.toLowerCase()) {
            case "avro":
                props.setProperty(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaAvroSerializer.class.getName());
                props.setProperty("schema.registry.url", SCHEMA_REGISTRY_URL);
                System.out.println("  Serialization: Avro (Schema Registry: " + SCHEMA_REGISTRY_URL + ")");
                break;
            case "json":
            case "string":
            default:
                props.setProperty(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
                System.out.println("  Serialization: " + format);
                break;
        }

        System.out.println("\nStarting Producer:");
        System.out.println("  Topic: " + topic);
        System.out.println("  Records: " + recordCount);
        String[] templateParts = templateName.split("\\+", 2);
        System.out.println("  Header template: " + templateParts[0]);
        System.out.println("  Value template: " + templateParts[1]);
        System.out.println();

        // Ensure topic exists before producing
        ensureTopicExists(topic, props);

        long startTime = System.currentTimeMillis();

        try (KafkaProducer<String, Object> producer = new KafkaProducer<>(props)) {
            produceRecords(producer, topic, recordCount, template);
        }

        long duration = System.currentTimeMillis() - startTime;
        System.out.printf("Completed: %d records in %d ms (%.2f records/sec)%n",
            recordCount, duration, (recordCount * 1000.0) / duration);
    }

    private static void ensureTopicExists(String topic, Properties producerProps) {
        Properties adminProps = new Properties();
        adminProps.setProperty(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
            producerProps.getProperty(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG));

        try (AdminClient adminClient = AdminClient.create(adminProps)) {
            Set<String> existingTopics = adminClient.listTopics().names().get();

            if (existingTopics.contains(topic)) {
                System.out.println("Topic '" + topic + "' already exists.");
            } else {
                System.out.println("Topic '" + topic + "' does not exist. Creating...");

                // Create topic with 3 partitions and replication factor 3 to match cluster config
                NewTopic newTopic = new NewTopic(topic, 3, (short) 3);

                adminClient.createTopics(Collections.singleton(newTopic)).all().get();
                System.out.println("Topic '" + topic + "' created successfully.");
            }
        } catch (ExecutionException e) {
            System.err.println("Error checking/creating topic: " + e.getCause().getMessage());
            throw new RuntimeException("Failed to ensure topic exists", e);
        } catch (Exception e) {
            System.err.println("Error checking/creating topic: " + e.getMessage());
            throw new RuntimeException("Failed to ensure topic exists", e);
        }
    }

    public static void produceRecords(org.apache.kafka.clients.producer.Producer<String, Object> producer,
            String topic, int recordCount, MessageTemplate template) {

        int count = 0;
        int errors = 0;

        while (count < recordCount) {
            try {
                String key = "key-" + count;
                MessageTemplate.MessageData messageData = template.generate();

                // Create record with headers
                ProducerRecord<String, Object> record = new ProducerRecord<>(topic, null, key, messageData.getValue());

                // Add headers (use UTF_8 explicitly to avoid charset lookup overhead)
                messageData.getHeaders().forEach((headerKey, headerValue) -> {
                    record.headers().add(headerKey, headerValue.getBytes(StandardCharsets.UTF_8));
                });

                // Asynchronous send with callback for error handling
                final int recordNum = count;
                producer.send(record, (metadata, exception) -> {
                    if (exception != null) {
                        System.err.println("Error publishing record " + recordNum + ": " + exception.getMessage());
                    }
                });

                count++;
                if (count % 5000 == 0) {
                    System.out.printf("Progress: %,d / %,d records sent (%.1f%%)%n",
                        count, recordCount, (count * 100.0) / recordCount);
                }
            } catch (Exception e) {
                System.err.println("Error creating record " + count + ": " + e.getMessage());
                e.printStackTrace();
                errors++;
                if (errors > 100) {
                    System.err.println("Too many errors, stopping producer");
                    break;
                }
            }
        }

        // Flush to ensure all messages are sent before returning
        System.out.println("Flushing remaining messages...");
        producer.flush();
        System.out.println("All messages flushed successfully.");
    }

    // Used by unit tests via MockProducer<String, String>
    public static void produceRecords(org.apache.kafka.clients.producer.Producer<String, String> producer,
            String topic, int recordCount, Runnable postSendAction) {
        Random random = new Random();
        int count = 0;

        while (count < recordCount) {
            try {
                String randomString = generateRandomString(random);
                int partitionNumber = random.nextInt(2);

                Future<RecordMetadata> future = producer.send(new ProducerRecord<String, String>(topic,
                        partitionNumber, null, randomString));
                RecordMetadata metadata = future.get();
                System.out.printf("Published record: %s into partition: %d, offset: %d\n", randomString,
                        metadata.partition(), metadata.offset());

                if (postSendAction != null) {
                    postSendAction.run();
                }
                count++;
            } catch (Exception e) {
                e.printStackTrace();
                break;
            }
        }
    }

    private static String generateRandomString(Random random) {
        int length = 5 + random.nextInt(16); // 5 to 20 characters
        String characters = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(characters.charAt(random.nextInt(characters.length())));
        }
        return sb.toString();
    }
}
