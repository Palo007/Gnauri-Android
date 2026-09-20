# BUILD_VERIFY — Native Audio Port

Local verification checklist for the two integration fixes (master-volume double
application; first-launch schedule race) and a general regression pass over the native
audio port. Written for manual execution in Android Studio — this sandbox cannot compile
or run the app. See `docs/CONTRACT_native-audio.md` (§3, §5) for the binding behavior spec.

## 1. Build gates

Run from the project root (Android Studio Terminal, or `gradlew.bat` on Windows):

- `gradlew :app:testDebugUnitTest` — the synth engine unit test suite must pass in full.
- `gradlew :app:assembleDebug` — debug APK must build clean.

JS note: `app/src/main/assets/index.html`'s embedded script was `node --check` verified
during the port. If Node is available locally, re-check after the master-volume edit:

```
node -e "const fs=require('fs');const html=fs.readFileSync('app/src/main/assets/index.html','utf8');const m=html.match(/<script>([\s\S]*?)<\/script>/);require('fs').writeFileSync('_extracted.js', m[1]);" 
node --check _extracted.js
```

(Adjust the extraction if there are multiple `<script>` blocks — grab the one containing
`_pushSettings`.)

## 2. Smoke test on device

Install the debug build and exercise the UI:

- Default schedule ("Wind and Waves") auto-loads on first open.
- Press play: binaural beat + background noise audible together.
- Pause / resume from the in-app UI, and from the media notification — both must control
  the same playback.
- Stop resets the playhead to 0:00.
- Seek slider works both while playing and while stopped.
- Loop-count UI reflects and controls the schedule's repeat count.
- L/R balance control audibly shifts channel trim.
- Master volume slider: verify overall loudness is sane at various positions — a `v·ov²`
  double-volume bug was just fixed here (FIX 1), so specifically listen for a correct,
  linear-feeling level across the slider range rather than an overly-quiet or squared-off
  curve.
- Fade-out toggle behaves as expected near the end of a pass.
- Mute/solo voices while playing: hot-applies and restarts audio at the same playback
  position (no audible jump/reset).
- Mass-edit / point-edit / scale-time while playing: schedule edits hot-apply without
  stopping playback.

## 3. WAV (type-2) voices

None of the bundled schedules use type-2 (audio-file) voices, so this needs a manual
schedule:

- Export a schedule from the app, or take any `.gnaural` file, and edit/add a voice with
  type 2 (audio-file) referencing a `.wav` by name.
- Load that `.gnaural` together with its `.wav` via the file picker (📂) — pick BOTH files
  in one selection.
- Verify the WAV voice is audible during playback.
- Then reload the same `.gnaural` WITHOUT picking the `.wav` — verify the missing-WAV
  warning banner appears (contract §5.1 `loadSchedule` `missing` report) and that voice is
  silent rather than crashing anything.

## 4. Background soak (the original bug)

- Start playback, turn the screen off, leave the device unplugged, and let it run
  uninterrupted for at least 90 minutes (ideally overnight with `loops = 0` / infinite).
- Audio must remain continuous for the entire soak — no dropouts, stutters, or silent
  gaps.
- Monitor for kills/underruns during and after the soak:

```
adb logcat | grep -i "underrun\|kill\|lmk\|NativeAudio"
```

## 5. Lifecycle behavior

- Swipe the app away from Recents while playing: audio must CONTINUE uninterrupted (the
  native engine lives in the foreground service, independent of the Activity), and the
  media notification's play/pause/stop controls must keep working.
- Audio focus: start another audio app (e.g. music/podcast player) while Gnauri is
  playing — Gnauri must pause (no ducking, per contract §8).
- Unplug wired headphones (or disconnect a Bluetooth audio device) while playing — Gnauri
  must pause (`ACTION_AUDIO_BECOMING_NOISY`).

## 6. First-launch race (regression test for FIX 2)

This targets the fix in `WebViewManager.kt` / `MediaForegroundService.kt`.

- Fresh install (or Settings → Apps → Gnauri → Storage → Clear Data) on a device/emulator
  running API 33+.
- Launch the app. The notification-permission prompt appears; the WebView will have
  already auto-loaded the default schedule by the time you respond.
- Grant the notification permission AFTER you can see the schedule has visibly loaded in
  the UI (graph populated, title shown).
- Press play — it MUST produce sound immediately. (Before the fix, the schedule sent
  during the race window was silently dropped, and playback would be silent until a
  schedule reload — e.g. switching schedules and back.)

## 7. Session Persistence

- Load a schedule, mute a voice, set volume to 50%, set balance to leaning-left 25%.
- Swipe the app away from Recents (stop the process).
- Restart the app.
- **Verification**: The schedule, muted voice, volume level, and balance must all be restored
  automatically without manual loading.
- Repeat the test by picking an external `.gnaural` and `.wav` files. After restart, the
  external schedule must still load and the WAVs must still be audible.

## 8. Known behavior notes (documented, not bugs)

- Pause → resume resets oscillator phase / noise state / WAV playback position back to
  the cursor position (matches the original JS engine's teardown-on-pause behavior); this
  is not a continuous-phase resume.
- The UI playhead updates at roughly 4 Hz (the native engine's tick rate), not
  continuously — expect small visible steps rather than a smooth sweep.
- The `played` (loop count) counter is not incremented on the final pass when a
  finite-loop schedule (`loops > 0`) is exhausted — this is a display-only nuance, not a
  playback-affecting bug.
