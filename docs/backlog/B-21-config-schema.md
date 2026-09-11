---
id: B-21
title: "The schema DSL and the reader"
status: done
priority: P0
size: M
stage: m4-config
epic: feature-typed-config
---

# B-21 — The schema DSL and the reader

A typed declaration — name, type, required or defaulted, secret or not, and pairs that must be set
together — read once, before anything serves.

- **The decision and its reason.** One source: the environment. Every additional source is a rule
  about which of two values wins, which is a new way to be wrong about what is running. The value of
  the feature is the schema and the refusal, not the plumbing.
- **Rejected:** a configuration framework with files and precedence. Explicitly a non-goal.
- A boolean means the exact string `true`; anything else, `"True"` included, means false, and an unset
  switch always means the closed position. A security switch that opens on a misspelling is one that
  ships open. This is the first consumer's rule already; kore makes it the type's behaviour rather
  than a comment beside each `==`.

- AC: a schema with a required field, a default, a secret and a declared pair reads correctly and
  refuses correctly; failure messages name the variable.
- Anchors: `kore-core/src/commonMain/kotlin/io/github/youndie/kore/config/`

## Iteration 1 — 2026-09-12

**Done.** `ConfigKey`, `ConfigSchema`, `ConfigPair`, `Environment`. 15 tests; 61 green on each
platform. No reflection anywhere, which is what makes it identical on Kotlin/Native — a key is a
value the caller holds, so a typo in a field name is a compile error rather than a lookup returning
null.

**Errors are collected rather than thrown on the first**, and that is the decision worth defending:
a process that fails on the first missing variable makes you fix them one at a time, one restart
each, and a deployment being configured for the first time has several.

**The pair rule needed a key type that did not exist.** An agent's endpoint and its key are each
*optional* — both unset means "no agent", which is a decision — so a pair cannot be made of required
fields. `ConfigKey.optional` came out of writing the rule down rather than out of the design.

**A blank value is an unset value**, which is not obvious and is the ordinary case: a chart that
renders an empty string for an absent setting would otherwise produce a required variable that is
present and useless.

**Mutations, all killed:**

| Mutation | Result |
|---|---|
| a boolean accepts anything truthy | 1 of 61 red |
| throw on the first problem instead of collecting | 1 of 61 red |
| a secret is not masked | 1 of 61 red |
| half a pair is accepted | 1 of 61 red |
| a defaulted value reports `ENV` | 2 of 61 red |
| a blank value counts as set | 1 of 61 red |

**Four of nine scenarios become `**Automated:**`**; coverage goes from 10 of 34 to 14 of 34.

One of the five that remain is worth naming: *"a variable outside the prefix is not the schema's
business"* would **pass vacuously** today, because there is no unknown check for it to survive. A
scenario that passes because the mechanism it tests does not exist is the failure this repository
keeps finding, so it stays unmarked until [B-24](B-24-unknown-variable-refusal.md).

**Not done and not pretended:** enumeration (B-22), the unknown-variable refusal (B-24) and
`--print-config` (B-23). `Environment` declares `lookup` and nothing else, so the harder question —
whether the environment can be *listed* on a given target — is still entirely B-22's.
