package com.example.discount.hotreload;

import java.util.List;
import java.util.Map;

import com.example.discount.support.TestSupportConfig;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The no-redeploy path: read the rules, change them, have the change take effect immediately, and
 * be able to go back. This is the capability the whole service exists for, so it is tested end to
 * end rather than through the service class.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestSupportConfig.class)
class DmnHotReloadTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void theLiveModelIsReadable() {
        ResponseEntity<String> response = rest.getForEntity("/api/dmn/content", String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody())
                .contains("PromotionCompatibility")
                .contains("CategoryPrecedence")
                .contains("CategoryIncompatibility")
                .contains("ItemIncompatibility")
                .contains("CategoryCardinality")
                .contains("Exceptions");
    }

    @Test
    void aMalformedModelIsRejected() {
        ResponseEntity<Map> response = put("<invalid>not a dmn</invalid>", Map.class);
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().get("status")).isEqualTo("error");
    }

    /**
     * The engine builds a model with a broken decision table "successfully" and then discards the
     * broken rule at evaluation time. Accepting that save would tell a rule owner their rule is
     * live when it has silently vanished, so the model has to be rejected instead.
     */
    @Test
    void aModelWithAStructurallyBrokenRuleIsRejected() {
        String current = rest.getForObject("/api/dmn/content", String.class);
        String ruleId = firstCategoryRuleId(current);
        String broken = setCellText(current, "<dmn:inputEntry id=\"" + ruleId + "_I1\">", "");

        assertThat(broken).as("the test needs to actually have broken something").isNotEqualTo(current);

        ResponseEntity<Map> response = put(broken, Map.class);
        assertThat(response.getStatusCode().value())
                .as("a rule the engine cannot compile must not be accepted.%n%s", response.getBody())
                .isEqualTo(400);
    }

    @Test
    void savingChangesTheRuleSetVersionAndTakesEffectImmediately() {
        String before = version();
        String current = rest.getForObject("/api/dmn/content", String.class);
        String ruleId = firstCategoryRuleId(current);

        // Change a rule the way a rule owner would: declare an explicit winner on the first
        // category rule, whichever rule that happens to be.
        String edited = setCellText(current, "<dmn:outputEntry id=\"" + ruleId + "_O4\">", "\"B\"");
        assertThat(edited).as("the edit did not apply, so this test would prove nothing")
                .isNotEqualTo(current);

        assertThat(put(edited, Map.class).getStatusCode().value()).isEqualTo(200);
        assertThat(version())
                .as("a rule change must change the rule set version")
                .isNotEqualTo(before);

        assertThat(rest.postForEntity("/api/dmn/rollback", null, Map.class)
                .getStatusCode().value()).isEqualTo(200);
        assertThat(version()).as("rolling back must restore the previous rule set").isEqualTo(before);
    }

    /** The id of the first rule row in CategoryIncompatibility, so no test hard-codes a rule. */
    private static String firstCategoryRuleId(String xml) {
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("<dmn:rule id=\"(_DT_CATRULES_R\\d+)\">").matcher(xml);
        assertThat(m.find()).as("the model should hold at least one category rule").isTrue();
        return m.group(1);
    }

    /**
     * Replaces the FEEL text of the cell that opens with {@code marker}. Plain string surgery
     * rather than a regex: the escaping in a Java regex over XML is its own source of bugs.
     */
    private static String setCellText(String xml, String marker, String feel) {
        int cell = xml.indexOf(marker);
        assertThat(cell).as("cell %s not found in the model", marker).isNotEqualTo(-1);
        int open = xml.indexOf("<dmn:text>", cell) + "<dmn:text>".length();
        int close = xml.indexOf("</dmn:text>", open);
        return xml.substring(0, open) + feel + xml.substring(close);
    }

    @Test
    void everySaveIsKeptSoOneUndoIsNotTheOnlyWayBack() {
        String current = rest.getForObject("/api/dmn/content", String.class);
        put(current, Map.class);
        put(current, Map.class);

        ResponseEntity<Map> response = rest.getForEntity("/api/dmn/versions", Map.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat((List<?>) response.getBody().get("versions"))
                .as("each save should leave a version behind")
                .hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void theDebugViewShowsEveryDecisionNode() {
        String body = """
                {"Items":[{"itemId":"X1","itemName":"Probe","itemType":"PROMOTION",
                           "discountCategory":"OTSDiscount"}]}
                """;
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> response = rest.postForEntity(
                "/api/dmn/evaluate", new HttpEntity<>(body, headers), Map.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> decisions = (Map<String, Object>) response.getBody().get("decisions");
        assertThat(decisions.keySet())
                .as("the debugging view is what makes a wrong intermediate value locatable")
                .contains("OrderedItems", "ApplicableRules", "CategoryConflicts", "ItemConflicts",
                        "CardinalityViolations", "ResolvedConflicts", "Verdict");
    }

    private String version() {
        return (String) rest.getForObject("/api/rules/version", Map.class).get("ruleSetVersion");
    }

    private <T> ResponseEntity<T> put(String xml, Class<T> type) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_XML);
        return rest.exchange("/api/dmn/content", org.springframework.http.HttpMethod.PUT,
                new HttpEntity<>(xml, headers), type);
    }

}
