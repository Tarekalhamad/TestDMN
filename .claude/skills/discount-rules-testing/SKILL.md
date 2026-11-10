---
name: discount-rules-testing
description: Build, run and extend the discount-rules test suite — the generated per-rule contract tests, the rule integrity checks, the engine property tests, and the hot-reload tests. Use when asked to run the app, verify a rule change works, add coverage, or diagnose why a test or endpoint returns unexpected output.
---

# Testing the discount rules service

## The one rule about this suite

**Test cases are generated from the configured rules. Never write a test for a specific rule.**

`RuleProbes` reads the live tables and, for each rule, builds a request that makes that rule
fire plus the outcome the rule row itself declares. Add a rule in the browser editor and a case
appears; change a `winner` and the expectation changes with it. Writing a case by hand puts a
copy of the rule outside the DMN, which goes stale the first time a rule owner edits it — and
rule owners edit rules without a release, which is the entire point of the service.

If a change needs new coverage, ask whether it is *rule data* (add the rule; the case follows)
or *engine behaviour* (add a property to `EnginePropertiesTest`).

## Commands

```bash
./mvnw.cmd clean install                 # build + all tests
./mvnw.cmd test                          # tests only
./mvnw.cmd test -Dtest=RuleContractTest  # one class
./mvnw.cmd spring-boot:run               # http://localhost:8080
./mvnw.cmd clean install -DskipTests
```

Windows uses `.\mvnw.cmd`. Set `JAVA_HOME` to a JDK 21+ install. `-o` runs offline once
dependencies are cached.

## The suite

| Class | What it proves |
|---|---|
| `RuleContractTest` | One dynamic test **per configured rule**. Asserts on the `conflicts` entry for that rule id — which reports what one named rule did, so it stays true even when another rule catches the same item first — plus that the declared loser really lost its place. A rule that cannot be made to fire fails as **dead**. |
| `RuleIntegrityTest` | Structure only, no engine: unique rule ids, every rule has a description, every category a rule names is ranked, `appliesToRuleId` resolves, `winner` is A/B/blank, no self-conflicts, `maxAllowed >= 1`. Catches the typo that makes a rule silently inert. |
| `EnginePropertiesTest` | Guarantees that hold whatever the rules say: order independence under shuffling, default-allow, unknown category survivable, scope isolation, inventory-beats-cart on a tie, `valid` ⇔ nothing rejected, allowed/rejected partition the input, `appliedOrder` contiguous, missing `Items` refused. |
| `DmnHotReloadTest` | The no-redeploy path end to end: read, edit, save, version changes, roll back, versions kept. Includes the case where a **structurally broken rule must be rejected**, not silently dropped. |

`CompatibilityClient` validates **every** response against the published JSON Schema in
`src/main/resources/static/schemas/`, so no test can pass on a response a real caller would
reject.

Tests run against a throwaway storage directory (`src/test/resources/application.properties`
sets `dmn.editor.storage-path` under the temp dir with a random uuid), so they never read or
write the developer's `dmn-storage/`, and a stale local model cannot change a test outcome.

## Diagnosing a failure

1. **Read the failure message.** They carry the request, the conflict and the whole verdict on
   purpose.
2. **`POST /api/dmn/evaluate`** returns every decision node's result and status. This is the
   fastest way to find which node produced the wrong intermediate value. Work down the chain:
   `OrderedItems` → `ApplicableRules` → the three conflict nodes → `ResolvedConflicts` →
   `Verdict`.
3. **A null result with no exception** usually means a missing input or one of the FEEL traps —
   see `dmn-feel-logic`. In particular, two sibling nested `for` loops in one `for` body return
   null or, worse, a wrong list.
4. **A rule that "does nothing"** is usually a category the precedence table does not rank.
   `RuleIntegrityTest` names it.
5. **`RuleContractTest` says a rule is dead**: either the exception names a rule that cannot
   catch its item (e.g. it exempts the *winner* of that rule), or `appliesToRuleId` points at
   nothing.

## Trying it by hand

```bash
curl -s -X POST http://localhost:8080/api/compatibility/check \
  -H 'Content-Type: application/json' \
  -d '{"Items":[
        {"itemId":"103","itemName":"Employee discount","itemType":"PROMOTION","discountCategory":"EmployeeDiscount"},
        {"itemId":"600","itemName":"Binding 24mo","itemType":"PROMOTION","discountCategory":"LegacyBindingDiscount"}]}'
```

`GET /api/rules` shows the rules as configured; `GET /api/rules/version` is the hash a verdict
can be traced back to.

The editor's **Try the rules** panel generates a case for every rule in the model, the same way
the JUnit suite does — the quickest way to show a rule owner their rule working.

## The `.scesim` file

`src/test/resources/PromotionCompatibilityTest.scesim` predates the current model and contains
no scenarios. Business-authored scenarios can be added with the Test Scenario editor against the
new `Items` / `Verdict` model; the runner is `ScenarioJunitActivatorTest` and it needs
`junit-vintage-engine`, which is why that dependency is on the test classpath.
