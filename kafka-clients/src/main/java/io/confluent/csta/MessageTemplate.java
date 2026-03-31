package io.confluent.csta;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.Locale;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class MessageTemplate {

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{([^}]+)\\}\\}");
    private static final Pattern REPEAT_HEADERS_PATTERN = Pattern.compile("\\{\\{repeat_headers:([^:]+):(\\d+)\\}\\}");
    private static final Pattern REPEAT_ARRAY_PATTERN = Pattern.compile("\\{\\{repeat_array:(\\d+)\\}\\}");
    private static final Random random = new Random();
    private static final AtomicLong counter = new AtomicLong(1);

    // Sample data for realistic generation
    private static final String[] FIRST_NAMES = {"John", "Jane", "Michael", "Emily", "David", "Sarah", "Robert", "Lisa", "James", "Maria"};
    private static final String[] LAST_NAMES = {"Smith", "Johnson", "Williams", "Brown", "Jones", "Garcia", "Miller", "Davis", "Rodriguez", "Martinez"};
    private static final String[] COUNTRIES = {"USA", "Canada", "UK", "Germany", "France", "Spain", "Italy", "Japan", "Australia", "Brazil"};
    private static final String[] CITIES = {"New York", "Los Angeles", "Chicago", "Houston", "Phoenix", "Philadelphia", "San Antonio", "San Diego", "Dallas", "San Jose"};
    private static final String[] STATES = {"CA", "TX", "FL", "NY", "PA", "IL", "OH", "GA", "NC", "MI"};

    // Word list for lorem_text generation -- mix of common English and tech domain terms
    private static final String[] LOREM_WORDS = {
        "the", "be", "to", "of", "and", "a", "in", "that", "have", "it",
        "for", "not", "on", "with", "he", "as", "you", "do", "at", "this",
        "but", "his", "by", "from", "they", "we", "say", "her", "she", "or",
        "an", "will", "my", "one", "all", "would", "there", "their", "what",
        "so", "up", "out", "if", "about", "who", "get", "which", "go", "me",
        "when", "make", "can", "like", "time", "no", "just", "him", "know",
        "take", "people", "into", "year", "your", "good", "some", "could",
        "them", "see", "other", "than", "then", "now", "look", "only", "come",
        "its", "over", "think", "also", "back", "after", "use", "two", "how",
        "our", "work", "first", "well", "way", "even", "new", "want", "because",
        "any", "these", "give", "day", "most", "us", "system", "data", "service",
        "error", "process", "request", "response", "server", "client", "network",
        "connection", "timeout", "failure", "retry", "configuration", "deployment",
        "monitoring", "performance", "throughput", "latency", "cluster", "node",
        "partition", "offset", "consumer", "producer", "broker", "topic", "message",
        "schema", "registry", "pipeline", "stream", "event", "record", "batch",
        "buffer", "cache", "queue", "log", "metric", "alert", "dashboard",
        "container", "instance", "replica", "load", "balance", "health", "check",
        "endpoint", "gateway", "proxy", "middleware", "handler", "controller",
        "repository", "database", "query", "index", "table", "column", "row",
        "transaction", "commit", "rollback", "snapshot", "backup", "restore",
        "encrypt", "decrypt", "token", "session", "authentication", "authorization",
        "certificate", "key", "secret", "vault", "policy", "role", "permission",
        "tenant", "workspace", "organization", "account", "user", "profile",
        "notification", "webhook", "callback", "subscription", "publish", "consume",
        "serialize", "deserialize", "compress", "decompress", "encode", "decode",
        "validate", "transform", "aggregate", "filter", "route", "dispatch",
        "schedule", "trigger", "execute", "monitor", "trace", "debug", "analyze",
        "optimize", "scale", "migrate", "upgrade", "provision", "configure",
        "initialize", "terminate", "restart", "recover", "failover", "replicate"
    };

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final String templateName;
    private final JsonNode headersTemplate;
    private final JsonNode valueTemplate;

    public MessageTemplate(String templateName) throws Exception {
        this.templateName = templateName;

        String[] parts = templateName.split("\\+", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException(
                "Template name must be in 'header+value' format, e.g., 'normal+medium-nested'. Got: " + templateName);
        }

        this.headersTemplate = loadTemplateFile("headers/" + parts[0]);
        this.valueTemplate = loadTemplateFile("values/" + parts[1]);
    }

    private JsonNode loadTemplateFile(String relativePath) throws Exception {
        // Try external directory first (allows users to add custom templates)
        Path externalPath = Paths.get("message-templates", relativePath + ".json");
        if (Files.exists(externalPath)) {
            System.out.println("Loading template from: " + externalPath.toAbsolutePath());
            String content = Files.readString(externalPath);
            return objectMapper.readTree(content);
        }

        // Fall back to built-in templates from classpath resources
        String resourcePath = "/message-templates/" + relativePath + ".json";
        InputStream inputStream = getClass().getResourceAsStream(resourcePath);

        if (inputStream == null) {
            throw new IllegalArgumentException(
                "Template not found: " + relativePath + "\n" +
                "Looked in:\n" +
                "  1. External: " + externalPath.toAbsolutePath() + "\n" +
                "  2. Built-in: " + resourcePath
            );
        }

        System.out.println("Loading built-in template: " + relativePath);
        return objectMapper.readTree(new String(inputStream.readAllBytes()));
    }

    public MessageData generate() throws Exception {
        MessageData data = new MessageData();

        // Process headers
        headersTemplate.fields().forEachRemaining(entry -> {
            String rawValue = entry.getValue().asText();

            // Handle repeat_headers directive
            Matcher repeatMatcher = REPEAT_HEADERS_PATTERN.matcher(rawValue);
            if (repeatMatcher.matches()) {
                String prefix = repeatMatcher.group(1);
                int count = Integer.parseInt(repeatMatcher.group(2));
                for (int i = 1; i <= count; i++) {
                    data.addHeader(
                        String.format("%s-%03d", prefix, i),
                        UUID.randomUUID().toString()
                    );
                }
                return; // Skip adding the __generate key itself
            }

            String value = processPlaceholders(entry.getValue().asText());
            data.addHeader(entry.getKey(), value);
        });

        // Process value
        String valueJson = processValueNode(valueTemplate);
        data.setValue(valueJson);

        return data;
    }

    private String processValueNode(JsonNode valueNode) throws Exception {
        if (!valueNode.isObject()) {
            return processPlaceholders(valueNode.toString());
        }

        // Check for _arrayItemTemplate and repeat_array pattern
        JsonNode arrayItemTemplate = valueNode.get("_arrayItemTemplate");

        if (arrayItemTemplate == null) {
            // No repeat_array, process normally
            return processPlaceholders(valueNode.toString());
        }

        // Build a new object, handling repeat_array fields specially
        ObjectNode processed = objectMapper.createObjectNode();
        Iterator<Map.Entry<String, JsonNode>> fields = valueNode.fields();

        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();

            // Skip the template definition itself
            if (entry.getKey().equals("_arrayItemTemplate")) {
                continue;
            }

            // Check if this field's value is a repeat_array placeholder
            if (entry.getValue().isTextual()) {
                Matcher repeatMatcher = REPEAT_ARRAY_PATTERN.matcher(entry.getValue().asText());
                if (repeatMatcher.matches()) {
                    int count = Integer.parseInt(repeatMatcher.group(1));
                    ArrayNode array = objectMapper.createArrayNode();
                    for (int i = 0; i < count; i++) {
                        String itemJson = processPlaceholders(arrayItemTemplate.toString());
                        array.add(objectMapper.readTree(itemJson));
                    }
                    processed.set(entry.getKey(), array);
                    continue;
                }
            }

            // Normal field -- copy as-is (will be processed at string level below)
            processed.set(entry.getKey(), entry.getValue());
        }

        return processPlaceholders(processed.toString());
    }

    private String processPlaceholders(String input) {
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(input);
        StringBuffer result = new StringBuffer();

        while (matcher.find()) {
            String placeholder = matcher.group(1);
            String replacement = generateValue(placeholder);
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);

        // Post-process: remove quotes around numbers and booleans
        String output = result.toString();
        // Remove quotes around integers and decimals
        output = output.replaceAll("\"(-?\\d+\\.?\\d*)\"", "$1");
        // Remove quotes around booleans
        output = output.replaceAll("\"(true|false)\"", "$1");

        return output;
    }

    private String generateValue(String placeholder) {
        String[] parts = placeholder.split(":");
        String function = parts[0];

        switch (function) {
            case "random_uuid":
                return UUID.randomUUID().toString();

            case "timestamp_ms":
                return String.valueOf(System.currentTimeMillis());

            case "counter":
                return String.valueOf(counter.getAndIncrement());

            case "random_boolean":
                return String.valueOf(random.nextBoolean());

            case "random_first_name":
                return FIRST_NAMES[random.nextInt(FIRST_NAMES.length)];

            case "random_last_name":
                return LAST_NAMES[random.nextInt(LAST_NAMES.length)];

            case "random_country":
                return COUNTRIES[random.nextInt(COUNTRIES.length)];

            case "random_city":
                return CITIES[random.nextInt(CITIES.length)];

            case "random_state":
                return STATES[random.nextInt(STATES.length)];

            case "random_ip":
                return random.nextInt(256) + "." + random.nextInt(256) + "." +
                       random.nextInt(256) + "." + random.nextInt(256);

            case "random_number":
                if (parts.length == 3) {
                    int min = Integer.parseInt(parts[1]);
                    int max = Integer.parseInt(parts[2]);
                    return String.valueOf(min + random.nextInt(max - min + 1));
                }
                return String.valueOf(random.nextInt(1000));

            case "random_decimal":
                if (parts.length == 3) {
                    double min = Double.parseDouble(parts[1]);
                    double max = Double.parseDouble(parts[2]);
                    double value = min + (max - min) * random.nextDouble();
                    return String.format(Locale.ROOT, "%.2f", value);
                }
                return String.format(Locale.ROOT, "%.2f", random.nextDouble() * 100);

            case "random_choice":
                if (parts.length > 1) {
                    String[] choices = parts[1].split(",");
                    return choices[random.nextInt(choices.length)];
                }
                return "";

            case "lorem_text":
                int wordCount = parts.length > 1 ? Integer.parseInt(parts[1]) : 50;
                return generateLoremText(wordCount);

            case "random_base64":
                int byteCount = parts.length > 1 ? Integer.parseInt(parts[1]) : 64;
                return generateRandomBase64(byteCount);

            default:
                return placeholder;
        }
    }

    private static String generateLoremText(int wordCount) {
        StringBuilder sb = new StringBuilder();
        int nextPeriod = 8 + random.nextInt(7); // Period every 8-15 words

        for (int i = 0; i < wordCount; i++) {
            String word = LOREM_WORDS[random.nextInt(LOREM_WORDS.length)];

            // Capitalize first word or word after a period
            if (i == 0 || (sb.length() > 1 && sb.charAt(sb.length() - 1) == ' '
                    && sb.length() > 2 && sb.charAt(sb.length() - 2) == '.')) {
                word = word.substring(0, 1).toUpperCase() + word.substring(1);
            }

            if (i > 0) {
                sb.append(' ');
            }
            sb.append(word);

            if (i == nextPeriod && i < wordCount - 1) {
                sb.append('.');
                nextPeriod = i + 8 + random.nextInt(7);
            }
        }

        if (sb.charAt(sb.length() - 1) != '.') {
            sb.append('.');
        }
        return sb.toString();
    }

    private static String generateRandomBase64(int byteCount) {
        byte[] bytes = new byte[byteCount];
        random.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    public String getTemplateName() {
        return templateName;
    }

    public static class MessageData {
        private final Map<String, String> headers = new HashMap<>();
        private String value;

        public void addHeader(String key, String value) {
            headers.put(key, value);
        }

        public Map<String, String> getHeaders() {
            return headers;
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }
    }
}
