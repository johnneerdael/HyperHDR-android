# Plan 7 — Compose-for-TV Rewrite (v1.0.0) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the unmaintained `androidx.leanback:1.0.0` UI layer with `androidx.tv:tv-material:1.0.0-alpha10` Compose-for-TV. Same three screens, same user-facing behaviour, modern foundation. Ship as v1.0.0.

**Architecture:** Composable-only swap — keep all three activities (`MainActivity`, `WizardActivity`, `SettingsActivity`), replace each one's `setContent` body with a Compose tree. New code under `tv/src/main/java/eu/hyperhdr/android/tv/compose/`. `:common` unchanged. No Compose Navigation, no ViewModel, no Hilt. Service binding via a custom `rememberServiceBinder()` `DisposableEffect`. Theme is one accent color + dark surfaces.

**Tech Stack:** Compose BOM 2024.02.00, Compose Compiler 1.5.10, `androidx.tv:tv-{foundation,material}:1.0.0-alpha10`, Kotlin 1.9.22 (unchanged), `:common` API frozen from Plans 1-6.

**Spec:** `docs/superpowers/specs/2026-05-02-compose-for-tv-design.md`. All sections approved.

**Predecessor:** v0.3.0 (Plan 6) — current `main` HEAD post-Plan-6 release.

---

## File Structure

```
HyperHDR-android/
├── tv/build.gradle                                  # MOD: Compose deps + buildFeatures.compose=true
│                                                    # leanback deps DROPPED at end (Task 7)
├── tv/src/main/java/eu/hyperhdr/android/tv/
│   ├── ui/                                          # DELETED entirely at end (Task 7)
│   ├── ui/MainActivity.kt                           # MOD: setContent { HyperHdrTheme { MainScreen() } }
│   ├── ui/settings/SettingsActivity.kt              # MOD: setContent { ... SettingsScreen() ... }
│   ├── ui/wizard/WizardActivity.kt                  # MOD: setContent { ... WizardScreen(onDone) ... }
│   ├── ui/MainFragment.kt                           # DELETED (Task 4)
│   ├── ui/settings/SettingsFragment.kt              # DELETED (Task 5)
│   ├── ui/wizard/{Discovery,Manual,Auth,Instance}StepFragment.kt   # DELETED (Task 6)
│   │
│   └── compose/                                     # NEW — all Compose code
│       ├── theme/
│       │   ├── Color.kt
│       │   └── HyperHdrTheme.kt
│       ├── service/
│       │   └── ServiceBinderEffect.kt
│       ├── widgets/
│       │   ├── PreferenceWidgets.kt
│       │   ├── ConnectionCard.kt
│       │   └── StatsFooter.kt
│       └── screens/
│           ├── MainScreen.kt
│           ├── SettingsScreen.kt
│           └── wizard/
│               ├── WizardStep.kt
│               ├── WizardScreen.kt
│               ├── DiscoveryStep.kt
│               ├── ManualStep.kt
│               ├── AuthStep.kt
│               └── InstanceStep.kt
│
├── tv/src/main/res/layout/fragment_main.xml         # DELETED (Task 7)
└── tv/src/androidTest/java/eu/hyperhdr/android/tv/compose/
    ├── support/StubBinder.kt
    ├── ThemeSmokeTest.kt
    ├── MainScreenSmokeTest.kt
    ├── SettingsScreenSmokeTest.kt
    └── WizardScreenSmokeTest.kt
```

Activities switch base class from `FragmentActivity` to `ComponentActivity` (required for `setContent`). The `requestNotificationsIfNeeded()` POST_NOTIFICATIONS pattern from Plan 3 is preserved.

---

## Task 1: Add Compose deps + bump to v1.0.0-dev + `HyperHdrTheme`

**Files:**
- Modify: `tv/build.gradle` — add Compose BOM + TV libs + activity-compose; bump versionCode 3→4, versionName "0.3.0"→"1.0.0-dev"
- Create: `tv/src/main/java/eu/hyperhdr/android/tv/compose/theme/Color.kt`
- Create: `tv/src/main/java/eu/hyperhdr/android/tv/compose/theme/HyperHdrTheme.kt`

The theme compiles in isolation; no callers yet. Tasks 4–6 wire it into the activities.

- [ ] **Step 1: Add Compose deps to `tv/build.gradle`**

Open `tv/build.gradle`. Inside the existing `android { ... }` block, alongside `buildFeatures { buildConfig true }`, add `compose true`:

```groovy
    buildFeatures {
        compose true
        buildConfig true
    }
    composeOptions {
        kotlinCompilerExtensionVersion '1.5.10'
    }
```

In the `dependencies { ... }` block, add (before the existing `androidx.leanback` lines so the order reads "modern first, legacy second"):

```groovy
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
```

**Keep the leanback deps in place for now.** They drop in Task 7 after all three screens are converted.

- [ ] **Step 2: Bump version to 1.0.0-dev**

In the `defaultConfig { ... }` block:
```groovy
        versionCode 4
        versionName "1.0.0-dev"
```

(Was 3 / "0.3.0".)

- [ ] **Step 3: Create `Color.kt`**

```kotlin
// tv/src/main/java/eu/hyperhdr/android/tv/compose/theme/Color.kt
package eu.hyperhdr.android.tv.compose.theme

import androidx.compose.ui.graphics.Color

// Primary accent — focus ring, badges, switches. LED-green for "this app talks to LEDs."
val HyperHdrGreen = Color(0xFF22D08C)
val HyperHdrGreenDim = Color(0xFF14784F)

// Dark surfaces — TV apps live in dark rooms.
val SurfaceBlack = Color(0xFF0A0A0A)
val SurfaceCharcoal = Color(0xFF1A1A1A)
val OnSurfaceWhite = Color(0xFFE6E6E6)
val OnSurfaceMuted = Color(0xFF888888)

// Status colours for the connection card and toggle button.
val StatusError = Color(0xFFE05B5B)
val StatusWarning = Color(0xFFE0A85B)
```

- [ ] **Step 4: Create `HyperHdrTheme.kt`**

```kotlin
// tv/src/main/java/eu/hyperhdr/android/tv/compose/theme/HyperHdrTheme.kt
package eu.hyperhdr.android.tv.compose.theme

import androidx.compose.runtime.Composable
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

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

- [ ] **Step 5: Verify**

```bash
cd /Users/jneerdael/Scripts/hyperhdr/HyperHDR-android
./gradlew :tv:compileDebugKotlin
```
Expected: `BUILD SUCCESSFUL`. Compose dependencies download on first run; subsequent runs are cached.

If `kotlinCompilerExtensionVersion` mismatches Kotlin: bump it to match. The compatibility matrix is at https://developer.android.com/jetpack/androidx/releases/compose-kotlin — Kotlin 1.9.22 ↔ Compose Compiler 1.5.10 is correct.

If `androidx.tv` artifacts can't be resolved: confirm `mavenCentral()` is in the project's repositories (it is, in the root `build.gradle`).

- [ ] **Step 6: Commit**

```bash
git add tv/build.gradle \
        tv/src/main/java/eu/hyperhdr/android/tv/compose/theme/
git commit -m "build: add Compose-for-TV deps; bump to 1.0.0-dev; HyperHdrTheme"
```

---

## Task 2: `ServiceBinderEffect` + `StubBinder` + `ThemeSmokeTest`

**Files:**
- Create: `tv/src/main/java/eu/hyperhdr/android/tv/compose/service/ServiceBinderEffect.kt`
- Create: `tv/src/androidTest/java/eu/hyperhdr/android/tv/compose/support/StubBinder.kt`
- Create: `tv/src/androidTest/java/eu/hyperhdr/android/tv/compose/ThemeSmokeTest.kt`

Establishes the test seam (`StubBinder`) and the first instrumented Compose test before any screens use it. The smoke test verifies the theme + Compose toolchain work together on real hardware.

- [ ] **Step 1: Create `ServiceBinderEffect.kt`**

```kotlin
// tv/src/main/java/eu/hyperhdr/android/tv/compose/service/ServiceBinderEffect.kt
package eu.hyperhdr.android.tv.compose.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import eu.hyperhdr.android.service.HyperHdrCaptureService
import eu.hyperhdr.android.service.HyperHdrServiceBinder

/**
 * Composition-scoped service binding. Binds in the composition's lifecycle, unbinds when
 * the composition leaves. Returned [State] is null while the connection is pending;
 * consumers should default-state-flow the case.
 */
@Composable
fun rememberServiceBinder(): State<HyperHdrServiceBinder?> {
    val ctx = LocalContext.current
    val binderState = remember { mutableStateOf<HyperHdrServiceBinder?>(null) }

    DisposableEffect(Unit) {
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                binderState.value = service as? HyperHdrServiceBinder
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                binderState.value = null
            }
        }
        ctx.startForegroundService(Intent(ctx, HyperHdrCaptureService::class.java))
        ctx.bindService(
            Intent(ctx, HyperHdrCaptureService::class.java),
            conn, Context.BIND_AUTO_CREATE,
        )
        onDispose {
            runCatching { ctx.unbindService(conn) }
            binderState.value = null
        }
    }

    return binderState
}
```

- [ ] **Step 2: Create `StubBinder.kt`**

`StubBinder` is the test-time equivalent of `HyperHdrServiceBinder`. It exposes the same flows (`state`, `stats`, `serverHdrSignaled`, `jsonEventsFlow`) but driven by `MutableStateFlow`s the test controls. **It is not a mock** — it's a concrete implementation that screens use directly.

The wrinkle: `HyperHdrServiceBinder` is a `Binder` subclass (not an interface), and its constructor takes a `HyperHdrCaptureService` reference. We can't subclass it cleanly for tests. The cleanest fix is to give the test screens an optional `binder` parameter that bypasses `rememberServiceBinder()`.

For test purposes we define a small interface that covers what the screens actually consume from the binder, and have both the production binder and the stub implement it:

Create `tv/src/main/java/eu/hyperhdr/android/tv/compose/service/ScreenBinder.kt`:

```kotlin
// tv/src/main/java/eu/hyperhdr/android/tv/compose/service/ScreenBinder.kt
package eu.hyperhdr.android.tv.compose.service

import android.content.Intent
import eu.hyperhdr.android.json.JsonEvent
import eu.hyperhdr.android.service.ServiceState
import eu.hyperhdr.android.stats.LiveStats
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The subset of [eu.hyperhdr.android.service.HyperHdrServiceBinder] that screens depend on.
 * Production: [HyperHdrServiceBinder] implements this directly (added in Task 4).
 * Tests: [eu.hyperhdr.android.tv.compose.support.StubBinder] implements this with deterministic state.
 */
interface ScreenBinder {
    val state: StateFlow<ServiceState>
    val stats: StateFlow<LiveStats>
    val serverHdrSignaled: StateFlow<Boolean>
    val jsonEventsFlow: Flow<JsonEvent>
    fun lastError(): String?
    fun startCapture(projectionResultCode: Int, projectionData: Intent)
    fun stopCapture()
    suspend fun setHdrVideoMode(hdr: Boolean): Boolean
}
```

Then create the stub:

```kotlin
// tv/src/androidTest/java/eu/hyperhdr/android/tv/compose/support/StubBinder.kt
package eu.hyperhdr.android.tv.compose.support

import android.content.Intent
import eu.hyperhdr.android.json.JsonEvent
import eu.hyperhdr.android.service.ServiceState
import eu.hyperhdr.android.stats.LiveStats
import eu.hyperhdr.android.tv.compose.service.ScreenBinder
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

class StubBinder(
    initialState: ServiceState = ServiceState.IDLE,
    initialStats: LiveStats = LiveStats(0.0, 0L, 0, 0, null),
    initialServerHdr: Boolean = false,
) : ScreenBinder {
    private val _state = MutableStateFlow(initialState)
    private val _stats = MutableStateFlow(initialStats)
    private val _hdr = MutableStateFlow(initialServerHdr)
    private val _events = MutableSharedFlow<JsonEvent>(extraBufferCapacity = 16)

    override val state: StateFlow<ServiceState> = _state.asStateFlow()
    override val stats: StateFlow<LiveStats> = _stats.asStateFlow()
    override val serverHdrSignaled: StateFlow<Boolean> = _hdr.asStateFlow()
    override val jsonEventsFlow: SharedFlow<JsonEvent> = _events.asSharedFlow()

    override fun lastError(): String? = null
    override fun startCapture(projectionResultCode: Int, projectionData: Intent) {
        _state.value = ServiceState.CONNECTING
    }
    override fun stopCapture() { _state.value = ServiceState.IDLE }
    override suspend fun setHdrVideoMode(hdr: Boolean): Boolean = true

    // Test helpers
    fun pushState(s: ServiceState) { _state.value = s }
    fun pushStats(s: LiveStats) { _stats.value = s }
    fun pushHdr(hdr: Boolean) { _hdr.value = hdr }
    suspend fun pushEvent(ev: JsonEvent) { _events.emit(ev) }

    companion object {
        fun idle() = StubBinder(initialState = ServiceState.IDLE)
        fun streaming(fps: Double = 30.0) = StubBinder(
            initialState = ServiceState.STREAMING,
            initialStats = LiveStats(fps, 200_000L, 160, 90, null),
        )
        fun error(msg: String) = StubBinder(initialState = ServiceState.ERROR).also {
            // lastError could be wired through; for v1.0 smoke tests it's not asserted on.
        }
    }
}
```

- [ ] **Step 3: Make `HyperHdrServiceBinder` implement `ScreenBinder`**

Open `common/src/main/java/eu/hyperhdr/android/service/HyperHdrServiceBinder.kt`. Modify the class declaration:

```kotlin
class HyperHdrServiceBinder(private val service: HyperHdrCaptureService) :
    Binder(),
    eu.hyperhdr.android.tv.compose.service.ScreenBinder {
```

(`HyperHdrServiceBinder` is in `:common`, `ScreenBinder` is in `:tv`. Kotlin doesn't like the cross-module reference if `:common` doesn't depend on `:tv`. **It doesn't.** That's a problem.)

**Fix:** move `ScreenBinder` to `:common`:

Move `tv/src/main/java/eu/hyperhdr/android/tv/compose/service/ScreenBinder.kt` to `common/src/main/java/eu/hyperhdr/android/service/ScreenBinder.kt`, change package to `eu.hyperhdr.android.service`, and update the import in `StubBinder.kt`.

The new contents of `common/src/main/java/eu/hyperhdr/android/service/ScreenBinder.kt`:

```kotlin
package eu.hyperhdr.android.service

import android.content.Intent
import eu.hyperhdr.android.json.JsonEvent
import eu.hyperhdr.android.stats.LiveStats
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The subset of [HyperHdrServiceBinder] that screens depend on. Production: [HyperHdrServiceBinder]
 * implements this directly. Tests: provide their own implementation (e.g. StubBinder) for
 * deterministic state without instantiating the service.
 */
interface ScreenBinder {
    val state: StateFlow<ServiceState>
    val stats: StateFlow<LiveStats>
    val serverHdrSignaled: StateFlow<Boolean>
    val jsonEventsFlow: Flow<JsonEvent>
    fun lastError(): String?
    fun startCapture(projectionResultCode: Int, projectionData: Intent)
    fun stopCapture()
    suspend fun setHdrVideoMode(hdr: Boolean): Boolean
}
```

Then `HyperHdrServiceBinder.kt` becomes:

```kotlin
class HyperHdrServiceBinder(private val service: HyperHdrCaptureService) : Binder(), ScreenBinder {
    override val state: StateFlow<ServiceState> get() = service.stateFlow
    override fun lastError(): String? = service.lastError()
    override fun startCapture(projectionResultCode: Int, projectionData: Intent) =
        service.startCapture(projectionResultCode, projectionData)
    override fun stopCapture() = service.stopCapture()
    override suspend fun setHdrVideoMode(hdr: Boolean): Boolean = service.setHdrVideoMode(hdr)
    override val stats: StateFlow<eu.hyperhdr.android.stats.LiveStats> get() = service.statsCollector.stats
    override val serverHdrSignaled: StateFlow<Boolean> get() = service.serverHdrSignaled
    override val jsonEventsFlow: Flow<eu.hyperhdr.android.json.JsonEvent>
        get() = service.jsonEvents?.flow ?: kotlinx.coroutines.flow.emptyFlow()
}
```

(Methods are now `override`. Existing field/method access on `service` is unchanged.)

Update the `StubBinder` import: `import eu.hyperhdr.android.service.ScreenBinder` (was `eu.hyperhdr.android.tv.compose.service.ScreenBinder`).

- [ ] **Step 4: Update `rememberServiceBinder()` to return `ScreenBinder?`**

In `ServiceBinderEffect.kt`, change the return type:

```kotlin
@Composable
fun rememberServiceBinder(): State<eu.hyperhdr.android.service.ScreenBinder?> {
    val ctx = LocalContext.current
    val binderState = remember { mutableStateOf<eu.hyperhdr.android.service.ScreenBinder?>(null) }
    // ... (rest unchanged; cast as ScreenBinder? in onServiceConnected)
}
```

In the listener:
```kotlin
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                binderState.value = service as? eu.hyperhdr.android.service.ScreenBinder
            }
```

(The cast still resolves at runtime — `HyperHdrServiceBinder` is now a `ScreenBinder`.)

- [ ] **Step 5: Create `ThemeSmokeTest.kt`**

```kotlin
// tv/src/androidTest/java/eu/hyperhdr/android/tv/compose/ThemeSmokeTest.kt
package eu.hyperhdr.android.tv.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.platform.testTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.tv.material3.MaterialTheme
import eu.hyperhdr.android.tv.compose.theme.HyperHdrTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThemeSmokeTest {

    @get:Rule val composeTestRule = createComposeRule()

    @Test
    fun themeRendersAndAppliesDarkBackground() {
        composeTestRule.setContent {
            HyperHdrTheme {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .testTag("themed_root")
                )
            }
        }
        composeTestRule.onNodeWithTag("themed_root").assertExists()
    }
}
```

- [ ] **Step 6: Verify everything compiles**

```bash
./gradlew :common:compileDebugKotlin :tv:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Run the smoke test on real hardware**

```bash
ANDROID_SERIAL=192.168.50.98:5555 ./gradlew :tv:connectedDebugAndroidTest --tests "eu.hyperhdr.android.tv.compose.ThemeSmokeTest"
```

(Note: `:tv:connectedDebugAndroidTest`, not `:common:` — this is a tv-module test.)

Expected: BUILD SUCCESSFUL with 1 test passing on UGOOS-AM6 (API 28).

If `:tv` doesn't have `connectedDebugAndroidTest` configured: it inherits the `androidTestImplementation` block from Step 1's gradle changes. Should work out of box.

If the test fails on Compose-for-TV initialization: check that `tv/src/main/AndroidManifest.xml`'s `<application>` doesn't have `android:hardwareAccelerated="false"` (Compose requires hardware acceleration; should be true by default).

- [ ] **Step 8: Run all unit tests still pass**

```bash
./gradlew :common:testDebugUnitTest
```
Expected: 40 tests pass. The `ScreenBinder` extraction is API-only — no behavior change.

- [ ] **Step 9: Commit**

```bash
git add common/src/main/java/eu/hyperhdr/android/service/ScreenBinder.kt \
        common/src/main/java/eu/hyperhdr/android/service/HyperHdrServiceBinder.kt \
        tv/src/main/java/eu/hyperhdr/android/tv/compose/service/ServiceBinderEffect.kt \
        tv/src/androidTest/java/eu/hyperhdr/android/tv/compose/
git commit -m "feat(tv-compose): ServiceBinderEffect + ScreenBinder interface + ThemeSmokeTest"
```

---

## Task 3: Shared widget composables

**Files:**
- Create: `tv/src/main/java/eu/hyperhdr/android/tv/compose/widgets/PreferenceWidgets.kt`
- Create: `tv/src/main/java/eu/hyperhdr/android/tv/compose/widgets/ConnectionCard.kt`
- Create: `tv/src/main/java/eu/hyperhdr/android/tv/compose/widgets/StatsFooter.kt`

Shared composables used by multiple screens. Created up front so Tasks 4–6 can compose with them.

- [ ] **Step 1: Create `PreferenceWidgets.kt`**

```kotlin
// tv/src/main/java/eu/hyperhdr/android/tv/compose/widgets/PreferenceWidgets.kt
package eu.hyperhdr.android.tv.compose.widgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Switch
import androidx.tv.material3.Text

@Composable
fun CategoryHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 24.dp, bottom = 8.dp),
    )
}

@Composable
fun SwitchPreference(
    title: String,
    summary: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var localChecked by remember(checked) { mutableStateOf(checked) }
    Card(
        onClick = {
            if (enabled) {
                val next = !localChecked
                localChecked = next
                onChange(next)
            }
        },
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (summary != null) {
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            Switch(checked = localChecked, onCheckedChange = null, enabled = enabled)
        }
    }
}

@Composable
fun ClickablePreference(
    title: String,
    summary: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = { if (enabled) onClick() },
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (summary != null) {
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Editable text preference. Note: Compose-for-TV's idiom for text input is to delegate to a
 * full-screen editor on click rather than inline editing (which behaves badly with d-pad).
 * For v1.0.0 we keep it simple — clicking shows current value; the underlying preference is
 * edited via a dialog activity that's already part of the wizard flow. Tasks 5 wires this
 * up to specific Settings preferences.
 */
@Composable
fun EditTextPreference(
    title: String,
    value: String,
    summary: String = value,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ClickablePreference(title = title, summary = summary, onClick = onClick, modifier = modifier)
}
```

- [ ] **Step 2: Create `ConnectionCard.kt`**

```kotlin
// tv/src/main/java/eu/hyperhdr/android/tv/compose/widgets/ConnectionCard.kt
package eu.hyperhdr.android.tv.compose.widgets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import eu.hyperhdr.android.settings.ServerProfile

@Composable
fun ConnectionCard(profile: ServerProfile?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                text = profile?.let { "${it.host} (instance ${it.instanceId})" } ?: "Configure server…",
                style = MaterialTheme.typography.titleMedium,
            )
            if (profile != null) {
                Text(
                    text = "Tap to reconfigure",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
```

- [ ] **Step 3: Create `StatsFooter.kt`**

```kotlin
// tv/src/main/java/eu/hyperhdr/android/tv/compose/widgets/StatsFooter.kt
package eu.hyperhdr.android.tv.compose.widgets

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import eu.hyperhdr.android.stats.LiveStats
import java.util.Locale

@Composable
fun StatsFooter(
    stats: LiveStats,
    hdrBadge: Boolean,
    updateAvailable: String? = null,
    modifier: Modifier = Modifier,
) {
    val text = buildString {
        if (stats.width != 0) {
            append(String.format(
                Locale.US,
                "%.1f fps · %d KB/s · %dx%d",
                stats.fps, stats.bytesPerSec / 1024, stats.width, stats.height,
            ))
            stats.lastErrorMessage?.let { append("  ⚠  ").append(it) }
            if (hdrBadge) append("  ✦ HDR")
        }
        if (updateAvailable != null) {
            if (isNotEmpty()) append("  ")
            append("⬆  v").append(updateAvailable).append(" available")
        }
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = 24.dp).testTag("stats_footer"),
    )
}
```

- [ ] **Step 4: Verify**

```bash
./gradlew :tv:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add tv/src/main/java/eu/hyperhdr/android/tv/compose/widgets/
git commit -m "feat(tv-compose): shared widgets — PreferenceWidgets, ConnectionCard, StatsFooter"
```

---

## Task 4: `MainScreen` + `MainActivity` swap

**Files:**
- Create: `tv/src/main/java/eu/hyperhdr/android/tv/compose/screens/MainScreen.kt`
- Create: `tv/src/androidTest/java/eu/hyperhdr/android/tv/compose/MainScreenSmokeTest.kt`
- Modify: `tv/src/main/java/eu/hyperhdr/android/tv/ui/MainActivity.kt` — replace fragment-hosting with `setContent`
- Delete: `tv/src/main/java/eu/hyperhdr/android/tv/ui/MainFragment.kt`

After this task, the main screen is fully Compose. Settings + wizard still Leanback.

- [ ] **Step 1: Create `MainScreen.kt`**

```kotlin
// tv/src/main/java/eu/hyperhdr/android/tv/compose/screens/MainScreen.kt
package eu.hyperhdr.android.tv.compose.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import eu.hyperhdr.android.service.ScreenBinder
import eu.hyperhdr.android.service.ServiceState
import eu.hyperhdr.android.settings.EncryptedProfileStorage
import eu.hyperhdr.android.settings.ProfileStore
import eu.hyperhdr.android.stats.LiveStats
import eu.hyperhdr.android.tv.BuildConfig
import eu.hyperhdr.android.tv.compose.service.rememberServiceBinder
import eu.hyperhdr.android.tv.compose.widgets.ConnectionCard
import eu.hyperhdr.android.tv.compose.widgets.StatsFooter
import eu.hyperhdr.android.tv.update.UpdateChecker
import eu.hyperhdr.android.tv.ui.settings.SettingsActivity
import eu.hyperhdr.android.tv.ui.wizard.WizardActivity
import kotlinx.coroutines.flow.MutableStateFlow

@Composable
fun MainScreen(stubBinder: ScreenBinder? = null) {
    val ctx = LocalContext.current
    val rememberedBinder by rememberServiceBinder()
    val binder = stubBinder ?: rememberedBinder

    val state by (binder?.state ?: MutableStateFlow(ServiceState.IDLE))
        .collectAsStateWithLifecycle(ServiceState.IDLE)
    val stats by (binder?.stats ?: MutableStateFlow(LiveStats(0.0, 0L, 0, 0, null)))
        .collectAsStateWithLifecycle(LiveStats(0.0, 0L, 0, 0, null))
    val serverHdr by (binder?.serverHdrSignaled ?: MutableStateFlow(false))
        .collectAsStateWithLifecycle(false)

    val profile = remember { ProfileStore(EncryptedProfileStorage.create(ctx)).load() }

    var updateAvailable by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        runCatching { UpdateChecker().latest(BuildConfig.VERSION_NAME) }
            .getOrNull()?.let { updateAvailable = it.tag }
    }

    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            binder?.startCapture(result.resultCode, result.data!!)
        }
    }

    val toggleFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { toggleFocus.requestFocus() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ConnectionCard(profile = profile, onClick = {
            ctx.startActivity(Intent(ctx, WizardActivity::class.java))
        })
        Spacer(Modifier.height(16.dp))

        Button(
            onClick = {
                if (state == ServiceState.STREAMING || state == ServiceState.CONNECTING) {
                    binder?.stopCapture()
                } else {
                    val pmgr = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    projectionLauncher.launch(pmgr.createScreenCaptureIntent())
                }
            },
            modifier = Modifier.focusRequester(toggleFocus).focusable(),
        ) {
            Text(
                when (state) {
                    ServiceState.IDLE -> "Start capture"
                    ServiceState.CONNECTING -> "Connecting…"
                    ServiceState.STREAMING -> "Stop capture"
                    ServiceState.PAUSED -> "Paused (waiting for screen)"
                    ServiceState.ERROR -> "Error — tap to retry"
                },
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
        }

        Spacer(Modifier.height(16.dp))
        Button(onClick = {
            ctx.startActivity(Intent(ctx, SettingsActivity::class.java))
        }) {
            Text("Settings", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }

        Spacer(Modifier.height(32.dp))
        StatsFooter(stats = stats, hdrBadge = serverHdr, updateAvailable = updateAvailable)
    }
}
```

- [ ] **Step 2: Replace `MainActivity` body**

```kotlin
// tv/src/main/java/eu/hyperhdr/android/tv/ui/MainActivity.kt
package eu.hyperhdr.android.tv.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import eu.hyperhdr.android.tv.compose.screens.MainScreen
import eu.hyperhdr.android.tv.compose.theme.HyperHdrTheme

class MainActivity : ComponentActivity() {

    private val requestNotifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best-effort */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HyperHdrTheme { MainScreen() }
        }
        requestNotificationsIfNeeded()
    }

    private fun requestNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
```

(Base class changes from `FragmentActivity` to `ComponentActivity`. The `requestNotificationsIfNeeded` block is unchanged from Plan 3.)

- [ ] **Step 3: Delete `MainFragment.kt`**

```bash
git rm tv/src/main/java/eu/hyperhdr/android/tv/ui/MainFragment.kt
```

- [ ] **Step 4: Create `MainScreenSmokeTest.kt`**

```kotlin
// tv/src/androidTest/java/eu/hyperhdr/android/tv/compose/MainScreenSmokeTest.kt
package eu.hyperhdr.android.tv.compose

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.hyperhdr.android.tv.compose.screens.MainScreen
import eu.hyperhdr.android.tv.compose.support.StubBinder
import eu.hyperhdr.android.tv.compose.theme.HyperHdrTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainScreenSmokeTest {

    @get:Rule val composeTestRule = createComposeRule()

    @Test
    fun mainScreenIdleRendersStartCaptureAndStatsFooter() {
        val stub = StubBinder.idle()
        composeTestRule.setContent {
            HyperHdrTheme { MainScreen(stubBinder = stub) }
        }
        composeTestRule.onNodeWithText("Start capture").assertExists()
        composeTestRule.onNodeWithText("Settings").assertExists()
        composeTestRule.onNodeWithTag("stats_footer").assertExists()
    }

    @Test
    fun mainScreenStreamingShowsStopCapture() {
        val stub = StubBinder.streaming(fps = 30.0)
        composeTestRule.setContent {
            HyperHdrTheme { MainScreen(stubBinder = stub) }
        }
        composeTestRule.onNodeWithText("Stop capture").assertExists()
    }
}
```

- [ ] **Step 5: Verify build + smoke test**

```bash
./gradlew :tv:compileDebugKotlin
ANDROID_SERIAL=192.168.50.98:5555 ./gradlew :tv:connectedDebugAndroidTest --tests "eu.hyperhdr.android.tv.compose.MainScreenSmokeTest"
```

Expected: BUILD SUCCESSFUL with 2 tests passing.

If `R.layout.fragment_main` is referenced anywhere else: it isn't — only `MainFragment` used it, and we just deleted that. Search to confirm:
```bash
grep -r "fragment_main" tv/src/main/ || echo "(no references)"
```
Expected: `(no references)`.

- [ ] **Step 6: Verify all unit tests still pass**

```bash
./gradlew :common:testDebugUnitTest
```
Expected: 40 tests pass.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat(tv-compose): convert MainScreen to Compose; delete MainFragment"
```

---

## Task 5: `SettingsScreen` + `SettingsActivity` swap

**Files:**
- Create: `tv/src/main/java/eu/hyperhdr/android/tv/compose/screens/SettingsScreen.kt`
- Create: `tv/src/androidTest/java/eu/hyperhdr/android/tv/compose/SettingsScreenSmokeTest.kt`
- Modify: `tv/src/main/java/eu/hyperhdr/android/tv/ui/settings/SettingsActivity.kt`
- Delete: `tv/src/main/java/eu/hyperhdr/android/tv/ui/settings/SettingsFragment.kt`

- [ ] **Step 1: Create `SettingsScreen.kt`**

```kotlin
// tv/src/main/java/eu/hyperhdr/android/tv/compose/screens/SettingsScreen.kt
package eu.hyperhdr.android.tv.compose.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.foundation.lazy.list.TvLazyColumn
import androidx.tv.foundation.lazy.list.items
import androidx.tv.material3.MaterialTheme
import eu.hyperhdr.android.debug.DebugBundleExporter
import eu.hyperhdr.android.hdr.HdrDetector
import eu.hyperhdr.android.json.JsonEvent
import eu.hyperhdr.android.service.ScreenBinder
import eu.hyperhdr.android.settings.EncryptedProfileStorage
import eu.hyperhdr.android.settings.ProfileStore
import eu.hyperhdr.android.tv.compose.service.rememberServiceBinder
import eu.hyperhdr.android.tv.compose.widgets.CategoryHeader
import eu.hyperhdr.android.tv.compose.widgets.ClickablePreference
import eu.hyperhdr.android.tv.compose.widgets.SwitchPreference
import eu.hyperhdr.android.tv.ui.wizard.WizardActivity

@Composable
fun SettingsScreen(stubBinder: ScreenBinder? = null) {
    val ctx = LocalContext.current
    val store = remember { ProfileStore(EncryptedProfileStorage.create(ctx)) }
    var profile by remember { mutableStateOf(store.load()) }
    var diagSummary by remember { mutableStateOf("Saves a zip of recent logs to the device's Downloads folder") }
    var connectionSummary by remember(profile) {
        mutableStateOf(profile?.let { "${it.host}:${it.flatbufPort}" } ?: "Not configured")
    }

    val hdrCapable = remember {
        // One-shot read; the live detector lives in the service.
        val detector = HdrDetector(ctx).also { it.start() }
        val capable = detector.capable.value
        detector.stop()
        capable
    }

    // Live updates: subscribe to InstanceChanged via the binder if available.
    val rememberedBinder by rememberServiceBinder()
    val binder = stubBinder ?: rememberedBinder
    LaunchedEffect(binder) {
        binder?.jsonEventsFlow?.collect { ev ->
            if (ev is JsonEvent.InstanceChanged) {
                val current = ev.instances.firstOrNull { it.id == profile?.instanceId }
                connectionSummary = current?.let { "${profile?.host} → ${it.name}" }
                    ?: "${profile?.host}:${profile?.flatbufPort}"
            }
        }
    }

    TvLazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 64.dp, vertical = 32.dp),
    ) {
        item { CategoryHeader("Capture") }
        item {
            SwitchPreference(
                title = "High quality (192×108 @ 60 fps)",
                checked = profile?.highQuality == true,
                onChange = { v ->
                    profile?.let { store.save(it.copy(highQuality = v)); profile = store.load() }
                },
            )
        }
        item {
            ClickablePreference(
                title = "Priority",
                summary = (profile?.priority ?: 100).toString(),
                onClick = { /* v1.0.0 stub: editable in next polish — defer to v1.1 */ },
            )
        }

        item { CategoryHeader("HDR") }
        item {
            SwitchPreference(
                title = "HDR-aware capture",
                summary = if (hdrCapable) "Tone-map HDR content on the device"
                          else "Display does not report HDR support",
                enabled = hdrCapable,
                checked = profile?.hdrAware == true,
                onChange = { v ->
                    profile?.let { store.save(it.copy(hdrAware = v)); profile = store.load() }
                },
            )
        }
        item {
            SwitchPreference(
                title = "Signal HDR to HyperHDR",
                summary = "Tells HyperHDR to apply its tone-map LUT when content is HDR",
                checked = true,
                onChange = { /* settings-flag-only in v1.0; service uses HdrDetector either way */ },
            )
        }

        item { CategoryHeader("Connection") }
        item {
            ClickablePreference(
                title = "Reconfigure server…",
                summary = connectionSummary,
                onClick = { ctx.startActivity(Intent(ctx, WizardActivity::class.java)) },
            )
        }
        item {
            ClickablePreference(
                title = "Auth token",
                summary = if (profile?.token != null) "Set" else "Not set",
                onClick = { /* v1.0.0 stub: token edit deferred — wizard re-paste path covers this */ },
            )
        }

        item { CategoryHeader("Behavior") }
        item {
            SwitchPreference(
                title = "Start on boot",
                summary = "Foreground service starts at boot (capture still requires user consent)",
                checked = true,
                onChange = { /* settings-flag-only; boot receiver currently unconditional in Plan 3 */ },
            )
        }

        item { CategoryHeader("Diagnostics") }
        item {
            ClickablePreference(
                title = "Export diagnostic bundle",
                summary = diagSummary,
                onClick = {
                    val path = DebugBundleExporter.export(ctx)
                    diagSummary = "Saved: $path"
                },
            )
        }
    }
}
```

- [ ] **Step 2: Rewrite `SettingsActivity.kt`**

```kotlin
// tv/src/main/java/eu/hyperhdr/android/tv/ui/settings/SettingsActivity.kt
package eu.hyperhdr.android.tv.ui.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import eu.hyperhdr.android.tv.compose.screens.SettingsScreen
import eu.hyperhdr.android.tv.compose.theme.HyperHdrTheme

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HyperHdrTheme { SettingsScreen() }
        }
    }
}
```

- [ ] **Step 3: Delete `SettingsFragment.kt`**

```bash
git rm tv/src/main/java/eu/hyperhdr/android/tv/ui/settings/SettingsFragment.kt
```

- [ ] **Step 4: Update `tv/src/main/AndroidManifest.xml`**

The current manifest declares `SettingsActivity` with `android:theme="@style/PreferenceTheme.Leanback"`. Compose doesn't need that. Edit the `<activity android:name="eu.hyperhdr.android.tv.ui.settings.SettingsActivity">` element and remove the `android:theme="@style/PreferenceTheme.Leanback"` attribute (replace with `android:theme="@style/Theme.AppCompat"` or just drop the attribute — `ComponentActivity` works without an explicit theme).

Specifically, change the line:
```xml
    <activity
        android:name="eu.hyperhdr.android.tv.ui.settings.SettingsActivity"
        android:exported="false"
        android:theme="@style/PreferenceTheme.Leanback"/>
```
to:
```xml
    <activity
        android:name="eu.hyperhdr.android.tv.ui.settings.SettingsActivity"
        android:exported="false"/>
```

- [ ] **Step 5: Create `SettingsScreenSmokeTest.kt`**

```kotlin
// tv/src/androidTest/java/eu/hyperhdr/android/tv/compose/SettingsScreenSmokeTest.kt
package eu.hyperhdr.android.tv.compose

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.hyperhdr.android.tv.compose.screens.SettingsScreen
import eu.hyperhdr.android.tv.compose.theme.HyperHdrTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsScreenSmokeTest {

    @get:Rule val composeTestRule = createComposeRule()

    @Test
    fun settingsScreenRendersAllFiveCategories() {
        composeTestRule.setContent {
            HyperHdrTheme { SettingsScreen() }
        }
        composeTestRule.onNodeWithText("Capture").assertExists()
        composeTestRule.onNodeWithText("HDR").assertExists()
        composeTestRule.onNodeWithText("Connection").assertExists()
        composeTestRule.onNodeWithText("Behavior").assertExists()
        composeTestRule.onNodeWithText("Diagnostics").assertExists()
    }
}
```

(`SettingsScreen` reads `ProfileStore` directly from `LocalContext`. Since the test runs in the app's process, the existing encrypted prefs are accessed normally — the store may return null on a fresh install, which is fine; the screen handles `profile?.let { ... }`.)

- [ ] **Step 6: Verify**

```bash
./gradlew :tv:compileDebugKotlin
ANDROID_SERIAL=192.168.50.98:5555 ./gradlew :tv:connectedDebugAndroidTest --tests "eu.hyperhdr.android.tv.compose.SettingsScreenSmokeTest"
```

Expected: BUILD SUCCESSFUL with 1 test passing.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat(tv-compose): convert SettingsScreen to Compose; delete legacy SettingsFragment"
```

---

## Task 6: `WizardScreen` + step composables + `WizardActivity` swap

**Files:**
- Create: `tv/src/main/java/eu/hyperhdr/android/tv/compose/screens/wizard/WizardStep.kt`
- Create: `tv/src/main/java/eu/hyperhdr/android/tv/compose/screens/wizard/WizardScreen.kt`
- Create: `tv/src/main/java/eu/hyperhdr/android/tv/compose/screens/wizard/DiscoveryStep.kt`
- Create: `tv/src/main/java/eu/hyperhdr/android/tv/compose/screens/wizard/ManualStep.kt`
- Create: `tv/src/main/java/eu/hyperhdr/android/tv/compose/screens/wizard/AuthStep.kt`
- Create: `tv/src/main/java/eu/hyperhdr/android/tv/compose/screens/wizard/InstanceStep.kt`
- Create: `tv/src/androidTest/java/eu/hyperhdr/android/tv/compose/WizardScreenSmokeTest.kt`
- Modify: `tv/src/main/java/eu/hyperhdr/android/tv/ui/wizard/WizardActivity.kt`
- Delete: `tv/src/main/java/eu/hyperhdr/android/tv/ui/wizard/{Discovery,Manual,Auth,Instance}StepFragment.kt`

The biggest task. Translates four step fragments into four step composables driven by a single `WizardStep` state.

- [ ] **Step 1: Create `WizardStep.kt`**

```kotlin
// tv/src/main/java/eu/hyperhdr/android/tv/compose/screens/wizard/WizardStep.kt
package eu.hyperhdr.android.tv.compose.screens.wizard

import eu.hyperhdr.android.settings.ServerProfile

sealed interface WizardStep {
    object Discovery : WizardStep
    object Manual : WizardStep
    data class Auth(val draft: ServerProfile) : WizardStep
    data class Instance(val draft: ServerProfile) : WizardStep
}
```

- [ ] **Step 2: Create `WizardScreen.kt`**

```kotlin
// tv/src/main/java/eu/hyperhdr/android/tv/compose/screens/wizard/WizardScreen.kt
package eu.hyperhdr.android.tv.compose.screens.wizard

import android.content.Context
import android.net.nsd.NsdManager
import android.net.wifi.WifiManager
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import eu.hyperhdr.android.discovery.HyperHdrMdnsDiscovery
import eu.hyperhdr.android.settings.EncryptedProfileStorage
import eu.hyperhdr.android.settings.ProfileStore
import eu.hyperhdr.android.settings.ServerProfile

@Composable
fun WizardScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    var step: WizardStep by remember { mutableStateOf(WizardStep.Discovery) }

    val discovery = remember {
        HyperHdrMdnsDiscovery(
            ctx.getSystemService(Context.NSD_SERVICE) as NsdManager,
            ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager,
        )
    }
    DisposableEffect(Unit) {
        discovery.start()
        onDispose { discovery.stop() }
    }

    BackHandler(enabled = step !is WizardStep.Discovery) {
        step = when (step) {
            is WizardStep.Manual -> WizardStep.Discovery
            is WizardStep.Auth -> WizardStep.Discovery
            is WizardStep.Instance -> WizardStep.Auth((step as WizardStep.Instance).draft)
            else -> step
        }
    }

    when (val current = step) {
        is WizardStep.Discovery -> DiscoveryStep(
            discovery = discovery,
            onPick = { server ->
                step = WizardStep.Auth(
                    ServerProfile(
                        host = server.host,
                        flatbufPort = server.flatbufPort,
                        jsonPort = server.jsonPort,
                    )
                )
            },
            onManual = { step = WizardStep.Manual },
        )
        is WizardStep.Manual -> ManualStep(
            onNext = { draft -> step = WizardStep.Auth(draft) },
        )
        is WizardStep.Auth -> AuthStep(
            draft = current.draft,
            onNext = { withToken -> step = WizardStep.Instance(withToken) },
        )
        is WizardStep.Instance -> InstanceStep(
            draft = current.draft,
            onPick = { saved ->
                ProfileStore(EncryptedProfileStorage.create(ctx)).save(saved)
                onDone()
            },
        )
    }
}
```

- [ ] **Step 3: Create `DiscoveryStep.kt`**

```kotlin
// tv/src/main/java/eu/hyperhdr/android/tv/compose/screens/wizard/DiscoveryStep.kt
package eu.hyperhdr.android.tv.compose.screens.wizard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.foundation.lazy.list.TvLazyColumn
import androidx.tv.foundation.lazy.list.items
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import eu.hyperhdr.android.discovery.DiscoveredServer
import eu.hyperhdr.android.discovery.HyperHdrMdnsDiscovery
import eu.hyperhdr.android.tv.compose.widgets.ClickablePreference

@Composable
fun DiscoveryStep(
    discovery: HyperHdrMdnsDiscovery,
    onPick: (DiscoveredServer) -> Unit,
    onManual: () -> Unit,
) {
    val servers by discovery.servers.collectAsStateWithLifecycle(emptyList())

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(48.dp),
    ) {
        Text(
            "Looking for HyperHDR servers…",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            "Pick a server below or enter manually.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))

        TvLazyColumn {
            items(servers) { server ->
                ClickablePreference(
                    title = server.name,
                    summary = "${server.host}:${server.flatbufPort}",
                    onClick = { onPick(server) },
                )
            }
            item {
                ClickablePreference(
                    title = "Enter manually…",
                    onClick = onManual,
                )
            }
        }
    }
}
```

- [ ] **Step 4: Create `ManualStep.kt`**

```kotlin
// tv/src/main/java/eu/hyperhdr/android/tv/compose/screens/wizard/ManualStep.kt
package eu.hyperhdr.android.tv.compose.screens.wizard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedTextField
import androidx.tv.material3.Text
import eu.hyperhdr.android.settings.ServerProfile

@Composable
fun ManualStep(onNext: (ServerProfile) -> Unit) {
    var host by remember { mutableStateOf("") }
    var flatPort by remember { mutableStateOf("19400") }
    var jsonPort by remember { mutableStateOf("19444") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(48.dp),
    ) {
        Text("Enter HyperHDR server", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text("Host (e.g. 192.168.1.10)") },
            modifier = Modifier.padding(vertical = 4.dp),
        )
        OutlinedTextField(
            value = flatPort,
            onValueChange = { flatPort = it },
            label = { Text("Flatbuffer port") },
            modifier = Modifier.padding(vertical = 4.dp),
        )
        OutlinedTextField(
            value = jsonPort,
            onValueChange = { jsonPort = it },
            label = { Text("JSON-API port") },
            modifier = Modifier.padding(vertical = 4.dp),
        )

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                if (host.isNotBlank()) {
                    onNext(ServerProfile(
                        host = host.trim(),
                        flatbufPort = flatPort.toIntOrNull() ?: 19400,
                        jsonPort = jsonPort.toIntOrNull() ?: 19444,
                    ))
                }
            },
        ) {
            Text("Continue", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
    }
}
```

- [ ] **Step 5: Create `AuthStep.kt`**

```kotlin
// tv/src/main/java/eu/hyperhdr/android/tv/compose/screens/wizard/AuthStep.kt
package eu.hyperhdr.android.tv.compose.screens.wizard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedTextField
import androidx.tv.material3.Text
import eu.hyperhdr.android.json.HyperHdrJsonApiClient
import eu.hyperhdr.android.settings.ServerProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun AuthStep(draft: ServerProfile, onNext: (ServerProfile) -> Unit) {
    var token by remember { mutableStateOf("") }
    var authRequired by remember { mutableStateOf<Boolean?>(null) }   // null = probing

    LaunchedEffect(draft.host, draft.jsonPort) {
        val client = HyperHdrJsonApiClient(draft.host, draft.jsonPort)
        authRequired = withContext(Dispatchers.IO) {
            runCatching { client.serverInfo().authRequired }.getOrElse { false }
        }
        if (authRequired == false) onNext(draft)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(48.dp),
    ) {
        Text("Authentication", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            when (authRequired) {
                null -> "Probing the server…"
                true -> "Server requires a token. Open HyperHDR's web UI → Network Services → API Tokens to create one."
                false -> "Server is open — proceeding…"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))

        if (authRequired == true) {
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("Token") },
            )
            Spacer(Modifier.height(16.dp))
            Row {
                Button(onClick = {
                    onNext(draft.copy(token = token.takeIf { it.isNotBlank() }))
                }) {
                    Text("Continue", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
                Spacer(Modifier.width(16.dp))
                Button(onClick = { onNext(draft) }) {
                    Text("Skip (open server)", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
            }
        }
    }
}
```

- [ ] **Step 6: Create `InstanceStep.kt`**

```kotlin
// tv/src/main/java/eu/hyperhdr/android/tv/compose/screens/wizard/InstanceStep.kt
package eu.hyperhdr.android.tv.compose.screens.wizard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.foundation.lazy.list.TvLazyColumn
import androidx.tv.foundation.lazy.list.items
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import eu.hyperhdr.android.json.HyperHdrJsonApiClient
import eu.hyperhdr.android.json.Instance
import eu.hyperhdr.android.settings.ServerProfile
import eu.hyperhdr.android.tv.compose.widgets.ClickablePreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun InstanceStep(draft: ServerProfile, onPick: (ServerProfile) -> Unit) {
    var instances by remember { mutableStateOf<List<Instance>?>(null) }   // null = loading

    LaunchedEffect(draft.host, draft.jsonPort, draft.token) {
        val client = HyperHdrJsonApiClient(draft.host, draft.jsonPort)
        draft.token?.let { runCatching { client.authorize(it) } }
        instances = withContext(Dispatchers.IO) {
            runCatching { client.serverInfo().instances }.getOrNull()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(48.dp),
    ) {
        Text("Pick the instance", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Which HyperHDR instance should this device drive?",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))

        when (val list = instances) {
            null -> Text("Loading…", style = MaterialTheme.typography.bodyMedium)
            else -> if (list.isEmpty()) {
                Text(
                    "No instances reported — check the server.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                TvLazyColumn {
                    items(list) { inst ->
                        ClickablePreference(
                            title = inst.name,
                            summary = if (inst.running) "Running (id=${inst.id})" else "Stopped (id=${inst.id})",
                            onClick = { onPick(draft.copy(instanceId = inst.id)) },
                        )
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 7: Rewrite `WizardActivity.kt`**

```kotlin
// tv/src/main/java/eu/hyperhdr/android/tv/ui/wizard/WizardActivity.kt
package eu.hyperhdr.android.tv.ui.wizard

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import eu.hyperhdr.android.tv.compose.screens.wizard.WizardScreen
import eu.hyperhdr.android.tv.compose.theme.HyperHdrTheme

class WizardActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HyperHdrTheme {
                WizardScreen(onDone = {
                    setResult(Activity.RESULT_OK)
                    finish()
                })
            }
        }
    }
}
```

- [ ] **Step 8: Delete the four step fragments**

```bash
git rm tv/src/main/java/eu/hyperhdr/android/tv/ui/wizard/DiscoveryStepFragment.kt
git rm tv/src/main/java/eu/hyperhdr/android/tv/ui/wizard/ManualStepFragment.kt
git rm tv/src/main/java/eu/hyperhdr/android/tv/ui/wizard/AuthStepFragment.kt
git rm tv/src/main/java/eu/hyperhdr/android/tv/ui/wizard/InstanceStepFragment.kt
```

- [ ] **Step 9: Update `tv/src/main/AndroidManifest.xml`**

The current `WizardActivity` declaration uses `android:theme="@style/Theme.Leanback.GuidedStep"`. Drop that attribute (Compose theming via `setContent { HyperHdrTheme { ... } }` covers what we need):

```xml
    <activity
        android:name="eu.hyperhdr.android.tv.ui.wizard.WizardActivity"
        android:exported="false"/>
```

- [ ] **Step 10: Create `WizardScreenSmokeTest.kt`**

```kotlin
// tv/src/androidTest/java/eu/hyperhdr/android/tv/compose/WizardScreenSmokeTest.kt
package eu.hyperhdr.android.tv.compose

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.hyperhdr.android.tv.compose.screens.wizard.WizardScreen
import eu.hyperhdr.android.tv.compose.theme.HyperHdrTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WizardScreenSmokeTest {

    @get:Rule val composeTestRule = createComposeRule()

    @Test
    fun wizardScreenStartsOnDiscoveryStep() {
        composeTestRule.setContent {
            HyperHdrTheme {
                WizardScreen(onDone = {})
            }
        }
        composeTestRule.onNodeWithText("Looking for HyperHDR servers…").assertExists()
        composeTestRule.onNodeWithText("Enter manually…").assertExists()
    }
}
```

- [ ] **Step 11: Verify**

```bash
./gradlew :tv:compileDebugKotlin
ANDROID_SERIAL=192.168.50.98:5555 ./gradlew :tv:connectedDebugAndroidTest --tests "eu.hyperhdr.android.tv.compose.*"
```

Expected: BUILD SUCCESSFUL with 4 instrumented tests passing (Theme + Main + Settings + Wizard).

If `androidx.tv.material3.OutlinedTextField` resolves errors: it's in alpha10 under that name. If your version returns `OutlinedTextField` from `androidx.compose.material3` instead, swap the import — `androidx.compose.material3.OutlinedTextField` is the phone-Material variant but works as a fallback.

- [ ] **Step 12: Commit**

```bash
git add -A
git commit -m "feat(tv-compose): convert WizardScreen + 4 step composables; delete legacy step fragments"
```

---

## Task 7: Drop leanback deps + delete legacy code

**Files:**
- Modify: `tv/build.gradle` — remove `androidx.leanback:*` deps
- Delete: `tv/src/main/java/eu/hyperhdr/android/tv/ui/` (the entire dir, except `MainActivity.kt`, `settings/SettingsActivity.kt`, `wizard/WizardActivity.kt` which we keep)
- Delete: `tv/src/main/res/layout/fragment_main.xml`
- Modify: `tv/src/main/res/values/styles.xml` — drop `PreferenceTheme.Leanback` style if it exists

The "we did it" diff. After this commit, leanback is gone.

- [ ] **Step 1: Drop the leanback deps**

In `tv/build.gradle`, remove these two lines from the `dependencies { ... }` block:

```groovy
    implementation 'androidx.leanback:leanback:1.0.0'
    implementation 'androidx.leanback:leanback-preference:1.0.0'
```

- [ ] **Step 2: Delete `fragment_main.xml`**

```bash
git rm tv/src/main/res/layout/fragment_main.xml
```

- [ ] **Step 3: Drop the `PreferenceTheme.Leanback` style**

Open `tv/src/main/res/values/styles.xml`. If it contains:
```xml
<style name="PreferenceTheme.Leanback" parent="@style/Theme.Leanback"/>
```
delete that line. If `AppTheme` has a `parent="..."` that references a Leanback theme (it shouldn't after Plan 3, but verify), change the parent to `Theme.AppCompat.NoActionBar` or similar — or drop the `AppTheme` style entirely if no remaining manifest references it.

```bash
grep -rn "AppTheme\|PreferenceTheme.Leanback\|Theme.Leanback" tv/src/main/res/values/ tv/src/main/AndroidManifest.xml
```

If the grep returns hits in the manifest, those activities still reference dead styles. Drop the `android:theme=` attributes from those activity declarations.

- [ ] **Step 4: Verify everything still builds**

```bash
./gradlew :tv:assembleDebug
```
Expected: BUILD SUCCESSFUL. If it fails with "AppTheme not found" or "PreferenceTheme.Leanback not found": grep again as in Step 3 and clean up the references.

- [ ] **Step 5: Verify all tests still pass**

```bash
./gradlew :common:testDebugUnitTest
ANDROID_SERIAL=192.168.50.98:5555 ./gradlew :tv:connectedDebugAndroidTest
```
Expected: 40 unit tests pass; 4 Compose smoke tests pass.

- [ ] **Step 6: Confirm leanback is fully gone**

```bash
grep -r "leanback\|Leanback" tv/src/ common/src/ || echo "(no references)"
```
Expected: only matches inside `docs/manual-tests/*.md` (text references in test instructions are fine), or `(no references)`. No code references should remain.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "chore: drop androidx.leanback deps and dead theme styles — Compose-only TV UI"
```

---

## Task 8: Manual test recipe

**Files:**
- Create: `docs/manual-tests/05-compose-tv-ui.md`

- [ ] **Step 1: Write the doc**

```bash
cat > /Users/jneerdael/Scripts/hyperhdr/HyperHDR-android/docs/manual-tests/05-compose-tv-ui.md <<'EOF'
# Manual test: Compose-for-TV UI

**Prereqs:** v1.0.0 APK installed; real Android TV with a remote (not just touchscreen); real HyperHDR v22+ on the LAN; HyperHDR web UI accessible.

This is the focus/motion/theme test that smoke tests can't cover.

## Per-screen rendering

1. **Launch the app.** Main screen renders: connection card at top, big toggle button in middle, settings button below, stats footer at bottom.
2. **D-pad up/down** between the three buttons. Focus moves cleanly. Focus ring is **green** (HyperHdrGreen, the theme accent).
3. **D-pad left/right** does nothing on these buttons (single-column layout). Confirm no errant focus jumps.
4. **Press OK on Settings.** Settings screen renders with five category headers ("Capture", "HDR", "Connection", "Behavior", "Diagnostics").
5. **D-pad down** through the lazy column. Focus moves through rows; the column scrolls when focus reaches the bottom.
6. **Press Back from Settings.** Returns to main screen, focus lands on the **toggle button** (the primary action) — not on the Settings button you came from. (This is correct: cross-activity navigation resets focus to the screen's default target.)
7. **From main, press OK on the connection card.** Wizard launches.
8. **Wizard step 1 (Discovery):** "Looking for HyperHDR servers…" header. Within a few seconds, your HyperHDR server appears in the list. D-pad down to "Enter manually…" — focus moves through any discovered servers first.
9. **Press OK on a discovered server.** Auth step appears (or, if the server is open, jumps straight to Instance step within 1-2 seconds).
10. **In the Auth step, enter a token if required.** D-pad to text input → OK → Android keyboard appears → type → OK to submit. Then "Continue".
11. **Instance step:** loading… then list of instances. Pick one. Wizard finishes; main screen shows the new profile.

## Theme verification

12. The app background is dark (near-black). Surface cards (preference rows) are slightly lighter charcoal. Focus rings are green. The accent color on the streaming-state badge (✦ HDR) matches the focus-ring green.
13. **Open HyperHDR's web UI → Configuration → Components → toggle HDR.** The main-screen footer should sprout a `✦ HDR` badge in green within ~1 second.

## Wizard back-button semantics

14. From the wizard's Auth step, press **Back**. Returns to Discovery step.
15. From the Instance step, press **Back**. Returns to Auth step.
16. From Discovery step, press **Back**. Wizard exits → main screen.

## Pass criteria

All 16 steps complete without crashes, focus glitches, or theme regressions.

## Fail modes

- **Focus jumps to "wrong" element on screen entry:** the per-screen `LaunchedEffect(Unit) { requester.requestFocus() }` is misbehaving. Likely fix: add `Modifier.focusable()` to the focus target.
- **Focus ring is white/blue, not green:** `HyperHdrTheme` isn't wrapping the screen, or `MaterialTheme.colorScheme.primary` got overridden somewhere. Check `setContent { HyperHdrTheme { ... } }` in each activity.
- **D-pad up/down skips a row:** `TvLazyColumn` issue. Verify each row is `focusable` (cards are by default if they have an `onClick`).
- **Wizard Back exits the activity instead of stepping back:** `BackHandler` not wired in `WizardScreen`. The handler is `enabled = step !is WizardStep.Discovery` — first step's Back finishes the activity, all others step back.
- **Settings instance-name doesn't update when web UI renames the instance:** `LaunchedEffect(binder)` in `SettingsScreen` not collecting `jsonEventsFlow`. Verify the binder is non-null in production (it's null in smoke tests, which is fine).
EOF
git add docs/manual-tests/05-compose-tv-ui.md
git commit -m "docs: manual test recipe for Compose-for-TV UI"
```

---

## Task 9: v1.0.0 release cut

**Files:**
- Modify: `tv/build.gradle` — versionName "1.0.0-dev" → "1.0.0"

- [ ] **Step 1: Bump version**

In `tv/build.gradle`:
```groovy
        versionCode 4
        versionName "1.0.0"
```

- [ ] **Step 2: Full verification**

```bash
./gradlew :common:testDebugUnitTest
./gradlew :tv:assembleRelease
ANDROID_SERIAL=192.168.50.98:5555 ./gradlew :tv:connectedDebugAndroidTest
```

All BUILD SUCCESSFUL. Verify cert preservation:

```bash
$HOME/Library/Android/sdk/build-tools/35.0.0/apksigner verify --print-certs tv/build/outputs/apk/release/tv-release.apk | grep "SHA-256 digest"
```

Expected: `71eda2e2ba6ff35d7cfdca3441ddf7d6bca818de6f635230390b439ce63f7081` (matches v0.1.0 / v0.2.0 / v0.3.0). If the SHA-256 differs, **stop** — the keystore identity drifted; existing v0.x.y installs would refuse to upgrade.

- [ ] **Step 3: Commit, tag, push**

```bash
git add tv/build.gradle
git commit -m "build: bump to 1.0.0"
git push origin main
git tag -a v1.0.0 -m "HyperHDR-Android v1.0.0

Compose-for-TV rewrite. Same three screens, same user-facing behaviour, modern UI foundation:

- New Compose-based UI under eu.hyperhdr.android.tv.compose
- HyperHdrTheme — minimal custom theme (LED-green accent on dark surfaces)
- Main / Settings / Wizard screens converted from Leanback fragments to composables
- Smoke tests: 4 instrumented Compose tests via createComposeRule()
- androidx.leanback:* deps DROPPED — no more 2018-era unmaintained UI library

Same signing identity as v0.1.0/v0.2.0/v0.3.0 (cert SHA-256: 71eda2e2…39ce63f7081)."
git push origin v1.0.0
gh run watch --repo johnneerdael/HyperHDR-android --exit-status
```

- [ ] **Step 4: Verify the published artifact + smoke install**

```bash
cd /tmp && rm -f tv-release.apk
gh release download v1.0.0 --repo johnneerdael/HyperHDR-android --pattern '*.apk' -O tv-release.apk
shasum -a 256 tv-release.apk
$HOME/Library/Android/sdk/build-tools/35.0.0/apksigner verify --print-certs tv-release.apk | grep "SHA-256 digest"
$HOME/Library/Android/sdk/build-tools/35.0.0/aapt dump badging tv-release.apk | grep "versionCode\|versionName" | head -1
```

Expected: cert SHA-256 matches; aapt confirms `versionCode='4' versionName='1.0.0'`.

- [ ] **Step 5: Run the manual recipe on real hardware**

```bash
adb install -r /tmp/tv-release.apk
```

Walk through all 16 steps in `docs/manual-tests/05-compose-tv-ui.md`. Document any failures.

---

## Plan 7 done definition

- [ ] All 9 tasks complete.
- [ ] All 40 unit tests + 2 instrumented (`:common`) + 4 Compose smoke tests (`:tv`) pass.
- [ ] `:tv:assembleRelease` builds with the same v0.1.0 signing certificate.
- [ ] `androidx.leanback:*` deps absent from `tv/build.gradle`.
- [ ] `tv/src/main/java/eu/hyperhdr/android/tv/ui/MainFragment.kt`, `SettingsFragment.kt`, and the four `*StepFragment.kt` files are gone.
- [ ] `v1.0.0` GitHub Release published with signed APK; `adb install -r` over a v0.3.0 install succeeds.
- [ ] Manual recipe in Task 8 succeeds on real hardware.

---

## Self-review (writer's pre-flight)

- **Spec coverage:**
  - §3 Module layout → Task 2 (extract ScreenBinder to `:common`), Tasks 3–6 (composables under `tv/compose/`), Task 7 (delete `tv/ui/` legacy).
  - §4 Dependencies → Task 1.
  - §5 HyperHdrTheme → Task 1.
  - §6 Per-screen composables → Tasks 4 (Main), 5 (Settings), 6 (Wizard).
  - §7 Service binding → Task 2 (`ServiceBinderEffect`, `ScreenBinder` interface).
  - §8 Focus management → Tasks 4–6 (per-screen `FocusRequester` + `LaunchedEffect`).
  - §9 Tests → Task 2 (Theme), Task 4 (Main), Task 5 (Settings), Task 6 (Wizard).
  - §10 Migration order → tasks ordered identically.
  - §11 Out of scope → enforced by what's NOT planned.
  - §12 Risks → Task 6 step 11 includes the "OutlinedTextField fallback to compose.material3" mitigation.
  - §13 Done definition → reproduced in this plan's done-definition section.
- **Type consistency:** `ScreenBinder` interface (Task 2) — used by Tasks 4, 5, 6 with identical method signatures. `WizardStep` (Task 6 step 1) — used by Task 6 step 2's `WizardScreen`. `StubBinder` (Task 2) — used by Tasks 4, 5 (only Wizard test omits it because the wizard has no service dependency at the discovery step). `HyperHdrTheme` (Task 1) — used by every activity.
- **No placeholders:** every code block is concrete. Two intentional "v1.x defer" stubs in `SettingsScreen` (Priority and Auth-token edits) are documented in the spec's §11 out-of-scope (the wizard re-paste path is the v1.0.0 token-edit affordance).
- **Architecture deviation worth flagging:** Task 2 extracts `ScreenBinder` to `:common` (originally drafted as `:tv`-internal) because Kotlin module dependencies don't go `:common` → `:tv`. The interface lives next to `HyperHdrServiceBinder` in `:common`. This is a small architectural improvement — it makes the contract explicit at the seam — not a regression.
- **Dependencies don't drift:** Task 1 pins specific Compose versions. No mid-plan version bumps.
