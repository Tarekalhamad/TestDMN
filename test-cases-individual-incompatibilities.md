# PromotionRules - Test Cases

Endpoint: `POST /PromotionCompatibility`

## Rules in the Decision Table

| # | promotion | blockedByGroup | blockedByPromotion | winner | description |
|---|---|---|---|---|---|
| R1 | 299 | EmployeeDiscount | *(empty)* | *(empty)* | BTL Boost MPO blocked by employee discount |
| R2 | 355 | *(empty)* | 449 | *(empty)* | Mobilrabatt 1 blocked by Mobilrabatt MPO |
| R3 | 263 | OTSDiscount | 389 | *(empty)* | BTL voucher Boost blocked by OTS or One-to-One campaign |
| R4 | 400 | SaveDeskDiscount | *(empty)* | *(empty)* | One-to-One mid-risk campaign blocked by save desk |
| R5 | 72 | *(empty)* | 73 | 73 | External and internal GEO discount can't combine, internal wins |
| R6 | 272 | *(empty)* | 306 | BY_PRECEDENCE | OTS discount and campaign MPO can't combine, precedence decides |

---

## Test Case 1: Block by group presence (R1 triggered)

**Use case:** Promotion 299 is blocked because EmployeeDiscount group is present.

### Request
```json
{
  "EligiblePromotions": [
    {
      "promotionId": "299",
      "promotionName": "BTL Boost - New or Existing MPO with binding 24 months",
      "promotionBaseType": "SubscriptionPromotion",
      "discountCategory": "SignUpDiscount"
    },
    {
      "promotionId": "103",
      "promotionName": "Employee discount on mobile main sim",
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
        "promotionId": "103",
        "promotionName": "Employee discount on mobile main sim",
        "discountCategory": "EmployeeDiscount",
        "appliedOrder": 1
      }
    ],
    "RejectedPromotions": [
      {
        "promotionId": "299",
        "promotionName": "BTL Boost - New or Existing MPO with binding 24 months",
        "discountCategory": "SignUpDiscount",
        "reason": "Blocked by rule: BTL Boost MPO blocked by employee discount"
      }
    ]
  }
}
```

### Verify
- AllowedPromotions has 1 item (103)
- RejectedPromotions has 1 item (299)
- Rejection reason contains "Blocked by rule"

---

## Test Case 2: No block - trigger group absent (R1 NOT triggered)

**Use case:** Promotion 299 is sent WITHOUT EmployeeDiscount, so the rule does not fire. Both survive.

### Request
```json
{
  "EligiblePromotions": [
    {
      "promotionId": "299",
      "promotionName": "BTL Boost - New or Existing MPO with binding 24 months",
      "promotionBaseType": "SubscriptionPromotion",
      "discountCategory": "SignUpDiscount"
    },
    {
      "promotionId": "72",
      "promotionName": "GEO rabatt external retail",
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
        "promotionId": "299",
        "promotionName": "BTL Boost - New or Existing MPO with binding 24 months",
        "discountCategory": "SignUpDiscount",
        "appliedOrder": 1
      },
      {
        "promotionId": "72",
        "promotionName": "GEO rabatt external retail",
        "discountCategory": "GeoDiscount",
        "appliedOrder": 2
      }
    ],
    "RejectedPromotions": []
  }
}
```

### Verify
- AllowedPromotions has 2 items (sorted by precedence: SignUpDiscount=5, GeoDiscount=13)
- RejectedPromotions is empty
- 299 is NOT blocked (EmployeeDiscount not present)

---

## Test Case 3: Block by promotion presence (R2 triggered)

**Use case:** Promotion 355 is blocked because promotion 449 is in the input.

### Request
```json
{
  "EligiblePromotions": [
    {
      "promotionId": "355",
      "promotionName": "Mobilrabatt - New or existing MPO - 1",
      "promotionBaseType": "SubscriptionPromotion",
      "discountCategory": "HWDiscount"
    },
    {
      "promotionId": "449",
      "promotionName": "Mobilrabatt - New or existing MPO",
      "promotionBaseType": "SubscriptionPromotion",
      "discountCategory": "HWDiscount"
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
        "promotionId": "449",
        "promotionName": "Mobilrabatt - New or existing MPO",
        "discountCategory": "HWDiscount",
        "appliedOrder": 1
      }
    ],
    "RejectedPromotions": [
      {
        "promotionId": "355",
        "promotionName": "Mobilrabatt - New or existing MPO - 1",
        "discountCategory": "HWDiscount",
        "reason": "Blocked by rule: Mobilrabatt 1 blocked by Mobilrabatt MPO"
      }
    ]
  }
}
```

### Verify
- AllowedPromotions has 1 item (449)
- 355 is blocked by promo, not by group
- Both are in the same category (HWDiscount) - this is NOT a category conflict

---

## Test Case 4: No block - trigger promotion absent (R2 NOT triggered)

**Use case:** Promotion 355 is sent WITHOUT promotion 449. No block fires.

### Request
```json
{
  "EligiblePromotions": [
    {
      "promotionId": "355",
      "promotionName": "Mobilrabatt - New or existing MPO - 1",
      "promotionBaseType": "SubscriptionPromotion",
      "discountCategory": "HWDiscount"
    },
    {
      "promotionId": "5",
      "promotionName": "Kombo Mobile Discount",
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
        "promotionId": "355",
        "promotionName": "Mobilrabatt - New or existing MPO - 1",
        "discountCategory": "HWDiscount",
        "appliedOrder": 1
      },
      {
        "promotionId": "5",
        "promotionName": "Kombo Mobile Discount",
        "discountCategory": "KomboDiscount",
        "appliedOrder": 2
      }
    ],
    "RejectedPromotions": []
  }
}
```

### Verify
- AllowedPromotions has 2 items (sorted by precedence: HWDiscount=12, KomboDiscount=14)
- RejectedPromotions is empty
- 355 survives because 449 is not in the input

---

## Test Case 5: OR-rule triggered by group leg (R3 triggered via OTSDiscount)

**Use case:** Promotion 263 is blocked because OTSDiscount group is present (the `blockedByGroup` side of the OR).

### Request
```json
{
  "EligiblePromotions": [
    {
      "promotionId": "263",
      "promotionName": "BTL voucher Boost - New main with binding",
      "promotionBaseType": "SubscriptionPromotion",
      "discountCategory": "SignUpDiscount"
    },
    {
      "promotionId": "314",
      "promotionName": "Discount OTS - New or existing MPO 5, 20 GB with binding",
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
        "promotionId": "314",
        "promotionName": "Discount OTS - New or existing MPO 5, 20 GB with binding",
        "discountCategory": "OTSDiscount",
        "appliedOrder": 1
      }
    ],
    "RejectedPromotions": [
      {
        "promotionId": "263",
        "promotionName": "BTL voucher Boost - New main with binding",
        "discountCategory": "SignUpDiscount",
        "reason": "Blocked by rule: BTL voucher Boost blocked by OTS or One-to-One campaign"
      }
    ]
  }
}
```

### Verify
- 263 blocked by the group leg of R3
- 389 is NOT in the input, but the group trigger alone is enough (OR logic)

---

## Test Case 6: OR-rule triggered by promotion leg (R3 triggered via promotion 389)

**Use case:** Promotion 263 is blocked because promotion 389 is present (the `blockedByPromotion` side of the OR). No OTSDiscount in input.

### Request
```json
{
  "EligiblePromotions": [
    {
      "promotionId": "263",
      "promotionName": "BTL voucher Boost - New main with binding",
      "promotionBaseType": "SubscriptionPromotion",
      "discountCategory": "SignUpDiscount"
    },
    {
      "promotionId": "389",
      "promotionName": "One to One campaigning - high-risk segment",
      "promotionBaseType": "SubscriptionPromotion",
      "discountCategory": "SignUpDiscount"
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
        "promotionId": "389",
        "promotionName": "One to One campaigning - high-risk segment",
        "discountCategory": "SignUpDiscount",
        "appliedOrder": 1
      }
    ],
    "RejectedPromotions": [
      {
        "promotionId": "263",
        "promotionName": "BTL voucher Boost - New main with binding",
        "discountCategory": "SignUpDiscount",
        "reason": "Blocked by rule: BTL voucher Boost blocked by OTS or One-to-One campaign"
      }
    ]
  }
}
```

### Verify
- 263 blocked by the promo leg of R3
- OTSDiscount is NOT present, but the promo trigger alone is enough (OR logic)

---

## Test Case 7: OR-rule triggered by BOTH legs (R3 triggered via OTSDiscount AND promotion 389)

**Use case:** Both trigger conditions are met at once. Promotion 263 should still only appear once in rejections.

### Request
```json
{
  "EligiblePromotions": [
    {
      "promotionId": "263",
      "promotionName": "BTL voucher Boost - New main with binding",
      "promotionBaseType": "SubscriptionPromotion",
      "discountCategory": "SignUpDiscount"
    },
    {
      "promotionId": "389",
      "promotionName": "One to One campaigning - high-risk segment",
      "promotionBaseType": "SubscriptionPromotion",
      "discountCategory": "SignUpDiscount"
    },
    {
      "promotionId": "446",
      "promotionName": "OTS Nykundsrabatt",
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
        "promotionId": "389",
        "promotionName": "One to One campaigning - high-risk segment",
        "discountCategory": "SignUpDiscount",
        "appliedOrder": 1
      },
      {
        "promotionId": "446",
        "promotionName": "OTS Nykundsrabatt",
        "discountCategory": "OTSDiscount",
        "appliedOrder": 2
      }
    ],
    "RejectedPromotions": [
      {
        "promotionId": "263",
        "promotionName": "BTL voucher Boost - New main with binding",
        "discountCategory": "SignUpDiscount",
        "reason": "Blocked by rule: BTL voucher Boost blocked by OTS or One-to-One campaign"
      }
    ]
  }
}
```

### Verify
- 263 appears only ONCE in rejections (not duplicated)
- Both other promos survive
- `blockedPromotionIds` uses `distinct values` so no duplication

---

## Test Case 8: OR-rule NOT triggered (R3 not triggered - neither condition met)

**Use case:** Promotion 263 is sent without OTSDiscount or promotion 389. No block fires.

### Request
```json
{
  "EligiblePromotions": [
    {
      "promotionId": "263",
      "promotionName": "BTL voucher Boost - New main with binding",
      "promotionBaseType": "SubscriptionPromotion",
      "discountCategory": "SignUpDiscount"
    },
    {
      "promotionId": "72",
      "promotionName": "GEO rabatt external retail",
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
        "promotionId": "263",
        "promotionName": "BTL voucher Boost - New main with binding",
        "discountCategory": "SignUpDiscount",
        "appliedOrder": 1
      },
      {
        "promotionId": "72",
        "promotionName": "GEO rabatt external retail",
        "discountCategory": "GeoDiscount",
        "appliedOrder": 2
      }
    ],
    "RejectedPromotions": []
  }
}
```

### Verify
- AllowedPromotions has 2 items (sorted by precedence: SignUpDiscount=5, GeoDiscount=13)
- 263 survives (neither OTSDiscount nor promotion 389 present)

---

## Test Case 9: Multiple blocks firing at once (R1 + R4)

**Use case:** Two different promotion rules fire simultaneously. Both 299 and 400 are blocked.

### Request
```json
{
  "EligiblePromotions": [
    {
      "promotionId": "299",
      "promotionName": "BTL Boost - New or Existing MPO with binding 24 months",
      "promotionBaseType": "SubscriptionPromotion",
      "discountCategory": "SignUpDiscount"
    },
    {
      "promotionId": "400",
      "promotionName": "One to One campaigning - mid-risk segment",
      "promotionBaseType": "SubscriptionPromotion",
      "discountCategory": "SignUpDiscount"
    },
    {
      "promotionId": "103",
      "promotionName": "Employee discount on mobile main sim",
      "promotionBaseType": "SubscriptionPromotion",
      "discountCategory": "EmployeeDiscount"
    },
    {
      "promotionId": "408",
      "promotionName": "Savedesk - 12 months discount main sim",
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
        "promotionId": "103",
        "promotionName": "Employee discount on mobile main sim",
        "discountCategory": "EmployeeDiscount",
        "appliedOrder": 1
      },
      {
        "promotionId": "408",
        "promotionName": "Savedesk - 12 months discount main sim",
        "discountCategory": "SaveDeskDiscount",
        "appliedOrder": 2
      }
    ],
    "RejectedPromotions": [
      {
        "promotionId": "299",
        "promotionName": "BTL Boost - New or Existing MPO with binding 24 months",
        "discountCategory": "SignUpDiscount",
        "reason": "Blocked by rule: BTL Boost MPO blocked by employee discount"
      },
      {
        "promotionId": "400",
        "promotionName": "One to One campaigning - mid-risk segment",
        "discountCategory": "SignUpDiscount",
        "reason": "Blocked by rule: One-to-One mid-risk campaign blocked by save desk"
      }
    ]
  }
}
```

### Verify
- 2 allowed (EmployeeDiscount, SaveDeskDiscount)
- 2 blocked by promotion rules (299 by R1, 400 by R4)
- Each rejection has its own distinct reason/description

---

## Test Case 10: Category conflict + promotion rule combined (R1 + category rule)

**Use case:** A promotion is rejected by category conflict AND another is blocked by promotion rule in the same request.

### Request
```json
{
  "EligiblePromotions": [
    {
      "promotionId": "299",
      "promotionName": "BTL Boost - New or Existing MPO with binding 24 months",
      "promotionBaseType": "SubscriptionPromotion",
      "discountCategory": "SignUpDiscount"
    },
    {
      "promotionId": "103",
      "promotionName": "Employee discount on mobile main sim",
      "promotionBaseType": "SubscriptionPromotion",
      "discountCategory": "EmployeeDiscount"
    },
    {
      "promotionId": "291",
      "promotionName": "Bindningsrabatt Nytt eller befintligt MPO med bindningstid",
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
        "promotionId": "103",
        "promotionName": "Employee discount on mobile main sim",
        "discountCategory": "EmployeeDiscount",
        "appliedOrder": 1
      }
    ],
    "RejectedPromotions": [
      {
        "promotionId": "291",
        "promotionName": "Bindningsrabatt Nytt eller befintligt MPO med bindningstid",
        "discountCategory": "LegacyBindingDiscount",
        "reason": "Incompatible with EmployeeDiscount (higher precedence)"
      },
      {
        "promotionId": "299",
        "promotionName": "BTL Boost - New or Existing MPO with binding 24 months",
        "discountCategory": "SignUpDiscount",
        "reason": "Blocked by rule: BTL Boost MPO blocked by employee discount"
      }
    ]
  }
}
```

### Verify
- 291 rejected by **category rule** (EmployeeDiscount vs LegacyBindingDiscount)
- 299 rejected by **promotion rule** (R1)
- Both rejection types appear in the same response
- Rejection reasons are different: "Incompatible with..." vs "Blocked by rule:..."

---

## Test Case 11: Blocked promo not in input (R1 rule exists but 299 not sent)

**Use case:** The trigger group (EmployeeDiscount) is present, but the blocked promo (299) is not in the input. Nothing should be blocked.

### Request
```json
{
  "EligiblePromotions": [
    {
      "promotionId": "104",
      "promotionName": "Employee discount on member voice sim",
      "promotionBaseType": "SubscriptionPromotion",
      "discountCategory": "EmployeeDiscount"
    },
    {
      "promotionId": "72",
      "promotionName": "GEO rabatt external retail",
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
        "promotionId": "104",
        "promotionName": "Employee discount on member voice sim",
        "discountCategory": "EmployeeDiscount",
        "appliedOrder": 1
      },
      {
        "promotionId": "72",
        "promotionName": "GEO rabatt external retail",
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
- R1 fires (activeBlocks contains the rule), but 299 is not in input so nothing is filtered

---

## Test Case 12: Blocked promo alone - no trigger present (R4 target without trigger)

**Use case:** Promotion 400 is in input but SaveDeskDiscount is NOT. The rule should not fire.

### Request
```json
{
  "EligiblePromotions": [
    {
      "promotionId": "400",
      "promotionName": "One to One campaigning - mid-risk segment",
      "promotionBaseType": "SubscriptionPromotion",
      "discountCategory": "SignUpDiscount"
    },
    {
      "promotionId": "5",
      "promotionName": "Kombo Mobile Discount",
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
        "promotionId": "400",
        "promotionName": "One to One campaigning - mid-risk segment",
        "discountCategory": "SignUpDiscount",
        "appliedOrder": 1
      },
      {
        "promotionId": "5",
        "promotionName": "Kombo Mobile Discount",
        "discountCategory": "KomboDiscount",
        "appliedOrder": 2
      }
    ],
    "RejectedPromotions": []
  }
}
```

### Verify
- 400 survives because SaveDeskDiscount is not in input
- R4 does NOT fire

---

## Test Case 13: Explicit winner - internal GEO wins over external GEO (R5 triggered)

**Use case:** Promotions 72 and 73 are both present. R5 has winner=73, so 72 (external) is rejected.

### Request
```json
{
  "EligiblePromotions": [
    {
      "promotionId": "72",
      "promotionName": "GEO rabatt external retail",
      "promotionBaseType": "SubscriptionPromotion",
      "discountCategory": "GeoDiscount"
    },
    {
      "promotionId": "73",
      "promotionName": "GEO rabatt interna kanaler",
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
        "promotionId": "73",
        "promotionName": "GEO rabatt interna kanaler",
        "discountCategory": "GeoDiscount",
        "appliedOrder": 1
      }
    ],
    "RejectedPromotions": [
      {
        "promotionId": "72",
        "promotionName": "GEO rabatt external retail",
        "discountCategory": "GeoDiscount",
        "reason": "Blocked by rule: External and internal GEO discount can't combine, internal wins"
      }
    ]
  }
}
```

### Verify
- 73 survives (it's the explicit winner)
- 72 rejected with reason referencing the rule description
- Both are same category — this is a promotion-level rule, not a category conflict

---

## Test Case 14: BY_PRECEDENCE winner - SignUpDiscount beats OTSDiscount (R6 triggered)

**Use case:** Promotion 272 (OTSDiscount, rank 8) and promotion 306 (SignUpDiscount, rank 5) are both present. R6 has winner=BY_PRECEDENCE, so the higher-precedence promo (lower rank number) wins.

### Request
```json
{
  "EligiblePromotions": [
    {
      "promotionId": "272",
      "promotionName": "Discount OTS - New MPO 10, 30 GB and extra användare mobil",
      "promotionBaseType": "SubscriptionPromotion",
      "discountCategory": "OTSDiscount"
    },
    {
      "promotionId": "306",
      "promotionName": "New or Existing MPO main or Extra användare Mobil with binding",
      "promotionBaseType": "SubscriptionPromotion",
      "discountCategory": "SignUpDiscount"
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
        "promotionId": "306",
        "promotionName": "New or Existing MPO main or Extra användare Mobil with binding",
        "discountCategory": "SignUpDiscount",
        "appliedOrder": 1
      }
    ],
    "RejectedPromotions": [
      {
        "promotionId": "272",
        "promotionName": "Discount OTS - New MPO 10, 30 GB and extra användare mobil",
        "discountCategory": "OTSDiscount",
        "reason": "Blocked by rule: OTS discount and campaign MPO can't combine, precedence decides"
      }
    ]
  }
}
```

### Verify
- 306 survives (SignUpDiscount rank 5 < OTSDiscount rank 8)
- 272 rejected because its category has lower precedence (higher rank number)
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
