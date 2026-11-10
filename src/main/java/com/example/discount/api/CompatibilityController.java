package com.example.discount.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.discount.hotreload.DmnHotReloadService;
import com.example.discount.rules.RuleTables;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.kie.dmn.api.core.DMNMessage;
import org.kie.dmn.api.core.DMNResult;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The service contract: post the union of inventory and cart items, get back what may be
 * applied, what may not and why, and which items conflict with which.
 *
 * <p>The service validates; the caller enforces. Nothing here has state or side effects, so it
 * can be called as often as the purchase flow likes.
 */
@RestController
@Tag(name = "Compatibility", description = "Campaign stacking validation")
public class CompatibilityController {

    private final DmnHotReloadService engine;

    public CompatibilityController(DmnHotReloadService engine) {
        this.engine = engine;
    }

    @PostMapping(value = "/api/compatibility/check",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Validate a set of promotions and add-on offerings",
            description = "Evaluates the whole set at once and resolves conflicts by declared "
                    + "precedence, so the verdict does not depend on the order items were added.")
    public ResponseEntity<Map<String, Object>> check(@RequestBody Map<String, Object> request) {
        Object items = request.get("Items");
        if (!(items instanceof List<?>)) {
            return ResponseEntity.badRequest().body(error(
                    "'Items' is required and must be an array."));
        }

        // Both inputs are always set, including Channel when the caller omitted it: a DMN input
        // that is absent from the context makes every dependent decision skip rather than
        // default, and Channel is optional in the contract.
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("Items", items);
        inputs.put("Channel", request.get("Channel"));

        DMNResult result;
        try {
            result = engine.evaluate(inputs);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(error(
                    "Evaluation failed: " + e.getMessage()));
        }

        List<DMNMessage> errors = result.getMessages(DMNMessage.Severity.ERROR);
        Object verdict = result.getContext().get("Verdict");
        if (verdict == null || !errors.isEmpty()) {
            List<String> texts = new ArrayList<>();
            for (DMNMessage m : errors) {
                texts.add(m.getText());
            }
            Map<String, Object> body = error("The request could not be evaluated against the rules.");
            body.put("details", texts);
            return ResponseEntity.badRequest().body(body);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        if (verdict instanceof Map<?, ?> v) {
            response.put("valid", v.get("valid"));
            response.put("ruleSetVersion", engine.getRuleSetVersion());
            response.put("allowed", v.get("allowed"));
            response.put("rejected", v.get("rejected"));
            response.put("conflicts", v.get("conflicts"));
        } else {
            return ResponseEntity.internalServerError().body(error(
                    "The model returned an unexpected verdict shape."));
        }
        return ResponseEntity.ok(response);
    }

    @GetMapping(value = "/api/rules", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "The live rule set",
            description = "The five business-editable tables exactly as configured, with the "
                    + "version a verdict can be traced back to.")
    public ResponseEntity<Map<String, Object>> rules() {
        RuleTables tables = engine.getRuleTables();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ruleSetVersion", tables.version());
        body.putAll(tables.all());
        return ResponseEntity.ok(body);
    }

    @GetMapping(value = "/api/rules/version", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "The live rule set version only",
            description = "Cheap enough to poll. Changes as soon as a rule owner saves.")
    public ResponseEntity<Map<String, Object>> rulesVersion() {
        return ResponseEntity.ok(Map.of("ruleSetVersion", engine.getRuleSetVersion()));
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "error");
        body.put("message", message);
        return body;
    }
}
