package com.example.discount.editor;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import com.example.discount.hotreload.DmnHotReloadService;
import com.example.discount.hotreload.DmnHotReloadService.DmnValidationException;

import org.kie.dmn.api.core.DMNResult;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dmn")
public class DmnEditorController {

    private final DmnHotReloadService hotReloadService;

    public DmnEditorController(DmnHotReloadService hotReloadService) {
        this.hotReloadService = hotReloadService;
    }

    /**
     * Returns the current DMN XML content for the editor.
     */
    @GetMapping(value = "/content", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> getDmnContent() {
        return ResponseEntity.ok(hotReloadService.getCurrentDmnXml());
    }

    /**
     * Updates the DMN model with new XML content, validates, and hot-reloads.
     */
    @PutMapping(value = "/content", consumes = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<Map<String, String>> updateDmnContent(@RequestBody String dmnXml) {
        try {
            hotReloadService.updateDmn(dmnXml);
            return ResponseEntity.ok(Map.of("status", "success", "message", "DMN updated and hot-reloaded successfully"));
        } catch (DmnValidationException e) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("status", "error", "message", "Failed to save DMN: " + e.getMessage()));
        }
    }

    /**
     * Evaluates the DMN model using the hot-reloaded runtime.
     * Use this endpoint instead of /PromotionCompatibility to get results from updated rules.
     */
    @PostMapping(value = "/evaluate", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> evaluate(@RequestBody Map<String, Object> variables) {
        try {
            DMNResult result = hotReloadService.evaluate(variables);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("CompatibilityResult", result.getContext().get("CompatibilityResult"));
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("status", "error");
            error.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }

    /**
     * Returns the backup DMN XML for rollback preview.
     */
    @GetMapping(value = "/backup", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> getBackupContent() throws IOException {
        String backup = hotReloadService.getBackupDmnXml();
        if (backup == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(backup);
    }

    /**
     * Rolls back to the previous DMN version.
     */
    @PostMapping("/rollback")
    public ResponseEntity<Map<String, String>> rollback() {
        try {
            hotReloadService.rollback();
            return ResponseEntity.ok(Map.of("status", "success", "message", "Rolled back to previous version"));
        } catch (DmnValidationException e) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("status", "error", "message", "Rollback failed: " + e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", e.getMessage()));
        }
    }
}
