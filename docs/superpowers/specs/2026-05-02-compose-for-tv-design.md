# Compose-for-TV rewrite — Design

**Date:** 2026-05-02
**Repo:** `github.com/johnneerdael/HyperHDR-android`
**Targets:** v1.0.0
**Status:** Approved design, ready for implementation planning
**Predecessor sketch:** `docs/superpowers/sketches/2026-05-02-sketch-compose-for-tv-rewrite.md`

---

## 1. Goal

Replace the unmaintained `androidx.leanback:1.0.0` (2018, no roadmap) UI layer with `androidx.tv:tv-material:1.0.0-alpha10` Compose-for-TV. Same three screens, same user-facing behaviour, modern foundation. Ship as v1.0.0 — the version-number bump signals the leanback-to-Compose pivot.

## 2. Locked decisions (from brainstorming)

| Topic | Decision |
|---|---|
| Scope | **Single-milestone rewrite.** All three screens converted in one plan; leanback dropped at the end. ~12-15 tasks, 1-2 weeks. |
| Theming | **Minimal custom `HyperHdrTheme`.** One LED-green accent, dark surfaces, default typography. ~zero animations beyond Compose-for-TV defaults. |
| Tests | **Smoke only.** Four `androidTest` cases (one per screen + theme). No focus or interaction tests. Manual recipe covers what smoke can't. |
| Architecture | **Composable-only swap.** Keep all three activities; replace each one's `setContent` body. No Compose Navigation, no Hilt, no module split. |
| Module structure | All Compose code under `tv/src/main/java/eu/hyperhdr/android/tv/compose/`. `:common` unchanged. |
| `:common` API | Frozen — Plan 1–6 contracts (`HyperHdrServiceBinder`, `ProfileStore`, `HyperHdrJsonApiClient`, etc.) unchanged. |

## 3. Module layout

```
HyperHDR-android/
├── tv/build.gradle                            # ADD compose deps + buildFeatures.compose=true
│                                              # leanback deps DROPPED at end of milestone
├── tv/src/main/java/eu/hyperhdr/android/tv/
│   ├── ui/                                    # DELETED at end of milestone
│   ├── ui/MainActivity.kt                     # MODIFY: setContent { MainScreen() }
│   ├── ui/settings/SettingsActivity.kt        # MODIFY: setContent { SettingsScreen() }
│   ├── ui/wizard/WizardActivity.kt            # MODIFY: setContent { WizardScreen(onDone) }
│   │
│   └── compose/                               # NEW PACKAGE
│       ├── theme/
│       │   ├── HyperHdrTheme.kt
│       │   └── Color.kt
│       ├── screens/
│       │   ├── MainScreen.kt
│       │   ├── SettingsScreen.kt
│       │   ├── widgets/                       # SwitchPreference, EditTextPreference,
│       │   │   ├── PreferenceWidgets.kt       # ClickablePreference, CategoryHeader
│       │   │   └── ConnectionCard.kt          # main-screen connection card composable
│       │   └── wizard/
│       │       ├── WizardScreen.kt
│       │       ├── WizardStep.kt              # sealed interface for step state
│       │       ├── DiscoveryStep.kt
│       │       ├── ManualStep.kt
│       │       ├── AuthStep.kt
│       │       └── InstanceStep.kt
│       └── service/
│           └── ServiceBinderEffect.kt         # rememberServiceBinder() helper

tv/src/androidTest/java/eu/hyperhdr/android/tv/compose/
├── MainScreenSmokeTest.kt
├── SettingsScreenSmokeTest.kt
├── WizardScreenSmokeTest.kt
├── ThemeSmokeTest.kt
└── support/
    └── StubBinder.kt                          # tests-only fake of HyperHdrServiceBinder's public flows
```

The activities switch base class from `FragmentActivity` to `ComponentActivity` (required for `setContent`).

## 4. Dependencies

```groovy
// tv/build.gradle additions

android {
    buildFeatures {
        compose true
        buildConfig true
    }
    composeOptions {
        kotlinCompilerExtensionVersion '1.5.10'
    }
}

dependencies {
    def composeBom = platform('androidx.compose:compose-bom:2024.02.00')
    implementation composeBom

    implementation 'androidx.compose.ui:ui'
    implementation 'androidx.compose.ui:ui-tooling-preview'
    debugImplementation 'androidx.compose.ui:ui-tooling'

    implementation 'androidx.tv:tv-foundation:1.0.0-alpha10'
    implementation 'androidx.tv:tv-material:1.0.0-alpha10'

    implementation 'androidx.lifecycle:lifecycle-runtime-compose:2.7.0'
    implementation 'androidx.activity:activity-compose:1.8.2'

    androidTestImplementation 'androidx.compose.ui:ui-test-junit4'
    androidTestImplementation composeBom
    debugImplementation 'androidx.compose.ui:ui-test-manifest'

    // KEEP UNTIL FINAL DELETION TASK
    implementation 'androidx.leanback:leanback:1.0.0'
    implementation 'androidx.leanback:leanback-preference:1.0.0'
}
```

**Why these versions:**
- Compose BOM `2024.02.00` ↔ Compose Compiler 1.5.10 ↔ Kotlin 1.9.22. Newer BOMs require Kotlin bumps (out of scope; would touch `:common`).
- `androidx.tv:tv-{foundation,material}:1.0.0-alpha10` — Compose-for-TV's stable 1.0 hasn't shipped; alpha10 is the production state as of 2026-05.
- Kotlin stays at 1.9.22; no `:common` changes.

## 5. `HyperHdrTheme`

```kotlin
// Color.kt
val HyperHdrGreen     = Color(0xFF22D08C)   // primary accent — focus ring, badges, switches
val HyperHdrGreenDim  = Color(0xFF14784F)
val SurfaceBlack      = Color(0xFF0A0A0A)
val SurfaceCharcoal   = Color(0xFF1A1A1A)
val OnSurfaceWhite    = Color(0xFFE6E6E6)
val OnSurfaceMuted    = Color(0xFF888888)
val StatusError       = Color(0xFFE05B5B)
val StatusWarning     = Color(0xFFE0A85B)

// HyperHdrTheme.kt
@Composable
fun HyperHdrTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = HyperHdrGreen,
            onPrimary = SurfaceBlack,
            primaryContainer = HyperHdrGreenDim,
            onPrimaryContainer = OnSurfaceWhite,
            background = SurfaceBlack,
            onBackground = OnSurfaceWhite,
            surface = SurfaceCharcoal,
            onSurface = OnSurfaceWhite,
            surfaceVariant = SurfaceCharcoal,
            onSurfaceVariant = OnSurfaceMuted,
            error = StatusError,
            onError = OnSurfaceWhite,
        ),
        content = content,
    )
}
```

Default typography and shapes from `androidx.tv.material3` are used unchanged. `MaterialTheme` here refers to the TV-tuned import (`androidx.tv.material3.MaterialTheme`), not the phone Material.

`StatusWarning` is referenced from screen-level connection-status widgets (CONNECTING / PAUSED states); not on the `MaterialTheme.colorScheme` surface, used as a constant where needed.

## 6. Per-screen composables

### 6.1 `MainScreen`

Three regions stacked vertically: connection card, big primary toggle, stats footer with the `✦ HDR` badge from Plan 6. Service binding via `rememberServiceBinder()`. `MediaProjection` consent via `rememberLauncherForActivityResult(StartActivityForResult())`. Replaces `MainFragment.kt` (~140 LOC) + `fragment_main.xml` (~50 LOC) with ~80 LOC of Compose.

### 6.2 `SettingsScreen`

A `TvLazyColumn` of `SwitchPreference` / `EditTextPreference` / `ClickablePreference` rows grouped by `CategoryHeader`. Five categories: Capture, HDR, Connection, Behavior, Diagnostics — same as Plan 4's settings.

`SwitchPreference`, `EditTextPreference`, `ClickablePreference`, `CategoryHeader` are local composables (~20 LOC each, ~80 LOC total) under `compose/screens/widgets/PreferenceWidgets.kt`. They wrap `androidx.tv.material3.TvCard` — the equivalent of `androidx.preference.Preference` rebuilt in Compose-for-TV. Compose-for-TV does not ship a Preferences component; we own this layer until/unless one ships upstream.

The same `LaunchedEffect { binder.jsonEventsFlow.collect { ... } }` from Plan 6 Task 6 lives in `SettingsScreen` to update the connection-card summary on `InstanceChanged` events.

### 6.3 `WizardScreen`

Step state modelled as a sealed interface:

```kotlin
sealed interface WizardStep {
    object Discovery : WizardStep
    object Manual : WizardStep
    data class Auth(val draft: ServerProfile) : WizardStep
    data class Instance(val draft: ServerProfile) : WizardStep
    data class Done(val saved: ServerProfile) : WizardStep
}
```

`WizardScreen` holds `var step: WizardStep by remember { mutableStateOf(WizardStep.Discovery) }` and dispatches via `when`. Each step composable receives an `onNext`/`onPick` callback that mutates the shared state.

Discovery (`HyperHdrMdnsDiscovery`) is created at the `WizardScreen` level and shared across steps via parameter passing — started in a `DisposableEffect(Unit)`, stopped on dispose.

Each step has its own `BackHandler` that pops the step state (Auth → Discovery, Instance → Auth, etc.). The system back button on the *first* step (`Discovery`) finishes the activity, matching today's behaviour.

## 7. Service binding

`ServiceBinderEffect.kt` provides `rememberServiceBinder(): State<HyperHdrServiceBinder?>`:

```kotlin
@Composable
fun rememberServiceBinder(): State<HyperHdrServiceBinder?> {
    val ctx = LocalContext.current
    val binderState = remember { mutableStateOf<HyperHdrServiceBinder?>(null) }
    DisposableEffect(Unit) {
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                binderState.value = service as? HyperHdrServiceBinder
            }
            override fun onServiceDisconnected(name: ComponentName?) { binderState.value = null }
        }
        ctx.startForegroundService(Intent(ctx, HyperHdrCaptureService::class.java))
        ctx.bindService(Intent(ctx, HyperHdrCaptureService::class.java), conn, Context.BIND_AUTO_CREATE)
        onDispose { runCatching { ctx.unbindService(conn) }; binderState.value = null }
    }
    return binderState
}
```

Composable usage:

```kotlin
val binderState by rememberServiceBinder()
val state by (binderState?.state ?: emptyFlow<ServiceState>())
    .collectAsStateWithLifecycle(ServiceState.IDLE)
```

The `?: emptyFlow()` fallback handles the brief connection-pending window; consumers default-state-flow the case.

**Deliberately not used:** `ViewModel` (the service is the long-lived state holder), `CompositionLocal` for the binder (no deep trees that would benefit from it).

## 8. Focus management

| Concern | Approach |
|---|---|
| Default focus on screen entry | Per-screen `FocusRequester` with `LaunchedEffect(Unit) { requester.requestFocus() }`. Targets: Main → toggle button; Settings → first row; each Wizard step → its primary input. |
| Focus restoration after intra-activity navigation | `Modifier.focusGroup().focusRestorer()` on each step's root container. Wizard step ↔ wizard step preserves focus on Back. |
| Focus across activity boundaries | Reset (Compose recomposes from scratch). Returning to main screen lands on the toggle button — correct, primary-action affordance. |
| D-pad up/down between rows | Native to `TvLazyColumn`; no code. |
| D-pad left/right on text inputs | `androidx.tv.material3.OutlinedTextField` consumes left/right when focused (cursor movement); correct default. |
| Async list rebuild (`InstanceStep`) | Focus the *container* via `FocusRequester` rather than a specific child; `TvLazyColumn` auto-focuses first child after rebuild. |
| Focus ring colour | Themed automatically by `MaterialTheme.colorScheme.primary` = `HyperHdrGreen`. |

## 9. Tests

### 9.1 Smoke tests (new in this milestone)

```
tv/src/androidTest/java/eu/hyperhdr/android/tv/compose/
├── MainScreenSmokeTest.kt
├── SettingsScreenSmokeTest.kt
├── WizardScreenSmokeTest.kt
├── ThemeSmokeTest.kt
└── support/StubBinder.kt
```

Each test uses `createComposeRule()` and asserts the screen's key composables exist after rendering. Stub binder provides deterministic state; not a mock — a real implementation of the binder's flow API driven by `MutableStateFlow`s.

`MainScreen` and `SettingsScreen` get an optional `binder: HyperHdrServiceBinder? = null` parameter. Production callers pass nothing → falls through to `rememberServiceBinder()`. Tests pass `StubBinder.idle()` etc. directly. This is the only test seam the production code grows.

### 9.2 Existing test posture (unchanged)

- 40 JVM unit tests in `:common` — unchanged.
- 2 instrumented tests in `:common:androidTest` (GPU pipeline smoke + HDR shader compile) — unchanged.

### 9.3 Manual recipe

New `docs/manual-tests/05-compose-tv-ui.md` covering:

- Each screen renders.
- D-pad up/down/left/right on each focusable element.
- OK / Back semantics on each screen.
- Wizard step-back behaviour.
- Theme rendering on real hardware (focus ring colour, dark surfaces).

Catches the focus/motion/theme failure modes that smoke tests can't.

## 10. Migration order within the plan

The implementation plan executes in this order; each step produces a green build:

1. Add Compose deps + `HyperHdrTheme` (compiles, no Compose code yet).
2. `ServiceBinderEffect` + `ThemeSmokeTest`.
3. `MainScreen` + `MainActivity` swap. `MainFragment.kt` deleted.
4. `SettingsScreen` + `SettingsActivity` swap. Old fragment deleted.
5. `WizardScreen` + `WizardActivity` swap. Old fragments deleted.
6. Delete leanback deps + remaining XML / `tv/ui/` package — the "we did it" diff.
7. Smoke tests for `MainScreen`, `SettingsScreen`, `WizardScreen`.
8. v1.0.0 release cut.

Three checkpoints where the app is fully usable: end of step 3 (main is Compose; settings + wizard still Leanback); end of step 4 (main + settings Compose); end of step 5 (everything Compose, leanback still in deps). After step 6 leanback is gone.

## 11. Out of scope

| Deferred | Why |
|---|---|
| Custom typography | Default `androidx.tv.material3.Typography` is TV-tuned correctly. v1.x polish if anyone asks. |
| Animations / transitions | Compose-for-TV defaults (focus-scale animation, focus ring) are sufficient. Hero animations are v1.x. |
| Custom splash screen | Native Android 12+ `SplashScreen` API works. v1.x. |
| TalkBack / accessibility audit | Not typically prioritised on TV apps. Revisit on request. |
| Compose Navigation | Decided in Section 4 — three activities each hosting one composable. |
| `ViewModel` per screen | Service is the state holder. Adding ViewModels would be ceremony. |
| `CompositionLocal` for the binder | No deep trees that justify it. |
| Behavioural / interaction tests | Out of test-scope. Smoke + manual covers v1.0.0. |
| In-app updates / install-package permission | Out of scope. The "v0.x.y available" indicator stays text-only. |
| Wizard polish (step indicator, breadcrumb header) | Compose's defaults are fine for v1.0.0. v1.x polish item. |

## 12. Risks

| Risk | Mitigation |
|---|---|
| `androidx.tv:*:1.0.0-alpha10` API breaks before stable 1.0 | Pin to alpha10. Bumps are deliberate, not side-effect. |
| `TvLazyColumn` performance on cheap hardware | Settings list is ~10 items; perf irrelevant. Watch if instance lists grow. |
| Focus management bugs only visible on a real remote | Smoke tests don't catch these. Manual recipe explicitly walks d-pad on each screen. |
| `BackHandler` semantics in wizard step state machine | Each step composable wires its own `BackHandler` that pops step state — well-trodden Compose pattern. Manual test verifies. |
| `MediaProjection` consent + `rememberLauncherForActivityResult` interaction on API 28 (UGOOS-AM6 floor) | Pattern is documented as supported on API 21+. Verified on real hardware via the manual recipe before tag. |

## 13. Done definition

v1.0.0 ships when:

1. All 40 unit tests + 2 instrumented tests + 4 new Compose smoke tests pass.
2. `:tv:assembleRelease` builds a signed APK with the same v0.1.0 cert SHA-256 (`71eda2e2…`).
3. `:tv:assembleDebug` builds clean.
4. `androidx.leanback:*` deps are absent from `tv/build.gradle`.
5. `tv/src/main/java/eu/hyperhdr/android/tv/ui/` package is gone.
6. The new manual recipe (`docs/manual-tests/05-compose-tv-ui.md`) succeeds on real hardware: each screen renders, d-pad navigation works, focus restores correctly between wizard steps, the `✦ HDR` badge still appears live when HyperHDR's web UI toggles HDR-tonemap.
7. v1.0.0 GitHub Release published with signed APK; `adb install -r` over a v0.3.0 install succeeds (proves cert-identity preservation).
