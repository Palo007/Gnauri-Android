# CONTRACT — Native Audio Port (binding spec for all work packages)

> **Status:** authoritative. If source code and this contract disagree during implementation,
> STOP and report; do not improvise. Companion docs:
> `ANALYSIS_background-stop_and_native-audio-refactor.md` (evidence),
> `REVIEW_background-stop_and_native-audio-refactor.md` (risks R1-R8, corrections).
> Scope decision (final): native engine replaces WebAudio **completely** — no JS fallback.

## 1. Target package & layout

Application namespace and all Kotlin: `com.aistudio.gnauri.bvpmlc`.
Pure DSP core: `com.aistudio.gnauri.bvpmlc.synth` — **zero `android.*` imports** (JVM-testable).

```
app/src/main/java/com/aistudio/gnauri/bvpmlc/
  MainActivity.kt            (modified)
  WebViewManager.kt          (modified)
  MediaForegroundService.kt  (rewritten)
  NativeAudioEngine.kt       (new — AudioTrack driver, Android-aware)
  WavRegistry.kt             (new — name→Uri capture + decode + resample)
  PlaybackActions.kt         (new — centralized constants, if any remain needed)
  ui/theme/                  (moved, package updated)
  synth/
    ScheduleModel.kt  Segments.kt  Oscillator.kt  Noise.kt  Biquad.kt
    IsoGate.kt  SynthEngine.kt
```

## 2. Schedule JSON (JS → Kotlin, Moshi reflection parse)

Exactly `JSON.stringify(sched)` of the object built by `parseGnaural` (index.html:589-670):

```json
{ "title": "...", "author": "...", "desc": "...",
  "voices": [ { "index":0, "type":0, "desc":"...", "file":"", "muted":false,
                "visible":true, "mono":false,
                "points":[ {"duration":600,"beat":10,"base":200,"volL":0.8,"volR":0.8,"state":1} ] } ],
  "totalTime": 1800, "ovL": 1, "ovR": 1, "loops": 0, "stereoswap": false }
```
Types: 0 binaural, 1 pink, 2 audio-file, 3 isochronic, 4 gapped isochronic, 5 white, 6 brown,
unknown → pink-noise fallback (matching index.html:1002-1006). `loops`: 0 = infinite.
`volL/volR` are already normalized (0..1) by the JS parser. Unknown/missing fields must not crash
(use defaults: muted=false, mono=false, state=1).

## 3. Pure core API — `synth.SynthEngine`

```kotlin
class SynthEngine(val sampleRate: Int) {
  fun setSchedule(s: Schedule)                 // rebuild segments; if playing: hot-apply (see 5.6)
  fun play(offsetSec: Double)                  // offsetSec < 0 ⇒ resume from current cursor
  fun pause()                                  // keep cursor & played count
  fun stopReset()                              // cursor=0, played=0
  fun seekElapsed(sec: Double)                 // full-timeline secs → (played, cursor); clamp
  fun render(out: FloatArray, frames: Int)     // interleaved stereo float; ONLY hot path; no alloc
  fun setMasterVolume(v: Double)               // master = v * ((ovL+ovR)/2)   [index.html:872-876]
  fun setChannelGains(l: Double, r: Double)    // clamp [0.0, 1.5], applied post-master, no ramp
  fun setFadeOut30s(b: Boolean)                // default true                 [index.html:750]
  fun setAudioBuffer(name: String, mono: FloatArray)  // pre-resampled to sampleRate, channel-0 only
  fun clearAudioBuffers()
  fun hasBuffer(name: String): Boolean         // lookup normName(name), then name+".wav" [index.html:810-814]
  val isPlaying: Boolean
  val elapsedSeconds: Double                   // played*totalTime + cursor — for ALL loop modes
                                               // (fixes the JS infinite-loop display bug; do NOT port it)
  val played: Int
  val loopsLeft: Double                        // Double.POSITIVE_INFINITY when loops==0
}
```
`normName(s)`: lowercase, strip path, trim — port of index.html:804.

## 4. DSP semantics (parity-critical; port sources in parentheses)

1. **Segments**: `buildSegments` (673-680) points→absolute `{t0,t1,beat,base,volL,volR}`;
   `sampleVoice` (682-702) linear-interp toward next segment's start values. Negative-duration
   points were already filtered by the JS parser.
2. **Binaural (0)**: two sines, `fL = base − beat/2`, `fR = base + beat/2`; per-oscillator
   **phase accumulator** (`phase += 2π·f/sr`; never recompute phase from t). `mono=true` ⇒ both
   tones summed into both output channels; else hard L/R (swap per `stereoswap`). (1086-1112)
3. **Isochronic (3/4)**: one sine carrier `f = max(1, base)`, identical signal to both ears,
   multiplied by gate `g(t)`: cycle `1/beat`; type 3 smooth on/off, type 4 gapped burst with
   0.66 duty; attack/release ramp `min(0.004, period·0.1)` s; `beat ≤ 0.01` ⇒ gate held open.
   The 200 000-event guard is NOT ported (WebAudio artifact). (1052-1084, 1206-1242)
4. **Noise (1/5/6/unknown)**: stateful per-sample generators, no buffers/caches —
   white = LCG (port `_generateWhiteBuffer` 896), brown = leaky random walk (911),
   pink = Paul Kellet (`_noiseBuffer`), unknown type ⇒ pink. Pink additionally passes through an
   **RBJ bandpass biquad, Q=0.7**, centre `max(20, base(t))`, ONLY if any segment has `base > 1`;
   recompute coefficients when centre changes ≥ 1 Hz (per render block is fine). Amplitude
   scaling must match the JS generators. Sample-exact parity NOT required (stochastic).
5. **Audio-file (2)**: mono source = **channel 0 of the WAV only** (WebAudio "discrete"
   downmix to 1ch keeps ch0 — index.html:983-986); both ears get the same mono stream scaled by
   `volL(t)`/`volR(t)`; `stereoswap` swaps ears. Buffer loops (`pos = tInstance % bufDur`) and
   **restarts from 0 at every play()/seek/loop-instance start** (JS `src.start(t0)` with no
   offset — 991). Voice with no buffer ⇒ silent skip.
6. **Master chain**: per-voice L/R vol from `sampleVoice` → sum → master gain
   `uiVol·((ovL+ovR)/2)` → per-channel trims (clamp 0..1.5) → out. Muted voices skipped.
7. **Loop & crossfade**: at a loop seam render BOTH the outgoing tail and the incoming start for
   `CROSSFADE = 0.18` s, mixed with equal-power windows (sin/cos quarter-wave — 792-796). The
   incoming instance resets oscillator phases, noise state, WAV position (fresh instance
   semantics, matching the JS node rebuild). `loops = N` ⇒ N passes total; 0 ⇒ infinite.
8. **Stop/pause fade**: `STOP_FADE = 0.12` s linear-to-zero ramp on pause/stopReset/seek/hot-apply;
   the engine keeps rendering the fading tail while starting the new state (seek/hot-apply) or
   until silent (pause/stop).
9. **fadeOut30s**: when enabled and on the FINAL loop pass, ramp the whole mix to 0 over the last
   `min(30, totalTime)` s. (1141-1164)
10. **End of schedule** (finite loops exhausted): engine stops, `isPlaying=false`, cursor=totalTime.
11. Output must never contain NaN/Inf; clamp final samples to [-1, 1].

## 5. Bridge — the ONLY JS↔Kotlin surface

### 5.1 JS → Kotlin: `window.NativeAudio` (@JavascriptInterface on the WebView)
```
loadSchedule(json: String): String   // replace schedule. Returns {"missing":["a.wav",...]} =
                                     // type-2 descs with no decodable WAV. If playing: hot-apply (5.6).
play(offsetSec: Double)              // <0 ⇒ resume from cursor
pause(); stopReset(); seekElapsed(sec: Double)
setMasterVolume(v: Double); setChannelGains(l: Double, r: Double)
setFadeOut30s(b: Boolean)
hasWav(name: String): Boolean        // for checkMissingAudio (index.html:2177-2185)
clearWavs()                          // replaces engine.clearAudioBuffers() in ingestFiles (2155)
isAvailable(): Boolean               // true
getLastSession(): String?            // returns combined JSON of last schedule, volume, balance, etc.
setFilename(name: String)            // record the filename for session persistence
```
### 5.2 Kotlin → JS (evaluateJavascript, main handler, ~4 Hz while playing + on every state change)
```
window.NativeAudioEvents.onTick(elapsedSec, played, loopsLeft)   // loopsLeft: -1 = infinite
window.NativeAudioEvents.onState(isPlaying)
```
JS guards: both are no-ops if `NativeAudioEvents` is undefined (page reloading).
### 5.3 Retained legacy bridge: `AndroidBridge.exportFile(xml, filename)` ONLY.
`AndroidBridge.playbackStateChanged` is DELETED (Kotlin and JS sides) — the service now owns
playback state natively. The `UPDATE_PLAYBACK_STATE_ACTION` / `ACTION_*_FROM_SERVICE` broadcasts
and the Activity-side receiver (MainActivity.kt:134-166) are DELETED (fixes R2).
### 5.4 MediaSession callbacks (in the service) call the engine DIRECTLY:
onPlay→play(-1), onPause→pause(), onStop→stopReset(), onSeekTo(ms)→seekElapsed(ms/1000).
Notification/MediaSession position & duration come from the native engine
(duration = totalTime·loops, or totalTime for infinite).
### 5.5 WAV bytes path: JS never sends bytes. `MainActivity` file-picker callback
(currently :108-117) records every picked `*.wav` URI in `WavRegistry`
(`normName(DISPLAY_NAME) → Uri`, best-effort `takePersistableUriPermission`). Kotlin decodes
lazily on `loadSchedule` (RIFF-PCM parser: int 8/16/24/32 + float32, mono/stereo, any rate;
fallback MediaExtractor/MediaCodec; linear resample to engine rate; keep channel 0)
and pushes into `SynthEngine.setAudioBuffer`.
### 5.6 Hot-apply (edit-while-playing contract, replaces `restartIfPlaying`, index.html:1863):
`loadSchedule` while playing ⇒ capture `elapsedSeconds`, STOP_FADE the old state, resume the new
schedule at the same elapsed position (clamped). While stopped ⇒ just replace, keep cursor clamped.

## 6. Session Persistence

The Android app automatically persists the last played session to `SharedPreferences` to allow seamless restoration on startup.

1. **Saved Data**:
   - `last_schedule_json`: The full schedule object (mutes, loops, points).
   - `wav_uris`: Map of `normName` to content URIs for external audio files.
   - `master_volume`: Master output gain (0.0–1.5).
   - `fade_out_30s`: The 30s fade toggle state.
   - `ch_l` / `ch_r`: Per-channel output trims (stereo balance).
   - `current_filename`: Name of the loaded file.
2. **Implementation**:
   - `SessionManager`: Handles `SharedPreferences` I/O with synchronous `commit()` for reliability.
   - `WebViewManager`: Bridge ensures it populates its internal `@Volatile` state from storage via `ensureLoaded()` before allowing any UI-triggered saves.
   - `MainActivity`: Restores WAV URIs on startup using `SessionManager.restoreWavs(this)`.

## 7. JS-side changes (WP2) — surgical rules

- **Keep** `parseGnaural`, `buildSegments`, `sampleVoice` in JS — the UI live-readout
  (index.html:2008-2015) and Graph still use them.
- Replace `class GnauriEngine` internals with a **proxy of the same public surface** used by UI
  code (`running`, `startOffset`, `_played`, `loopsLeft`, `_elapsedSeconds()`, `currentTime()`,
  `setSchedule`, `play`, `stop`, `setMasterVolume`, `setChannelGains`, `setAudioBuffer`→
  `NativeAudio` registry no-op, `_lookupBuffer`→`NativeAudio.hasWav`, `clearAudioBuffers`→
  `clearWavs`, `fadeOut30s` property → setter call, `onTick` callback) so transport/UI call sites
  stay untouched wherever possible. State (`running`, elapsed, played, loopsLeft) is a mirror
  updated from `NativeAudioEvents`.
- `_elapsedSeconds()` proxy returns the mirrored native elapsed (bug thereby fixed UI-wide).
- Seek handler (2024-2044): compute `targetElapsed` as today, then single call
  `NativeAudio.seekElapsed(targetElapsed)`; delete `_played/loopsLeft` juggling.
- `ingestFiles` (2143-2174): delete `decodeAudioData` block; WAV names are only checked via
  `hasWav`; keep the status messaging and `checkMissingAudio` logic.
- DELETE: all WebAudio node code, `_scheduleParam`, `_scheduleIsoGate`, `_xfadeCurve`,
  `_teardownInstance`, `_ensureCtx`, noise buffer generators & caches, crossfade/boundary timer
  logic, `ReliableTimer` + `TimerWorkerBlob` (516-555), `window.AndroidPlayerControl`
  (1982-1994), `AndroidBridge.playbackStateChanged` calls (1979, 1286 region).
- `updateTransportUI` keeps only its UI work.
- The app is Android-WebView-only now, but keep changes minimal elsewhere; do not restructure UI.

## 7. Unit tests (WP4) — `app/src/test/java/com/aistudio/gnauri/bvpmlc/synth/`

Plain JUnit4, no Robolectric. Load real schedules from `app/src/main/assets/schedules/` via
plain XML parsing fixtures OR hand-built `Schedule` objects (do not port the full parser).
1. Binaural zero-crossing freq: base=200, beat=10 const → ≈195/205 Hz ±0.5 per ear over 10 s.
2. No click on base sweep: max |x[n]−x[n−1]| bounded (< 0.05) during 100→400 Hz ramp.
3. Gain envelope tracks `sampleVoice` within 1e-3 at random t (tone voice, volume ramp).
4. Loop-seam continuity: binaural 10 s schedule, loops=3 — no adjacent-sample jump > 0.05
   across seams; equal-power sum ≈ constant RMS through the seam (±1.5 dB).
5. Isochronic: pulse rate == beat (count gate openings); type 4 duty ≈ 0.66 ±0.05;
   beat=0 ⇒ constant carrier.
6. Full render of ≥ 3 representative schedules (binaural+noise mix): no NaN/Inf/|x|>1.
7. `elapsedSeconds`/`played`/`loopsLeft` correct across seams for loops=2 and loops=0.
8. stopReset vs pause semantics; seekElapsed mapping incl. loops.
9. hasBuffer/normName lookup incl. the `name+".wav"` fallback.
10. Type-2: buffer loops and restarts per instance; ch0-only; volL/volR applied.

## 8. Android layer rules (WP3)

- `NativeAudioEngine`: render thread `THREAD_PRIORITY_URGENT_AUDIO`; AudioTrack MODE_STREAM,
  ENCODING_PCM_FLOAT, stereo, `getNativeOutputSampleRate(STREAM_MUSIC)`, buffer ≥ 4× min;
  one preallocated FloatArray; `write(..., WRITE_BLOCKING)`; underruns logged.
- Audio focus: `AudioFocusRequest` (API≥26) + legacy fallback (24/25); pause on transient+full
  loss (no duck), resume only on GAIN after transient. `ACTION_AUDIO_BECOMING_NOISY` ⇒ pause.
- **R1**: `startForeground` when a session starts; stay foreground until `stopReset` or service
  destroy — NO `STOP_FOREGROUND_DETACH` on pause; try/catch `ForegroundServiceStartNotAllowedException`.
- Wake lock: acquire on play, release on pause/stop (as today).
- Keep framework `MediaSession` (no Media3 migration — R8).
- DELETE from the old service: silent AudioTrack, 10 s heartbeat, `resumeTimers/onResume` pings.
- DELETE from MainActivity: `onPause/onStop → webView.onResume()` hack, `FLAG_KEEP_SCREEN_ON`,
  and the broadcast receiver in `WebApp` (5.3). Keep the battery-optimization request.
- WebView ↔ service coupling: the service must reach the WebView only through a single
  `NativeAudioEvents` pusher util; guard for null WebView (activity may be gone) — engine must
  keep playing regardless.

## 9. Build changes (WP0)

- `namespace = "com.aistudio.gnauri.bvpmlc"`; move sources; update manifest theme refs if needed
  (`.MainActivity` stays relative). `applicationId` unchanged.
- `WebViewManager.kt:42` → `setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)`
  (still useful pre-cutover; harmless after).
- Delete: `ExampleUnitTest.kt`, `ExampleRobolectricTest.kt`, `GreetingScreenshotTest.kt`,
  `ExampleInstrumentedTest.kt`.
- Remove plugins: ksp, roborazzi, secrets, google-services (+ their config blocks and the
  `MissingGoogleServicesStrategy` import). Remove deps: room (all + ksp compiler), retrofit,
  converter-moshi, okhttp, logging-interceptor, firebase (bom/ai/appcheck), moshi codegen,
  explicit coroutines artifacts, all robolectric/roborazzi/espresso/androidx-test artifacts.
  **KEEP**: `moshi-kotlin` (used by ScheduleModel), compose stack, androidx-webkit, core-ktx,
  activity-compose, lifecycle artifacts, `junit` (test). Prune matching `libs.versions.toml`
  entries; leave no dangling alias references.

## 10. Verification gates

- After WP0/WP1/WP4: user runs `gradlew :app:testDebugUnitTest` locally (sandbox has no JDK17/SDK).
- Sandbox checks: `node --check` on extracted index.html JS; grep cross-check that every
  `NativeAudio.*` call in JS exists in the Kotlin interface and vice versa; no `android.` import
  under `synth/`; no leftover references to deleted symbols.
- Final local checklist is delivered as `docs/BUILD_VERIFY_native-audio.md`.
