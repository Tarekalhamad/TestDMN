# Refactoring plan — campaign stacking prevention engine

Goal: one service that handles **every kind of discount conflict**, with an **explicit input and
output contract**, that stays **easy to test and easy to follow**.

Three decisions are already locked and everything below follows from them:

| Decision | Choice |
|---|---|
| Output contract | **Verdict *and* conflict list** — keep auto-resolution, additionally expose which items conflict with which |
| Input model | **Unified item model** — one item type covering promotions and add-on offerings, with dimensions |
| API compatibility | **Clean break** — one contract, old endpoint removed |

Requirement IDs below refer to [campaign-stacking-context.md](campaign-stacking-context.md).

---

## 0. Status — implemented

All eight phases are in. What follows is the plan as agreed; this section records where the
implementation deviates from it and why. Read it before trusting a detail below.

| Phase | State | Note |
|---|---|---|
| 0 Freeze the contract | Done, **differently** | JSON Schemas were written; the golden fixtures were **not** — see "Fixtures" below |
| 1 Item model + rule tables | Done | Five tables, every rule carries `ruleId` + `description`; `winner` unified |
| 2 Decompose the logic | Done | 9 logic decisions + 2 BKMs; no expression is more than a few lines |
| 3 New conflict types | Done | Item↔item, item↔category incl. add-ons, cardinality, scope isolation, serviceType/channel |
| 4 Output assembly | Done | `valid`, `conflicts`, `reasonCode`, `ruleId`, `ruleSetVersion` |
| 5 Single evaluation path | Done | `kogito.generate.rest=false`; the old endpoint 404s; `dmn-storage/` git-ignored, seeded at startup, N versions kept |
| 6 Test hardening | Done, **differently** | Integrity, coverage, contract and determinism checks all exist, generated rather than enumerated |
| 7 GUI + docs | Done | Editor handles all five tables and generates a case per rule; CLAUDE.md, three skills and this doc updated |

### Fixtures — §5.1 was not implemented as written, deliberately

The plan called for ~15 golden-file JSON fixtures, each stating an expected `ruleId`, winner and
reason code. They were built, and then removed. The reason is the plan's own premise: **rule
owners change rules in the browser without a release.** A fixture saying
`"ruleId": "CAT-004", "winnerItemId": "500"` is a second copy of a rule living outside the DMN.
The first time somebody edits that rule in the GUI, fifteen JSON files are wrong and somebody
has to hand-edit them — which is M4 relocated, not solved.

What replaced it: `RuleProbes` reads the live tables and, **for each rule that is configured**,
derives both the request that makes it fire and the outcome that rule row declares. So:

- adding a rule adds a test case, with no file to write;
- changing a `winner` changes the expectation with it;
- a rule that cannot be made to fire fails as **dead**, which is `RuleCoverageTest`'s job done
  by construction rather than by a separate uncovered-rule report;
- the editor's test panel generates its cases the same way, so the GUI presets and the suite
  cannot diverge — §5.3 asked for that and this is how it is achieved.

The parts of §5 that were kept exactly: `RuleIntegrityTest`, `DeterminismTest` (shuffles the
input, in `EnginePropertiesTest`), and `ContractTest` — the last as schema validation applied to
**every** response the suite makes, not one dedicated test.

### Other deviations worth knowing

- **`serviceType` is matched per item, not used to prefilter rules.** §4 put it in
  `ApplicableRules`. Filtering the rule set by service type is wrong when service type is a
  property of each item: `ApplicableRules` prefilters on channel plus "is this service type
  present at all", and the per-item match happens inside conflict detection.
- **`conflicts[]` carries `exemptedItemIds` and `exemptedByRuleIds`** beyond the shape in §2.
  Without them an `EXEMPTED` conflict cannot say which exception rescued what, and exception
  rules would not be traceable.
- **Ordering is a total order** — rank, then `INVENTORY` before `CART`, then `itemId`. §3.4 said
  survivors are the "highest-precedence items in that category", but every item in a category
  shares a rank, so rank alone cannot pick survivors and Drools' `sort` is not stable. Without a
  tie-break the verdict would depend on input order, which is the defect the service exists to
  fix.
- **`ScopeCategories` and `ScopeItemIds` are two decisions**, not one. Drools 10's FEEL
  mis-scopes two sibling nested `for` loops in one `for` body — silently returning a wrong list.
  Several nodes are shaped around that; see CLAUDE.md.
- **The Kogito resource is not deleted, it is not generated.** §6 said delete. Setting
  `kogito.generate.rest=false` achieves the same visible result (the endpoint 404s) while
  keeping `generateModel` as the build-time validator §6 also asked for.
- **Rollback keeps 20 versions**, not unlimited.

### One defect found that the plan did not list

`DmnHotReloadService.buildDmnRuntime` only failed when the *build* threw. A model whose decision
table is structurally broken builds "successfully" and the broken rule is then discarded at
evaluation time — so the editor reported `{"status":"success"}` while the rule silently vanished.
That is how `dmn-storage/` came to hold a `KomboDiscount`/`GeoDiscount` rule that never applied.
The service now checks `DMNModel.getMessages(ERROR)` and rejects such a model with 400 and the
engine's own message.

---

## 1. What is wrong today

Ordered by how much damage each does.

### Defects

**D1 — `overrideWinner` means opposite things in two tables.** In `CategoryBlockingRules` an
empty cell means *"decide by precedence"*. In `PromotionBlockingRules` an empty cell means
*"the left side always loses"*. Same column name, inverted semantics, no warning. Any rule
author moving between the two tables will get it wrong.

**D2 — an empty-string cell silently rejects the wrong side.** `conflictResolutions` guards on
`overrideWinner != null` but never on `!= ""`. A cell holding `""` rather than being truly empty
— which the browser editor can produce — makes `winnerCategory` the empty string, rejects
`groupA` regardless of rank, and emits the reason `"Incompatible with  (override winner)"`. It
is a live correctness bug reachable from the GUI.

**D3 — exception rules behave differently on the two paths.** `categoryExemptedIds` requires
`allowedWithGroup` to equal the *winning category of that item's own conflict*;
`promoExemptedIds` only requires it to be *present anywhere in the input*. Two different rules
for one table.

**D4 — no rule identity.** Rules cannot be referenced, traced, or reported on. Rejection reasons
are assembled strings, so a caller cannot tell *which* rule fired, and the conflict output
required by 1.50 has nothing to point at.

**D5 — everything conflicts with everything.** All input items are evaluated as one undivided
set. An employee discount on a mobile subscription and a binding discount on a broadband
subscription are reported as conflicting. Requirement 1.10 is explicitly about contracts.

### Gaps against requirements

| Gap | Requirement |
|---|---|
| No add-on offerings anywhere in the model | 1.10, 1.20 — **Must have** |
| Conflicts computed but never exposed | 1.50 — **Must have** |
| No cardinality rules ("only one of this type") | 1.60 — **Must have** |
| No inventory-vs-cart origin | 1.40, 1.60 |
| No service type or channel scoping | 1.10 open question |
| No validation that a rule references anything real | 1.30 |

### Maintainability

**M1 — one 754-line file with twenty single-line FEEL expressions.** `categoryExemptedIds` is a
single 500-character nested expression. It cannot be read, reviewed, or unit-tested.

**M2 — nothing below the endpoint is testable.** 16 tests, all end-to-end over HTTP. A wrong
intermediate value can only be found by bisecting the whole chain by hand.

**M3 — two evaluation paths that drift.** The compiled Kogito endpoint and the hot-reload
runtime serve different rules the moment anyone saves in the GUI.

**M4 — adding a test case costs Java.** Each new scenario is a hand-written REST-assured method,
so coverage grows slowly and unevenly.

---

## 2. Target contract

The contract is the deliverable. Everything else is implementation.

### Input

```json
{
  "Channel": "ONLINE",
  "Items": [
    {
      "itemId": "500",
      "itemName": "Employee Discount 50%",
      "itemType": "PROMOTION",
      "discountCategory": "EmployeeDiscount",
      "origin": "INVENTORY",
      "scopeId": "SUB-100200",
      "serviceType": "Mobile"
    },
    {
      "itemId": "A-8891",
      "itemName": "Router discount 20%",
      "itemType": "ADDON",
      "discountCategory": "HWDiscount",
      "origin": "CART",
      "scopeId": "SUB-100201",
      "serviceType": "Broadband"
    }
  ]
}
```

| Field | Required | Values | Notes |
|---|---|---|---|
| `itemId` | yes | string | Unique within the request |
| `itemName` | yes | string | Used in explanations |
| `itemType` | yes | `PROMOTION` \| `ADDON` | **This is what closes the add-on gap** — an add-on is just an item, so every rule works on both without a parallel rule set |
| `discountCategory` | yes | string | Must exist in `CategoryPrecedence` |
| `origin` | no | `INVENTORY` \| `CART` | Default `CART`. The caller passes the **union** (1.40, 1.60); this records which side each item came from so explanations can say so |
| `scopeId` | no | string | The contract/subscription the item applies to. **Conflicts are evaluated within a scope, never across scopes.** Omitted ⇒ all items share one implicit scope, which is exactly today's behaviour |
| `serviceType` | no | string | Enables per-service-type rules (open question in 1.10) without another input redesign |
| `Channel` | no | string | Request-level, not per item — channel is a property of the transaction |

`scopeId` is an addition beyond the option selected, and it is the fix for **D5**. Without it
the engine reports false conflicts on any multi-subscription basket. It is optional and
backwards-compatible in behaviour, so it costs nothing to carry.

### Output

```json
{
  "valid": false,
  "ruleSetVersion": "sha256:ab12cd…",
  "allowed": [
    { "itemId": "500", "itemName": "Employee Discount 50%", "itemType": "PROMOTION",
      "discountCategory": "EmployeeDiscount", "scopeId": "SUB-100200", "appliedOrder": 1 }
  ],
  "rejected": [
    { "itemId": "600", "itemName": "Binding Discount 24mo", "itemType": "PROMOTION",
      "discountCategory": "LegacyBindingDiscount", "scopeId": "SUB-100200",
      "reasonCode": "CATEGORY_INCOMPATIBLE", "ruleId": "CAT-004",
      "reasonText": "Incompatible with Employee Discount 50% (EmployeeDiscount, higher precedence)" }
  ],
  "conflicts": [
    { "ruleId": "CAT-004", "ruleType": "CATEGORY_INCOMPATIBILITY", "scopeId": "SUB-100200",
      "participants": [
        { "itemId": "500", "discountCategory": "EmployeeDiscount",      "origin": "INVENTORY" },
        { "itemId": "600", "discountCategory": "LegacyBindingDiscount", "origin": "CART" } ],
      "resolution": "AUTO_BY_PRECEDENCE",
      "winnerItemId": "500",
      "loserItemIds": ["600"],
      "explanation": "EmployeeDiscount (rank 1) takes precedence over LegacyBindingDiscount (rank 7)" }
  ]
}
```

| Field | Purpose |
|---|---|
| `valid` | `true` when `rejected` is empty. **This is the checkout gate** (1.50) — one boolean, no interpretation needed |
| `ruleSetVersion` | Hash of the live rule set. Lets a caller prove which rules produced a verdict, and lets analytics reproduce it |
| `allowed` | Survivors in precedence order with `appliedOrder` |
| `rejected` | Losers, each carrying a **machine-readable `reasonCode`, the `ruleId` that fired**, and human text |
| `conflicts` | **The 1.50 answer** — which items conflict with which, under which rule, and how it was resolved. Present whether or not auto-resolution succeeded |

Enumerations, fixed and asserted in tests:

```
reasonCode   CATEGORY_INCOMPATIBLE | ITEM_INCOMPATIBLE
             ITEM_CATEGORY_INCOMPATIBLE | CARDINALITY_EXCEEDED

ruleType     CATEGORY_INCOMPATIBILITY | ITEM_INCOMPATIBILITY | CARDINALITY

resolution   AUTO_BY_PRECEDENCE | AUTO_BY_OVERRIDE | AUTO_BY_CARDINALITY | EXEMPTED
```

Reason **codes** exist so tests and callers assert on a stable token instead of a generated
sentence, and so the text can be localised or rewritten without breaking anyone. `EXEMPTED`
appears in `conflicts` when a rule fired but an exception rescued the item — the conflict is
still reported, nothing is rejected. That visibility is what makes exception rules debuggable.

### Endpoint

```
POST /api/compatibility/check      the one contract
GET  /api/rules                    current rule set + version
GET  /api/rules/version            version only, cheap
```

`POST /PromotionCompatibility` is removed.

---

## 3. Target rule model

Five tables. One table per conflict *shape*, so each stays a legible grid — the explicit-over-
abstract decision from the context doc. Every rule gains a `ruleId` and a `description`.

### 3.1 `CategoryPrecedence` — unchanged in shape

`discountCategory` · `precedenceRank`

### 3.2 `CategoryIncompatibility`

`ruleId` · `categoryA` · `categoryB` · `winner` · `serviceType` · `channel` · `description`

### 3.3 `ItemIncompatibility` — covers promotions **and** add-ons

`ruleId` · `itemId` · `conflictsWithItemId` · `conflictsWithCategory` · `winner` · `serviceType` · `channel` · `description`

Because rules address *items*, add-on incompatibility (1.10) needs no new table — an add-on is
an item. Promotion↔promotion, addon↔addon and promotion↔addon all fall out of one table.

### 3.4 `CategoryCardinality` — new

`ruleId` · `discountCategory` · `maxAllowed` · `serviceType` · `channel` · `description`

Answers 1.60's "multiple discounts of the same type cannot be checked out". Survivors are the
`maxAllowed` highest-precedence items in that category within the scope; the rest are rejected
with `CARDINALITY_EXCEEDED`.

### 3.5 `Exceptions`

`ruleId` · `exemptItemId` · `appliesToRuleId` · `whenCategoryPresent` · `whenItemPresent` · `description`

`appliesToRuleId` is new and fixes **D3**: an exception targets one named rule, so its scope is
stated rather than inferred. Left blank, it applies to any rule the item is caught by.

### 3.6 Unifying `winner` — a behaviour change

One column, three states, identical in every table:

| `winner` | Meaning |
|---|---|
| *(empty)* | Decide by precedence rank |
| `A` / `B` | That side always wins, regardless of rank |

This removes the magic `BY_PRECEDENCE` string (empty already says it) and fixes **D1** by giving
the empty cell one meaning everywhere.

> **Migration required.** Today an empty cell in `PromotionBlockingRules` means "left always
> loses". Under the new rule it means "decide by precedence" — a real behaviour change. Every
> existing promotion rule with an empty `overrideWinner` must be rewritten as `winner = B`.
> There are currently two such rules, so the migration is small, but it must be deliberate and
> covered by a fixture.

---

## 4. Target logic structure

Replace the single 20-entry context (**M1**) with named decisions, each a node in the DRG and
each independently evaluable:

```
Items (input)
  |
  +-> Scopes                 distinct scopeIds
  +-> ScopedItems            items grouped per scope
        |
        +-> ApplicableRules      rules filtered by serviceType + channel
              |
              +-> CategoryConflicts       both categories present in scope
              +-> ItemConflicts           item-vs-item and item-vs-category
              +-> CardinalityViolations   count per category exceeds maxAllowed
                    |
                    +-> Exemptions        exception rules applied to the above
                          |
                          +-> Resolutions   winner / loser / resolution per conflict
                                |
                                +-> Verdict   allowed + rejected + conflicts + valid
```

Three things this buys:

1. **Follow-ability.** The DRG diagram becomes the documentation. A rule owner opening the
   editor sees the actual evaluation flow instead of one opaque box.
2. **Testability.** Every node can be asserted on its own, in a `.scesim` or a direct DMN
   evaluation, so a wrong intermediate value is located rather than hunted.
3. **Reviewability.** Each expression is short enough to read in a diff.

Cost: more nodes on the canvas. Mitigate by laying data tables and logic decisions out in
separate bands, and keep the "How It Works" panel generating from the tables only.

---

## 5. Test architecture

The current model — one Java method per scenario (**M4**) — cannot cover the matrix this plan
creates. Replace it with data-driven fixtures.

### 5.1 Golden-file cases

```
src/test/resources/cases/
  category/employee-beats-binding.json
  category/override-winner-b.json
  item/addon-blocks-promotion.json
  item/promotion-blocks-addon.json
  cardinality/two-signup-discounts-max-one.json
  exception/kombo-exempt-from-employee.json
  exception/exception-scoped-to-one-rule.json
  scope/no-conflict-across-subscriptions.json
  origin/inventory-item-wins-over-cart.json
  channel/rule-applies-online-only.json
  edge/empty-input.json
  edge/single-item.json
  edge/unknown-category.json
```

Each file is the whole case:

```json
{
  "name": "Employee discount beats binding discount by precedence",
  "requirement": "1.25",
  "given":  { "Items": [ … ] },
  "expect": {
    "valid": false,
    "allowed":  [ { "itemId": "500", "appliedOrder": 1 } ],
    "rejected": [ { "itemId": "600", "reasonCode": "CATEGORY_INCOMPATIBLE", "ruleId": "CAT-004" } ],
    "conflicts":[ { "ruleId": "CAT-004", "resolution": "AUTO_BY_PRECEDENCE", "winnerItemId": "500" } ]
  }
}
```

One parameterised JUnit test discovers and runs every file. **Adding a scenario costs one JSON
file and no Java.** Expected blocks assert on the fields that matter, not the whole document.

### 5.2 Guard tests

| Test | Asserts | Fixes |
|---|---|---|
| `RuleIntegrityTest` | `ruleId` unique; every referenced category exists in `CategoryPrecedence`; every referenced `appliesToRuleId` exists; every row has a description | 1.30, and the "typo makes an inert rule" trap |
| `RuleCoverageTest` | Every `ruleId` in the tables is exercised by at least one fixture; fails listing the uncovered ones | Dead rules |
| `ContractTest` | Response matches the published JSON schema; every `reasonCode` / `ruleType` / `resolution` is a declared enum value | Contract drift |
| `DeterminismTest` | Shuffling the input `Items` array produces an identical verdict | The order-independence property |

`DeterminismTest` deserves emphasis: order-independence is the answer to the
first-come-first-served problem, and it is the kind of property that silently regresses. Assert
it, don't assume it.

### 5.3 Retained

`.scesim` scenario files stay for business-authored cases. One thin REST smoke test stays to
prove wiring. The GUI presets stay and should be regenerated from the fixture files so the two
cannot diverge.

---

## 6. Single evaluation path

Kill **M3**. One runtime, one source of truth:

- Serve the one endpoint from the **hot-reload runtime**. Hot reload is the primary business
  requirement, so it must be the path that carries traffic.
- Delete the Kogito-generated resource.
- **Keep `kogito-maven-plugin` as a build-time validator** — it fails the build on a malformed
  DMN, which is worth keeping even with nothing routed through the generated code.
- Gitignore `dmn-storage/`, seed it from the classpath at startup, and make promoting a GUI edit
  back to source an explicit documented step rather than a git diff nobody expects.
- Expose `ruleSetVersion` (hash of the live DMN) on every response and at `GET /api/rules/version`.
- Replace the single `.backup` with N timestamped versions — one undo is too thin for a surface
  business users edit directly.

---

## 7. Phases

Each phase is independently shippable and ends green.

| # | Phase | Output | Done when |
|---|---|---|---|
| **0** | **Freeze the contract** | JSON schemas for request/response; ~15 golden fixtures covering every conflict type; the parameterised runner | Fixtures exist and **fail** — an executable specification before any implementation |
| **1** | Item model + rule tables | New `itemDefinition`s; the five tables with `ruleId`; existing rule data migrated incl. the `winner` change (§3.6) | Model builds; migrated rules reproduce today's verdicts on the ported fixtures |
| **2** | Decompose the logic | The named decisions in §4, behaviour unchanged | Phase-1 fixtures still pass; no expression longer than a few lines |
| **3** | New conflict types | Item↔item incl. add-ons, item↔category, cardinality, scope isolation, serviceType/channel filters | Every §5.1 fixture passes |
| **4** | Output assembly | `valid`, `conflicts`, `reasonCode`, `ruleId`, `ruleSetVersion` | `ContractTest` green; `conflicts` populated for every rejection |
| **5** | Single evaluation path | One endpoint on the hot-reload runtime; old endpoint deleted; storage and versioning cleaned up | `/api/rules/version` changes after a GUI save; no second runtime exists |
| **6** | Test hardening | `RuleIntegrityTest`, `RuleCoverageTest`, `DeterminismTest` wired into the build | Build fails on a duplicate `ruleId`, an uncovered rule, or an order-dependent verdict |
| **7** | GUI + docs | Editor handles the five tables; presets generated from fixtures; `CLAUDE.md`, the three skills and the context doc updated | A rule owner can add each rule type in the GUI and test it there |

Phase 0 first is the whole point: it is what makes the input and output *clear* rather than
emergent, and every later phase is measured against it.

---

## 8. Decisions still open

Flagged rather than assumed. None blocks phase 0.

1. **Are rules global or per service type?** (1.10, 1.30). The plan carries a `serviceType`
   column so either answer works — blank means global. The business still needs to answer
   whether they intend to use it.
2. **Is precedence staying?** Rated *nice-to-have, possibly not* (1.25), yet it drives
   resolution and ordering. This plan keeps it and adds the conflict list alongside, so if
   precedence is later dropped the engine degrades to a pure reporter without a redesign.
3. **`scopeId` adoption.** The fix for D5 only works if callers populate it. Confirm the caller
   can supply a contract/subscription identifier per item.
4. **Commerce Studio.** It is the intended configuration surface. Phase 7 invests in the local
   editor; cap that investment if migration is near.
5. **Validation timing** (1.40, flagged by the business). Where in the flow the check runs is a
   caller decision, but it determines whether `origin` is ever meaningfully populated.

## 9. Explicitly out of scope

Not designed for, not accidentally half-built: discount **amounts** and value-based selection;
customer **choice** between mutually exclusive offers; cart **mutation** or mid-flow
replacement; real-time behavioural signals; campaign performance monitoring. Each is recorded in
[campaign-stacking-context.md](campaign-stacking-context.md) with what it would take.

The one thing this plan does to keep those doors open: the item model and the conflict output
are both extensible by adding fields, so amounts or alternatives can arrive later as additive
changes rather than a second contract break.
