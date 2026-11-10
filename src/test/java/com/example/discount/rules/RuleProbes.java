package com.example.discount.rules;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

/**
 * Builds, for every rule that is configured, a request that makes that rule fire and the outcome
 * the rule itself declares.
 *
 * <p>Nothing here restates a rule. The inputs and the expectations are both derived from the rule
 * row, so adding a rule in the browser editor adds a test case, and changing a winner changes the
 * expectation with it. That is the only arrangement that survives rule owners editing rules
 * without a release.
 *
 * <p>Probe items use the {@value #PROBE} prefix, and {@code RuleIntegrityTest} asserts no rule
 * ever references such an id &mdash; which is what keeps a probe from colliding with real data.
 */
public final class RuleProbes {

    public static final String PROBE = "PROBE-";

    private final RuleTables tables;

    public RuleProbes(RuleTables tables) {
        this.tables = tables;
    }

    /** One probe per rule, in table order. */
    public List<Probe> all() {
        List<Probe> probes = new ArrayList<>();
        for (Map<String, String> rule : tables.categoryRules()) {
            probes.add(categoryProbe(rule));
        }
        for (Map<String, String> rule : tables.itemRules()) {
            probes.add(itemProbe(rule));
        }
        for (Map<String, String> rule : tables.cardinalityRules()) {
            probes.add(cardinalityProbe(rule));
        }
        for (Map<String, String> rule : tables.exceptions()) {
            probes.add(exceptionProbe(rule));
        }
        return probes;
    }

    // ---------------------------------------------------------------- category

    private Probe categoryProbe(Map<String, String> rule) {
        String ruleId = rule.get("ruleId");
        String a = rule.get("categoryA");
        String b = rule.get("categoryB");
        String svc = rule.get("serviceType");

        Map<String, Object> itemA = item(PROBE + "A", a, svc);
        Map<String, Object> itemB = item(PROBE + "B", b, svc);

        boolean aWins = aWins(rule.get("winner"), a, b);
        String winner = aWins ? PROBE + "A" : PROBE + "B";
        String loser = aWins ? PROBE + "B" : PROBE + "A";

        return new Probe(ruleId, "CategoryIncompatibility",
                describe(ruleId, a + " vs " + b),
                request(rule.get("channel"), List.of(itemA, itemB)),
                Expectation.resolvedBy(ruleId, "CATEGORY_INCOMPATIBILITY",
                        rule.get("winner") == null ? "AUTO_BY_PRECEDENCE" : "AUTO_BY_OVERRIDE",
                        winner, List.of(loser)));
    }

    /** Empty winner means the better rank wins, and a tie falls to side A, as the model does. */
    private boolean aWins(String winner, String categoryA, String categoryB) {
        if ("A".equals(winner)) {
            return true;
        }
        if ("B".equals(winner)) {
            return false;
        }
        return rank(categoryA) <= rank(categoryB);
    }

    private int rank(String category) {
        OptionalInt r = tables.rankOf(category);
        return r.isPresent() ? r.getAsInt() : 999;
    }

    // -------------------------------------------------------------------- item

    private Probe itemProbe(Map<String, String> rule) {
        String ruleId = rule.get("ruleId");
        String subjectId = rule.get("itemId");
        String withItem = rule.get("conflictsWithItemId");
        String withCategory = rule.get("conflictsWithCategory");
        String svc = rule.get("serviceType");

        // The counterpart's category is fixed by the rule when it names one; the subject's is
        // ours to choose, so pick a ranked category that is not the counterpart's.
        String counterpartCategory = withCategory != null ? withCategory : secondCategory(null);
        String subjectCategory = firstCategoryOtherThan(counterpartCategory);

        List<Map<String, Object>> items = new ArrayList<>();
        items.add(item(subjectId, subjectCategory, svc));

        List<String> counterpartIds = new ArrayList<>();
        if (withItem != null) {
            items.add(item(withItem, counterpartCategory, svc));
            counterpartIds.add(withItem);
        }
        if (withCategory != null) {
            items.add(item(PROBE + "B", withCategory, svc));
            counterpartIds.add(PROBE + "B");
        }

        boolean subjectWins = aWins(rule.get("winner"), subjectCategory, counterpartCategory);
        String winner = subjectWins ? subjectId : counterpartIds.get(0);
        List<String> losers = subjectWins ? counterpartIds : List.of(subjectId);

        return new Probe(ruleId, "ItemIncompatibility",
                describe(ruleId, subjectId + " vs " + String.join(" and ", counterpartIds)),
                request(rule.get("channel"), items),
                Expectation.resolvedBy(ruleId, "ITEM_INCOMPATIBILITY",
                        rule.get("winner") == null ? "AUTO_BY_PRECEDENCE" : "AUTO_BY_OVERRIDE",
                        winner, losers));
    }

    // ------------------------------------------------------------- cardinality

    private Probe cardinalityProbe(Map<String, String> rule) {
        String ruleId = rule.get("ruleId");
        String category = rule.get("discountCategory");
        int max = maxAllowed(rule);
        String svc = rule.get("serviceType");

        // One more than the limit allows, so exactly one must be dropped. Ids are zero padded
        // because the engine breaks a rank tie on the id, and the expectation has to agree.
        List<Map<String, Object>> items = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (int i = 1; i <= max + 1; i++) {
            String id = String.format("%sC%02d", PROBE, i);
            ids.add(id);
            items.add(item(id, category, svc));
        }

        return new Probe(ruleId, "CategoryCardinality",
                describe(ruleId, (max + 1) + " x " + category + ", at most " + max + " allowed"),
                request(rule.get("channel"), items),
                Expectation.resolvedBy(ruleId, "CARDINALITY", "AUTO_BY_CARDINALITY",
                        ids.get(0), List.of(ids.get(ids.size() - 1))));
    }

    private int maxAllowed(Map<String, String> rule) {
        try {
            return Integer.parseInt(rule.get("maxAllowed"));
        } catch (RuntimeException e) {
            throw new IllegalStateException(rule.get("ruleId")
                    + " has a maxAllowed that is not a whole number: " + rule.get("maxAllowed"));
        }
    }

    // --------------------------------------------------------------- exception

    /**
     * Puts the exempt item into a situation where the rule it names would drop it, then adds
     * whatever the exception's condition asks for. If no such situation can be built, the
     * exception can never fire, and the probe says so rather than passing quietly.
     */
    private Probe exceptionProbe(Map<String, String> rule) {
        String ruleId = rule.get("ruleId");
        String exemptId = rule.get("exemptItemId");
        String appliesTo = rule.get("appliesToRuleId");

        Map<String, String> target = appliesTo != null ? findRule(appliesTo) : firstCatchableRule();
        if (target == null) {
            return Probe.unreachable(ruleId, "Exceptions", describe(ruleId, "nothing to exempt from"),
                    appliesTo != null
                            ? "names rule " + appliesTo + ", which does not exist"
                            : "no rule in any table can catch item " + exemptId
                              + ", so this exception can never fire");
        }

        String targetId = target.get("ruleId");
        List<Map<String, Object>> items = new ArrayList<>();
        String ruleType;

        if (tables.categoryRules().contains(target)) {
            ruleType = "CATEGORY_INCOMPATIBILITY";
            String a = target.get("categoryA");
            String b = target.get("categoryB");
            boolean aWins = aWins(target.get("winner"), a, b);
            String losingCategory = aWins ? b : a;
            String winningCategory = aWins ? a : b;
            items.add(item(exemptId, losingCategory, target.get("serviceType")));
            items.add(item(PROBE + "W", winningCategory, target.get("serviceType")));
        } else if (tables.cardinalityRules().contains(target)) {
            ruleType = "CARDINALITY";
            String category = target.get("discountCategory");
            int max = maxAllowed(target);
            items.add(item(exemptId, category, target.get("serviceType")));
            // Enough others to breach the limit on their own, so the conflict fires even though
            // the exempt item has been taken out of the count.
            for (int i = 1; i <= max + 1; i++) {
                items.add(item(String.format("%sC%02d", PROBE, i), category, target.get("serviceType")));
            }
        } else {
            ruleType = "ITEM_INCOMPATIBILITY";
            String withCategory = target.get("conflictsWithCategory");
            String withItem = target.get("conflictsWithItemId");
            String counterpartCategory = withCategory != null ? withCategory : secondCategory(null);
            String subjectCategory = firstCategoryOtherThan(counterpartCategory);
            boolean subjectWins = aWins(target.get("winner"), subjectCategory, counterpartCategory);

            String subjectId = target.get("itemId");
            boolean exemptIsSubject = exemptId.equals(subjectId);
            if (exemptIsSubject == subjectWins) {
                return Probe.unreachable(ruleId, "Exceptions", describe(ruleId, "cannot lose " + targetId),
                        "exempts item " + exemptId + " from " + targetId + ", but that item is the "
                        + "winner of that rule, so there is nothing to rescue it from");
            }
            items.add(item(subjectId, subjectCategory, target.get("serviceType")));
            if (withItem != null) {
                items.add(item(withItem, counterpartCategory, target.get("serviceType")));
            }
            if (withCategory != null) {
                items.add(item(PROBE + "B", withCategory, target.get("serviceType")));
            }
        }

        // Whatever the exception is conditioned on has to be in the request too.
        String whenCategory = rule.get("whenCategoryPresent");
        String whenItem = rule.get("whenItemPresent");
        if (whenCategory != null && items.stream().noneMatch(i -> whenCategory.equals(i.get("discountCategory")))) {
            items.add(item(PROBE + "COND", whenCategory, null));
        }
        if (whenItem != null && items.stream().noneMatch(i -> whenItem.equals(i.get("itemId")))) {
            items.add(item(whenItem, neutralCategory(), null));
        }

        return new Probe(ruleId, "Exceptions",
                describe(ruleId, "rescues " + exemptId + " from " + targetId),
                request(target.get("channel"), items),
                Expectation.exempts(targetId, ruleType, exemptId, ruleId));
    }

    private Map<String, String> findRule(String ruleId) {
        for (String table : RuleTables.RULE_TABLES) {
            for (Map<String, String> row : tables.table(table)) {
                if (ruleId.equals(row.get("ruleId"))) {
                    return row;
                }
            }
        }
        return null;
    }

    /** The easiest rule to force an item to lose: any category rule will do. */
    private Map<String, String> firstCatchableRule() {
        if (!tables.categoryRules().isEmpty()) {
            return tables.categoryRules().get(0);
        }
        if (!tables.cardinalityRules().isEmpty()) {
            return tables.cardinalityRules().get(0);
        }
        return null;
    }

    // ------------------------------------------------------------------ pieces

    private String firstCategoryOtherThan(String excluded) {
        for (String category : tables.rankedCategories()) {
            if (!category.equals(excluded)) {
                return category;
            }
        }
        throw new IllegalStateException("The precedence table needs at least two categories");
    }

    private String secondCategory(String excluded) {
        List<String> ranked = tables.rankedCategories();
        for (int i = ranked.size() - 1; i >= 0; i--) {
            if (!ranked.get(i).equals(excluded)) {
                return ranked.get(i);
            }
        }
        throw new IllegalStateException("The precedence table needs at least two categories");
    }

    /**
     * A category no rule mentions, for filler items that must not start a conflict of their own.
     */
    public String neutralCategory() {
        for (String category : tables.rankedCategories()) {
            if (!mentioned(category)) {
                return category;
            }
        }
        // Every ranked category is spoken for; an unranked one cannot trigger a category rule.
        return "ProbeNeutralCategory";
    }

    private boolean mentioned(String category) {
        for (Map<String, String> r : tables.categoryRules()) {
            if (category.equals(r.get("categoryA")) || category.equals(r.get("categoryB"))) {
                return true;
            }
        }
        for (Map<String, String> r : tables.itemRules()) {
            if (category.equals(r.get("conflictsWithCategory"))) {
                return true;
            }
        }
        for (Map<String, String> r : tables.cardinalityRules()) {
            if (category.equals(r.get("discountCategory"))) {
                return true;
            }
        }
        return false;
    }

    public Map<String, Object> item(String itemId, String category, String serviceType) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("itemId", itemId);
        item.put("itemName", "Probe " + itemId);
        item.put("itemType", "PROMOTION");
        item.put("discountCategory", category);
        if (serviceType != null) {
            item.put("serviceType", serviceType);
        }
        return item;
    }

    public static Map<String, Object> request(String channel, List<Map<String, Object>> items) {
        Map<String, Object> request = new LinkedHashMap<>();
        if (channel != null) {
            request.put("Channel", channel);
        }
        request.put("Items", items);
        return request;
    }

    private static String describe(String ruleId, String what) {
        return ruleId + ": " + what;
    }

    /** A generated case: what to send, and what the rule says should come back. */
    public record Probe(String ruleId, String table, String name,
                        Map<String, Object> request, Expectation expect) {

        static Probe unreachable(String ruleId, String table, String name, String why) {
            return new Probe(ruleId, table, name, null, Expectation.unreachable(why));
        }

        public boolean isUnreachable() {
            return expect.unreachableReason() != null;
        }
    }

    /**
     * What the rule row declares. Assertions are made against the conflict list rather than the
     * rejected list: a conflict entry reports what one named rule did, so it stays true even when
     * another rule happens to catch the same item first.
     */
    public record Expectation(String ruleId, String ruleType, String resolution,
                              String winnerItemId, List<String> loserItemIds,
                              String exemptedItemId, String exemptedByRuleId,
                              String unreachableReason) {

        static Expectation resolvedBy(String ruleId, String ruleType, String resolution,
                                      String winnerItemId, List<String> loserItemIds) {
            return new Expectation(ruleId, ruleType, resolution, winnerItemId, loserItemIds,
                    null, null, null);
        }

        static Expectation exempts(String ruleId, String ruleType, String exemptedItemId,
                                   String exemptedByRuleId) {
            return new Expectation(ruleId, ruleType, null, null, null,
                    exemptedItemId, exemptedByRuleId, null);
        }

        static Expectation unreachable(String why) {
            return new Expectation(null, null, null, null, null, null, null, why);
        }
    }
}
