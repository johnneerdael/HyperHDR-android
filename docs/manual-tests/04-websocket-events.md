# Manual test: WebSocket live events

**Prereqs:** v0.3.0+ APK installed; real HyperHDR v22+ on the LAN; HyperHDR web UI open in a browser; capture started in the app.

**Steps:**

1. With capture running, open the app's main screen. Note the stats footer.
2. In HyperHDR's web UI, go to *Configuration → Components* (or wherever HDR-tonemap is toggled).
3. Toggle the HDR component **on**. Within ~1 second the app's stats footer should grow a `✦ HDR` badge.
4. Toggle it **off**. Within ~1 second the badge disappears.
5. In the web UI, rename one of the instances (e.g. "LED Frame" → "LED Frame v2"). In the app, open Settings — the "Reconfigure server…" summary should reflect the new name.

**Optional disconnect-recovery check:**

6. With the app open on the main screen, restart the HyperHDR service on the server (`systemctl restart hyperhdr`).
7. The badge briefly disappears as the WebSocket drops.
8. Within a few seconds, the WebSocket reconnects and the badge state is restored.

**Pass:** all five (or eight) steps complete without app restarts.

**Fail modes:**
- Badge never appears: WebSocket isn't opening. Check `adb logcat | grep HyperHdr.JsonApi` for `onFailure` messages — likely the `ws://` URL or path is wrong for your HyperHDR version.
- Badge appears but never disappears: parser is mapping `enabled=false` somewhere.
- Settings instance name doesn't update: `findPreference("server_card")` is failing — verify the preference's `key = "server_card"` was set in Plan 6 Task 6.
- Badge oscillates rapidly: feedback loop between local detector and server signaling. The Plan 6 design explicitly avoids this by treating server-side as observe-only; if you see oscillation, check that no code path in `HyperHdrCaptureService` calls `setHdrVideoMode` from inside the events collector.
- Reconnect takes ~60 seconds instead of ~150 ms: the `onClosing` override in `HyperHdrJsonApiEvents` is missing. OkHttp's internal CANCEL_AFTER_CLOSE_MILLIS is 60 s; the override forces a graceful close immediately.
