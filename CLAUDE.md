# CLAUDE.md

Guidance for Claude Code when working in this repository.

## What this project is

`discount-rules` — a Spring Boot + Drools/Kogito **DMN decision service** for Telenor's
TMF-based Promotion API.

**The problem — campaign stacking.** Roughly 185 promotions across ~300 campaign configurations
are in circulation. The Promotion API grants them independently, with no mechanism to stop
conflicting ones stacking on the same contract (e.g. an employee discount plus a legacy binding
discount plus an OTS discount all landing on one subscription). That yields wrong prices to
customers, **order-dependent outcomes** — cart behaviour is first come, first served, so the
same basket can settle at different prices by different routes — configuration nobody can reason
about at that scale, and no explanation when a discount is dropped.

Evaluating the whole eligible set at once and resolving by declared precedence is what makes the
verdict order-independent. That property is the point; preserve it.

**The constraint that shapes the architecture:** rule owners on the business side must be able
to change compatibility rules **without a code change or redeploy**. That is why rules are data
in decision tables rather than logic in Java, and why the service ships a browser rule editor
with hot reload. It is also why **nothing about a rule may be written down twice** — see
"Rules are configuration" below, which is the single most important convention here.

**The contract:** POST the union of inventory and cart items, get back the subset that may be
applied (ordered, with an `appliedOrder`), every rejected item with a machine-readable reason
code and the rule that fired, and the full conflict list. Intended to be consumed as a library
with API access, called in real time during the purchase flow.

**The service validates; the caller enforces.** The service is stateless and side-effect free,
so it can be called as often as the flow likes. Enforcement belongs at **checkout**, in the
caller; conflicting items are explicitly allowed to sit in the cart until then. Do not add
gating, state, or side effects here.

For the full requirement set, what is deliberately out of scope, and domain vocabulary, read
[docs/campaign-stacking-context.md](docs/campaign-stacking-context.md). For the target contract
and the phased plan this implementation follows, read
[docs/REFACTORING-PLAN.md](docs/REFACTORING-PLAN.md).

Status: POC. Java 21, Maven, single module, no database.

## Rules are configuration — the rule that governs every other decision

Rule owners change rules in the browser, at any time, without a release. Everything follows:

- **Never hand-code rule data in Java, in a test, or in the GUI.** Not a rule id, not "employee
  beats binding", not a list of discount categories. Any copy of a rule goes stale the first
  time someone edits the model.
- **Derive, don't restate.** The tests read the live tables and generate a case per rule
  ([RuleProbes](src/test/java/com/example/discount/rules/RuleProbes.java)); the editor's test
  panel generates its cases the same way; `GET /api/rules` publishes the tables; Swagger loads
  the contract from the published JSON Schema. Adding a rule adds a test case and a GUI preset
  automatically, and nothing needs editing to keep up.
- A previous iteration of this work added ~30 golden-file JSON fixtures with expectations like
  `"ruleId": "CAT-001"` baked in. That was **wrong** and was removed. If you find yourself
  writing a rule id into a file that is not the DMN, stop.

## Stack

| | | |
|---|---|---|
| Spring Boot | 3.5.16 | Latest of the 3.5 line. **Not 4.x** — see below |
| Drools / Kogito | 10.2.0 | `drools-decisions-spring-boot-starter`, `kogito-maven-plugin` |
| Java | 21 | Deliberate — see below |
| springdoc-openapi | 2.9.0 | 2.x is the Spring Boot 3 line; 3.x requires Boot 4 |
| Editor UI | dmn-js 17.10.1 | unpkg CDN. **DMN 1.3 namespace only** — see below |
| Schema validation | networknt json-schema-validator 3.0.6 | test scope |
| Maven | wrapper 3.3.4 / Maven 3.9.16 | Maven 4 is still rc |

Three version ceilings are load-bearing. They are not laziness; each was checked against the
artifacts on Maven Central and each has a hard blocker:

- **Spring Boot stays on 3.5.x.** Boot 4 requires Spring Framework 7 and Jackson 3.
  `kogito-dependencies-bom:10.2.0` pins Spring Framework 6.2.15, Spring Boot 3.5.10 and Jackson
  2.19.2, and the newest Kogito development snapshot still does. Boot 4 also drops rest-assured
  from its managed dependencies and moves to JUnit 6, which strands the vintage engine the
  `.scesim` runner needs. Two incompatible framework generations, not a "probably works".
- **Java stays on 21.** Drools states JDK 17 as its minimum and builds on 21; there is no Java 25
  support statement for a bytecode-generating rules engine. The Dockerfile matches.
- **The DMN namespace stays `20191111` (DMN 1.3).** Drools 10 runs 1.3, 1.4 and 1.5 identically,
  but dmn-js is built on dmn-moddle 12, which binds exactly one namespace URI and hard-errors on
  anything else. A DMN 1.5 file would not open in the editor at all, and the editor is the whole
  no-redeploy capability. There is no dmn-js line that supports 1.5.

## Layout

| Path | Purpose |
|---|---|
| [src/main/resources/PromotionCompatibility.dmn](src/main/resources/PromotionCompatibility.dmn) | **All business logic.** 5 data tables, 2 BKMs, 9 logic decisions. |
| [src/main/java/com/example/discount/api/CompatibilityController.java](src/main/java/com/example/discount/api/CompatibilityController.java) | The contract: `/api/compatibility/check`, `/api/rules`, `/api/rules/version`. |
| [src/main/java/com/example/discount/rules/RuleTables.java](src/main/java/com/example/discount/rules/RuleTables.java) | Reads the five tables back out of the live DMN. The only place rule data becomes Java, and it never interprets a rule. |
| [src/main/java/com/example/discount/hotreload/DmnHotReloadService.java](src/main/java/com/example/discount/hotreload/DmnHotReloadService.java) | The one DMN runtime: validate, archive, write, atomic swap, version. |
| [src/main/java/com/example/discount/editor/DmnEditorController.java](src/main/java/com/example/discount/editor/DmnEditorController.java) | `/api/dmn/*` — read, update, evaluate-every-node, versions, rollback. |
| [src/main/java/com/example/discount/config/OpenApiConfig.java](src/main/java/com/example/discount/config/OpenApiConfig.java) | Loads the published JSON Schemas into the OpenAPI document. |
| [src/main/resources/static/schemas/](src/main/resources/static/schemas/) | **The contract.** Request and response JSON Schema, served, and asserted against every response in tests. |
| [src/main/resources/static/editor.html](src/main/resources/static/editor.html) | Browser rule editor + test harness. No build step. |
| [src/test/java/com/example/discount/rules/RuleProbes.java](src/test/java/com/example/discount/rules/RuleProbes.java) | Generates a request + expected outcome per configured rule. |
| [dmn-storage/](dmn-storage/) | Live DMN + `versions/`. Written at runtime, **git-ignored**. |
| [src/jsonExample/](src/jsonExample/) | Reference: real Promotion API payloads, the `refferdtype` category enum. |
| [docs/campaign-stacking-context.md](docs/campaign-stacking-context.md) | Business requirements, rationale, out-of-scope list, vocabulary. |
| [docs/REFACTORING-PLAN.md](docs/REFACTORING-PLAN.md) | Agreed target contract and phased plan, with what was implemented differently and why. |

## One evaluation path

There used to be two runtimes that drifted the moment anyone saved in the GUI. There is now one.

```
src/main/resources/PromotionCompatibility.dmn
        |
        |  kogito-maven-plugin (compile)  ->  build-time validation only,
        |                                     kogito.generate.rest=false
        |
        |  seeded on first start ->  dmn-storage/PromotionCompatibility.dmn
        |                                     |
        |                                     |  DMNRuntimeBuilder, hot-reloadable
        |                                     v
        |                            POST /api/compatibility/check
```

- `POST /PromotionCompatibility` **no longer exists.** `kogito.generate.rest=false` in
  `application.properties` stops the resource being generated at all; `scanBasePackages` no
  longer includes `http*`.
- The Kogito plugin still runs `generateModel` at compile time, so a malformed DMN fails the
  build. That is worth keeping even with nothing routed through the generated code.
- `dmn-storage/` is git-ignored and seeded from the classpath when absent. If a stored model
  differs from the packaged one, startup logs a WARN with both versions — that is the failure
  mode where a stale local file silently serves rules nobody expects.
- **Promoting a GUI edit to source is a deliberate step:** download the model from the editor
  (or `GET /api/dmn/content`), replace `src/main/resources/PromotionCompatibility.dmn`, rebuild.
- Tests get their own throwaway storage directory (`src/test/resources/application.properties`),
  so they neither read nor write the developer's `dmn-storage/`.

## Endpoints

| Method | Path | Notes |
|---|---|---|
| POST | `/api/compatibility/check` | **The contract.** Body `{"Channel": "...", "Items": [...]}`. |
| GET | `/api/rules` | The five tables as configured, plus `ruleSetVersion`. |
| GET | `/api/rules/version` | Version only. Cheap enough to poll. |
| GET/PUT | `/api/dmn/content` | The DMN XML. PUT validates, archives and hot-reloads. |
| POST | `/api/dmn/evaluate` | Every decision node's result. Debugging aid. |
| GET | `/api/dmn/versions` | Archived versions, newest first. |
| GET | `/api/dmn/backup` | The most recent archived model. |
| POST | `/api/dmn/rollback` · `/api/dmn/rollback/{versionId}` | Restore previous, or a named version. |
| GET | `/editor.html` · `/swagger-ui.html` · `/actuator/health` | GUI, API docs, health. |

## Request / response shape

```json
{"Channel": "ONLINE",
 "Items": [{"itemId": "103", "itemName": "Employee discount", "itemType": "PROMOTION",
            "discountCategory": "EmployeeDiscount", "origin": "INVENTORY",
            "scopeId": "SUB-100200", "serviceType": "Mobile"}]}
```

```json
{"valid": false,
 "ruleSetVersion": "sha256:…",
 "allowed":  [{"itemId": "103", "…": "…", "appliedOrder": 1}],
 "rejected": [{"itemId": "600", "reasonCode": "CATEGORY_INCOMPATIBLE", "ruleId": "CAT-001",
               "reasonText": "EmployeeDiscount (rank 1) takes precedence over …"}],
 "conflicts":[{"ruleId": "CAT-001", "ruleType": "CATEGORY_INCOMPATIBILITY", "scopeId": "",
               "participants": [...], "resolution": "AUTO_BY_PRECEDENCE",
               "winnerItemId": "103", "loserItemIds": ["600"],
               "exemptedItemIds": [], "exemptedByRuleIds": [], "explanation": "…"}]}
```

Only `itemId`, `itemName`, `itemType` and `discountCategory` are required. `origin` defaults to
`CART`; an omitted `scopeId` puts the item in one implicit shared scope; a blank `serviceType`
matches every rule. The authoritative shape is
[the JSON Schemas](src/main/resources/static/schemas/), and every response in the test suite is
validated against them.

Fixed enumerations:

```
reasonCode   CATEGORY_INCOMPATIBLE | ITEM_INCOMPATIBLE | ITEM_CATEGORY_INCOMPATIBLE | CARDINALITY_EXCEEDED
ruleType     CATEGORY_INCOMPATIBILITY | ITEM_INCOMPATIBILITY | CARDINALITY
resolution   AUTO_BY_PRECEDENCE | AUTO_BY_OVERRIDE | AUTO_BY_CARDINALITY | EXEMPTED
```

`conflicts` reports every rule that fired **including ones an exception neutralised**
(`resolution: EXEMPTED`, empty `loserItemIds`). A rule that fires and then does nothing is
exactly what a rule owner needs to see.

## The DMN model

Namespace `https://telenor.se/promotion-compatibility`, model name `PromotionCompatibility`.

### Data tables — business-editable

All five use hit policy `COLLECT`, `Rule-as-Row`, and a dummy input column `"match"` with `-` in
every row (DMN requires an input column; these are static lookup data).

| Decision | Columns | Meaning |
|---|---|---|
| `CategoryPrecedence` | `discountCategory`, `precedenceRank` | 18 categories ranked 1–18. **Lower rank wins.** Unranked ⇒ 999. |
| `CategoryIncompatibility` | `ruleId`, `categoryA`, `categoryB`, `winner`, `serviceType`, `channel`, `description` | Two categories cannot coexist on one contract. |
| `ItemIncompatibility` | `ruleId`, `itemId`, `conflictsWithItemId`, `conflictsWithCategory`, `winner`, `serviceType`, `channel`, `description` | A named item conflicts with another item and/or a whole category. **This is what covers add-ons** — an add-on is just an item. |
| `CategoryCardinality` | `ruleId`, `discountCategory`, `maxAllowed`, `serviceType`, `channel`, `description` | At most `maxAllowed` of this category per contract. |
| `Exceptions` | `ruleId`, `exemptItemId`, `appliesToRuleId`, `whenCategoryPresent`, `whenItemPresent`, `description` | Rescues an item from a rule. |

`winner` has **one meaning in every table**: empty ⇒ decide by precedence rank; `A` ⇒ the
left/`itemId` side always wins; `B` ⇒ the right/`conflictsWith` side always wins. There is no
`BY_PRECEDENCE` magic string — empty already says that.

`appliesToRuleId` names the single rule an exception covers. Left blank it covers any rule that
catches the item. Both `when*` columns blank makes the exemption unconditional.

### Logic — developer-owned

```
Items, Channel (inputs)          BKMs: PrecedenceOf, MatchingExceptions
  |
  +-> NormalisedItems      defaults filled, rank + origin key attached
        +-> OrderedItems   the one canonical total order
              +-> Scopes -> ScopeCategories, ScopeItemIds
              +-> ApplicableRules          filtered by channel / service type present
                    +-> CategoryConflicts
                    +-> ItemConflicts
                    +-> CardinalityViolations   (exemptions applied before counting)
                          +-> ResolvedConflicts  exceptions, resolution, participants
                                +-> Verdict      valid + allowed + rejected + conflicts
```

Each node is a DRG box you can evaluate on its own — `POST /api/dmn/evaluate` returns all of
them, which is how you locate a wrong intermediate value instead of hunting for it.

**Ordering is a total order**, not a rank comparison: `precedenceRank`, then `INVENTORY` before
`CART`, then `itemId` ascending. Rank alone leaves ties, and Drools' `sort` is not stable, so a
tie would make the verdict depend on input order — the exact failure this service exists to fix.

Conflict priority is `CategoryConflicts`, then `ItemConflicts`, then `CardinalityViolations`; an
item caught by several rules is rejected once, against the first.

A rule's trigger is **presence in the submitted set**, not survival of it. If item X is blocked
by category Y, it stays blocked even when the Y item is itself rejected by another rule. That is
deliberate: cascading would need iteration and could reintroduce order-dependence.

## Working in FEEL — read before editing the logic

Drools 10's FEEL has a defect that produces **silently wrong answers**, verified against the
engine:

> **A `for` body may contain at most one nested `for`.**
>
> With two sibling nested loops over an empty list the whole expression evaluates to `null`.
> With three, the loop variable leaks from the previous iteration and you get a plausible but
> wrong list. No error, no warning.

Every decision in this model is shaped around that. When a step needs several per-item lists,
flatten once into rows carrying their keys and then use **plain list filters**
(`hits[ruleId = c.ruleId and scopeId = c.scopeId]`), which are unaffected. `ScopeCategories` and
`ScopeItemIds` are two decisions rather than one for exactly this reason.

Other verified behaviour worth knowing:

- `"rank " + 5` returns **null**, silently. Always `string(x)` before concatenating a number.
- An empty decision table (rule owner deleted the last row) evaluates to `null`, not `[]`.
  `count(null)`, `flatten(null)` and `distinct values(null)` then fail. Guard with
  `if X = null then [] else …`.
- A blank output cell is genuinely `null`, but the browser editor can write `""`. Guard both:
  `x = null or x = ""`.
- `sublist(list, 1, n)` returns null if `n` exceeds the list length.
- Chained projection over a list path (`{sorted: sort(...)}.sorted.itemId`) silently returns
  null. Bind to a name, or use an explicit `for`.
- A missing input (e.g. `Channel` absent from the context) makes every dependent decision SKIP
  and return null rather than erroring — which is why the controller always sets both inputs.
- `typeRef` on a logic decision is documentation, not enforcement. Type the data tables
  strictly; leave logic decisions `Any`.

## Build, run, test

```bash
./mvnw.cmd clean install          # build + all tests   (Windows: .\mvnw.cmd)
./mvnw.cmd clean install -DskipTests
./mvnw.cmd spring-boot:run        # http://localhost:8080
./mvnw.cmd test -Dtest=RuleContractTest
```

Docker: multi-stage `eclipse-temurin:21`, exposes 8080. Port comes from `${PORT:8080}`.

## Tests — generated from the rules, not written per rule

| Test | What it does |
|---|---|
| `RuleContractTest` | One dynamic test **per configured rule**: builds a request that makes the rule fire, asserts the outcome the rule row declares. A rule that cannot be made to fire fails as dead. |
| `RuleIntegrityTest` | Unique rule ids, every rule has a description, every category a rule names is ranked, `appliesToRuleId` resolves, `winner` is A/B/blank, no self-conflicts. No engine needed. |
| `EnginePropertiesTest` | Properties that hold whatever the rules say: order independence under shuffling, default-allow, scope isolation, inventory-beats-cart, `valid` ⇔ nothing rejected, allowed/rejected partition the input, `appliedOrder` contiguous. |
| `DmnHotReloadTest` | The no-redeploy path: edit, save, version changes, roll back. Includes the case where a structurally broken rule must be **rejected**, not silently dropped. |

Every response is validated against the published JSON Schema on its way through
`CompatibilityClient`, so no test can pass on a response a real caller would reject.

**To add coverage, add a rule** — in the editor or the DMN. Do not add a case file.

## Conventions

- **Never hand-code rule data in Java.** Rules belong in the DMN tables. There are no entity
  classes, no repository, no rule service layer — that is intentional and follows directly from
  the no-redeploy requirement.
- **Default-allow.** An item no rule touches stacks with everything. Silence means compatible;
  only conflicts are declared. Do not invert this.
- **Evaluation is whole-set and stateless.** Same input, same verdict, every time. Do not
  introduce incremental evaluation, hidden state, or anything order-sensitive — consumers
  reproduce this calculation independently and rely on it matching.
- **The model carries no discount amounts and no cart state.** Precedence is declared rank only.
  These are known gaps, not oversights; read
  [docs/campaign-stacking-context.md](docs/campaign-stacking-context.md) before designing around
  them.
- Business-facing rule changes go in the five data tables. Only touch the logic decisions when
  the *shape* of the evaluation changes.
- DMN element IDs follow a strict convention (`_D_<NAME>`, `_DT_<NAME>_R<n>_O<m>`, `_CE_…`,
  `_EX_…`). Hand-written XML must keep every ID unique — a duplicate makes the whole model fail
  to build with an opaque error.
- Keep FEEL expressions XML-escaped inside `<dmn:text>` (`&lt;`, `&gt;`, `&amp;`).
- Adding a new *decision* also requires a `dmndi:DMNShape`, a `dmndi:DMNEdge` per requirement,
  and a `kie:ComponentWidths` entry. Adding a *row* to an existing table requires none of that.
- `springdoc.packages-to-scan=com.example.discount` is deliberate — widening it breaks Swagger.

## Known drift

- **[PLAN.md](PLAN.md), [test-plan.md](test-plan.md) and
  [test-cases-individual-incompatibilities.md](test-cases-individual-incompatibilities.md) are
  stale.** They describe the pre-refactor model (`EligiblePromotions`, `CategoryBlockingRules`,
  `overrideWinner`, `BY_PRECEDENCE`) and a per-scenario test suite that no longer exists. Kept
  as design-time history; do not trust them for current behaviour.
- The demo rule dataset uses real Telenor promo ids but is illustrative, not the production rule
  set: 4 category rules, 5 item rules, 2 cardinality rules, 4 exceptions.
- The editor GUI depends on unpkg being reachable; it will not load offline or behind a strict
  egress policy.
