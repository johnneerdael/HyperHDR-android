# Nexio → HyperHDR Wide-Color Gating v3 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the v2 HyperHDR integration self-aware of the host device's compositor capabilities. On devices whose `Display.getSupportedColorModes()` returns only `COLOR_MODE_DEFAULT` (e.g. UGOOS-AM6, API 28), force SDR_NV12 capture regardless of source colorimetry or user setting, and grey-out the HDR Mode picker in Settings with explanatory text. On devices that report wide-color modes (Google TV Streamer 4K, modern Android TV boxes), behaviour stays exactly as v2.

**Architecture:** A new `DisplayColorCapability` helper (Hilt-singleton) reads `Display.getSupportedColorModes()` once at construction and exposes a boolean `composesWideColor`. `FormatDetector.detect()` gains a `deviceComposesWideColor: Boolean` parameter and short-circuits to `SDR_NV12` when it's false (before checking either user `HdrMode` or source `colorInfo`). The Settings ViewModel exposes the capability so the UI can render the HDR Mode picker as enabled-with-options or locked-to-Force-SDR-with-explanatory-text. The Player.Listener wiring in `PlayerRuntimeControllerInitialization` reads the capability via the same Hilt entry point and passes it to every `FormatDetector.detect()` call.

**Tech Stack:** Kotlin, Compose-for-TV, Hilt DI, Android `DisplayManager`/`Display.getSupportedColorModes()` (API 24+), JUnit + Truth.

**Repository:** `~/Scripts/nexio` only. No fork modifications.

**Reference:** v2 plan at `/Users/jneerdael/Scripts/hyperhdr/HyperHDR-android/docs/superpowers/plans/2026-05-03-nexio-hyperhdr-hdr-aware-v2.md`. v2 commits already on Nexio `main` (latest: `891a97f`). v3 builds on v2; do not revert v2 commits.

**Test devices:**
- Google TV Streamer 4K (`192.168.50.102:5555`) — wide-color compositor, gets full HDR Auto/ForceSdr UX
- AM6 (`192.168.50.98:5555`, API 28) — sRGB-only compositor, gets locked-to-SDR UX

All device-side verification deferred per the user's standing instruction.

---

## File Structure

```
nexio/app/src/main/java/com/nexio/tv/integrations/hyperhdr/
├── capture/
│   ├── DisplayColorCapability.kt              [NEW]
│   ├── FormatDetector.kt                      [MODIFY: add deviceComposesWideColor param]
│   └── (unchanged: CaptureMode, NvDownscaleShaders, PqDownscaleShaders, …)
└── ui/
    ├── HyperHdrSettingsViewModel.kt            [MODIFY: expose composesWideColor]
    └── HyperHdrSettingsContent.kt              [MODIFY: gate HDR Mode picker on capability]

nexio/app/src/main/java/com/nexio/tv/ui/screens/player/
└── PlayerRuntimeControllerInitialization.kt    [MODIFY: pass capability to FormatDetector.detect()]

nexio/app/src/test/java/com/nexio/tv/integrations/hyperhdr/capture/
├── DisplayColorCapabilityTest.kt               [NEW]
└── FormatDetectorTest.kt                       [MODIFY: cover new parameter]

nexio/docs/manual-tests/
└── 01-hyperhdr-ambilight.md                    [MODIFY: SDR-only-device note]
```

---

## Task 1: `DisplayColorCapability` helper + TDD

> **API note (lesson from implementation):** Nexio's compileSdk 36 stub has `Display.COLOR_MODE_BT2020/BT2100_PQ/BT2100_HLG` and `getSupportedColorModes()` marked `@hide` — they're not in the public-SDK stubs even though they're real on-device APIs (API 24+). Use raw integer literals `12, 13, 14` for the wide-color color-mode constants in `WIDE_COLOR_MODES`, and access `getSupportedColorModes()` via reflection wrapped in `runCatching` (returns empty array on `NoSuchMethodError`). The companion's `containsWideColorMode(IntArray)` signature is unchanged and JVM-testable. The on-device reflection call reaches the real hidden method.

**Files:**
- Create: `app/src/main/java/com/nexio/tv/integrations/hyperhdr/capture/DisplayColorCapability.kt`
- Create: `app/src/test/java/com/nexio/tv/integrations/hyperhdr/capture/DisplayColorCapabilityTest.kt`

A small Hilt-singleton wrapper around `DisplayManager.getDisplay(Display.DEFAULT_DISPLAY).supportedModes` (API 24+). Exposes a single boolean `composesWideColor` computed at construction time. Pure-logic helper `containsWideColorMode(IntArray): Boolean` is JVM-testable; the Display read itself is exercised on-device.

The wide-color color modes (per `Display.kt` constants in the AOSP source):
- `Display.COLOR_MODE_DEFAULT = 0` — sRGB
- `Display.COLOR_MODE_BT601_625 = 1`
- `Display.COLOR_MODE_BT601_525 = 6`
- `Display.COLOR_MODE_BT709 = 7`
- `Display.COLOR_MODE_DCI_P3 = 8`
- `Display.COLOR_MODE_SRGB = 9`
- `Display.COLOR_MODE_ADOBE_RGB = 10`
- `Display.COLOR_MODE_DISPLAY_P3 = 11`
- `Display.COLOR_MODE_BT2020 = 12`
- `Display.COLOR_MODE_BT2100_PQ = 13`
- `Display.COLOR_MODE_BT2100_HLG = 14`

We treat `BT2020`, `BT2100_PQ`, and `BT2100_HLG` as "wide-color / HDR-capable compositor". The wide-gamut SDR modes (`DCI_P3`, `DISPLAY_P3`, `ADOBE_RGB`) are NOT enough — they're SDR with extended primaries, not HDR.

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/nexio/tv/integrations/hyperhdr/capture/DisplayColorCapabilityTest.kt`:

```kotlin
package com.nexio.tv.integrations.hyperhdr.capture

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DisplayColorCapabilityTest {

    @Test
    fun `containsWideColorMode returns false for default-only display`() {
        val modes = intArrayOf(0)   // COLOR_MODE_DEFAULT
        assertThat(DisplayColorCapability.containsWideColorMode(modes)).isFalse()
    }

    @Test
    fun `containsWideColorMode returns false for SDR wide-gamut modes`() {
        // DCI_P3, DISPLAY_P3, ADOBE_RGB are wide-gamut but SDR — not enough for HDR ambilight.
        val modes = intArrayOf(0, 8, 10, 11)
        assertThat(DisplayColorCapability.containsWideColorMode(modes)).isFalse()
    }

    @Test
    fun `containsWideColorMode returns true when BT2020 is present`() {
        val modes = intArrayOf(0, 12)
        assertThat(DisplayColorCapability.containsWideColorMode(modes)).isTrue()
    }

    @Test
    fun `containsWideColorMode returns true when BT2100_PQ is present`() {
        val modes = intArrayOf(0, 13)
        assertThat(DisplayColorCapability.containsWideColorMode(modes)).isTrue()
    }

    @Test
    fun `containsWideColorMode returns true when BT2100_HLG is present`() {
        val modes = intArrayOf(0, 14)
        assertThat(DisplayColorCapability.containsWideColorMode(modes)).isTrue()
    }

    @Test
    fun `containsWideColorMode returns false for empty array`() {
        assertThat(DisplayColorCapability.containsWideColorMode(intArrayOf())).isFalse()
    }
}
```

- [ ] **Step 2: Run tests, expect compile failure**

```bash
cd ~/Scripts/nexio && ./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.integrations.hyperhdr.capture.DisplayColorCapabilityTest" 2>&1 | tail -10
```
Expected: compile error — `DisplayColorCapability` doesn't exist.

- [ ] **Step 3: Implement `DisplayColorCapability.kt`**

```kotlin
package com.nexio.tv.integrations.hyperhdr.capture

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads `Display.getSupportedColorModes()` for the default display once at construction
 * and exposes whether the device's compositor can operate in any wide-color / HDR mode
 * (`BT2020`, `BT2100_PQ`, or `BT2100_HLG`).
 *
 * On devices whose compositor only reports `COLOR_MODE_DEFAULT` (sRGB) — e.g. UGOOS-AM6
 * on Android 9 — HDR content arrives at the GL effect pipeline already SDR-tone-mapped,
 * so [FormatDetector] should force SDR_NV12 regardless of source colorimetry.
 *
 * Wide-gamut SDR modes (`DCI_P3`, `DISPLAY_P3`, `ADOBE_RGB`) are deliberately NOT counted
 * as wide-color here — they're SDR with extended primaries, not HDR-composable.
 */
@Singleton
class DisplayColorCapability @Inject constructor(
    @ApplicationContext context: Context,
) {
    val composesWideColor: Boolean = run {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = dm.getDisplay(Display.DEFAULT_DISPLAY)
        val modes = display?.supportedColorModes ?: intArrayOf()
        containsWideColorMode(modes)
    }

    companion object {
        /** Display color-mode constants we consider "HDR-capable compositor". */
        private val WIDE_COLOR_MODES = intArrayOf(
            Display.COLOR_MODE_BT2020,
            Display.COLOR_MODE_BT2100_PQ,
            Display.COLOR_MODE_BT2100_HLG,
        )

        /** Pure-logic helper, JVM-testable. */
        fun containsWideColorMode(modes: IntArray): Boolean =
            modes.any { it in WIDE_COLOR_MODES }
    }
}
```

- [ ] **Step 4: Run tests, expect 6/6 passing**

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.integrations.hyperhdr.capture.DisplayColorCapabilityTest" 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL` with 6 tests passing.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/integrations/hyperhdr/capture/DisplayColorCapability.kt \
        app/src/test/java/com/nexio/tv/integrations/hyperhdr/capture/DisplayColorCapabilityTest.kt
git commit -m "feat(hyperhdr): DisplayColorCapability — detect wide-color compositor support

@Singleton helper that reads Display.getSupportedColorModes() once and
exposes composesWideColor: Boolean. True only when the compositor reports
BT2020, BT2100_PQ, or BT2100_HLG — wide-gamut SDR modes (DCI_P3,
DISPLAY_P3, ADOBE_RGB) deliberately don't count.

Used by FormatDetector to short-circuit to SDR_NV12 on devices whose
compositor doesn't operate in HDR (e.g. UGOOS-AM6 on Android 9 reports
supportedColorModes=[0]). Pure-logic containsWideColorMode helper is
JVM-tested with 6 cases."
```

---

## Task 2: `FormatDetector` adds `deviceComposesWideColor` parameter

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/integrations/hyperhdr/capture/FormatDetector.kt`
- Modify: `app/src/test/java/com/nexio/tv/integrations/hyperhdr/capture/FormatDetectorTest.kt`

`FormatDetector.detect(...)` gains a third parameter. The new parameter is checked **first**, before either the user's `HdrMode` setting or the source's `colorInfo`. This makes the SDR-only-device override the strongest gate.

- [ ] **Step 1: Write the failing tests** — extend `FormatDetectorTest.kt`. Keep the existing 6 tests; update them to pass `deviceComposesWideColor = true`, then add 2 new tests for the false case.

Modify each existing test's `FormatDetector.detect(…)` call to add `deviceComposesWideColor = true` as a third argument. For example:

```kotlin
// before
val mode = FormatDetector.detect(colorInfo(C.COLOR_TRANSFER_ST2084), HdrMode.Auto)
// after
val mode = FormatDetector.detect(
    colorInfo(C.COLOR_TRANSFER_ST2084),
    HdrMode.Auto,
    deviceComposesWideColor = true,
)
```

(Update all 6 existing call sites — including the loop in `ForceSdr always yields SDR_NV12 regardless of colorInfo`.)

Then append two new tests:

```kotlin
@Test
fun `device without wide-color support forces SDR_NV12 regardless of source HDR`() {
    val mode = FormatDetector.detect(
        colorInfo(C.COLOR_TRANSFER_ST2084),
        HdrMode.Auto,
        deviceComposesWideColor = false,
    )
    assertThat(mode).isEqualTo(CaptureMode.SDR_NV12)
}

@Test
fun `device without wide-color support forces SDR_NV12 even if user picks ForceSdr`() {
    // ForceSdr already forces SDR; this test just confirms the device gate doesn't
    // accidentally bypass it.
    val mode = FormatDetector.detect(
        colorInfo(C.COLOR_TRANSFER_HLG),
        HdrMode.ForceSdr,
        deviceComposesWideColor = false,
    )
    assertThat(mode).isEqualTo(CaptureMode.SDR_NV12)
}
```

- [ ] **Step 2: Run tests, expect compile failure**

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.integrations.hyperhdr.capture.FormatDetectorTest" 2>&1 | tail -10
```
Expected: compile error — `detect(...)` signature has only two params.

- [ ] **Step 3: Modify `FormatDetector.kt`** — add the third parameter as the first gate.

Replace the entire `detect` method body with:

```kotlin
@UnstableApi
fun detect(
    colorInfo: ColorInfo?,
    hdrMode: HdrMode,
    deviceComposesWideColor: Boolean,
): CaptureMode {
    // Strongest gate first: if the device's compositor doesn't operate in wide-color,
    // any HDR pipeline we attempt would receive SDR-tone-mapped data anyway. Force SDR.
    if (!deviceComposesWideColor) return CaptureMode.SDR_NV12

    if (hdrMode == HdrMode.ForceSdr) return CaptureMode.SDR_NV12

    return when (colorInfo?.colorTransfer) {
        C.COLOR_TRANSFER_ST2084, C.COLOR_TRANSFER_HLG -> CaptureMode.HDR_P010
        else -> CaptureMode.SDR_NV12
    }
}
```

- [ ] **Step 4: Run tests, expect 8/8 passing (6 existing updated + 2 new)**

```bash
./gradlew :app:testDebugUnitTest --tests "com.nexio.tv.integrations.hyperhdr.capture.FormatDetectorTest" 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL` with 8 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/integrations/hyperhdr/capture/FormatDetector.kt \
        app/src/test/java/com/nexio/tv/integrations/hyperhdr/capture/FormatDetectorTest.kt
git commit -m "feat(hyperhdr): FormatDetector gates on deviceComposesWideColor

Adds deviceComposesWideColor: Boolean as the first parameter check. When
false, returns SDR_NV12 regardless of source colorInfo or user HdrMode
setting. This is the strongest gate in the detector — it overrides
everything else, because no amount of source-PQ data helps if the
compositor doesn't operate in wide-color.

Existing 6 tests updated to pass deviceComposesWideColor = true; 2 new
tests cover the false case (HDR source + Auto = SDR; HLG + ForceSdr +
no-wide-color = SDR). Net 8/8 tests pass."
```

---

## Task 3: Wire `DisplayColorCapability` through ViewModel + Settings UI + lifecycle

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/integrations/hyperhdr/ui/HyperHdrSettingsViewModel.kt`
- Modify: `app/src/main/java/com/nexio/tv/integrations/hyperhdr/ui/HyperHdrSettingsContent.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt`

The Hilt module from v1 Task 4 is already wired for `HyperHdrConfigDataStore` injection. `DisplayColorCapability` is `@Inject`-able directly without module changes (it has `@Inject constructor`).

- [ ] **Step 1: Modify `HyperHdrSettingsViewModel.kt`** — inject `DisplayColorCapability`, expose `composesWideColor` as a property (or include in the StateFlow).

Find the existing `@HiltViewModel class HyperHdrSettingsViewModel @Inject constructor(...)`. Add `DisplayColorCapability` to the constructor:

```kotlin
@HiltViewModel
class HyperHdrSettingsViewModel @Inject constructor(
    private val store: HyperHdrConfigDataStore,
    private val displayCapability: DisplayColorCapability,
) : ViewModel() {

    val composesWideColor: Boolean = displayCapability.composesWideColor

    // … existing config StateFlow, testResult StateFlow, setters unchanged …
}
```

Add the new import:

```kotlin
import com.nexio.tv.integrations.hyperhdr.capture.DisplayColorCapability
```

- [ ] **Step 2: Modify `HyperHdrSettingsContent.kt`** — gate the HDR Mode picker on `composesWideColor`.

Find the HDR Mode section in the existing Composable. Replace with:

```kotlin
        Text("HDR mode")
        if (viewModel.composesWideColor) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                HdrModeRadio("Auto (detect from source)", cfg.hdrMode == HdrMode.Auto) {
                    viewModel.setHdrMode(HdrMode.Auto)
                }
                Spacer(Modifier.height(4.dp))
                HdrModeRadio("Force SDR", cfg.hdrMode == HdrMode.ForceSdr) {
                    viewModel.setHdrMode(HdrMode.ForceSdr)
                }
            }
        } else {
            // Compositor is sRGB-only on this device — HDR ambilight isn't possible
            // here regardless of source. Lock the mode and explain why.
            Text(
                text = "• Force SDR (locked)",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "This device's compositor only supports sRGB. HDR ambilight " +
                    "requires a wide-color-capable Android TV (Google TV Streamer, " +
                    "Shield, or any Android 13+ device whose Display reports a BT2020 " +
                    "or BT2100 color mode). SDR ambilight works correctly here.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
```

If `cfg.hdrMode` is `HdrMode.Auto` on a non-wide-color device, the displayed-as-locked path implies SDR but the persisted setting is still Auto. That's fine because `FormatDetector` will short-circuit to SDR anyway — the displayed value is informational, not authoritative. If you want to be belt-and-braces, also call `viewModel.setHdrMode(HdrMode.ForceSdr)` once in a `LaunchedEffect(Unit)` block when `!composesWideColor && cfg.hdrMode != HdrMode.ForceSdr` — optional polish.

- [ ] **Step 3: Modify `PlayerRuntimeControllerInitialization.kt`** — pass the capability to every `FormatDetector.detect(...)` call.

Find the existing `EntryPoint` definition (the `HyperHdrEntryPoint` interface that exposes `hyperHdrConfigDataStore()`). Add another method:

```kotlin
@EntryPoint
@InstallIn(SingletonComponent::class)
interface HyperHdrEntryPoint {
    fun hyperHdrConfigDataStore(): com.nexio.tv.integrations.hyperhdr.data.HyperHdrConfigDataStore
    fun displayColorCapability(): com.nexio.tv.integrations.hyperhdr.capture.DisplayColorCapability
}
```

(If the existing entry point is named differently, match it. The point is to add one method that returns `DisplayColorCapability`.)

In the same file, where `hyperHdrStore` is acquired via `EntryPoints.get(...)`, also acquire the capability:

```kotlin
val hyperHdrStore = EntryPoints.get(context, HyperHdrEntryPoint::class.java).hyperHdrConfigDataStore()
val hyperHdrDisplayCapability = EntryPoints.get(context, HyperHdrEntryPoint::class.java).displayColorCapability()
```

Then update the two `FormatDetector.detect(...)` call sites (one in `onIsPlayingChanged`, one in `onTracksChanged`) to pass `hyperHdrDisplayCapability.composesWideColor` as the third argument:

```kotlin
val mode = com.nexio.tv.integrations.hyperhdr.capture.FormatDetector.detect(
    _exoPlayer?.videoFormat?.colorInfo,
    hyperHdrCfg.hdrMode,
    deviceComposesWideColor = hyperHdrDisplayCapability.composesWideColor,
)
```

- [ ] **Step 4: Verify compile**

```bash
cd ~/Scripts/nexio && ./gradlew :app:compileUniversalDebugKotlin 2>&1 | tail -3
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/integrations/hyperhdr/ui/HyperHdrSettingsViewModel.kt \
        app/src/main/java/com/nexio/tv/integrations/hyperhdr/ui/HyperHdrSettingsContent.kt \
        app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt
git commit -m "feat(hyperhdr): gate HDR Mode UI + lifecycle on compositor wide-color support

ViewModel injects DisplayColorCapability and exposes composesWideColor.
Settings UI hides the HDR Mode Auto/ForceSdr picker on non-wide-color
devices and displays a locked 'Force SDR' state with a one-paragraph
explanation. Both onIsPlayingChanged and onTracksChanged in
PlayerRuntimeControllerInitialization now pass deviceComposesWideColor
to FormatDetector.detect().

On devices whose compositor only reports COLOR_MODE_DEFAULT (e.g. AM6
on Android 9), this silently makes the integration a clean SDR-only
ambilight source — Auto/ForceSdr distinction is moot, NV12 path always
selected, no broken HDR_P010 attempts."
```

---

## Task 4: Manual test recipe — note SDR-only-device behaviour

**Files:**
- Modify: `nexio/docs/manual-tests/01-hyperhdr-ambilight.md`

Add a short section explaining what users should expect on devices like AM6.

- [ ] **Step 1: Add section + commit**

Read the existing recipe. Insert this section after the **HDR Mode override** section (around line ~76 after the existing 11-13 numbered steps), before **Verify clean handoff between sessions**:

```markdown
## SDR-only device behaviour (compositor without wide-color support)

Some Android TV devices — including UGOOS-AM6 on Android 9 and older
hardware in general — have compositors that only report
`COLOR_MODE_DEFAULT` (sRGB). On these devices, Nexio's HyperHDR
integration automatically forces SDR_NV12 regardless of the user's HDR
Mode setting or the source's colorimetry, because no HDR data reaches
the GL effect pipeline anyway.

You'll see this in the Settings screen:

- Toggle **Enabled** on
- Fill in host/ports/priority as normal
- Where the HDR Mode picker would be, the screen displays:
  > • Force SDR (locked)
  >
  > This device's compositor only supports sRGB. HDR ambilight requires
  > a wide-color-capable Android TV (Google TV Streamer, Shield, or any
  > Android 13+ device whose Display reports a BT2020 or BT2100 color
  > mode). SDR ambilight works correctly here.

Behaviour on the wire is then identical to the **HDR Mode override** →
**Force SDR** scenario: NV12 frames, `videomode HDR=0` JSON signal,
HyperHDR's SDR LUT engaged. The connected HDR display still shows HDR
content from the device's hardware overlay path — Nexio's ambilight
just reflects the SDR composition that the compositor produces, not
the HDR overlay.

To confirm the device is SDR-only:
```bash
adb -s <device-ip>:5555 shell dumpsys display | grep "supportedColorModes"
```
If the output reads `supportedColorModes=[0]` or `mSupportedColorModes=[0]`,
this gate is correctly engaged. If the array contains additional entries
(e.g. `[0, 12, 13]` for BT2020/BT2100_PQ), the device IS wide-color
capable and the HDR Mode picker should be visible.
```

Also add to **Common failures**:

```markdown
- **HDR Mode picker isn't visible on a device you expect to be HDR-capable** —
  the device's compositor doesn't actually report HDR modes. Verify with
  the dumpsys command above. Some HDR-capable TVs/displays connected to
  SDR-compositor source devices (e.g. AM6 → HDR TV) won't unlock the
  picker, because Nexio's capture path operates on the device's
  compositor output, not the display.
```

Then commit:

```bash
git add docs/manual-tests/01-hyperhdr-ambilight.md
git commit -m "docs: explain SDR-only-device behaviour in HyperHDR ambilight recipe

Adds a section covering what users should expect on devices whose
compositor only reports COLOR_MODE_DEFAULT (e.g. UGOOS-AM6 on
Android 9). Behaviour is identical to Force SDR: NV12 wire format,
HyperHDR SDR LUT engaged, ambilight reflects SDR composition. Recipe
includes the dumpsys command to confirm the gate is engaged correctly."
```

---

## Self-Review

**Spec coverage:**

| Requirement | Implemented in |
|---|---|
| `DisplayColorCapability` Hilt-singleton helper | Task 1 |
| Pure-logic `containsWideColorMode(IntArray)` | Task 1 |
| `FormatDetector.detect(...)` gains `deviceComposesWideColor` parameter | Task 2 |
| The new gate is checked FIRST, before HdrMode and colorInfo | Task 2 |
| ViewModel exposes `composesWideColor` | Task 3 |
| Settings UI hides HDR Mode picker on non-wide-color devices and shows explanatory text | Task 3 |
| Lifecycle wiring passes capability through to every FormatDetector call | Task 3 |
| Manual test recipe documents SDR-only-device behaviour | Task 4 |

**Placeholder scan:**
- No "TBD"/"implement later"/"similar to Task N" patterns.
- The KDoc on `DisplayColorCapability` references AM6 as the concrete example — not a placeholder, just context for future readers.
- One optional polish item flagged in Task 3 Step 2 (`LaunchedEffect` to also persist ForceSdr on non-wide-color devices). Marked optional explicitly.

**Type consistency:**
- `DisplayColorCapability.composesWideColor: Boolean` defined Task 1, referenced Tasks 2 (test param), 3 (ViewModel + lifecycle).
- `containsWideColorMode(modes: IntArray): Boolean` defined Task 1 companion, called once internally from the constructor — only the public companion API is tested.
- `FormatDetector.detect(colorInfo, hdrMode, deviceComposesWideColor)` defined Task 2; both call sites (Task 3) match the signature.
- New `EntryPoint` method `displayColorCapability(): DisplayColorCapability` referenced consistently in Task 3.

**Scope check:** Single feature (capability-aware capture-mode gating), four tasks, ~50 LOC of new code total + ~30 LOC of test additions. No subsystem decomposition needed.
