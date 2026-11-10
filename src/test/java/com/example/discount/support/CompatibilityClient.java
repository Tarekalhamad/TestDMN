package com.example.discount.support;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.example.discount.rules.RuleTables;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;

import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Talks to the running service the way a caller would, and validates every response against the
 * published JSON Schema on the way past &mdash; so no test can pass on a response that a real
 * caller would reject.
 */
public class CompatibilityClient {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final TestRestTemplate rest;
    private final Schema responseSchema;

    public CompatibilityClient(TestRestTemplate rest) {
        this.rest = rest;
        this.responseSchema = load("/static/schemas/compatibility-response.schema.json");
    }

    private static Schema load(String resource) {
        try (InputStream is = CompatibilityClient.class.getResourceAsStream(resource)) {
            if (is == null) {
                throw new IllegalStateException("Contract schema missing from the classpath: " + resource);
            }
            String schemaJson = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                    .getSchema(schemaJson, InputFormat.JSON);
        } catch (Exception e) {
            throw new IllegalStateException("Could not load " + resource, e);
        }
    }

    /** The live rule set, read from the service rather than from a copy. */
    public RuleTables rules() {
        ResponseEntity<String> response = rest.getForEntity("/api/dmn/content", String.class);
        assertThat(response.getStatusCode().value()).as("GET /api/dmn/content").isEqualTo(200);
        return RuleTables.parse(response.getBody());
    }

    public String ruleSetVersion() {
        ResponseEntity<Map> response = rest.getForEntity("/api/rules/version", Map.class);
        assertThat(response.getStatusCode().value()).as("GET /api/rules/version").isEqualTo(200);
        return (String) response.getBody().get("ruleSetVersion");
    }

    public Verdict check(Map<String, Object> request) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = rest.postForEntity(
                "/api/compatibility/check", new HttpEntity<>(toJson(request), headers), String.class);

        assertThat(response.getStatusCode().value())
                .as("POST /api/compatibility/check%nrequest: %s%nresponse: %s",
                        toJson(request), response.getBody())
                .isEqualTo(200);

        List<Error> violations = responseSchema.validate(response.getBody(), InputFormat.JSON);
        assertThat(describe(violations))
                .as("the response does not match the published contract schema at "
                        + "src/main/resources/static/schemas.%nresponse: %s", response.getBody())
                .isEmpty();

        return new Verdict(toMap(parse(response.getBody())), toJson(request), response.getBody());
    }

    /** Posts without asserting success, for the cases that are supposed to be rejected. */
    public ResponseEntity<String> checkRaw(String rawJson) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity("/api/compatibility/check",
                new HttpEntity<>(rawJson, headers), String.class);
    }

    private static List<String> describe(List<Error> violations) {
        List<String> out = new ArrayList<>();
        for (Error violation : violations) {
            out.add(violation.getInstanceLocation() + " " + violation.getMessage());
        }
        return out;
    }

    private static String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static JsonNode parse(String json) {
        try {
            return JSON.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("Response was not JSON: " + json, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(JsonNode node) {
        return JSON.convertValue(node, Map.class);
    }

    /** A verdict, with the request that produced it kept alongside for failure messages. */
    public record Verdict(Map<String, Object> body, String requestJson, String json) {

        public boolean valid() {
            return Boolean.TRUE.equals(body.get("valid"));
        }

        @SuppressWarnings("unchecked")
        public List<Map<String, Object>> allowed() {
            return (List<Map<String, Object>>) body.getOrDefault("allowed", List.of());
        }

        @SuppressWarnings("unchecked")
        public List<Map<String, Object>> rejected() {
            return (List<Map<String, Object>>) body.getOrDefault("rejected", List.of());
        }

        @SuppressWarnings("unchecked")
        public List<Map<String, Object>> conflicts() {
            return (List<Map<String, Object>>) body.getOrDefault("conflicts", List.of());
        }

        public List<String> allowedIds() {
            return ids(allowed());
        }

        public List<String> rejectedIds() {
            return ids(rejected());
        }

        public List<String> conflictIds() {
            List<String> out = new ArrayList<>();
            for (Map<String, Object> c : conflicts()) {
                out.add((String) c.get("ruleId"));
            }
            return out;
        }

        public Map<String, Object> conflictFor(String ruleId) {
            for (Map<String, Object> c : conflicts()) {
                if (ruleId.equals(c.get("ruleId"))) {
                    return c;
                }
            }
            return null;
        }

        private static List<String> ids(List<Map<String, Object>> rows) {
            List<String> out = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                out.add((String) row.get("itemId"));
            }
            return out;
        }
    }
}
