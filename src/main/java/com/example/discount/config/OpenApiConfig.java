package com.example.discount.config;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.SpecVersion;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Schema;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Publishes the request and response contract in Swagger UI.
 *
 * <p>The schemas are not restated here: they are loaded from the same JSON Schema files served
 * at {@code /schemas/...} and asserted by the contract test, so there is exactly one description
 * of the contract and it cannot drift from the one callers validate against. OpenAPI 3.1 is a
 * superset of JSON Schema 2020-12, which is what makes that possible.
 */
@Configuration
public class OpenApiConfig {

    private static final Logger log = LoggerFactory.getLogger(OpenApiConfig.class);

    public static final String REQUEST_SCHEMA = "/static/schemas/compatibility-request.schema.json";
    public static final String RESPONSE_SCHEMA = "/static/schemas/compatibility-response.schema.json";

    private static final String DESCRIPTION = String.join(System.lineSeparator() + System.lineSeparator(),
            "Campaign stacking validation for the TMF Promotion API.",
            "Post the union of inventory and shopping-cart items. The whole set is evaluated at "
                    + "once and conflicts are resolved by declared precedence, so the same basket "
                    + "always settles the same way whatever order it was built in.",
            "The service validates, the caller enforces: there is no state and no side effect "
                    + "here, and conflicting items are free to sit in the cart until checkout.",
            "Rules live in five decision tables inside the DMN model and are edited in the browser "
                    + "at /editor.html without a redeploy. Every verdict carries the ruleSetVersion "
                    + "that produced it.");

    @Bean
    public OpenAPI promotionCompatibilityOpenAPI() {
        OpenAPI openApi = new OpenAPI()
                .specVersion(SpecVersion.V31)
                .info(new Info()
                        .title("Promotion Compatibility")
                        .version("2.0")
                        .description(DESCRIPTION));

        Components components = new Components();
        addSchema(components, "CompatibilityRequest", REQUEST_SCHEMA);
        addSchema(components, "CompatibilityResponse", RESPONSE_SCHEMA);
        return openApi.components(components);
    }

    private void addSchema(Components components, String name, String resource) {
        try (InputStream is = OpenApiConfig.class.getResourceAsStream(resource)) {
            if (is == null) {
                log.warn("Contract schema {} not found on the classpath; Swagger UI will show a "
                        + "free-form object instead", resource);
                return;
            }
            String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            @SuppressWarnings("unchecked")
            Map<String, Object> tree = Json31.mapper().readValue(json, Map.class);
            // $schema and $id are document-level JSON Schema keywords. Inside an OpenAPI
            // components block they are noise at best and a broken resolver hint at worst.
            tree.remove("$schema");
            tree.remove("$id");
            Schema<?> schema = Json31.mapper().convertValue(tree, Schema.class);
            components.addSchemas(name, schema);
        } catch (Exception e) {
            log.warn("Could not publish {} in the OpenAPI document: {}", name, e.getMessage());
        }
    }
}
