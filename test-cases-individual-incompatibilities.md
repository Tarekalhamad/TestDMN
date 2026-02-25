# PromotionRules - Test Cases

Endpoint: `POST /PromotionCompatibility`

## Rules in the Decision Table

| # | promotion | blockedByGroup | blockedByPromotion | winner | description |
|---|---|---|---|---|---|
| R1 | PROMO-ATL-001 | EmployeeDiscount | *(empty)* | *(empty)* | ATL campaign blocked by employee discount |
| R2 | PROMO-SUMMER-01 | *(empty)* | PROMO-VIP-99 | *(empty)* | Summer sale blocked by VIP deal |
| R3 | PROMO-FLASH-01 | OTSDiscount | PROMO-LOYAL-01 | *(empty)* | Flash sale blocked by OTS or loyalty |
| R4 | PROMO-RETAIN-05 | SaveDeskDiscount | *(empty)* | *(empty)* | Retention offer blocked by save desk |
| R5 | PROMO-EAST-01 | *(empty)* | PROMO-WEST-01 | PROMO-WEST-01 | East and West can't combine, West wins |
| R6 | PROMO-BAS-01 | *(empty)* | PROMO-PREM-01 | BY_PRECEDENCE | Basic and Premium can't combine, precedence decides |

---

## Test Case 1: Block by group presence (R1 triggered)

**Use case:** PROMO-ATL-001 is blocked because EmployeeDiscount group is present.

### Request
```json
{
  "EligiblePromotions": [
    {
      "promotionId": "PROMO-ATL-001",
      "promotionName": "ATL Campaign Special",
      "promotionBaseType": "SubscriptionPromotion",
      "discountCategory": "PromotionOffering"
    },
    {
      "promotionId": "P031",
      "promotionName": "Employee Discount 50%",
      "promotionBaseType": "SubscriptionPromotion",
      "discountCategory": "EmployeeDiscount"
    }
  ]
}
```

### Expected Response
```json
{
  "CompatibilityResult": {
    "AllowedPromotions": [
      {
        "promotionId": "P031",
        "promotionName": "Employee Discount 50%",
        "discountCategory": "EmployeeDiscount",
        "appliedOrder": 1
      }
    ],
    "RejectedPromotions": [
      {
        "promotionId": "PROMO-ATL-001",
        "promotionName": "ATL Campaign Special",
        "discountCategory": "PromotionOffering",
        "reason": "Blocked by rule: ATL campaign blocked by employee discount"
      }
    ]
  }
}
```

### Verify
- AllowedPromotions has 1 item (P031)
- RejectedPromotions has 1 item (PROMO-ATL-001)
- Rejection reason contains "Blocked by rule"

---

## Test Case 2: No block - trigger group absent (R1 NOT triggered)

**Use case:** PROMO-ATL-001 is sent WITHOUT EmployeeDiscount, so the rule does not fire. Both survive.

### Request
```json
{
  "EligiblePromotions": [
    {
      "promotionId": "PROMO-ATL-001",
      "promotionName": "ATL Campaign Special",
      "promotionBaseType": "SubscriptionPromotion",
      "discountCategory": "PromotionOffering"
    },
    {
      "promotionId": "P100",
      "promotionName": "Geo Discount Stockholm",
      "promotionBaseType": "SubscriptionPromotion",
      "discountCategory": "GeoDiscount"
    }
  ]
}
```

### Expected Response
```json
{
  "CompatibilityResult": {
    "AllowedPromotions": [
      {
        "promotionId": "P100",
        "promotionName": "Geo Discount Stockholm",
        "discountCategory": "GeoDiscount",
        "appliedOrder": 1
      },
      {
        "promotionId": "PROMO-ATL-001",
        "promotionName": "ATL Campaign Special",
        "discountCategory": "PromotionOffering",
        "appliedOrder": 2
      }
    ],
    "RejectedPromotions": []
  }
}
```

### Verify
- AllowedPromotions has 2 items (sorted by precedence: GeoDiscount=13, PromotionOffering=15)
- RejectedPromotions is empty
- PROMO-ATL-001 is NOT blocked (EmployeeDiscount not present)

---

## Test Case 3: Block by promotion presence (R2 triggered)

**Use case:** PROMO-SUMMER-01 is blocked because PROMO-VIP-99 is in the input.

### Request
```json
{
  "EligiblePromotions": [
    {
      "promotionId": "PROMO-SUMMER-01",
      "promotionName": "Summer Sale 2025",
      "promotionBaseType": "SubscriptionPromotion",
      "discountCategory": "PromotionOffering"
    },
    {
      "promotionId": "PROMO-VIP-99",
      "promotionName": "VIP Exclusive Deal",
      "promotionBaseType": "SubscriptionPromotion",
      "discountCategory": "PromotionOffering"
    }
  ]
}
```

### Expected Response
```json
{
  "CompatibilityResult": {
    "AllowedPromotions": [
      {
        "promotionId": "PROMO-VIP-99",
        "promotionName": "VIP Exclusive Deal",
        "discountCategory": "PromotionOffering",
        "appliedOrder": 1
      }
    ],
    "RejectedPromotions": [
      {
        "promotionId": "PROMO-SUMMER-01",
        "promotionName": "Summer Sale 2025",
        "discountCategory": "PromotionOffering",
        "reason": "Blocked by rule: Summer sale blocked by VIP deal"
      }
    ]
  }
}
```

### Verify
- AllowedPromotions has 1 item (PROMO-VIP-99)
- PROMO-SUMMER-01 is blocked by promo, not by group
- Both are in the same category (PromotionOffering) - this is NOT a category conflict

---

## Test Case 4: No block - trigger promotion absent (R2 NOT triggered)

**Use case:** PROMO-SUMMER-01 is sent WITHOUT PROMO-VIP-99. No block fires.

### Request
```json
{
  "EligiblePromotions": [
    {
      "promotionId": "PROMO-SUMMER-01",
      "promotionName": "Summer Sale 2025",
      "promotionBaseType": "SubscriptionPromotion",
      "discountCategory": "PromotionOffering"
    },
    {
      "promotionId": "P200",
      "promotionName": "Kombo Bundle Offer",
      "promotionBaseType": "SubscriptionPromotion",
      "discountCategory": "KomboDiscount"
    }
  ]
}
```

### Expected Response
```json
{
  "CompatibilityResult": {
    "AllowedPromotions": [
      {
        "promotionId": "P200",
        "promotionName": "Kombo Bundle Offer",
        "discountCategory": "KomboDiscount",
        "appliedOrder": 1
      },
      {
        "promotionId": "PROMO-SUMMER-01",
        "promotionName": "Summer Sale 2025",
        "discountCategory": "PromotionOffering",
        "appliedOrder": 2
      }
    ],
    "RejectedPromotions": []
  }
}
```

### Verify
- AllowedPromotions has 2 items
- RejectedPromotions is empty
- PROMO-SUMMER-01 survives because PROMO-VIP-99 is not in the input

---

## Test Case 5: OR-rule triggered by group leg (R3 triggered via OTSDiscount)

**Use case:** PROMO-FLASH-01 is blocked because OTSDiscount group is present (the `blockedByGroup` side of the OR).

### Request
```json
{
  "EligiblePromotions": [
    {
      "promotionId": "PROMO-FLASH-01",
      "promotionName": "Flash Sale Weekend",
      "promotionBaseType": "SubscriptionPromotion",
      "discountCategory": "PromotionOffering"
    },
    {
      "promotionId": "P300",
      "promotionName": "OTS Summer Special",
      "promotionBaseType": "SubscriptionPromotion",
      "discountCategory": "OTSDiscount"
    }
  ]
}
```

### Expected Response
```json
{
  "CompatibilityResult": {
    "AllowedPromotions": [
      {
        "promotionId": "P300",
        "promotionName": "OTS Summer Special",
        "discountCategory": "OTSDiscount",
        "appliedOrder": 1
      }
    ],
    "RejectedPromotions": [
      {
        "promotionId": "PROMO-FLASH-01",
        "promotionName": "Flash Sale Weekend",
        "discountCategory": "PromotionOffering",
        "reason": "Blocked by rule: Flash sale blocked by OTS or loyalty"
      }
    ]
  }
}
```

### Verify
- PROMO-FLASH-01 blocked by the group leg of R3
- PROMO-LOYAL-01 is NOT in the input, but the group trigger alone is enough (OR logic)

---

## Test Case 6: OR-rule triggered by promotion leg (R3 triggered via PROMO-LOYAL-01)

**Use case:** PROMO-FLASH-01 is blocked because PROMO-LOYAL-01 is present (the `blockedByPromotion` side of the OR). No OTSDiscount in input.

### Request
```json
{
  "EligiblePromotions": [
    {
      "promotionId": "PROMO-FLASH-01",
      "promotionName": "Flash Sale Weekend",
      "promotionBaseType": "SubscriptionPromotion",
      "discountCategory": "PromotionOffering"
    },
    {
      "promotionId": "PROMO-LOYAL-01",
      "promotionName": "Loyalty Reward Program",
      "promotionBaseType": "SubscriptionPromotion",
      "discountCategory": "PromotionOffering"
    }
  ]
}
```

### Expected Response
```json
{
  "CompatibilityResult": {
    "AllowedPromotions": [
      {
        "promotionId": "PROMO-LOYAL-01",
        "promotionName": "Loyalty Reward Program",
        "discountCategory": "PromotionOffering",
        "appliedOrder": 1
      }
    ],
    "RejectedPromotions": [
      {
        "promotionId": "PROMO-FLASH-01",
        "promotionName": "Flash Sale Weekend",
        "discountCategory": "PromotionOffering",
        "reason": "Blocked by rule: Flash sale blocked by OTS or loyalty"
      }
    ]
  }
}
```

### Verify
- PROMO-FLASH-01 blocked by the promo leg of R3
- OTSDiscount is NOT present, but the promo trigger alone is enough (OR logic)

---

## Test Case 7: OR-rule triggered by BOTH legs (R3 triggered via OTSDiscount AND PROMO-LOYAL-01)

**Use case:** Both trigger conditions are met at once. PROMO-FLASH-01 should still only appear once in rejections.

### Request
```json
{
  "EligiblePromotions": [
    {
      "promotionId": "PROMO-FLASH-01",
      "promotionName": "Flash Sale Weekend",
      "promotionBaseType": "SubscriptionPromotion",
      "discountCategory": "PromotionOffering"
    },
    {
      "promotionId": "PROMO-LOYAL-01",
      "promotionName": "Loyalty Reward Program",
      "promotionBaseType": "SubscriptionPromotion",
      "discountCategory": "PromotionOffering"
    },
    {
      "promotionId": "P400",
      "promotionName": "OTS Discount 200kr",
      "promotionBaseType": "SubscriptionPromotion",
      "discountCategory": "OTSDiscount"
    }
  ]
}
```

### Expected Response
```json
{
  "CompatibilityResult": {
    "AllowedPromotions": [
      {
        "promotionId": "P400",
        "promotionName": "OTS Discount 200kr",
        "discountCategory": "OTSDiscount",
        "appliedOrder": 1
      },
      {
        "promotionId": "PROMO-LOYAL-01",
        "promotionName": "Loyalty Reward Program",
        "discountCategory": "PromotionOffering",
        "appliedOrder": 2
      }
    ],
    "RejectedPromotions": [
      {
        "promotionId": "PROMO-FLASH-01",
        "promotionName": "Flash Sale Weekend",
        "discountCategory": "PromotionOffering",
        "reason": "Blocked by rule: Flash sale blocked by OTS or loyalty"
      }
    ]
  }
}
```

### Verify
- PROMO-FLASH-01 appears only ONCE in rejections (not duplicated)
- Both other promos survive
- `blockedPromotionIds` uses `distinct values` so no duplication

---

## Test Case 8: OR-rule NOT triggered (R3 not triggered - neither condition met)

**Use case:** PROMO-FLASH-01 is sent without OTSDiscount or PROMO-LOYAL-01. No block fires.

### Request
```json
{
  "EligiblePromotions": [
    {
      "promotionId": "PROMO-FLASH-01",
      "promotionName": "Flash Sale Weekend",
      "promotionBaseType": "SubscriptionPromotion",
      "discountCategory": "PromotionOffering"
    },
    {
      "promotionId": "P500",
      "promotionName": "Geo Discount Gothenburg",
      "promotionBaseType": "SubscriptionPromotion",
      "discountCategory": "GeoDiscount"
    }
  ]
}
```

### Expected Response
```json
{
  "CompatibilityResult": {
    "AllowedPromotions": [
      {
        "promotionId": "P500",
        "promotionName": "Geo Discount Gothenburg",
        "discountCategory": "GeoDiscount",
        "appliedOrder": 1
      },
      {
        "promotionId": "PROMO-FLASH-01",
        "promotionName": "Flash Sale Weekend",
        "discountCategory": "PromotionOffering",
        "appliedOrder": 2
      }
    ],
    "RejectedPromotions": []
  }
}
```

### Verify
- AllowedPromotions has 2 items
- PROMO-FLASH-01 survives (neither OTSDiscount nor PROMO-LOYAL-01 present)

---

## Test Case 9: Multiple blocks firing at once (R1 + R4)

**Use case:** Two different promotion rules fire simultaneously. Both PROMO-ATL-001 and PROMO-RETAIN-05 are blocked.

### Request
```json
{
  "EligiblePromotions": [
    {
      "promotionId": "PROMO-ATL-001",
      "promotionName": "ATL Campaign Special",
      "promotionBaseType": "SubscriptionPromotion",
      "discountCategory": "PromotionOffering"
    },
    {
      "promotionId": "PROMO-RETAIN-05",
      "promotionName": "Retention Win-back Offer",
      "promotionBaseType": "SubscriptionPromotion",
      "discountCategory": "PromotionOffering"
    },
    {
      "promotionId": "P600",
      "promotionName": "Employee Discount 50%",
      "promotionBaseType": "SubscriptionPromotion",
      "discountCategory": "EmployeeDiscount"
    },
    {
      "promotionId": "P601",
      "promotionName": "Save Desk Retention",
      "promotionBaseType": "SubscriptionPromotion",
      "discountCategory": "SaveDeskDiscount"
    }
  ]
}
```

### Expected Response
```json
{
  "CompatibilityResult": {
    "AllowedPromotions": [
      {
        "promotionId": "P600",
        "promotionName": "Employee Discount 50%",
        "discountCategory": "EmployeeDiscount",
        "appliedOrder": 1
      },
      {
        "promotionId": "P601",
        "promotionName": "Save Desk Retention",
        "discountCategory": "SaveDeskDiscount",
        "appliedOrder": 2
      }
    ],
    "RejectedPromotions": [
      {
        "promotionId": "PROMO-ATL-001",
        "promotionName": "ATL Campaign Special",
        "discountCategory": "PromotionOffering",
        "reason": "Blocked by rule: ATL campaign blocked by employee discount"
      },
      {
        "promotionId": "PROMO-RETAIN-05",
        "promotionName": "Retention Win-back Offer",
        "discountCategory": "PromotionOffering",
        "reason": "Blocked by rule: Retention offer blocked by save desk"
      }
    ]
  }
}
```

### Verify
- 2 allowed (EmployeeDiscount, SaveDeskDiscount)
- 2 blocked by promotion rules (PROMO-ATL-001 by R1, PROMO-RETAIN-05 by R4)
- Each rejection has its own distinct reason/description

---

## Test Case 10: Category conflict + promotion rule combined (R1 + category rule)

**Use case:** A promotion is rejected by category conflict AND another is blocked by promotion rule in the same request.

### Request
```json
{
  "EligiblePromotions": [
    {
      "promotionId": "PROMO-ATL-001",
      "promotionName": "ATL Campaign Special",
      "promotionBaseType": "SubscriptionPromotion",
      "discountCategory": "PromotionOffering"
    },
    {
      "promotionId": "P700",
      "promotionName": "Employee Discount 50%",
      "promotionBaseType": "SubscriptionPromotion",
      "discountCategory": "EmployeeDiscount"
    },
    {
      "promotionId": "P701",
      "promotionName": "Binding Discount 24mo",
      "promotionBaseType": "SubscriptionPromotion",
      "discountCategory": "LegacyBindingDiscount"
    }
  ]
}
```

### Expected Response
```json
{
  "CompatibilityResult": {
    "AllowedPromotions": [
      {
        "promotionId": "P700",
        "promotionName": "Employee Discount 50%",
        "discountCategory": "EmployeeDiscount",
        "appliedOrder": 1
      }
    ],
    "RejectedPromotions": [
      {
        "promotionId": "P701",
        "promotionName": "Binding Discount 24mo",
        "discountCategory": "LegacyBindingDiscount",
        "reason": "Incompatible with EmployeeDiscount (higher precedence)"
      },
      {
        "promotionId": "PROMO-ATL-001",
        "promotionName": "ATL Campaign Special",
        "discountCategory": "PromotionOffering",
        "reason": "Blocked by rule: ATL campaign blocked by employee discount"
      }
    ]
  }
}
```

### Verify
- P701 rejected by **category rule** (EmployeeDiscount vs LegacyBindingDiscount)
- PROMO-ATL-001 rejected by **promotion rule** (R1)
- Both rejection types appear in the same response
- Rejection reasons are different: "Incompatible with..." vs "Blocked by rule:..."

---

## Test Case 11: Blocked promo not in input (R1 rule exists but PROMO-ATL-001 not sent)

**Use case:** The trigger group (EmployeeDiscount) is present, but the blocked promo (PROMO-ATL-001) is not in the input. Nothing should be blocked.

### Request
```json
{
  "EligiblePromotions": [
    {
      "promotionId": "P800",
      "promotionName": "Employee Discount 50%",
      "promotionBaseType": "SubscriptionPromotion",
      "discountCategory": "EmployeeDiscount"
    },
    {
      "promotionId": "P801",
      "promotionName": "Geo Discount Malmo",
      "promotionBaseType": "SubscriptionPromotion",
      "discountCategory": "GeoDiscount"
    }
  ]
}
```

### Expected Response
```json
{
  "CompatibilityResult": {
    "AllowedPromotions": [
      {
        "promotionId": "P800",
        "promotionName": "Employee Discount 50%",
        "discountCategory": "EmployeeDiscount",
        "appliedOrder": 1
      },
      {
        "promotionId": "P801",
        "promotionName": "Geo Discount Malmo",
        "discountCategory": "GeoDiscount",
        "appliedOrder": 2
      }
    ],
    "RejectedPromotions": []
  }
}
```

### Verify
- Both survive even though EmployeeDiscount is present
- R1 fires (activeBlocks contains the rule), but PROMO-ATL-001 is not in input so nothing is filtered

---

## Test Case 12: Blocked promo alone - no trigger present (R4 target without trigger)

**Use case:** PROMO-RETAIN-05 is in input but SaveDeskDiscount is NOT. The rule should not fire.

### Request
```json
{
  "EligiblePromotions": [
    {
      "promotionId": "PROMO-RETAIN-05",
      "promotionName": "Retention Win-back Offer",
      "promotionBaseType": "SubscriptionPromotion",
      "discountCategory": "PromotionOffering"
    },
    {
      "promotionId": "P900",
      "promotionName": "Kombo Bundle Discount",
      "promotionBaseType": "SubscriptionPromotion",
      "discountCategory": "KomboDiscount"
    }
  ]
}
```

### Expected Response
```json
{
  "CompatibilityResult": {
    "AllowedPromotions": [
      {
        "promotionId": "P900",
        "promotionName": "Kombo Bundle Discount",
        "discountCategory": "KomboDiscount",
        "appliedOrder": 1
      },
      {
        "promotionId": "PROMO-RETAIN-05",
        "promotionName": "Retention Win-back Offer",
        "discountCategory": "PromotionOffering",
        "appliedOrder": 2
      }
    ],
    "RejectedPromotions": []
  }
}
```

### Verify
- PROMO-RETAIN-05 survives because SaveDeskDiscount is not in input
- R4 does NOT fire

---

## Test Case 13: Explicit winner - West wins over East (R5 triggered)

**Use case:** PROMO-EAST-01 and PROMO-WEST-01 are both present. R5 has winner=PROMO-WEST-01, so East is rejected.

### Request
```json
{
  "EligiblePromotions": [
    {
      "promotionId": "PROMO-EAST-01",
      "promotionName": "East Region Campaign",
      "promotionBaseType": "SubscriptionPromotion",
      "discountCategory": "PromotionOffering"
    },
    {
      "promotionId": "PROMO-WEST-01",
      "promotionName": "West Region Campaign",
      "promotionBaseType": "SubscriptionPromotion",
      "discountCategory": "PromotionOffering"
    }
  ]
}
```

### Expected Response
```json
{
  "CompatibilityResult": {
    "AllowedPromotions": [
      {
        "promotionId": "PROMO-WEST-01",
        "promotionName": "West Region Campaign",
        "discountCategory": "PromotionOffering",
        "appliedOrder": 1
      }
    ],
    "RejectedPromotions": [
      {
        "promotionId": "PROMO-EAST-01",
        "promotionName": "East Region Campaign",
        "discountCategory": "PromotionOffering",
        "reason": "Blocked by rule: East and West can't combine, West wins"
      }
    ]
  }
}
```

### Verify
- PROMO-WEST-01 survives (it's the explicit winner)
- PROMO-EAST-01 rejected with reason referencing the rule description
- Both are same category — this is a promotion-level rule, not a category conflict

---

## Test Case 14: BY_PRECEDENCE winner - OTSDiscount beats PromotionOffering (R6 triggered)

**Use case:** PROMO-BAS-01 (OTSDiscount, rank 8) and PROMO-PREM-01 (PromotionOffering, rank 15) are both present. R6 has winner=BY_PRECEDENCE, so the higher-precedence promo (lower rank number) wins.

### Request
```json
{
  "EligiblePromotions": [
    {
      "promotionId": "PROMO-BAS-01",
      "promotionName": "Basic Offer",
      "promotionBaseType": "SubscriptionPromotion",
      "discountCategory": "OTSDiscount"
    },
    {
      "promotionId": "PROMO-PREM-01",
      "promotionName": "Premium Offer",
      "promotionBaseType": "SubscriptionPromotion",
      "discountCategory": "PromotionOffering"
    }
  ]
}
```

### Expected Response
```json
{
  "CompatibilityResult": {
    "AllowedPromotions": [
      {
        "promotionId": "PROMO-BAS-01",
        "promotionName": "Basic Offer",
        "discountCategory": "OTSDiscount",
        "appliedOrder": 1
      }
    ],
    "RejectedPromotions": [
      {
        "promotionId": "PROMO-PREM-01",
        "promotionName": "Premium Offer",
        "discountCategory": "PromotionOffering",
        "reason": "Blocked by rule: Basic and Premium can't combine, precedence decides"
      }
    ]
  }
}
```

### Verify
- PROMO-BAS-01 survives (OTSDiscount rank 8 < PromotionOffering rank 15)
- PROMO-PREM-01 rejected because its category has lower precedence (higher rank number)
- Winner was determined dynamically by category precedence, not hardcoded

---

## Summary Matrix

| Test | Rule(s) | Trigger Type | Fired? | What it verifies |
|------|---------|-------------|--------|-----------------|
| 1 | R1 | blockedByGroup | Yes | Basic block by group |
| 2 | R1 | blockedByGroup | No | No block when trigger absent |
| 3 | R2 | blockedByPromotion | Yes | Block by promo |
| 4 | R2 | blockedByPromotion | No | No block when trigger promo absent |
| 5 | R3 | OR - group leg | Yes | OR-rule fires on group |
| 6 | R3 | OR - promo leg | Yes | OR-rule fires on promo |
| 7 | R3 | OR - both legs | Yes | Both legs met, no duplicate rejection |
| 8 | R3 | OR - neither leg | No | OR-rule does NOT fire |
| 9 | R1+R4 | Multiple rules | Yes | Two blocks fire simultaneously |
| 10 | R1 + cat rule | Category + Promotion rule | Yes | Both rejection types in same response |
| 11 | R1 | Group present, blocked promo absent | N/A | Rule fires but target not in input |
| 12 | R4 | Target present, trigger absent | No | Rule does not fire |
| 13 | R5 | Explicit winner | Yes | Winner column with specific promo ID |
| 14 | R6 | BY_PRECEDENCE winner | Yes | Winner column with precedence-based resolution |
