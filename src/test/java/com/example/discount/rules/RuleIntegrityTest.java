package com.example.discount.rules;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural checks on the rule tables themselves. No engine, no HTTP: these catch the mistakes a
 * rule owner can make in the editor that would otherwise produce a rule that is quietly inert.
 *
 * <p>Requirement 1.30 asks for rules validated against the catalogue. There is no catalogue of
 * promotion ids to validate against, but every category a rule names must exist in
 * CategoryPrecedence, and that much can be enforced here.
 */
class RuleIntegrityTest {

    private static RuleTables tables;

    @BeforeAll
    static void load() throws Exception {
        try (InputStream is = RuleIntegrityTest.class.getResourceAsStream("/PromotionCompatibility.dmn")) {
            assertThat(is).as("the model must be on the classpath").isNotNull();
            tables = RuleTables.parse(new String(is.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void everyRuleHasAnIdAndTheIdsAreUnique() {
        List<String> ids = tables.allRuleIds();
        assertThat(ids).as("no rules are configured at all").isNotEmpty();
        assertThat(ids).as("every rule needs an id so a verdict can point at it").doesNotContainNull();
        assertThat(ids).as("rule ids must be unique across all tables").doesNotHaveDuplicates();
    }

    @Test
    void everyRuleExplainsItself() {
        for (String table : RuleTables.RULE_TABLES) {
            for (Map<String, String> row : tables.table(table)) {
                assertThat(row.get("description"))
                        .as("%s in %s has no description, so nobody can tell what it is for",
                                row.get("ruleId"), table)
                        .isNotNull();
            }
        }
    }

    @Test
    void precedenceRanksEveryCategoryOnceWithADistinctRank() {
        List<String> categories = new ArrayList<>();
        List<String> ranks = new ArrayList<>();
        for (Map<String, String> row : tables.precedence()) {
            categories.add(row.get("discountCategory"));
            ranks.add(row.get("precedenceRank"));
            assertThat(row.get("discountCategory")).as("a precedence row with no category").isNotNull();
            assertThat(row.get("precedenceRank")).as("%s has no rank", row.get("discountCategory")).isNotNull();
            assertThat(Integer.parseInt(row.get("precedenceRank")))
                    .as("%s rank", row.get("discountCategory")).isPositive();
        }
        assertThat(categories).as("a category ranked twice makes the winner ambiguous").doesNotHaveDuplicates();
        assertThat(ranks).as("two categories sharing a rank cannot be separated by precedence")
                .doesNotHaveDuplicates();
    }

    @Test
    void everyCategoryARuleNamesIsRanked() {
        Set<String> known = new HashSet<>(tables.rankedCategories());
        List<String> unknown = new ArrayList<>();

        for (Map<String, String> r : tables.categoryRules()) {
            checkCategory(known, unknown, r.get("ruleId"), "categoryA", r.get("categoryA"));
            checkCategory(known, unknown, r.get("ruleId"), "categoryB", r.get("categoryB"));
        }
        for (Map<String, String> r : tables.itemRules()) {
            checkCategory(known, unknown, r.get("ruleId"), "conflictsWithCategory", r.get("conflictsWithCategory"));
        }
        for (Map<String, String> r : tables.cardinalityRules()) {
            checkCategory(known, unknown, r.get("ruleId"), "discountCategory", r.get("discountCategory"));
        }
        for (Map<String, String> r : tables.exceptions()) {
            checkCategory(known, unknown, r.get("ruleId"), "whenCategoryPresent", r.get("whenCategoryPresent"));
        }

        assertThat(unknown)
                .as("these rules name a category that is not in CategoryPrecedence, so they are "
                        + "either typos or the category is missing from the precedence table. "
                        + "Either way the rule does not do what its author intended.")
                .isEmpty();
    }

    private void checkCategory(Set<String> known, List<String> problems,
                               String ruleId, String column, String value) {
        if (value != null && !known.contains(value)) {
            problems.add(ruleId + "." + column + " = " + value);
        }
    }

    @Test
    void categoryRulesNameTwoDifferentCategories() {
        for (Map<String, String> r : tables.categoryRules()) {
            assertThat(r.get("categoryA")).as("%s categoryA", r.get("ruleId")).isNotNull();
            assertThat(r.get("categoryB")).as("%s categoryB", r.get("ruleId")).isNotNull();
            assertThat(r.get("categoryA"))
                    .as("%s names the same category on both sides. That is a limit on how many "
                            + "of one category may be applied, which belongs in CategoryCardinality; "
                            + "as a pairwise rule it would drop every member of the category.",
                            r.get("ruleId"))
                    .isNotEqualTo(r.get("categoryB"));
        }
    }

    @Test
    void winnerIsAOrBOrLeftToPrecedence() {
        for (String table : List.of(RuleTables.CATEGORY_INCOMPATIBILITY, RuleTables.ITEM_INCOMPATIBILITY)) {
            for (Map<String, String> r : tables.table(table)) {
                assertThat(r.get("winner"))
                        .as("%s winner must be A, B, or blank for 'decide by precedence'", r.get("ruleId"))
                        .isIn(null, "A", "B");
            }
        }
    }

    @Test
    void itemRulesNameSomethingToConflictWith() {
        for (Map<String, String> r : tables.itemRules()) {
            assertThat(r.get("itemId")).as("%s has no itemId", r.get("ruleId")).isNotNull();
            assertThat(r.get("conflictsWithItemId") != null || r.get("conflictsWithCategory") != null)
                    .as("%s names neither an item nor a category to conflict with, so it can never fire",
                            r.get("ruleId"))
                    .isTrue();
            assertThat(r.get("itemId"))
                    .as("%s makes an item conflict with itself", r.get("ruleId"))
                    .isNotEqualTo(r.get("conflictsWithItemId"));
        }
    }

    @Test
    void cardinalityLimitsAreWholeNumbersOfAtLeastOne() {
        for (Map<String, String> r : tables.cardinalityRules()) {
            assertThat(r.get("discountCategory")).as("%s has no category", r.get("ruleId")).isNotNull();
            assertThat(r.get("maxAllowed")).as("%s has no limit", r.get("ruleId")).isNotNull();
            assertThat(Integer.parseInt(r.get("maxAllowed")))
                    .as("%s limit. A limit below one would reject the whole category, which is a "
                            + "category rule, not a cardinality rule.", r.get("ruleId"))
                    .isGreaterThanOrEqualTo(1);
        }
    }

    @Test
    void exceptionsPointAtSomethingThatExists() {
        Set<String> ruleIds = new HashSet<>(tables.allRuleIds());
        for (Map<String, String> r : tables.exceptions()) {
            assertThat(r.get("exemptItemId")).as("%s exempts nothing", r.get("ruleId")).isNotNull();
            String appliesTo = r.get("appliesToRuleId");
            if (appliesTo != null) {
                assertThat(ruleIds)
                        .as("%s is scoped to rule %s, which does not exist. A typo here silently "
                                + "makes the exception apply to nothing.", r.get("ruleId"), appliesTo)
                        .contains(appliesTo);
                assertThat(appliesTo)
                        .as("%s cannot be scoped to itself", r.get("ruleId"))
                        .isNotEqualTo(r.get("ruleId"));
            }
        }
    }

    @Test
    void noRuleReferencesAnIdReservedForTestProbes() {
        List<String> clashes = new ArrayList<>();
        for (Map<String, String> r : tables.itemRules()) {
            collectProbeIds(clashes, r.get("ruleId"), r.get("itemId"), r.get("conflictsWithItemId"));
        }
        for (Map<String, String> r : tables.exceptions()) {
            collectProbeIds(clashes, r.get("ruleId"), r.get("exemptItemId"), r.get("whenItemPresent"));
        }
        assertThat(clashes)
                .as("the generated tests build items with the %s prefix; a rule using that prefix "
                        + "would make those tests assert against themselves", RuleProbes.PROBE)
                .isEmpty();
    }

    private void collectProbeIds(List<String> clashes, String ruleId, String... values) {
        for (String value : values) {
            if (value != null && value.startsWith(RuleProbes.PROBE)) {
                clashes.add(ruleId + " -> " + value);
            }
        }
    }
}
