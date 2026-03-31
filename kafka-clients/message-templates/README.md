# Custom Message Templates

This directory allows you to create custom message templates without rebuilding the JAR.

## Template Structure

Templates are split into **headers** and **values** as independent components. Place custom templates in the corresponding subdirectory:

```
message-templates/
  headers/       # Header template files
  values/        # Value template files
```

The producer combines them using `header+value` syntax:

```bash
mvn exec:java -Dexec.mainClass="io.confluent.csta.Producer" \
  -Dexec.args="json gzip my-headers+my-values 10000"
```

## Creating Custom Header Templates

Create a JSON file in `message-templates/headers/` with key-value pairs for Kafka record headers:

```json
{
  "event-type": "custom.event",
  "source": "my-service",
  "correlation-id": "{{random_uuid}}",
  "timestamp": "{{timestamp_ms}}"
}
```

## Creating Custom Value Templates

Create a JSON file in `message-templates/values/` with the message value structure:

```json
{
  "id": "{{counter}}",
  "name": "{{random_first_name}} {{random_last_name}}",
  "amount": "{{random_decimal:0:1000}}",
  "status": "{{random_choice:pending,approved,rejected}}"
}
```

### Using `repeat_array` for Repeated Elements

To generate arrays of repeated objects, use the `{{repeat_array:COUNT}}` placeholder with a sibling `_arrayItemTemplate`:

```json
{
  "batchId": "BATCH-{{counter}}",
  "readings": "{{repeat_array:20}}",
  "_arrayItemTemplate": {
    "ts": "{{timestamp_ms}}",
    "value": "{{random_decimal:0:100}}"
  }
}
```

The `_arrayItemTemplate` is processed COUNT times with fresh placeholder values each time, producing a JSON array. The `_arrayItemTemplate` key is removed from the final output.

## Available Placeholders

### Basic Generators

- `{{random_uuid}}` - Random UUID
- `{{timestamp_ms}}` - Current timestamp in milliseconds
- `{{counter}}` - Auto-incrementing counter (starts at 1)
- `{{random_boolean}}` - Random true/false

### Random Data

- `{{random_first_name}}` - Random first name
- `{{random_last_name}}` - Random last name
- `{{random_country}}` - Random country name
- `{{random_city}}` - Random US city
- `{{random_state}}` - Random US state code (e.g., "CA", "TX")
- `{{random_ip}}` - Random IPv4 address

### Numeric Generators

- `{{random_number}}` - Random number between 0 and 999
- `{{random_number:MIN:MAX}}` - Random number between MIN and MAX (inclusive)
- `{{random_decimal}}` - Random decimal between 0.00 and 100.00
- `{{random_decimal:MIN:MAX}}` - Random decimal between MIN and MAX

### Text and Binary Generators

- `{{lorem_text:WORD_COUNT}}` - Generate N words of quasi-natural English text (common + tech domain words). Highly compressible.
- `{{random_base64:BYTE_COUNT}}` - Generate BYTE_COUNT random bytes, base64-encoded. Essentially incompressible.

### Choice Generator

- `{{random_choice:option1,option2,option3}}` - Randomly picks one option

### Header-only Generators

- `{{repeat_headers:PREFIX:COUNT}}` - Generate COUNT headers named `PREFIX-001` through `PREFIX-N` with UUID values. Only works in header templates.

### Value-only Generators

- `{{repeat_array:COUNT}}` - Repeat sibling `_arrayItemTemplate` COUNT times as a JSON array. Only works in value templates.

## Quoting Rules

All placeholders must be quoted in JSON to ensure valid JSON syntax:

```json
{
  "id": "{{counter}}",
  "price": "{{random_decimal:10:100}}",
  "active": "{{random_boolean}}"
}
```

The system automatically removes quotes from numbers and booleans after processing, so the final message will have correct types.

## Template Loading Priority

The system loads templates in this order:

1. **External templates** - Files in `kafka-clients/message-templates/headers/` and `kafka-clients/message-templates/values/` (this directory)
2. **Built-in templates** - Packaged in the JAR at `src/main/resources/message-templates/headers/` and `values/`

This means you can override built-in templates by creating a file with the same name in this directory.

## Built-in Templates

### Headers

- `normal` (~350B) - 7 standard event metadata headers
- `big` (~3KB) - ~35 rich headers (tracing, auth, routing, compliance)
- `enormous` (~100KB) - ~1800 generated headers for extreme overhead testing

### Values

- `tiny-flat` (~60B) - Minimal numeric payload (IoT heartbeat)
- `small-flat` (~300B) - Flat key-value event (clickstream)
- `medium-nested` (~1.5KB) - Nested objects with mixed types (e-commerce order)
- `large-text` (~5-20KB) - Natural language text, highly compressible (support ticket)
- `wide-array` (~5-15KB) - Repeated array elements, very compressible (sensor batch)
- `high-entropy` (~5KB) - Random base64, incompressible (encrypted payload)
- `deep-nested` (~5KB) - 5-7 nesting levels (org hierarchy)

## Testing Your Template

1. Create your header and value JSON files in the appropriate subdirectories
2. Run the producer from the `kafka-clients` directory:

   ```bash
   cd kafka-clients
   mvn exec:java -Dexec.mainClass="io.confluent.csta.Producer" \
     -Dexec.args="json none my-headers+my-values 10"
   ```

3. Check the console output to verify the templates were loaded
4. Use the consumer to inspect the generated messages

## Troubleshooting

**Error: "Template name must be in 'header+value' format"**

- Ensure you use the `header+value` syntax, e.g., `normal+small-flat`

**Error: "Template not found"**

- Ensure header files are in `message-templates/headers/` and value files in `message-templates/values/`
- Check the filename matches exactly (case-sensitive, `.json` extension)
- Run from the `kafka-clients` directory

**Invalid JSON errors**

- Ensure all placeholders are quoted: `"{{placeholder}}"`
- Validate your JSON syntax using a JSON validator
- Check for trailing commas (not allowed in JSON)
