# Manual test: end-to-end install + first-run flow

**Prereqs:** Real Android TV (or any Android 9+ device) with ADB. Real HyperHDR v22+ on the same LAN with mDNS unblocked.

**Steps:**

1. Build + install:
   ```bash
   ./gradlew :tv:installDebug
   ```
2. From the TV launcher, open "HyperHDR." App opens to the main screen, the connection card says "Configure server…".
3. Tap the connection card → wizard opens. Within ~3 seconds, the discovery list should show your HyperHDR server. (If empty, pick "Enter manually" and enter host + ports.)
4. Pick the server. If auth is enabled, the auth step appears; paste a token and continue. If auth is disabled, the wizard skips to instance picking.
5. Pick an instance. Wizard closes; main screen shows `<host> (instance N)`.
6. Press "Start capture." Approve the projection dialog. The toggle button becomes "Stop capture" once `STREAMING`.
7. Open any colourful app on the TV. LEDs follow.
8. Press "Stop capture." LEDs go quiet within ~200 ms.
9. Reboot the device. After boot, you should see HyperHDR's foreground notification "Idle — open the app to start capture." Tap → main screen shows the saved profile already.
10. Open the Quick Settings panel. The "HyperHDR" tile is present. Tapping it opens the app (Android does not allow projection from a tile click).

**Pass criteria:**
- All 10 steps complete without crashes.
- The notification persists across the toggle cycles.
- The wizard's auth probe correctly distinguishes auth-required from auth-disabled servers (test both by toggling auth on/off in HyperHDR's web UI).
- After the wizard, settings are remembered across `am force-stop` + relaunch.

**Known limitations to verify (these are not bugs):**
- Capture cannot auto-start on boot — projection consent is mandatory each session on Android 12–13. On Android 14+ token reuse is platform-dependent.
- The Quick Settings tile cannot directly start capture; tapping it opens the app.
