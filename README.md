# Kafka Format Showdown: Kafka Message Format Performance Comparison

This demo showcases the performance differences between various message formats (String, JSON, Avro) and compression algorithms (none, gzip, snappy, lz4, zstd) in Apache Kafka.

## Overview

Compare message throughput, storage efficiency, and resource utilization across different serialization formats and compression types. This hands-on demo helps you understand the trade-offs between:

- **Serialization formats**: String, JSON, and Avro (with Schema Registry)
- **Compression types**: None, gzip, snappy, lz4, and zstd

## Architecture

**Cluster Configuration:**

- **3 brokers** in KRaft mode
- **3 partitions** per topic (default)
- **Replication factor 3** for all topics
- **Min ISR: 2** for durability

**Monitoring Configuration:**

- **JMX Exporter** on all brokers
- **JMX Exporter** on Schema Registry
- **Prometheus** scraping metrics every 10s
- **Grafana** pre-configured with Kafka dashboards

## Prerequisites

- Docker and Docker Compose installed
- Java 21 or higher (for running kafka-clients)
- Maven 3.6+ (for building kafka-clients)

## Quick Start

### 1. Start the Environment

```bash
docker-compose up -d
```

All services should show "healthy" or "running" status.

### 2. Build the Kafka Clients

```bash
cd kafka-clients
mvn clean package
cd ..
```

## Monitoring and Management

The demo includes production-grade monitoring and management tools to observe cluster performance and manage Kafka resources.

### Access Monitoring and Management UIs

Once the cluster is up and running, you can access:

1. **Control Center**: http://localhost:9021
2. **Grafana Dashboard**: http://localhost:3000/d/message-format-comparison/message-format-and-compression-comparison?orgId=1&from=now-15m&to=now&timezone=browser&refresh=10s (login with user="admin", password="prom-operator")
3. **Prometheus UI**: http://localhost:9090
4. **Alertmanager**: http://localhost:9093


## Producing data for the Performance Tests

The producer and the consumer are Java clients that you can invoke using Maven. To run the producer, move to the kafka-clients folder and use:

```bash
mvn exec:java -Dexec.mainClass="io.confluent.csta.Producer" \
  -Dexec.args="{FORMAT} {COMPRESSION} {HEADER_TEMPLATE}+{VALUE_TEMPLATE} [{NUM_MESSAGES}] [{LINGER_MS}] [{BATCH_SIZE}] [{COMPRESSION_LEVEL}]"
```

where the terms in curly braces represent the parameters.

### Required Arguments

- **{FORMAT}**: Serialization format - `string`, `json`, or `avro`
- **{COMPRESSION}**: Compression type - `none`, `gzip`, `snappy`, `lz4`, or `zstd`
- **{HEADER_TEMPLATE}+{VALUE_TEMPLATE}**: Message template in `header+value` format. Headers and values are independent template components that you mix and match.
  - **Header options**: `normal` (~350B), `big` (~3KB), `enormous` (~100KB)
  - **Value options**: `tiny-flat` (~60B), `small-flat` (~300B), `medium-nested` (~1.5KB), `large-text` (~5-20KB), `wide-array` (~5-15KB), `high-entropy` (~5KB), `deep-nested` (~5KB)
  - Example: `normal+medium-nested`, `big+large-text`, `enormous+high-entropy`

### Optional Arguments

- **{NUM_MESSAGES}**: Number of messages to produce (default: `50000`)
- **{LINGER_MS}**: Producer linger time in milliseconds (default: `100`)
  - Controls batching delay - higher values = better batching = better compression
  - Production values: 0-100ms (low latency) or 100-1000ms (high throughput)
- **{BATCH_SIZE}**: Maximum batch size in bytes (default: `16384` = 16KB)
  - Larger batches = better compression ratio
  - Production values: 16KB (default), 32KB, 64KB, 100KB
- **{COMPRESSION_LEVEL}**: How hard the codec works to compress (omit to use each codec's built-in default)
  - Higher levels = smaller output, more CPU; lower levels = faster, less compression
  - `gzip`: 1 (fastest) – 9 (smallest), default 6
  - `lz4`: 1 (fastest) – 17 (smallest), default 9
  - `zstd`: 1 (fastest) – 22 (smallest), default 3
  - Ignored for `none` and `snappy`
  - When specified, the level is embedded in the topic name (e.g., `json-gzip-l1-normal-large-text`) so different levels appear as separate series in Grafana automatically

For example, to produce 50000 messages in JSON format with normal headers and medium-nested values, compressed using Gzip:

```bash
mvn exec:java -Dexec.mainClass="io.confluent.csta.Producer" \
  -Dexec.args="json gzip normal+medium-nested 50000"
```

The messages will automatically be published in a topic with the name `{FORMAT}-{COMPRESSION}-{HEADER_TEMPLATE}-{VALUE_TEMPLATE}` (e.g., `json-gzip-normal-medium-nested`).

You can easily compare the differences between different configurations by invoking the Producer several times in for loops:

```bash
# Test 1: Compression algorithm comparison with compressible vs incompressible data
for compression in none gzip lz4 zstd; do
  for value in large-text high-entropy; do
    mvn exec:java -Dexec.mainClass="io.confluent.csta.Producer" \
      -Dexec.args="json $compression normal+$value"
    sleep 5
  done
done
```

```bash
# Test 2: Header overhead impact (same value, different header sizes)
for headers in normal big enormous; do
  mvn exec:java -Dexec.mainClass="io.confluent.csta.Producer" \
    -Dexec.args="json gzip ${headers}+small-flat"
done
```

```bash
# Test 3: Format efficiency by message structure (JSON vs Avro)
for value in tiny-flat medium-nested wide-array deep-nested; do
  for format in json avro; do
    mvn exec:java -Dexec.mainClass="io.confluent.csta.Producer" \
      -Dexec.args="$format zstd normal+${value}"
  done
done
```

```bash
# Test 4: Message size impact on throughput
for value in tiny-flat small-flat medium-nested large-text; do
  mvn exec:java -Dexec.mainClass="io.confluent.csta.Producer" \
    -Dexec.args="json gzip normal+${value}"
done
```

```bash
# Test 5: Compression level — speed vs ratio trade-off (uses large-text for maximum visibility)
for level in 1 6 9; do
  mvn exec:java -Dexec.mainClass="io.confluent.csta.Producer" \
    -Dexec.args="json gzip normal+large-text 50000 100 16384 $level"
done
```

## Consuming and Inspecting Messages

After producing messages, you can consume them to verify the content and observe the throughput. The consumer automatically detects the message format from the topic name:

```bash
mvn exec:java -Dexec.mainClass="io.confluent.csta.Consumer" \
  -Dexec.args="{TOPIC}"
```

### Examples

```bash
# Consume messages produced with JSON format and gzip compression
mvn exec:java -Dexec.mainClass="io.confluent.csta.Consumer" \
  -Dexec.args="json-gzip-normal-medium-nested"

# Consume Avro messages (format auto-detected as 'avro' from topic name)
mvn exec:java -Dexec.mainClass="io.confluent.csta.Consumer" \
  -Dexec.args="avro-zstd-normal-tiny-flat"

# Consume uncompressed JSON messages
mvn exec:java -Dexec.mainClass="io.confluent.csta.Consumer" \
  -Dexec.args="json-none-normal-high-entropy"
```

**Note**: The consumer automatically extracts the serialization format from the topic name prefix (`json`, `avro`, or `string`) and configures the appropriate deserializer.

### Consumer Output

The consumer displays:
- **First message**: Full headers and value for verification
- **Progress updates**: Every 1,000 records with consumption rate
- **Final statistics**: Total records consumed and average throughput

The consumer automatically exits once all available messages have been consumed. Press `Ctrl+C` to stop early.

<details>
<summary><h2>Message Templates</h2></summary>

Templates are split into **headers** and **values** so you can independently control two key performance dimensions: header overhead and value structure/size.

### Header Templates

| Template | Headers | Approx Size | Description |
|----------|---------|-------------|-------------|
| `normal` | 7 standard headers | ~350B | Typical event metadata: event-type, source, trace-id, region |
| `big` | ~35 rich headers | ~3KB | Observability-heavy: tracing, auth, tenant, routing, compliance |
| `enormous` | ~1800 generated headers | ~100KB | Extreme case showing header overhead dominance |

### Value Templates

Each template targets a specific performance dimension, so different combinations produce meaningfully different compression ratios, batch sizes, and throughput:

| Template | ~Size | Compressibility | Real-world Analog | Why It's Different |
|----------|-------|-----------------|-------------------|--------------------|
| `tiny-flat` | 60B | Negligible | IoT heartbeat, metric tick | Per-message overhead dominates; compression can't help |
| `small-flat` | 300B | Low-moderate | Clickstream event, audit entry | Baseline flat structure; unique tokens (UUIDs, IPs) limit compression |
| `medium-nested` | 1.5KB | Moderate | E-commerce order, CRM update | Mixed types + nesting; shows JSON field-name overhead vs Avro |
| `large-text` | 5-20KB | **Very high** | Support ticket, log aggregation, LLM prompt log | Natural language compresses 3-5x; biggest compression ratio difference |
| `wide-array` | 5-15KB | **Very high** | Batch sensor readings, time-series window | Repeated homogeneous objects; extremely compressible |
| `high-entropy` | 5KB | **Near zero** | Encrypted payload, base64 blob | Random data is incompressible; shows when compression wastes CPU |
| `deep-nested` | 5KB | High | Config snapshot, org hierarchy tree | 5-7 nesting levels; JSON brace/quote overhead adds up; Avro shines |

### Creating Custom Templates

You can create your own production-like custom templates without rebuilding the JAR by placing files in the `kafka-clients/message-templates/` directory:

1. **Create a header template** in `kafka-clients/message-templates/headers/my-headers.json`:

   ```json
   {
     "event-type": "custom.event",
     "source": "my-service",
     "timestamp": "{{timestamp_ms}}"
   }
   ```

2. **Create a value template** in `kafka-clients/message-templates/values/my-values.json`:

   ```json
   {
     "id": "{{counter}}",
     "name": "{{random_first_name}} {{random_last_name}}",
     "amount": "{{random_decimal:0:1000}}"
   }
   ```

3. **Use your custom templates**:

   ```bash
   mvn exec:java -Dexec.mainClass="io.confluent.csta.Producer" \
     -Dexec.args="json gzip my-headers+my-values 10000"
   ```

### Available Placeholders

| Placeholder | Description |
|-------------|-------------|
| `{{random_uuid}}` | Random UUID |
| `{{timestamp_ms}}` | Current timestamp in milliseconds |
| `{{counter}}` | Auto-incrementing counter (starts at 1) |
| `{{random_boolean}}` | Random true/false |
| `{{random_first_name}}` | Random first name |
| `{{random_last_name}}` | Random last name |
| `{{random_country}}` | Random country name |
| `{{random_city}}` | Random US city |
| `{{random_state}}` | Random US state code |
| `{{random_ip}}` | Random IPv4 address |
| `{{random_number:MIN:MAX}}` | Random integer between MIN and MAX |
| `{{random_decimal:MIN:MAX}}` | Random decimal between MIN and MAX |
| `{{random_choice:a,b,c}}` | Random pick from comma-separated options |
| `{{lorem_text:WORD_COUNT}}` | Generate N words of quasi-natural English text |
| `{{random_base64:BYTE_COUNT}}` | Random bytes, base64-encoded (high entropy) |
| `{{repeat_headers:PREFIX:COUNT}}` | Generate COUNT headers named PREFIX-001..N (headers only) |
| `{{repeat_array:COUNT}}` | Repeat sibling `_arrayItemTemplate` COUNT times as array (values only) |

For complete documentation and examples, see [kafka-clients/message-templates/README.md](kafka-clients/message-templates/README.md).

</details>

## Clean Up
```bash
# Stop and remove all containers, networks, and volumes
docker-compose down -v
```
The `-v` flag removes volumes to ensure a clean state for the next run.

## References
- [Apache Kafka Documentation](https://kafka.apache.org/documentation/)
- [Confluent Schema Registry](https://docs.confluent.io/platform/current/schema-registry/index.html)
- [Kafka Compression Comparison](https://blog.cloudflare.com/squeezing-the-firehose/)
- [Confluent Best Practices](https://docs.confluent.io/platform/current/installation/configuration/producer-configs.html#compression-type)
