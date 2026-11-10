---
name: promotion-domain
description: Translate a business request about campaign stacking, discount incompatibility, priority, add-on offerings, cardinality or exceptions into the right change in this service — or recognise when the request needs a capability the model does not have (discount amounts, customer choice, cart-stage re-prioritisation). Use when the ask arrives in business language rather than as a concrete file change, or when scoping a new feature against the existing rule model.
---

# From a business request to a change here

The service answers one question: **given everything this customer could have, what may actually
be applied, and why was the rest dropped?** It evaluates the whole set at once and resolves by
declared precedence, which is what makes the answer independent of the order things were added.

Read [docs/campaign-stacking-context.md](../../../docs/campaign-stacking-context.md) for the
requirement set and vocabulary before designing anything new.

## Which table does this map to?

| The request sounds like | Table | Notes |
|---|---|---|
| "X and Y can't be on the same subscription" (both are *classes* of discount) | `CategoryIncompatibility` | The common case |
| "promotion 299 can't go with an employee discount" | `ItemIncompatibility` | Named item vs a whole category |
| "promo 72 and 73 are alternatives" | `ItemIncompatibility` | Named item vs named item |
| "this **add-on offering** can't go with …" | `ItemIncompatibility` | An add-on is just an item — no separate table |
| "only one sign-up discount per contract" | `CategoryCardinality` | Not a pairwise rule |
| "…except for promotion 5" | `Exceptions` | Name the rule in `appliesToRuleId` to scope the carve-out |
| "employee discounts always beat everything" | `CategoryPrecedence` | Lower rank wins |
| "X wins even though Y ranks higher" | `winner` = A or B on the rule | Overrides rank for that rule only |
| "only online" / "only broadband" | `channel` / `serviceType` on the rule | Blank = always |

If it maps to a table, it is a configuration change: no code, no redeploy, and a generated test
case appears automatically. Route it to `dmn-rule-tables`.

## What the model does *not* carry

Say so plainly rather than approximating it:

- **Discount amounts.** Precedence is declared rank only, so "pick whichever is worth more to
  the customer" cannot be expressed. This is the most frequently requested missing capability.
- **Customer choice between mutually exclusive offers.** The engine returns one deterministic
  verdict, with no notion of alternatives to present.
- **Cart state and re-prioritisation mid-flow.** The engine is stateless and judges a snapshot.
  Replacing a discount when a better one becomes applicable is the caller's job: re-invoke with
  the full set and act on the result.
- **A promotion catalogue.** Rules can be checked against `CategoryPrecedence`, but there is no
  list of valid promotion ids to validate an `itemId` against, so a typo'd id is an inert rule
  that only a test can find.
- **Real-time behavioural signals**, propensity, churn risk. Out of scope for this component.

## Shape of the answer

- `valid` — false means checkout must be blocked. One boolean, no interpretation.
- `allowed` — survivors in priority order with `appliedOrder`.
- `rejected` — each with a stable `reasonCode`, the `ruleId` that fired, and human text.
- `conflicts` — which items clashed with which, under which rule, and how it was settled.
  Reported **even when an exception rescued everyone** (`resolution: EXEMPTED`).

That last one matters for requirement 1.50: the business asked to be told *which items conflict*
so a customer can be shown what to correct, not only what was taken away. The engine does both —
it auto-resolves *and* reports — so a caller can use either mode.

## Things worth pushing back on

- **"Just make it prevent the discount from being added."** The requirement puts enforcement at
  checkout and explicitly allows conflicts into the cart. This service has no veto, no state and
  no side effects; adding one belongs in the caller.
- **"Can we make the rules more general instead of listing pairs?"** A live tension, recorded in
  the context doc. Explicit pairs get verbose at 300 configurations; abstract rules are harder
  for rule owners to reason about unaided. The current answer is explicit, with a description on
  every rule. Do not change it quietly.
- **"Add a field for X."** The item model and the conflict output are both extensible by adding
  fields, so amounts or alternatives can arrive later as additive changes. Check
  `docs/REFACTORING-PLAN.md` §9 first — some of these are deliberately out of scope.
- **Precedence.** The business rates it *nice to have, possibly not*, yet it drives resolution
  and ordering. Do not delete it casually, and do not assume the business has committed to it.

## Scoping something genuinely new

1. Can it be a row in an existing table? Then it is configuration — done.
2. Does it need a new *kind* of relationship? That is a sixth table plus a decision node; see
   `dmn-feel-logic`, and keep the table a legible grid rather than generalising the five.
3. Does it need data the item model does not carry? Then the contract changes: the JSON Schemas
   in `src/main/resources/static/schemas/`, the `tItem` definition, and `NormalisedItems`.
4. Does it need state, ordering, or a decision the customer makes? It does not belong here.
