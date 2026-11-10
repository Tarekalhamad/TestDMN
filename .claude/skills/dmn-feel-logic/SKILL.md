---
name: dmn-feel-logic
description: Modify or debug the FEEL evaluation logic in PromotionCompatibility.dmn — the decision chain that scopes items, detects conflicts, applies exceptions, resolves winners and assembles the verdict. Use when changing HOW rules are evaluated (a new rule type, a new output field, a new decision node) rather than adding rule rows, or when an evaluation returns a wrong or null result.
---

# Changing the evaluation logic

Rule *data* lives in five decision tables (see `dmn-rule-tables`). Everything below is the
engine, and it is developer-owned.

## The decision chain

```
Items, Channel (inputs)                BKMs: PrecedenceOf, MatchingExceptions
  |
  +-> NormalisedItems       defaults filled in; precedenceRank and originRank attached once
        +-> OrderedItems    the one canonical total order
              +-> Scopes -> ScopeCategories, ScopeItemIds
              +-> ApplicableRules           rules that can apply to this request at all
                    +-> CategoryConflicts
                    +-> ItemConflicts
                    +-> CardinalityViolations    exemptions applied before counting
                          +-> ResolvedConflicts  exceptions, resolution, participants, id lists
                                +-> Verdict      valid + allowed + rejected + conflicts
```

Every node is an independently evaluable DRG box. `POST /api/dmn/evaluate` returns **all** of
them with their evaluation status — that is how you locate a wrong intermediate value instead of
bisecting the whole chain by hand. Use it first, always.

## The FEEL defect that shapes this model — read this before editing

Verified against Drools 10's engine, not from documentation:

> **A `for` body may contain at most one nested `for`.**
>
> Two sibling nested loops over an empty list make the whole expression evaluate to `null`.
> Three make the loop variable leak from the previous iteration, so you get a plausible but
> **wrong** list. There is no error and no warning in either case.

```
for c in raw return {x: (for h in hits[k = c.k] return h),
                     y: (for h in hits[k = c.k] return h),
                     z: (for h in hits[k = c.k] return h)}
   -> the second iteration returns z from the FIRST iteration
```

The pattern that works: flatten once into rows carrying their keys, then use **plain list
filters**, which are unaffected.

```
hits       : flatten(for c in raw return for m in c.members return {ruleId: c.ruleId, ...})
exemptHits : (for h in hits return if count(h.exceptions) > 0 then h else null)[item != null]
counted    : for c in raw return {exempt: exemptHits[ruleId = c.ruleId and scopeId = c.scopeId], ...}
```

`ScopeCategories` and `ScopeItemIds` are two decisions rather than one for exactly this reason.
If you add a node that needs several per-item lists, split it the same way.

## Other verified engine behaviour

| Trap | What actually happens | Do this |
|---|---|---|
| `"rank " + 5` | Returns **null**, silently. The whole reason string is lost. | `"rank " + string(5)` |
| Empty decision table | Evaluates to `null`, not `[]`. `count(null)`, `flatten(null)`, `distinct values(null)` then fail. | `if X = null then [] else ...` |
| Blank output cell | Genuinely `null` — but the browser editor can write `""`. | Guard both: `x = null or x = ""` |
| `sublist(list, 1, n)` | Null when `n` exceeds the length. | Guard the length first |
| `{s: sort(...)}.s.itemId` | Chained projection over a list path silently returns null. | Bind to a name, or use an explicit `for` |
| Missing input in the context | Every dependent decision SKIPs and returns null; no exception. | The controller always sets both `Items` and `Channel` |
| `typeRef` on a logic decision | Not enforced; a typo'd output field is not caught. | Type the data tables strictly, leave logic decisions `Any` |
| Filters, `some`/`every`, `flatten`, `[item != null]` on empty lists | All safe | — |

## Invariants you must not break

- **Order independence.** `OrderedItems` is a *total* order — rank, then INVENTORY before CART,
  then itemId. Rank alone leaves ties, and Drools' `sort` is not stable, so any tie makes the
  verdict depend on input order. That is the exact failure this service exists to fix, and
  `EnginePropertiesTest` shuffles the input to catch a regression.
- **Scope isolation.** Conflicts are evaluated inside one `scopeId`, never across. An omitted
  `scopeId` becomes the implicit scope `""`.
- **Default-allow.** An item no rule touches survives.
- **One rejection per item.** `ResolvedConflicts.all` concatenates category, then item, then
  cardinality conflicts; `Verdict` keeps the first rejection per item.
- **A rule's trigger is presence in the submitted set**, not survival of it. Cascading would
  need iteration and could reintroduce order-dependence.

## Adding a decision node

1. Add the `<dmn:decision>` with its `<dmn:variable>` and one `<dmn:informationRequirement>` per
   input it reads (`<dmn:knowledgeRequirement>` for a BKM).
2. Add a `dmndi:DMNShape` for it and a `dmndi:DMNEdge` per requirement, plus a
   `kie:ComponentWidths` entry for a boxed context. Without these the editor renders it badly or
   not at all.
3. Keep every id unique. XML-escape FEEL: `&lt;`, `&gt;`, `&amp;`.
4. Rebuild — `kogito-maven-plugin` validates the model at compile time and fails the build on a
   malformed one.

Adding a *row* to an existing table needs none of that.

## Changing the output contract

The response shape is published as JSON Schema in `src/main/resources/static/schemas/` and every
response in the test suite is validated against it. Change the schema and the `Verdict` decision
together, or the suite will tell you which one you forgot. The enumerations (`reasonCode`,
`ruleType`, `resolution`) are fixed there too.

`CompatibilityController` maps the DMN `Verdict` onto the response and adds `ruleSetVersion`.
Nothing there interprets a rule, and nothing should.
