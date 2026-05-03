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
- **Settings categories below "Connection" don't render until you scroll:** `TvLazyColumn` lazy rendering is the cause; that's expected and harmless. The smoke tests work around it with `performScrollToNode`.
- **Wizard Back exits the activity instead of stepping back:** `BackHandler` not wired in `WizardScreen`. The handler is `enabled = step !is WizardStep.Discovery` — first step's Back finishes the activity, all others step back.
- **Settings instance-name doesn't update when web UI renames the instance:** `LaunchedEffect(binder)` in `SettingsScreen` not collecting `jsonEventsFlow`. Verify the binder is non-null in production (it's null in smoke tests, which is fine).
