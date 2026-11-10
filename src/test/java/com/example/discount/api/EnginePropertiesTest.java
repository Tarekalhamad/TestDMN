package com.example.discount.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import com.example.discount.rules.RuleProbes;
import com.example.discount.rules.RuleTables;
import com.example.discount.support.CompatibilityClient;
import com.example.discount.support.CompatibilityClient.Verdict;
import com.example.discount.support.TestSupportConfig;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Properties the engine must hold whatever the rules happen to say.
 *
 * <p>These are the guarantees the service is built on rather than statements about any particular
 * rule, so they are written against whatever is configured: the inputs are derived from the live
 * tables, never hard-coded.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestSupportConfig.class)
class EnginePropertiesTest {

    @Autowired
    private CompatibilityClient client;

    private RuleTables tables;
    private RuleProbes probes;

    @BeforeEach
    void loadRules() {
        tables = client.rules();
        probes = new RuleProbes(tables);
    }

    @Test
    @DisplayName("shuffling the items produces an identical verdict")
    void orderIndependence() {
        // Everything every rule touches, in one basket, so the shuffle has real work to do.
        List<Map<String, Object>> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (RuleProbes.Probe probe : probes.all()) {
            if (probe.isUnreachable()) {
                continue;
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> probeItems =
                    (List<Map<String, Object>>) probe.request().get("Items");
            for (Map<String, Object> item : probeItems) {
                if (seen.add((String) item.get("itemId"))) {
                    items.add(item);
                }
            }
        }
        assertThat(items).as("no items to shuffle").hasSizeGreaterThan(2);

        String baseline = client.check(RuleProbes.request(null, items)).json();

        Random random = new Random(20260820);
        for (int i = 0; i < 12; i++) {
            List<Map<String, Object>> shuffled = new ArrayList<>(items);
            Collections.shuffle(shuffled, random);
            String verdict = client.check(RuleProbes.request(null, shuffled)).json();
            assertThat(verdict)
                    .as("the same basket in a different order settled differently on shuffle %d. "
                            + "Order independence is the whole answer to first-come-first-served, "
                            + "so this must never depend on input order.", i)
                    .isEqualTo(baseline);
        }
    }

    @Test
    @DisplayName("an item no rule mentions stacks with everything")
    void defaultAllow() {
        String neutral = probes.neutralCategory();
        List<Map<String, Object>> items = List.of(
                probes.item("PROBE-N1", neutral, null),
                probes.item("PROBE-N2", "ProbeUnrankedCategory", null));

        Verdict verdict = client.check(RuleProbes.request(null, items));

        assertThat(verdict.valid()).as("silence means compatible.%n%s", verdict.json()).isTrue();
        assertThat(verdict.rejected()).isEmpty();
        assertThat(verdict.conflicts()).isEmpty();
        assertThat(verdict.allowedIds()).containsExactly("PROBE-N1", "PROBE-N2");
    }

    @Test
    @DisplayName("an unranked category still evaluates, and sorts last")
    void unknownCategoryIsSurvivable() {
        String best = tables.rankedCategories().get(0);
        List<Map<String, Object>> items = List.of(
                probes.item("PROBE-UNKNOWN", "NoSuchCategoryAnywhere", null),
                probes.item("PROBE-RANKED", best, null));

        Verdict verdict = client.check(RuleProbes.request(null, items));

        assertThat(verdict.allowedIds())
                .as("an unranked category must not break evaluation.%n%s", verdict.json())
                .containsExactly("PROBE-RANKED", "PROBE-UNKNOWN");
    }

    @Test
    @DisplayName("conflicts do not cross contracts")
    void scopeIsolation() {
        List<Map<String, String>> categoryRules = tables.categoryRules();
        org.junit.jupiter.api.Assumptions.assumeFalse(categoryRules.isEmpty(),
                "no category rule configured to test scope isolation with");
        Map<String, String> rule = categoryRules.get(0);

        Map<String, Object> a = probes.item("PROBE-S1", rule.get("categoryA"), rule.get("serviceType"));
        Map<String, Object> b = probes.item("PROBE-S2", rule.get("categoryB"), rule.get("serviceType"));

        Map<String, Object> together = RuleProbes.request(rule.get("channel"), List.of(a, b));
        assertThat(client.check(together).conflictIds())
                .as("both categories on one contract must conflict, or this test proves nothing")
                .contains(rule.get("ruleId"));

        Map<String, Object> aScoped = new java.util.LinkedHashMap<>(a);
        Map<String, Object> bScoped = new java.util.LinkedHashMap<>(b);
        aScoped.put("scopeId", "SUB-1");
        bScoped.put("scopeId", "SUB-2");

        Verdict apart = client.check(RuleProbes.request(rule.get("channel"), List.of(aScoped, bScoped)));
        assertThat(apart.conflictIds())
                .as("the same two categories on different contracts are not a conflict.%n%s", apart.json())
                .doesNotContain(rule.get("ruleId"));
        assertThat(apart.valid()).isTrue();
    }

    @Test
    @DisplayName("an inventory item outranks a cart item when nothing else separates them")
    void inventoryBeatsCartOnATie() {
        List<Map<String, String>> cardinalityRules = tables.cardinalityRules();
        org.junit.jupiter.api.Assumptions.assumeFalse(cardinalityRules.isEmpty(),
                "no cardinality rule configured to force a tie with");
        Map<String, String> rule = cardinalityRules.get(0);
        int max = Integer.parseInt(rule.get("maxAllowed"));
        String category = rule.get("discountCategory");

        // The inventory item is given the id that sorts last, so only origin can save it.
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 1; i <= max; i++) {
            Map<String, Object> cart = new java.util.LinkedHashMap<>(
                    probes.item(String.format("PROBE-T%02d", i), category, rule.get("serviceType")));
            cart.put("origin", "CART");
            items.add(cart);
        }
        Map<String, Object> inventory = new java.util.LinkedHashMap<>(
                probes.item("PROBE-T99", category, rule.get("serviceType")));
        inventory.put("origin", "INVENTORY");
        items.add(inventory);

        Verdict verdict = client.check(RuleProbes.request(rule.get("channel"), items));

        assertThat(verdict.allowedIds())
                .as("the provisioned item should survive a tie against one still in the cart.%n%s",
                        verdict.json())
                .contains("PROBE-T99");
        assertThat(verdict.rejectedIds())
                .as("something had to give: %d items against a limit of %d", items.size(), max)
                .isNotEmpty();
    }

    @Test
    @DisplayName("valid is true exactly when nothing was rejected")
    void validIsTheCheckoutGate() {
        for (RuleProbes.Probe probe : probes.all()) {
            if (probe.isUnreachable()) {
                continue;
            }
            Verdict verdict = client.check(probe.request());
            assertThat(verdict.valid())
                    .as("valid must mean 'nothing rejected' for %s.%n%s", probe.name(), verdict.json())
                    .isEqualTo(verdict.rejected().isEmpty());
        }
    }

    @Test
    @DisplayName("an item is never both allowed and rejected")
    void allowedAndRejectedArePartitions() {
        for (RuleProbes.Probe probe : probes.all()) {
            if (probe.isUnreachable()) {
                continue;
            }
            Verdict verdict = client.check(probe.request());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> sent = (List<Map<String, Object>>) probe.request().get("Items");

            List<String> sentIds = new ArrayList<>();
            for (Map<String, Object> item : sent) {
                sentIds.add((String) item.get("itemId"));
            }

            if (!verdict.rejectedIds().isEmpty()) {
                assertThat(verdict.allowedIds())
                        .as("%s: an item cannot be both applied and refused.%n%s",
                                probe.name(), verdict.json())
                        .doesNotContainAnyElementsOf(verdict.rejectedIds());
            }
            assertThat(new HashSet<>(concat(verdict.allowedIds(), verdict.rejectedIds())))
                    .as("%s: every item sent must come back either allowed or rejected.%n%s",
                            probe.name(), verdict.json())
                    .containsExactlyInAnyOrderElementsOf(new HashSet<>(sentIds));
        }
    }

    @Test
    @DisplayName("appliedOrder runs 1..n over the allowed items")
    void appliedOrderIsContiguous() {
        for (RuleProbes.Probe probe : probes.all()) {
            if (probe.isUnreachable()) {
                continue;
            }
            Verdict verdict = client.check(probe.request());
            List<Map<String, Object>> allowed = verdict.allowed();
            for (int i = 0; i < allowed.size(); i++) {
                assertThat(allowed.get(i).get("appliedOrder"))
                        .as("%s: appliedOrder at position %d.%n%s", probe.name(), i, verdict.json())
                        .isEqualTo(i + 1);
            }
        }
    }

    @Test
    @DisplayName("every verdict names the rule set that produced it")
    void verdictCarriesTheRuleSetVersion() {
        Verdict verdict = client.check(RuleProbes.request(null, List.of(
                probes.item("PROBE-V1", tables.rankedCategories().get(0), null))));
        assertThat((String) verdict.body().get("ruleSetVersion"))
                .as("a caller has to be able to prove which rules produced a verdict")
                .isEqualTo(client.ruleSetVersion())
                .matches("sha256:[0-9a-f]{64}");
    }

    @Test
    @DisplayName("an empty basket is valid and produces nothing")
    void emptyBasket() {
        Verdict verdict = client.check(RuleProbes.request(null, List.of()));
        assertThat(verdict.valid()).isTrue();
        assertThat(verdict.allowed()).isEmpty();
        assertThat(verdict.rejected()).isEmpty();
        assertThat(verdict.conflicts()).isEmpty();
    }

    @Test
    @DisplayName("a request without Items is refused rather than silently answered")
    void itemsIsRequired() {
        ResponseEntity<String> response = client.checkRaw("{\"Channel\":\"ONLINE\"}");
        assertThat(response.getStatusCode().value())
                .as("a missing input makes every DMN decision skip, which would otherwise return "
                        + "a null verdict with a 200.%n%s", response.getBody())
                .isEqualTo(400);
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }
}
