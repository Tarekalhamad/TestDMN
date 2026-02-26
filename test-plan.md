# Test Plan: PromotionCompatibility DMN

## Current State

The existing tests in `DiscountRulesTest.java` are **REST integration tests** (Spring Boot + RestAssured). They boot the entire application, hit `POST /PromotionCompatibility`, and assert JSON responses. This works but has two problems:

1. **Hardcoded promo IDs and names** — when a business manager changes the DMN tables, these tests break even though the logic is still correct.
2. **Slow** — each test run boots Spring Boot + Tomcat.

## Goal

Replace the current Java tests with **Kogito Test Scenario Simulation (.scesim)** files — the industry-standard way to test DMN decision tables in Drools/Kogito.

## What is .scesim?

- XML-based test scenario files that live in `src/test/resources/`
- Each row = one test scenario with **Given** (inputs) and **Expect** (outputs)
- Runs via Maven Surefire — needs a JUnit activator class but no actual test logic
- Editable visually in the **Kogito DMN editor** (same tool business managers use)
- When DMN data changes, you update the `.scesim` file in the same editor session

## Known Blocker (from previous attempt)

The `KogitoJunitActivator` (JUnit 4 `@RunWith`) discovers `.scesim` files by scanning `java.class.path` via `ResourceHelper.getResourcesByExtension("scesim")`. On our setup (Maven Surefire 3.5.3 + Windows), this correctly finds the file but returns **0 test children**. The Vintage engine is present and the file is on the classpath. The root cause is likely in how the `ScenarioJunitActivator.getChildren()` pipeline (parse → filter) handles the file. Possible issues:

- The `.scesim` XML parsing fails silently (the filter in `getChildren()` may swallow parse errors)
- The DMN namespace/name in the `.scesim` settings doesn't match what the Kie container expects
- Kogito 10.1.0 may require a `kmodule.xml` or specific classpath layout for the Kie container

**Next step to unblock**: Add debug logging or a custom test that calls `ScenarioJunitActivator.getChildren()` directly to see if it returns an empty list or throws. Alternatively, try the Drools 10 `ScenarioJunitActivator` directly (without the Kogito wrapper) since this is a pure Drools project.

---

## Test Scenarios

The test plan covers **two layers** of the DMN logic:

### Layer 1: Category-level compatibility (CategoryRules decision table)

These test that when two discount categories are incompatible, the higher-precedence category wins.

### Layer 2: Individual promotion rules (PromotionRules decision table)

These test specific business rules like "promo X is blocked when group Y is present" or "promo A and promo B can't coexist."

---

### Test 1: Category conflict — EmployeeDiscount vs LegacyBindingDiscount

| | Value |
|---|---|
| **Input** | P001 (EmployeeDiscount), P002 (LegacyBindingDiscount) |
| **Expected Allowed** | P001 (appliedOrder: 1) |
| **Expected Rejected** | P002 — reason contains "EmployeeDiscount" |
| **What it verifies** | Category conflict, higher precedence wins |

### Test 2: Compatible categories — GeoDiscount + KomboDiscount

| | Value |
|---|---|
| **Input** | P010 (GeoDiscount), P011 (KomboDiscount) |
| **Expected Allowed** | P010 (order: 1), P011 (order: 2) |
| **Expected Rejected** | *empty* |
| **What it verifies** | Compatible categories both survive |

### Test 3: Three-way category conflict

| | Value |
|---|---|
| **Input** | P020 (OTSDiscount), P021 (EmployeeDiscount), P022 (LegacyBindingDiscount) |
| **Expected Allowed** | P021 (EmployeeDiscount, order: 1) |
| **Expected Rejected** | P020, P022 (2 items) |
| **What it verifies** | Highest precedence wins over multiple incompatible categories |

### Test 4: Promotion rule R1 — block by group (299 blocked by EmployeeDiscount)

| | Value |
|---|---|
| **Input** | 299 (SignUpDiscount), 103 (EmployeeDiscount) |
| **Expected Allowed** | 103 (order: 1) |
| **Expected Rejected** | 299 — reason contains "Blocked by rule" |
| **What it verifies** | `blockedByGroup` fires when group is present |

### Test 5: Promotion rule R1 negative — trigger group absent

| | Value |
|---|---|
| **Input** | 299 (SignUpDiscount), 72 (GeoDiscount) |
| **Expected Allowed** | 299 (order: 1), 72 (order: 2) |
| **Expected Rejected** | *empty* |
| **What it verifies** | R1 does NOT fire when EmployeeDiscount is absent |

### Test 6: Promotion rule R2 — block by promotion (355 blocked by 449)

| | Value |
|---|---|
| **Input** | 355 (HWDiscount), 449 (HWDiscount) |
| **Expected Allowed** | 449 (order: 1) |
| **Expected Rejected** | 355 — reason contains "Blocked by rule" |
| **What it verifies** | `blockedByPromotion` fires when specific promo present |

### Test 7: Promotion rule R2 negative — trigger promo absent

| | Value |
|---|---|
| **Input** | 355 (HWDiscount), 5 (KomboDiscount) |
| **Expected Allowed** | 355 (order: 1), 5 (order: 2) |
| **Expected Rejected** | *empty* |
| **What it verifies** | R2 does NOT fire when promo 449 is absent |

### Test 8: OR-rule R3 — triggered by group leg (OTSDiscount)

| | Value |
|---|---|
| **Input** | 263 (SignUpDiscount), 314 (OTSDiscount) |
| **Expected Allowed** | 314 (order: 1) |
| **Expected Rejected** | 263 — reason contains "Blocked by rule" |
| **What it verifies** | R3 `blockedByGroup` OR leg fires on group |

### Test 9: OR-rule R3 — triggered by promotion leg (389)

| | Value |
|---|---|
| **Input** | 263 (SignUpDiscount), 389 (SignUpDiscount) |
| **Expected Allowed** | 389 (order: 1) |
| **Expected Rejected** | 263 — reason contains "Blocked by rule" |
| **What it verifies** | R3 `blockedByPromotion` OR leg fires on promo |

### Test 10: OR-rule R3 — both legs triggered, no duplicate

| | Value |
|---|---|
| **Input** | 263 (SignUpDiscount), 389 (SignUpDiscount), 446 (OTSDiscount) |
| **Expected Allowed** | 389 (order: 1), 446 (order: 2) |
| **Expected Rejected** | 263 — appears exactly ONCE |
| **What it verifies** | `distinct values` prevents duplicate rejection |

### Test 11: OR-rule R3 negative — neither leg triggered

| | Value |
|---|---|
| **Input** | 263 (SignUpDiscount), 72 (GeoDiscount) |
| **Expected Allowed** | 263 (order: 1), 72 (order: 2) |
| **Expected Rejected** | *empty* |
| **What it verifies** | R3 does NOT fire when neither OTSDiscount nor 389 present |

### Test 12: Multiple promotion rules fire (R1 + R4)

| | Value |
|---|---|
| **Input** | 299 (SignUpDiscount), 400 (SignUpDiscount), 103 (EmployeeDiscount), 408 (SaveDeskDiscount) |
| **Expected Allowed** | 103 (order: 1), 408 (order: 2) |
| **Expected Rejected** | 299 (R1), 400 (R4) — 2 items with distinct reasons |
| **What it verifies** | Multiple independent rules fire simultaneously |

### Test 13: Category + promotion rule combined (R1 + category conflict)

| | Value |
|---|---|
| **Input** | 299 (SignUpDiscount), 103 (EmployeeDiscount), 291 (LegacyBindingDiscount) |
| **Expected Allowed** | 103 (order: 1) |
| **Expected Rejected** | 291 (category conflict), 299 (promotion rule R1) |
| **What it verifies** | Both rejection types coexist in one response |

### Test 14: Blocked promo not in input (R1 target absent)

| | Value |
|---|---|
| **Input** | 104 (EmployeeDiscount), 72 (GeoDiscount) |
| **Expected Allowed** | 104 (order: 1), 72 (order: 2) |
| **Expected Rejected** | *empty* |
| **What it verifies** | Rule fires but target (299) isn't in input — nothing blocked |

### Test 15: Explicit overrideWinner (R5: 73 wins over 72)

| | Value |
|---|---|
| **Input** | 72 (GeoDiscount), 73 (GeoDiscount) |
| **Expected Allowed** | 73 (order: 1) |
| **Expected Rejected** | 72 — reason contains "GEO" |
| **What it verifies** | `overrideWinner` with specific promo ID |

### Test 16: BY_PRECEDENCE overrideWinner (R6: SignUpDiscount beats OTSDiscount)

| | Value |
|---|---|
| **Input** | 272 (OTSDiscount), 306 (SignUpDiscount) |
| **Expected Allowed** | 306 (order: 1) |
| **Expected Rejected** | 272 — reason contains "SignUpDiscount" |
| **What it verifies** | `overrideWinner=BY_PRECEDENCE` resolves by category rank |
| **Note** | OTSDiscount vs SignUpDiscount is also a category conflict, so category rule may fire first |

### Test 17: Single promotion passes through

| | Value |
|---|---|
| **Input** | P040 (OTSDiscount) |
| **Expected Allowed** | P040 (order: 1) |
| **Expected Rejected** | *empty* |
| **What it verifies** | No conflicts when only one promo |

### Test 18: Empty input

| | Value |
|---|---|
| **Input** | *empty list* |
| **Expected Allowed** | *empty* |
| **Expected Rejected** | *empty* |
| **What it verifies** | Edge case — no promotions |

### Test 19: Binding vs HWSubscription — binding wins

| | Value |
|---|---|
| **Input** | P050 (HWSubscriptionDiscount), P051 (LegacyBindingDiscount) |
| **Expected Allowed** | P051 (order: 1) |
| **Expected Rejected** | P050 — reason contains "LegacyBindingDiscount" |
| **What it verifies** | Category conflict between LegacyBindingDiscount and HWSubscriptionDiscount |

---

## Summary Matrix

| Test | Layer | Rule(s) | Trigger | Fired? | What it verifies |
|------|-------|---------|---------|--------|-----------------|
| 1 | Category | CategoryRules | Employee vs Binding | Yes | Basic category conflict |
| 2 | Category | CategoryRules | Geo + Kombo | No | Compatible categories |
| 3 | Category | CategoryRules | 3-way conflict | Yes | Multi-category conflict |
| 4 | Promo | R1 | blockedByGroup | Yes | Block by group |
| 5 | Promo | R1 | blockedByGroup | No | No block when trigger absent |
| 6 | Promo | R2 | blockedByPromotion | Yes | Block by promo |
| 7 | Promo | R2 | blockedByPromotion | No | No block when trigger promo absent |
| 8 | Promo | R3 | OR — group leg | Yes | OR-rule fires on group |
| 9 | Promo | R3 | OR — promo leg | Yes | OR-rule fires on promo |
| 10 | Promo | R3 | OR — both legs | Yes | No duplicate rejection |
| 11 | Promo | R3 | OR — neither leg | No | OR-rule does NOT fire |
| 12 | Promo | R1 + R4 | Multiple rules | Yes | Two blocks simultaneously |
| 13 | Both | R1 + category | Category + promo rule | Yes | Both rejection types |
| 14 | Promo | R1 | Target absent | N/A | Rule fires but target not in input |
| 15 | Promo | R5 | Explicit overrideWinner | Yes | overrideWinner with promo ID |
| 16 | Promo | R6 | BY_PRECEDENCE | Yes | overrideWinner by precedence |
| 17 | Edge | — | Single promo | N/A | Pass-through |
| 18 | Edge | — | Empty input | N/A | Edge case |
| 19 | Category | CategoryRules | Binding vs HW | Yes | Another category conflict |

## Implementation Options

### Option A: Fix .scesim (recommended long-term)

Unblock the `KogitoJunitActivator` so the 19 scenarios above live in a `.scesim` file editable in the Kogito DMN editor. This is the industry-standard approach. See "Known Blocker" section above.

### Option B: Keep REST integration tests (current)

Keep the existing `DiscountRulesTest.java` approach but expand to 19 tests. Pros: works today. Cons: hardcoded, slow, not editable by business managers.

### Option C: Unit-test the DMN engine directly (no Spring Boot)

Use `DmnRuntime` to evaluate the DMN programmatically in a plain JUnit 5 test — no REST layer, no Spring Boot. Faster than Option B, still Java-based.

## Files involved

| File | Action |
|------|--------|
| `src/test/resources/PromotionCompatibilityTest.scesim` | Already exists (2 scenarios) — expand to 19 |
| `src/test/java/testscenario/ScenarioJunitActivatorTest.java` | Already exists — keep |
| `src/test/java/com/example/discount/DiscountRulesTest.java` | Delete once .scesim works |
| `pom.xml` | Already has kogito-scenario-simulation + junit-vintage-engine + surefire config |
