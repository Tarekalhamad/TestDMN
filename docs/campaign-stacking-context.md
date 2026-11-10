# Campaign stacking prevention — business context

Why this service exists, what the business needs from it, and which of those needs the current
implementation covers. Compiled from requirement-gathering sessions; it records **intent**, so
read it before proposing changes to the rule model.

No individuals are named here. Requirements are stated impersonally, by capability.

## The business problem

Roughly **185 promotions across ~300 campaign configurations** are in circulation. They are
granted independently by the Promotion API, and nothing prevents conflicting ones — an employee
discount alongside a binding discount, say — from applying at the same time. Four concrete
failures follow:

1. **Wrong price to the customer.** Incompatible discounts combine and the customer sees, or is
   quoted, a price the business never intended to offer. Showing an eligible price before
   eligibility is confirmed carries its own legal and trust exposure, so the price has to be
   right at the moment it is presented, not corrected afterwards.
2. **Order dependence.** Cart behaviour today is effectively **first come, first served**: the
   final price depends on the sequence in which discounts happened to be applied. The same
   basket can settle at different prices by different routes.
3. **No re-evaluation.** When a discount is added later in the purchase flow, the cart is not
   re-assessed. A higher-priority discount that becomes applicable cannot displace what is
   already there.
4. **Unmaintainable, invisible configuration.** At this scale nobody can reason about which
   combinations are possible. There is no central catalogue of discounts carrying compatibility
   metadata, so the relationships between them exist only in people's heads. And when a discount
   does not apply, neither the customer nor the agent is told why.

The root gap: **no unified model of discount compatibility.** This service is that model.

## Formal requirements

The backlog stories, with how this service currently answers each. Priorities are as stated by
the business; open questions are recorded verbatim in intent.

### 1.10 — Configure stacking prevention rules · Must have

Configure rules defining incompatibility relations between **a set of discount categories
and/or individual add-on offerings** (i.e. individual discount offerings), so that no configured
incompatibility can exist on any contract created or updated.

*Status:* **partly implemented.** Category-to-category and promotion-to-promotion
incompatibility exist (`CategoryBlockingRules`, `PromotionBlockingRules`). **Add-on offerings
are not modelled at all** — the input carries promotions and their discount categories, nothing
else. This is the largest gap against a Must-have story.

*Preferred configuration surface:* **Commerce Studio** (an internal initiative building its own
catalogue GUI, not a purchased product). The `editor.html` GUI in this repo should be treated as
an interim surface, not the destination.

*Open question, unresolved:* should these rules differ **per service type**?

### 1.20 — Exceptions to an incompatibility relation · Must have

Optionally define exceptions as part of a rule definition, expressed as **one or a list of
add-on offerings**, so specific offerings within a category or product specification are
excluded from the incompatibility and therefore accepted.

*Status:* **partly implemented.** `PromotionExceptionRules` provides the mechanism, but keyed on
`promotionId`, not on add-on offerings, and it takes a single value per column rather than a
list. The intended usage — "these add-ons inside the blocked category are exempt" — needs the
add-on dimension from 1.10 first.

### 1.25 — Precedence within an incompatibility relation · Nice to have (possibly not)

Optionally declare that some categories or add-on offers take precedence over others within a
relation, so it is clear what has to be removed from the shopping cart.

*Status:* **implemented, and more central than the business asked for.** See "Precedence: built
deeper than required" below — this is the most consequential divergence between the
implementation and the requirements.

### 1.30 — Rules validated against all active promotions · Must have

Stacking prevention rules are validated against all active promotions, removing the need to
define blocking criteria individually inside each promotion.

*Status:* **implemented at evaluation time.** Rules are declared centrally, once, and apply to
whatever is passed in — no per-promotion blocking configuration. Not implemented as
**design-time validation**: there is no facility to check a newly written rule against the
active promotion catalogue, and no validation that a referenced category or promo ID exists. A
typo yields a silently inert rule.

*Open question, unresolved:* are these rules service-specific or global?

### 1.40 — Validate against inventory *and* shopping cart · Must have

Rules are validated against both the existing inventory and the shopping cart, so conflicts
never reach the inventory. **The union of inventory and shopping cart items must be valid.**

*Status:* **supported by the contract, and the caller's responsibility.** The service takes a
flat `EligiblePromotions` list and evaluates it as a set, so passing the union of inventory and
cart items is exactly the intended usage — this is why the API takes a list rather than a
customer or cart reference. The service has no knowledge of which items came from where, and
cannot distinguish "already provisioned" from "being added". If a rejection reason ever needs to
say which side an item came from, that origin must become an input field.

*Flagged for clarification by the business:* **timing** — when in the flow this validation runs.

### 1.50 — Block checkout, with clear conflict feedback · Must have

Checking out a cart that violates any rule must be impossible, while giving the client clear
feedback about **which items conflict with each other**, so the user can be shown what to
correct. Explicitly: conflicts are **not** prevented from entering the cart.

*Status:* **partly implemented — and the shape differs.** The engine returns
`RejectedPromotions` with a reason per item, which is close, but it *resolves* the conflict
itself rather than reporting it for the user to resolve. See below.

### 1.60 — Cart discounts count as inventory · Must have

A discount added to the shopping cart is considered to be "in the inventory", so multiple
discounts of the same type cannot be checked out to the same cart where rules disallow it.

*Status:* **follows from 1.40** — if the caller passes the union, cart items are already treated
identically to inventory items. Note the sub-case: *multiple discounts of the same type*. The
current rules are pairwise between two named categories or promotions; a same-category rule
(`groupA` = `groupB`) has not been tested and cardinality constraints ("at most one of category
X") are not expressible.

## Two divergences worth deciding on

**Precedence: built deeper than required.** The business rates precedence **1.25 Nice to have,
possibly not**. The implementation makes it the *central* mechanism: an 18-row
`CategoryPrecedence` table drives conflict resolution, survivor ordering and the `appliedOrder`
output. That is not automatically wrong — precedence is what lets the engine produce a
deterministic answer to the first-come-first-served problem — but it means significant
implementation weight sits behind a nice-to-have, and it shapes the output contract. Do not
delete it casually; do not assume the business has committed to it either.

**Auto-resolution vs conflict reporting.** These are different products, and the requirements
lean toward the second:

- *What is built:* the engine picks winners by precedence and returns `AllowedPromotions`
  (ordered) plus `RejectedPromotions` (with reasons). It decides.
- *What 1.50 asks for:* identify **which items conflict with each other** and block checkout, so
  the client can highlight what the user must correct. The user decides.

1.25 rating precedence as optional is consistent with the second reading — without precedence
you cannot auto-resolve, so you must report conflicts and let someone choose.

The good news is that the two are not exclusive, and the gap is small. `activeConflicts` and
`conflictResolutions` inside `CompatibilityResult` already compute the **pairwise conflict
structure**; it is simply not exposed. Surfacing it as a third output key — the conflicting
pairs, alongside the resolved verdict — would satisfy 1.50 without giving up auto-resolution,
and would let a caller choose which mode to use. That is the cheapest high-value change
available against this requirement set.

## Wider capability set

Everything raised in discussion, including items with no backlog story yet. The numbered
requirements above are authoritative where the two overlap.

| # | Capability | Status |
|---|---|---|
| 1 | Declare that two **discount categories** cannot coexist | Implemented — `CategoryBlockingRules` |
| 2 | Declare that two **specific promotions** conflict, not just categories | Implemented — `PromotionBlockingRules` |
| 3 | Define **exceptions** where a promotion overrides the general rule | Implemented — `PromotionExceptionRules` |
| 4 | Set **priority** deciding which discount wins when several apply | Implemented — `CategoryPrecedence`, `overrideWinner` |
| 5 | Return applicable **and** rejected promotions, each rejection with a reason | Implemented — `RejectedPromotions[].reason` |
| 6 | Let business rule owners change all of the above **without a release** | Implemented — editor GUI + hot reload |
| 7 | Default behaviour with no rule configured: **everything stacks** | Implemented — an untouched promotion always survives |
| 8 | Deterministic outcome regardless of the order discounts were added | Implemented — evaluation is order-independent over the input set |
| 9 | Central catalogue of discounts carrying compatibility metadata | Partly — the four rule tables are that catalogue, and the API echoes them |
| 10 | Incompatibility rules covering **add-on offerings**, not only discount categories | **Not implemented** — req 1.10 / 1.20, Must have |
| 11 | Report **which items conflict with each other** so the client can prompt a correction | **Not implemented** — req 1.50; the pairwise structure exists internally, unexposed |
| 12 | Validate the **union of inventory and cart** | Supported by the contract — req 1.40 / 1.60; the caller composes the union |
| 13 | Block **checkout** on violation (conflicts may enter the cart freely) | Caller's enforcement point — req 1.50 |
| 14 | Design-time validation of a new rule against the active promotion catalogue | **Not implemented** — no existence checks at all |
| 15 | Cardinality rules ("at most one of category X") | **Not implemented** — rules are pairwise only |
| 16 | Automatically remove or replace cart discounts when a higher-priority one becomes applicable | **Not implemented** — engine is stateless |
| 17 | Prioritise by **calculated customer value**, not declared sequence | **Not implemented** — no amounts in the model |
| 18 | Let the customer **choose** between mutually exclusive offers | **Not implemented** |
| 19 | Per **sales channel** rule variation | **Not implemented** |
| 20 | Per **service type** rule variation | **Not implemented** — open question in req 1.10 |
| 21 | Real-time campaign adjustment on behavioural signals (e.g. churn risk) | Out of scope for this service |

Capability 8 is worth noting as a property rather than a feature: because the service evaluates
the whole eligible set at once and resolves conflicts by declared precedence, the same input
always yields the same verdict. That is the direct answer to the first-come-first-served
problem — provided the caller re-evaluates the full set rather than applying discounts
incrementally.

## Design decisions and their rationale

**Rules are data, not code.** Capability 6 is why there is no rule logic in Java, no entity
model, no rules database. The four decision tables *are* the configuration surface; the browser
editor plus hot reload is the change mechanism. Any proposal that moves rule data into Java, or
needs a redeploy to change a rule, defeats the purpose of the service.

**Explicit rules over generalised logic.** Whether conflicts should be declared scenario by
scenario or derived from generalised priority / algebraic rules was raised as an open question.
The current model answers *explicit*: rules are stated pair by pair, each with a description
column, so the people maintaining them can read them. The trade is real and the tension is
live — explicit pairs get verbose at 300 configurations, while abstract rules are harder for
rule owners to reason about unaided. Expect this to be revisited; do not treat the current
answer as settled, but do not quietly change it either.

**Whole-set evaluation, not incremental.** The service takes the entire eligible promotion list
and returns a complete verdict. This is what makes the outcome order-independent, and it is why
the API takes a promotion list rather than a customer or cart reference. It is also what makes
requirement 1.40 workable: the caller composes the union of inventory and cart items and passes
it as one set.

**The service validates; the caller enforces.** Requirement 1.50 puts the enforcement point at
**checkout**, and explicitly does *not* prevent conflicting items from entering the cart. This
service is therefore a validator that can be called freely at any point — it has no veto, no
side effects and no state. Anything that makes it gate an operation belongs in the caller.

**Default-allow.** A promotion with no rule touching it stacks with everything. Silence means
compatible. This keeps the tables small — only conflicts are declared — but it fails open, in
the customer's favour and against the business. That was the deliberate choice for unconfigured
campaigns.

**Exceptions are first-class.** Overriding a general rule for a named promotion came up as a
distinct requirement, not an edge case, which is why `PromotionExceptionRules` is a separate
table evaluated after both blocking tables rather than a flag on a blocking rule.

**Reasons are generated, not stored.** Rejection reasons are built at evaluation time from the
rule that fired (`buildReason()`), resolving promo IDs to promotion names where possible. This
serves capability 5 and the wider need to explain removals in the cart.

## Consumption model

Intended to be used as a **library with API access**, called in real time during the purchase
flow. One call returns:

- the promotions that may be applied, in priority order (`appliedOrder`)
- the promotions that were rejected, each with a reason
- the rule tables themselves — blocked promotions, blocked categories, current priority list

The caller passes the **union of inventory and shopping cart items** (req 1.40, 1.60) and calls
the service as often as it likes — it is stateless and side-effect free. Enforcement happens at
**checkout** in the caller (req 1.50); conflicting items may sit in the cart until then.

Applicability is **context-dependent**: it is determined at the point of customer interaction,
when cart contents are known, rather than precomputed as bundles in the catalogue. Rules are
generic rather than tied to specific product combinations, and the applicable set is computed
dynamically as items are added.

**Where evaluation belongs is an open question.** Backend rule engine, frontend/cart, and
external analytics have all been raised. The current answer is backend, with the rules exposed
through the API so other consumers can reproduce the calculation. That reproducibility — same
inputs, same result, wherever it runs — is a design constraint, not an accident: it is what lets
analytics or the browser compute the same answer independently.

A known limitation: the engine's output still has to be **interpreted**. A machine-readable
verdict plus a reason string is a step toward explainability, but turning that into
customer-facing copy remains the consumer's job.

## Adjacent needs this service does not solve

Recorded so they are not accidentally designed into this component. **Add-on offerings are
deliberately not in this list** — they are a Must-have gap inside scope, tracked under
requirements 1.10 and 1.20 above.

- **Cart-stage re-evaluation and replacement.** Automatically removing or replacing a discount
  when a higher-priority one becomes applicable mid-flow needs cart state and a sequencing
  policy. The engine is stateless and evaluates a snapshot; today the caller must re-invoke it
  with the full set and act on the result.
- **Business control vs customer choice.** Where several offers are mutually exclusive — a
  tablet versus a discount, for instance — someone must decide whether the business picks or the
  customer does. The engine currently returns one deterministic verdict with no notion of
  alternatives.
- **Value-based selection.** Choosing what is worth most to the customer, or most to the
  business, requires discount amounts, which this model does not carry. Precedence is declared
  rank only.
- **Omni-channel consistency.** Online, customer service and in-store must present discounts
  consistently, and external retailers run separate systems that count as distinct channels
  needing their own configuration. There is no channel dimension in the model.
- **Analytics and propensity.** Analytics and technical teams need access to the rules for
  reporting and propensity modelling, and there is interest in feeding real-time signals —
  behaviour, churn risk, propensity scores — into discount selection. This is an argument for
  keeping the rule tables exposed through the API and the engine free of hidden state.
- **Campaign performance monitoring.** Real-time visibility into campaign performance, and
  adjusting discounts for high-risk customers, is a separate capability.

## Vocabulary

| Term | Meaning here |
|---|---|
| Promotion | One offer, identified by `promotionId`, granting a discount product offering |
| Discount category | The class of discount (`EmployeeDiscount`, `OTSDiscount`, ...); source enum in `src/jsonExample/refferdtype` |
| Campaign configuration | One configured instance of a promotion; several may exist per promotion |
| Stacking | Two or more discounts applying to the same contract simultaneously |
| Incompatibility | A declared conflict, at category or promotion level |
| Exception | A declared carve-out permitting a normally-blocked combination |
| Precedence / priority | Declared rank deciding which side of a conflict survives; lower number wins |
| Eligible promotion | A promotion the Promotion API has already determined the customer qualifies for — the input to this service |
| Applied order | Position in the final ordered list of surviving promotions |
