# Sketch — Compose-for-TV rewrite

**Status:** Pre-plan brainstorming brief. Do **not** start implementing. The migration shape isn't decided.

**Why this is a sketch:** Compose-for-TV migrations have well-known footguns (focus management, leanback parity gaps) and the right answer depends on whether we're willing to drop minSdk 28 (Compose-for-TV's effective floor is messy on older Android TV builds), how much UI code we're willing to rewrite at once vs. screen-at-a-time, and whether v1.x users care about the reskin.

The Plan 3 brainstorm already considered this and explicitly chose Leanback for v1: "Approach 2 — Clean-room Compose-for-TV rewrite. Pro: long-term maintainability. Con: 3–4× the work. Compose-for-TV is still maturing; you'll hit edge cases (focus management, leanback parity) that upstream already solved with Java leanback fragments. Ships much later, with more risk."

This sketch is the followup that asks: *now* (post-v0.3.0, v0.x stable), is the trade-off different?

---

## What we have today

- `tv/src/main/java/eu/hyperhdr/android/tv/ui/`:
  - `MainActivity` + `MainFragment` (Plan 3 / Plan 4 stats)
  - `wizard/WizardActivity` + 4 `GuidedStepSupportFragment`s
  - `settings/SettingsActivity` + `SettingsFragment` (legacy `LeanbackPreferenceFragment` because `LeanbackPreferenceFragmentCompat` is absent in Leanback 1.0.0)
- `androidx.leanback:leanback:1.0.0` and `leanback-preference:1.0.0` — both unchanged since 2018, with no roadmap to a successor.

## Why considering it

- **Leanback is unmaintained.** No new releases since 2018. The gaps from the AndroidX migration (the `Compat` fragment never shipping in 1.0.0) won't be filled.
- **Compose-for-TV** is maintained. `androidx.tv:tv-foundation` and `androidx.tv:tv-material` are real, active libraries.
- **Our UI is small.** Three screens (main, wizard, settings) = ~500 lines of XML/Kotlin. A Compose rewrite is feasible in a single milestone, not 3–4× as in Plan 3's original estimate (the Plan 3 estimate assumed we were starting from upstream's much larger UI surface; we've stripped most of it).

## Open design questions for brainstorming

### 1. Migration order

- **(a) All screens at once.** Riskier; ship as v1.0.0 reskin.
- **(b) Settings first.** The legacy fragment is the worst maintenance debt; replacing it with `androidx.tv.material.surface` + Compose preferences is the highest-leverage swap.
- **(c) Wizard first.** Most user-visible; sets the design language for the rest.
- **(d) Main screen first.** Smallest scope; lets us validate the Compose-for-TV layout primitives without much risk.

Recommendation: **(d) → (b) → (c)**. Cumulative learning, each release ships something visibly better, leanback dependency drops only at the end.

### 2. Coexistence

While migrating screen-at-a-time, can a Compose screen launch a Leanback screen and vice versa? Yes — Compose screens are hosted in `ComponentActivity`, Leanback screens are hosted in `FragmentActivity`. They navigate via `Intent` like any other activity transition. The dependency graph for v1.0.0 would temporarily contain both.

### 3. Theming

- Do we want a custom design language ("HyperHDR-themed" — dark, accent color, hero animations) or stock Material-for-TV?
- Stock is faster; custom is more memorable.
- Plan 3's brainstorm used the Leanback default theme. No design ambition was ever set.

### 4. Focus management

The hardest part. On a TV remote, every focusable view needs predictable d-pad navigation. Compose-for-TV's `Modifier.focusable()` and `tvLazyColumn` handle the basics, but:
- Default focus on screen entry (hand-holding required).
- Focus restoration on Back navigation.
- Long-press handling (some remotes treat long-press as a separate keycode).

These are exactly the things Leanback's `GuidedStepSupportFragment` and friends handled out-of-the-box. Compose-for-TV is closer to "build it yourself."

### 5. State management

Today the app uses `StateFlow` from the binder + `lifecycleScope.launch { collect { }}` in fragments. Compose's idiom is `collectAsState()` inside `@Composable`. Trivial migration; no architectural change required.

### 6. Tests

- Compose UI tests use `createComposeRule()` + `onNodeWithText(...)` etc. Different toolkit from `androidx.test.espresso` (which we don't actually use today — our UI has no automated tests beyond compile-checks).
- Adding Compose UI tests is a net win — current UI has zero behavioral test coverage.

### 7. minSdk

`androidx.tv:tv-material:1.0.0+` requires `minSdk 21` officially, but Compose-for-TV in practice only works smoothly on API 26+. We're at minSdk 28 already; that floor is fine. No bump needed.

## Pre-rewrite checklist

Before writing the implementation plan:

- [ ] Brainstorm the seven questions above with the user (or with whoever owns design).
- [ ] Decide migration order (recommendation: main → settings → wizard).
- [ ] Establish a theme — even just "stock Material-for-TV with our existing accent color."
- [ ] Set up a small Compose-for-TV experiment in the existing repo: a `tv/src/main/java/eu/hyperhdr/android/tv/compose/` package with one trivial composable that renders to a new activity, alongside the existing UI. Validate that `androidx.tv` actually works on the AM6 + AM9 Pro before committing to the migration.
- [ ] Estimate per-screen effort: how many `@Composable`s to write, how much custom focus logic, how much theme work. Order-of-magnitude check.

Once that's done, the plan will look something like:

1. `androidx.tv.material` deps + a hello-world Compose activity.
2. Convert main screen to Compose; ship as v1.0.0-rc1.
3. Convert settings screen; ship as v1.0.0-rc2.
4. Convert wizard (4 GuidedStep fragments → Compose flow); ship as v1.0.0-rc3.
5. Drop `androidx.leanback` deps; ship as v1.0.0.

Roughly 5 plans, one per screen, each producing an installable APK that's mostly Compose-converted. Spread over a couple weeks of focused work.

## Risks

- **Compose-for-TV API instability.** It's still on 1.0.x; minor bumps could change focus or theme APIs. Pin a specific version per plan.
- **Performance.** Compose has higher recomposition cost than imperative XML. For a TV app with mostly-static screens, irrelevant. For the live stats footer (1 Hz updates), trivial.
- **Loss of feature parity.** Leanback's `GuidedStepSupportFragment` does specific things (animated step transitions, breadcrumb header) that Compose has to reimplement. Decide upfront which Leanback affordances are required vs. nice-to-have.

## Don't write the plan until brainstorming completes

Specifically because of question 7 (theming) and question 1 (migration order), this is a design conversation, not an implementation conversation. The implementation plans flow naturally once those are decided.
