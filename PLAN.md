# Plan: DMN Promotion/Discount Compatibility Rules

## Context

The Telenor Promotion API (TMF-based) allows promotions to independently grant discount product offerings to customers. Currently there is no mechanism to prevent incompatible discounts from being combined. The business needs rules to control which discount types can coexist, with the ability for business managers to easily update these rules over time.

**Goal:** A DMN-based `PromotionCompatibility` service that evaluates a list of eligible promotions and returns only the allowed (compatible) ones, ordered by precedence, along with rejection reasons for the ones that were removed.

---

## Architecture: Multi-Decision DMN with Editable Tables

One DMN file (`src/main/resources/PromotionCompatibility.dmn`) with **4 decisions**:
- **3 lookup-table decisions** (Decision Tables) — business managers edit these directly as spreadsheet grids
- **1 main decision** (`CompatibilityResult`) — contains FEEL resolution logic, not touched by business managers

**Note:** The API response now includes `CategoryPrecedence`, `CategoryRules`, and `PromotionRules` as additional top-level keys (additive only, does not break existing consumers).

```
EligiblePromotions (Input)
        |
        v
                    CompatibilityResult (Boxed Context - resolution logic)
                   /           |            \              \
                  /            |             \              \
 CategoryPrecedence      CategoryRules      PromotionRules    EligiblePromotions
 (Decision Table)        (Decision Table)   (Decision Table)  (Input Data)
 18 rows, COLLECT        9 rows, COLLECT    6 rows, COLLECT

CompatibilityResult internals (resolution logic - do not edit):
  precedenceOf() → categoriesPresent → activeConflicts → conflictResolutions
  → losingCategories → activeBlocks → blockedPromotionIds → surviving
  → sorted → allowedResult → categoryRejections → promotionRejections
  → rejectedResult → {AllowedPromotions, RejectedPromotions}
```

Kogito auto-generates: `POST /PromotionCompatibility`

---

## Custom Data Types (Item Definitions)

**tPromotion** (input):
- `promotionId` (string), `promotionName` (string), `promotionBaseType` (string), `discountCategory` (string)

**tAllowedPromotion** (output):
- `promotionId` (string), `promotionName` (string), `discountCategory` (string), `appliedOrder` (number)

**tRejectedPromotion** (output):
- `promotionId` (string), `promotionName` (string), `discountCategory` (string), `reason` (string)

Collection types: `tPromotionList`, `tAllowedPromotionList`, `tRejectedPromotionList`

---

## Static Data Tables (Separate Decision Tables — editable as spreadsheet grids)

> **Note about the "match" column in the DMN editor:** When you open a decision table in the DMN editor, you will see a first input column containing a dash (`-`) in every row. This is a technical requirement of the DMN standard — every decision table must have at least one input column. Since these tables are static lookup data (not conditional rules), the dash means "always include this row." **You can safely ignore this column. Do not edit or remove it.**

### Table 1: CategoryPrecedence
Lower rank = higher precedence = wins conflicts. Business managers reorder by changing the rank number.

| discountCategory | precedenceRank |
|---|---|
| EmployeeDiscount | 1 |
| ComplaintDiscount | 2 |
| SaveDeskDiscount | 3 |
| ChurnBlockerDiscount | 4 |
| SignUpDiscount | 5 |
| SignUpDiscountLimited | 6 |
| LegacyBindingDiscount | 7 |
| OTSDiscount | 8 |
| HWSubscriptionDiscount | 9 |
| HWSubscriptionDiscountLimited | 10 |
| HWSubscriptionDiscountAccessory | 11 |
| HWDiscount | 12 |
| GeoDiscount | 13 |
| KomboDiscount | 14 |
| PromotionOffering | 15 |
| RecurringExtraSurf | 16 |
| MiscMigratedDiscount | 17 |
| MiscMigratedDiscountLimited | 18 |

### Table 2: CategoryRules
Declares which discount group pairs CANNOT coexist. `overrideWinner` is normally empty (resolved by precedence), but can be set to force a specific winner for that pair.

| groupA | groupB | overrideWinner |
|---|---|---|
| EmployeeDiscount | LegacyBindingDiscount | |
| EmployeeDiscount | OTSDiscount | |
| EmployeeDiscount | HWSubscriptionDiscount | |
| EmployeeDiscount | ChurnBlockerDiscount | |
| LegacyBindingDiscount | HWSubscriptionDiscount | |
| LegacyBindingDiscount | HWSubscriptionDiscountLimited | |
| LegacyBindingDiscount | HWSubscriptionDiscountAccessory | |
| OTSDiscount | SignUpDiscount | |
| OTSDiscount | SignUpDiscountLimited | |

Business managers add/remove rows in the Decision Table grid to change rules.

### Table 3: PromotionRules
Controls which specific promotions cannot coexist with a group or another promotion.

| promotion | blockedByGroup | blockedByPromotion | overrideWinner | description |
|---|---|---|---|---|
| PROMO-ATL-001 | EmployeeDiscount | | | ATL campaign blocked by employee discount |
| PROMO-SUMMER-01 | | PROMO-VIP-99 | | Summer sale blocked by VIP deal |
| PROMO-FLASH-01 | OTSDiscount | PROMO-LOYAL-01 | | Flash sale blocked by OTS or loyalty |
| PROMO-RETAIN-05 | SaveDeskDiscount | | | Retention offer blocked by save desk |
| PROMO-EAST-01 | | PROMO-WEST-01 | PROMO-WEST-01 | East and West can't combine, West wins |
| PROMO-BAS-01 | | PROMO-PREM-01 | BY_PRECEDENCE | Basic and Premium can't combine, precedence decides |

**How to read each column:**
- **promotion** — the promotion affected by this rule
- **blockedByGroup** — if this discount group is present, the rule triggers (leave empty if not applicable)
- **blockedByPromotion** — if this specific promotion is present, the rule triggers (leave empty if not applicable)
- **overrideWinner** — who wins when both are present? (same concept as in CategoryRules)
  - *Empty* = always block `promotion`
  - *A promotion ID* = that specific promotion wins, the other is rejected
  - `BY_PRECEDENCE` = compare both promotions' group precedence, higher precedence wins
- **description** — human-readable explanation of the rule

If both `blockedByGroup` and `blockedByPromotion` are filled, the rule triggers if EITHER condition is met (OR logic).

Business managers add rows in the Decision Table grid as needed.

---

## FEEL Logic Steps (inside CompatibilityResult context)

1. **precedenceOf(cat)** — Helper function: looks up precedenceRank for a category (returns 999 if not found)
2. **categoriesPresent** — Extracts distinct discountCategory values from EligiblePromotions
3. **activeConflicts** — Filters categoryRules where BOTH groups are present in input
4. **conflictResolutions** — For each active conflict, determines winner (lower rank or overrideWinner) and loser
5. **losingCategories** — Distinct list of all losing categories from step 4
6. **activeBlocks** — Evaluates promotionRules to find which rules are triggered
7. **blockedPromotionIds** — Determines which promo IDs to block, considering the overrideWinner column
8. **surviving** — Filters EligiblePromotions: removes promotions in losing categories + blocked IDs
9. **sorted** — Sorts survivors by precedence rank (ascending)
10. **allowedResult** — Maps sorted list to output structure with `appliedOrder` (1, 2, 3...)
11. **categoryRejections** — Builds rejection list for category-conflict losers with reason: `"Incompatible with [winnerCategory] (higher precedence)"`
12. **promotionRejections** — Builds rejection list for promotion-rule blocked promotions with reason: `"Blocked by rule: [description]"`
13. **rejectedResult** — Concatenates both rejection lists
14. **Final output** — `{AllowedPromotions: allowedResult, RejectedPromotions: rejectedResult}`

---

## How to Modify Rules (for Business Managers)

Open `PromotionCompatibility.dmn` in a DMN editor (VS Code Kogito plugin or Kogito online editor). Each table is a separate clickable decision node in the DRG diagram.

### Add a new discount category
1. Click **CategoryPrecedence** in the DRG → add a row with the category name and rank number
2. If needed, click **CategoryRules** → add rows for conflict pairs

### Add a new group incompatibility rule
Click **CategoryRules** → add a row: fill `groupA`, `groupB`, leave `overrideWinner` empty (uses precedence). To force a winner, type the group name in `overrideWinner`.

### Block a specific promotion
Click **PromotionRules** → add a row: fill `promotion`, `blockedByGroup` (or `blockedByPromotion`), and `description`. Leave `overrideWinner` empty to always block the promotion.

### Make two promotions mutually exclusive
Click **PromotionRules** → add a row: fill `promotion` with one promo ID, `blockedByPromotion` with the other. Set `overrideWinner` to the promo ID that should win, or `BY_PRECEDENCE` to let group rank decide.

### Change category precedence
Click **CategoryPrecedence** → change the `precedenceRank` number. Lower rank = higher precedence.

---

## Files

| File | Purpose |
|------|---------|
| `src/main/resources/PromotionCompatibility.dmn` | The DMN file with all rules and logic |
| `src/main/java/com/example/discount/DiscountApplication.java` | Spring Boot application entry point |
| `src/test/java/com/example/discount/DiscountRulesTest.java` | 9 integration tests |
| `pom.xml` | Maven config: Spring Boot 3.5.5, Drools/Kogito 10.1.0 |

---

## API

### Endpoint
`POST /PromotionCompatibility`

### Request
```json
{
  "EligiblePromotions": [
    {
      "promotionId": "273",
      "promotionName": "Discount OTS - New MPO Obegransat",
      "promotionBaseType": "SubscriptionPromotion",
      "discountCategory": "OTSDiscount"
    },
    {
      "promotionId": "500",
      "promotionName": "Employee Discount 50%",
      "promotionBaseType": "SubscriptionPromotion",
      "discountCategory": "EmployeeDiscount"
    },
    {
      "promotionId": "600",
      "promotionName": "Binding Discount 24mo",
      "promotionBaseType": "SubscriptionPromotion",
      "discountCategory": "LegacyBindingDiscount"
    }
  ]
}
```

### Response
```json
{
  "CompatibilityResult": {
    "AllowedPromotions": [
      {
        "promotionId": "500",
        "promotionName": "Employee Discount 50%",
        "discountCategory": "EmployeeDiscount",
        "appliedOrder": 1
      }
    ],
    "RejectedPromotions": [
      {
        "promotionId": "273",
        "promotionName": "Discount OTS - New MPO Obegransat",
        "discountCategory": "OTSDiscount",
        "reason": "Incompatible with EmployeeDiscount (higher precedence)"
      },
      {
        "promotionId": "600",
        "promotionName": "Binding Discount 24mo",
        "discountCategory": "LegacyBindingDiscount",
        "reason": "Incompatible with EmployeeDiscount (higher precedence)"
      }
    ]
  },
  "EligiblePromotions": [...]
}
```

---

## Test Cases

1. **Two incompatible categories** — EmployeeDiscount + LegacyBindingDiscount → only EmployeeDiscount survives, 1 rejected
2. **Two compatible categories** — GeoDiscount + KomboDiscount → both survive, 0 rejected
3. **Three-way conflict** — EmployeeDiscount + OTSDiscount + LegacyBindingDiscount → only EmployeeDiscount, 2 rejected
4. **Promotion rule block** — PROMO-ATL-001 blocked when EmployeeDiscount group present, reason: "Blocked by rule: ..."
5. **Single promotion** — passes through unchanged, 0 rejected
6. **Empty input** — returns empty lists
7. **Binding vs HW subscription** — LegacyBindingDiscount wins by precedence (rank 7 vs 9), 1 rejected
8. **Explicit overrideWinner** — PROMO-EAST-01 + PROMO-WEST-01, overrideWinner=PROMO-WEST-01 → East rejected, West allowed
9. **BY_PRECEDENCE overrideWinner** — PROMO-BAS-01 (OTSDiscount rank 8) + PROMO-PREM-01 (PromotionOffering rank 15) → Premium rejected

---

## Build & Run

```bash
# Build and test
.\mvnw.cmd clean install

# Build without tests
.\mvnw.cmd clean install -DskipTests

# Run
.\mvnw.cmd spring-boot:run

# Test via Swagger UI
http://localhost:8080/swagger-ui.html
```

---

## Discount Categories Reference (from TMF Promotion API)

Source: `src/jsonExample/refferdtype`

| Code | Category Name |
|------|--------------|
| D13 | EmployeeDiscount |
| D14 | ComplaintDiscount |
| D15 | SaveDeskDiscount |
| D16 | ChurnBlockerDiscount |
| D17 | SignUpDiscount |
| D18 | SignUpDiscountLimited |
| D21 | LegacyBindingDiscount |
| D22 | OTSDiscount |
| D23 | HWSubscriptionDiscount |
| D24 | HWSubscriptionDiscountLimited |
| D25 | HWSubscriptionDiscountAccessory |
| D51 | HWDiscount |
| D52 | GeoDiscount |
| D53 | KomboDiscount |
| D54 | PromotionOffering |
| D55 | RecurringExtraSurf |
| D62 | MiscMigratedDiscount |
| D63 | MiscMigratedDiscountLimited |
