# Plan: DMN Promotion/Discount Compatibility Rules

## Context

The Telenor Promotion API (TMF-based) allows promotions to independently grant discount product offerings to customers. Currently there is no mechanism to prevent incompatible discounts from being combined. The business needs rules to control which discount types can coexist, with the ability for business managers to easily update these rules over time.

**Goal:** A DMN-based `PromotionCompatibility` service that evaluates a list of eligible promotions and returns only the allowed (compatible) ones, ordered by precedence, along with rejection reasons for the ones that were removed.

---

## Architecture: Single DMN Decision

One DMN file (`src/main/resources/PromotionCompatibility.dmn`) with a **single decision** (`CompatibilityResult`) that contains all static data tables and FEEL logic as context entries. This design ensures the API response is clean — no sub-decision noise.

```
EligiblePromotions (Input)
        |
        v
CompatibilityResult (Boxed Context)
  ├── categoryPrecedence          (FEEL list literal - static data)
  ├── categoryIncompatibilities   (FEEL list literal - static data)
  ├── individualIncompatibilities (FEEL list literal - static data)
  ├── precedenceOf()              (helper function)
  ├── categoriesPresent           (Step 1: distinct categories)
  ├── activeConflicts             (Step 2: filter incompatibilities)
  ├── conflictResolutions         (Step 3: determine winner/loser)
  ├── losingCategories            (Step 4: collect losers)
  ├── activeBlocks                (Step 5: individual blocks)
  ├── blockedPromotionIds         (Step 6: extract IDs)
  ├── surviving                   (Step 7: filter)
  ├── sorted                      (Step 8: sort by precedence)
  ├── allowedResult               (Step 9: map with appliedOrder)
  ├── categoryRejections          (Step 10: rejection reasons - conflicts)
  ├── individualRejections        (Step 11: rejection reasons - blocks)
  ├── rejectedResult              (Step 12: combine rejections)
  └── {AllowedPromotions, RejectedPromotions} (Final output)
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

## Static Data Tables (FEEL List Literals inside CompatibilityResult context)

### Table 1: categoryPrecedence
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

### Table 2: categoryIncompatibilities
Declares which discount category pairs CANNOT coexist. `overrideWinner` is normally null (resolved by precedence), but can be set to force a specific winner for that pair.

| categoryA | categoryB | overrideWinner |
|---|---|---|
| EmployeeDiscount | LegacyBindingDiscount | null |
| EmployeeDiscount | OTSDiscount | null |
| EmployeeDiscount | HWSubscriptionDiscount | null |
| EmployeeDiscount | ChurnBlockerDiscount | null |
| LegacyBindingDiscount | HWSubscriptionDiscount | null |
| LegacyBindingDiscount | HWSubscriptionDiscountLimited | null |
| LegacyBindingDiscount | HWSubscriptionDiscountAccessory | null |
| OTSDiscount | SignUpDiscount | null |
| OTSDiscount | SignUpDiscountLimited | null |

Business managers add/remove entries in the FEEL list to change rules.

### Table 3: individualIncompatibilities
Blocks specific promotion IDs (not categories) based on presence of a category or another specific promotion.

| blockedPromotionId | whenCategoryPresent | whenPromotionIdPresent | ruleName |
|---|---|---|---|
| PROMO-ATL-001 | EmployeeDiscount | null | Block ATL campaign when employee discount present |

Business managers add entries to the FEEL list as needed.

---

## FEEL Logic Steps (inside CompatibilityResult context)

1. **precedenceOf(cat)** — Helper function: looks up precedenceRank for a category (returns 999 if not found)
2. **categoriesPresent** — Extracts distinct discountCategory values from EligiblePromotions
3. **activeConflicts** — Filters categoryIncompatibilities where BOTH categories are present in input
4. **conflictResolutions** — For each active conflict, determines winner (lower rank or overrideWinner) and loser
5. **losingCategories** — Distinct list of all losing categories from step 4
6. **activeBlocks** — Evaluates individualIncompatibilities to find which block rules are triggered
7. **blockedPromotionIds** — Distinct promotion IDs from step 6
8. **surviving** — Filters EligiblePromotions: removes promotions in losing categories + individually blocked IDs
9. **sorted** — Sorts survivors by precedence rank (ascending)
10. **allowedResult** — Maps sorted list to output structure with `appliedOrder` (1, 2, 3...)
11. **categoryRejections** — Builds rejection list for category-conflict losers with reason: `"Incompatible with [winnerCategory] (higher precedence)"`
12. **individualRejections** — Builds rejection list for individually blocked promotions with reason: `"Individually blocked: [ruleName]"`
13. **rejectedResult** — Concatenates both rejection lists
14. **Final output** — `{AllowedPromotions: allowedResult, RejectedPromotions: rejectedResult}`

---

## How to Modify Rules

### Add a new discount category
1. Add a new entry to `categoryPrecedence` list with the category name and rank number
2. Add any incompatibility pairs to `categoryIncompatibilities` list

### Add a new incompatibility rule
Add an entry to `categoryIncompatibilities`:
```feel
{categoryA: "CategoryX", categoryB: "CategoryY", overrideWinner: null}
```
Set `overrideWinner` to a specific category name to force it as the winner regardless of precedence.

### Block a specific promotion
Add an entry to `individualIncompatibilities`:
```feel
{blockedPromotionId: "PROMO-ID", whenCategoryPresent: "SomeCategory", whenPromotionIdPresent: null, ruleName: "Descriptive rule name"}
```

### Change category precedence
Change the `precedenceRank` number in `categoryPrecedence`. Lower rank = higher precedence.

---

## Files

| File | Purpose |
|------|---------|
| `src/main/resources/PromotionCompatibility.dmn` | The DMN file with all rules and logic |
| `src/main/java/com/example/discount/DiscountApplication.java` | Spring Boot application entry point |
| `src/test/java/com/example/discount/DiscountRulesTest.java` | 7 integration tests |
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
4. **Individual block** — PROMO-ATL-001 blocked when EmployeeDiscount category present, reason: "Individually blocked: ..."
5. **Single promotion** — passes through unchanged, 0 rejected
6. **Empty input** — returns empty lists
7. **Binding vs HW subscription** — LegacyBindingDiscount wins by precedence (rank 7 vs 9), 1 rejected

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
