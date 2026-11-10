---
name: dmn-rule-tables
description: Add, change, or remove promotion compatibility rules in PromotionCompatibility.dmn — category precedence, category incompatibility, item incompatibility (promotions and add-ons), cardinality limits, and exception (rescue) rules. Use whenever the task is "make discount X incompatible with Y", "let promo Z stack with W", "at most one of category C", "reorder precedence", "block add-on N", or any change to business rule data rather than engine logic.
---

# Editing the promotion compatibility rule tables

All rules live in `src/main/resources/PromotionCompatibility.dmn`. There is no rule data in
Java, in tests, or in the GUI — and there must never be. Adding a rule automatically adds a
generated test case and a GUI preset; **do not write a test for a new rule.**

Two ways to edit:

- **The browser editor** (`/editor.html`) — what rule owners use. Save applies immediately.
  To make a GUI edit permanent, download the model and replace
  `src/main/resources/PromotionCompatibility.dmn`, then rebuild.
- **The XML directly** — for changes made in this repo. Keep every element id unique; a
  duplicate makes the whole model fail to build with an opaque error.

## The five tables

All use hit policy `COLLECT`, `Rule-as-Row`, and a dummy input column `"match"` holding `-` in
every row. Adding a row means copying an existing `<dmn:rule>` block and changing the id suffix
and the output entries. A blank cell is `<dmn:text></dmn:text>` — genuinely empty, not `""`.

### CategoryPrecedence — `discountCategory`, `precedenceRank`

Ranks all 18 categories. **Lower rank wins.** A category not in this table ranks 999, so it
loses every precedence contest but is otherwise fine. Ranks must be unique — two categories
sharing a rank cannot be separated, and `RuleIntegrityTest` fails on it.

### CategoryIncompatibility — `ruleId`, `categoryA`, `categoryB`, `winner`, `serviceType`, `channel`, `description`

Two categories cannot sit on the same contract. Fires when **both** are present in one scope.

```
CAT-005 | EmployeeDiscount | OTSDiscount |   |   |   | Employee discounts replace OTS offers.
```

`categoryA` must differ from `categoryB`. "At most one of this category" is a *cardinality*
question — put it in `CategoryCardinality`. (A same-category pairwise rule would drop every
member of the category, which is never what the author meant.)

### ItemIncompatibility — `ruleId`, `itemId`, `conflictsWithItemId`, `conflictsWithCategory`, `winner`, `serviceType`, `channel`, `description`

A named item against another named item and/or a whole category. **This is the add-on table
too** — an add-on offering is just an item, so promotion↔promotion, addon↔addon and
promotion↔addon all fall out of this one grid. Fill either counterpart column or both (both ⇒
either counterpart triggers it). An item never conflicts with itself.

```
ITM-006 | ADDON-TV-BOX |     | SignUpDiscount | A |   |   | The TV box add-on outranks sign-up discounts.
ITM-007 | 355          | 449 |                | B |   |   | 449 wins over 355.
```

### CategoryCardinality — `ruleId`, `discountCategory`, `maxAllowed`, `serviceType`, `channel`, `description`

At most `maxAllowed` items of this category per contract. The highest-ranked survive; the rest
are rejected with `CARDINALITY_EXCEEDED`. Ties are broken by inventory-before-cart, then itemId.
`maxAllowed` must be at least 1.

### Exceptions — `ruleId`, `exemptItemId`, `appliesToRuleId`, `whenCategoryPresent`, `whenItemPresent`, `description`

Rescues one item from a rule that would otherwise drop it.

- `appliesToRuleId` names **one** rule. Blank ⇒ any rule that catches the item.
- `whenCategoryPresent` / `whenItemPresent` are the condition. Both blank ⇒ unconditional.
- For a cardinality rule, an exempt item leaves the count entirely: it neither counts toward the
  limit nor is dropped by it.

An exemption still shows up in the response as a conflict with `resolution: EXEMPTED` and empty
`loserItemIds`. That visibility is the point.

## `winner` means the same thing everywhere

| Value | Meaning |
|---|---|
| *(blank)* | Decide by precedence rank |
| `A` | The `categoryA` / `itemId` side always wins |
| `B` | The `categoryB` / `conflictsWith` side always wins |

There is no `BY_PRECEDENCE` string — blank already says it. If you find one in a document,
that document predates the current model.

## `serviceType` and `channel`

Blank means the rule is global. `channel` is matched against the request-level `Channel`;
`serviceType` is matched **per item**, so a rule naming a service type only sees items carrying
it. A rule naming a channel is inert when the caller sends no `Channel`.

## Before you finish

- Every rule needs a unique `ruleId` and a non-empty `description` — both are asserted.
- Every category a rule names must exist in `CategoryPrecedence`, or the rule is silently inert.
  `RuleIntegrityTest` catches this; it is the most common real mistake.
- `appliesToRuleId` must resolve to a rule that exists.
- Never use the `PROBE-` item id prefix; the generated tests reserve it.
- Run the build. `RuleContractTest` will generate a case for the new rule and fail if the rule
  cannot be made to fire — which is how a dead rule is found.

See `dmn-feel-logic` if you need to change *how* rules are evaluated rather than *which* rules
exist, and `discount-rules-testing` for running and reading the suite.
