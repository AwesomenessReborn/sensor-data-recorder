# Bug: Worker Stuck on "Syncing clocks with ..." Screen

**Date observed:** 2026-03-31
**Branch:** feature/bluetooth-sync
**Affected role:** Worker
**Symptom:** Worker UI is stuck displaying "Syncing clocks with Pixel 8a..." indefinitely, even after the controller has completed clock sync and shows READY state.

---

## Root Cause

The worker state machine has no mechanism to transition out of `SYNCING`. The controller is the only side that knows when clock sync is complete, but it never sends a "sync done" notification to the worker.

### Trace through the code

**Worker side (`BluetoothSyncService.kt`)**

1. `startListening()` → controller connects → `updateConnectionState(BtConnectionState.SYNCING)` is called explicitly at line 367.
2. `startReaderLoop(peer)` starts. Worker handles `Ping` messages by replying with `Pong`. That is the full extent of the worker's involvement.
3. **There is no code path that ever transitions the worker out of `SYNCING`.** The worker simply waits for `CMD_START`.

**Controller side**

1. After connecting to the worker, `runClockSync(peer)` runs 10 ping-pong rounds.
2. On completion, `peer.isSyncing = false`, `peer.clockOffsetResult = result`, and `updateConnectionState()` is called → controller transitions to `READY`.
3. **The controller never sends any message to inform the worker that sync is done.**

**UI side (`MainActivity.kt`, line 455)**

```kotlin
state.role == DeviceRole.WORKER && !state.isRecording &&
state.btState in listOf(IDLE, CONNECTING, SYNCING) -> ListenerWaitingScreen(...)
```

The worker is shown `ListenerWaitingScreen` whenever its state is `IDLE`, `CONNECTING`, or `SYNCING`. Since the state never leaves `SYNCING` after the controller connects, the worker is permanently stuck here until `CMD_START` arrives (which requires the controller user to press START RECORDING) — at which point the worker jumps directly from `SYNCING` → `RECORDING` without ever showing READY.

### Timeline confirmation from logs

| Time | Event |
|------|-------|
| 15:59:47 | Worker: controller connected, state set to `SYNCING` |
| 16:01:38 | Controller: BT socket connected |
| 16:01:38–16:01:40 | Controller: runs 10 ping-pong rounds |
| 16:01:40 | Controller: `Clock sync done`, transitions to `READY` |
| (never) | Worker: no transition out of `SYNCING` |

The worker logs show no state transition after the initial `SYNCING` — only silence.

---

## Impact

- The worker shows a spinner indefinitely after connection, giving no feedback that sync succeeded.
- The worker cannot display its READY screen (where it could show sync quality info).
- The worker transitions directly `SYNCING → RECORDING` when START is pressed, bypassing READY entirely.

---

## Proposed Solutions

### Option A — Add a `SyncDone` message (recommended)

Add a new `SyncDone` message type that the controller sends to the worker after `runClockSync` completes successfully. The worker handles it by transitioning to `READY`.

**Changes required:**

1. **`SyncMessage.kt`** — add:
   ```kotlin
   object SyncDone : SyncMessage() {
       override fun toJson() = JSONObject().apply { put("type", "SYNC_DONE") }.toString()
   }
   ```
   And in `fromJson`:
   ```kotlin
   "SYNC_DONE" -> SyncDone
   ```

2. **`BluetoothSyncService.kt`, `runClockSync()`** — after `updateConnectionState()` at the end of a successful sync, add:
   ```kotlin
   sendLineToPeer(peer, SyncMessage.SyncDone.toJson())
   ```

3. **`BluetoothSyncService.kt`, `handleMessage()`** — add case:
   ```kotlin
   is SyncMessage.SyncDone -> {
       updateConnectionState(BtConnectionState.READY)
   }
   ```

This is the minimal, correct fix. The worker only transitions to READY when the controller explicitly confirms sync is done, keeping both sides in sync about state.

### Option B — Infer READY from receiving N pings

The worker could count incoming PING messages and assume sync is complete after 10. This is brittle (the round count could change, or rounds can time out) and creates a hidden coupling between constants on both sides. Not recommended.

### Option C — Worker transitions to READY on first CMD_START/CMD_STOP

Keep the current behavior but update the UI condition so READY is not required before RECORDING is shown. This hides the bug rather than fixing it, and the worker READY screen never gets shown. Not recommended.

---

## Notes

- The clock offset is computed entirely on the controller. The worker never stores a clock offset — it simply waits to be commanded. So `SyncDone` does not need to carry any payload.
- If `runClockSync` fails (result == null), a `SyncDone` should NOT be sent, and the worker should remain in `SYNCING` state (or optionally receive a `SyncFailed` message to show an error).
- Consider also sending `SyncDone` when the controller re-syncs clocks manually (the `ACTION_SYNC_CLOCKS` path) so the worker re-enters READY after a manual re-sync.
