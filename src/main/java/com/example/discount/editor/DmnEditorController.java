package com.example.discount.editor;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import com.example.discount.hotreload.DmnHotReloadService;
import com.example.discount.hotreload.DmnHotReloadService.DmnValidationException;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.kie.dmn.api.core.DMNDecisionResult;
import org.kie.dmn.api.core.DMNResult;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The rule-authoring surface behind editor.html. Everything here exists so a rule owner can
 * change a rule without a release.
 */
@RestController
@RequestMapping("/api/dmn")
@Tag(name = "Rule authoring", description = "Read, edit, version and roll back the rule model")
public class DmnEditorController {

    private final DmnHotReloadService hotReloadService;

    public DmnEditorController(DmnHotReloadService hotReloadService) {
        this.hotReloadService = hotReloadService;
    }

    @GetMapping(value = "/content", produces = MediaType.APPLICATION_XML_VALUE)
    @Operation(summary = "The live DMN model")
    public ResponseEntity<String> getDmnContent() {
        return ResponseEntity.ok(hotReloadService.getCurrentDmnXml());
    }

    @PutMapping(value = "/content", consumes = MediaType.APPLICATION_XML_VALUE)
    @Operation(summary = "Replace the rule model",
            description = "Validated before it is accepted; a model the engine reports errors "
                    + "on is rejected rather than silently serving fewer rules.")
    public ResponseEntity<Map<String, String>> updateDmnContent(@RequestBody String dmnXml) {
        try {
            hotReloadService.updateDmn(dmnXml);
            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "message", "Rules updated and hot-reloaded",
                    "ruleSetVersion", hotReloadService.getRuleSetVersion()));
        } catch (DmnValidationException e) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("status", "error", "message", "Failed to save: " + e.getMessage()));
        }
    }

    /**
     * Evaluates every decision, not just the verdict. This is the debugging view: it is how you
     * find out which node produced a surprising intermediate value. Callers use
     * {@code POST /api/compatibility/check}.
     */
    @PostMapping(value = "/evaluate", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Evaluate every decision node",
            description = "Debugging aid that returns each node's result, so a wrong "
                    + "intermediate value is located rather than hunted.")
    public ResponseEntity<Map<String, Object>> evaluate(@RequestBody Map<String, Object> variables) {
        try {
            Map<String, Object> inputs = new LinkedHashMap<>(variables);
            inputs.putIfAbsent("Items", variables.get("Items"));
            inputs.putIfAbsent("Channel", null);

            DMNResult result = hotReloadService.evaluate(inputs);

            Map<String, Object> decisions = new LinkedHashMap<>();
            for (DMNDecisionResult decision : result.getDecisionResults()) {
                Map<String, Object> node = new LinkedHashMap<>();
                node.put("status", decision.getEvaluationStatus().name());
                node.put("result", decision.getResult());
                decisions.put(decision.getDecisionName(), node);
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("ruleSetVersion", hotReloadService.getRuleSetVersion());
            response.put("Verdict", result.getContext().get("Verdict"));
            response.put("decisions", decisions);
            response.put("messages", result.getMessages().stream().map(Object::toString).toList());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("status", "error");
            error.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }

    @GetMapping(value = "/versions", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Archived versions, newest first")
    public ResponseEntity<Map<String, Object>> versions() throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ruleSetVersion", hotReloadService.getRuleSetVersion());
        body.put("versions", hotReloadService.listVersionIds());
        return ResponseEntity.ok(body);
    }

    @GetMapping(value = "/backup", produces = MediaType.APPLICATION_XML_VALUE)
    @Operation(summary = "The most recently archived model")
    public ResponseEntity<String> getBackupContent() throws IOException {
        String backup = hotReloadService.getBackupDmnXml();
        if (backup == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(backup);
    }

    @PostMapping("/rollback")
    @Operation(summary = "Restore the previous version")
    public ResponseEntity<Map<String, String>> rollback() {
        try {
            hotReloadService.rollback();
            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "message", "Rolled back to the previous version",
                    "ruleSetVersion", hotReloadService.getRuleSetVersion()));
        } catch (DmnValidationException | IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("status", "error", "message", "Rollback failed: " + e.getMessage()));
        }
    }

    @PostMapping("/rollback/{versionId}")
    @Operation(summary = "Restore one named archived version")
    public ResponseEntity<Map<String, String>> rollbackTo(@PathVariable String versionId) {
        try {
            hotReloadService.rollbackTo(versionId);
            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "message", "Rolled back to " + versionId,
                    "ruleSetVersion", hotReloadService.getRuleSetVersion()));
        } catch (DmnValidationException | IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("status", "error", "message", "Rollback failed: " + e.getMessage()));
        }
    }
}
