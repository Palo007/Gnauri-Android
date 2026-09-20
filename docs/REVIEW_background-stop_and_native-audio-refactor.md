# Review of `ANALYSIS_background-stop_and_native-audio-refactor.md`

> **Reviewer:** lead-dev review pass, verified against the working tree on 2026-07-06.
> **Companion to:** `docs/ANALYSIS_background-stop_and_native-audio-refactor.md` (left unmodified).
> **Contents:** (1) verification results, (2) corrections, (3) resolved open questions,
> (4) new risks the analysis missed, (5) an enhanced, agent-executable implementation plan
> with exact edits, interface specs, acceptance criteria, and stop-and-ask triggers.

---

## 1. Review verdict

The analysis is **accurate and well-evidenced**. Every cited file path and line number was
re-checked against the current tree and **all of them still hold** (no drift). The diagnosis
(WebView renderer is the unprotected audio producer; `waivedWhenNotVisible=true` is the concrete
trigger), the scope decision (native audio only, WebView UI stays), the effort estimate, and the
sequencing are all endorsed. The corrections below are refinements, not reversals.

### Claims re-verified (spot-check log)
| Claim | Verified against | Status |
|---|---|---|
| `setRendererPriorityPolicy(RENDERER_PRIORITY_IMPORTANT, true)` | `WebViewManager.kt:41-43` | ✅ exact |
| Heartbeat 10 s, `resumeTimers()`+`onResume()`+`ctx.resume()` inside renderer | `MediaForegroundService.kt:103-138` | ✅ exact |
| Silent `AudioTrack` keep-alive | `MediaForegroundService.kt:36-101` | ✅ exact |
| `onPause`/`onStop` call `webView.onResume()` | `MainActivity.kt:83-91` | ✅ exact |
| `FLAG_KEEP_SCREEN_ON` | `MainActivity.kt:48` | ✅ exact |
| Battery-optimization request (not verified granted) | `MainActivity.kt:50-62` | ✅ exact |
| Engine landmarks: `ReliableTimer` 530, `parseGnaural` 589, `buildSegments` 673, `sampleVoice` 682, `GnauriEngine` 744, `play()` 923, `_scheduleParam` 1181, `_scheduleIsoGate` 1206, 200 000-event guard 1216, `_onBoundary` 1278, `stop()` 1292, `Graph` 1325, engine instance 1789, bridge 1982-1994, `exportFile` call 2925, `buildGnauralXML` 2891 | `index.html` | ✅ all exact |
| `CROSSFADE=0.18`, `STOP_FADE=0.12`, noise caches capped 600 s / pink 60 s | `index.html:768-769, 1013, 1020, 1028` | ✅ exact |
| `_elapsedSeconds()` infinite-loop discrepancy | `index.html:781-788` | ✅ confirmed (loops>0 adds `_played*totalTime`, infinite path returns span-only `t`) |
| `restartIfPlaying()` hot-apply contract | `index.html:1863` + 8 call sites (1883, 1886, 1891, 1972, 2171, 2399, 2445, 2878) | ✅ confirmed |
| Line counts (index.html 2944; Kotlin 174/339/116) | `wc -l` | ✅ exact |
| Four boilerplate test files, zero real coverage | `app/src/test`, `app/src/androidTest` | ✅ confirmed |
| Namespace mismatch `com.example` vs `com.aistudio.gnauri.bvpmlc` | `build.gradle.kts:13,17` | ✅ confirmed |
| ~200 MB noise-buffer math | 600 s × 44 100 Hz × 4 B × 2 ch ≈ 212 MB | ✅ arithmetic checks out (worst case, schedules ≥ 599 s) |

---

## 2. Corrections & precision fixes to the analysis

1. **§1 fix applies only on API 26+.** `setRendererPriorityPolicy` is inside a
   `Build.VERSION.SDK_INT >= O` guard (`WebViewManager.kt:41`). On API 24-25 (minSdk is 24 —
   see §3) the call never runs; those devices typically run the WebView renderer in-process, so
   the bug class is different there. The fix is still correct; keep the existing guard.
2. **§2.5 golden-vector harness: "headless node/vitest" will not work as written.** Node has no
   Web Audio API. Practical options, in order of preference:
   (a) `OfflineAudioContext` in headless Chrome via Puppeteer/Playwright;
   (b) the `node-web-audio-api` npm package (Rust-backed, near-spec);
   (c) a temporary dev-only export button in the app itself that renders N seconds through an
   `OfflineAudioContext` and downloads the Float32 samples via the existing `exportFile` bridge.
   Option (c) is the cheapest and requires no new toolchain — recommended.
3. **Golden vectors are only meaningful for deterministic voices** (binaural type 0, isochronic
   3/4, and gain envelopes). White/brown/pink use random generators; parity there is statistical
   (spectrum/RMS), not sample-exact. The plan should say so explicitly to prevent an implementing
   agent from chasing impossible sample-exact noise parity.
4. **§2.2 omits one real DSP item:** the pink-noise path conditionally routes through a
   `BiquadFilterNode` bandpass (`Q=0.7`, freq automated from `s.base`) when any segment has
   `base>1` (`index.html` type-1 branch). A native port needs an RBJ/Audio-EQ-Cookbook biquad
   (the Web Audio spec uses those formulas). Small (~30 lines), but it is not "arithmetic in a
   mix loop" and must be on the port checklist.
5. **§3.4 nuance:** the heartbeat does more than probe — it calls `wv.resumeTimers()` and
   `wv.onResume()` from the service (`MediaForegroundService.kt:114-115`). The cleanup section
   already lists these; noting here so the §3.4 bridge inventory is complete.
6. **Pause vs. stop semantics must be preserved in the port.** `stopBtn` (index.html:1995) resets
   `startOffset/_played/_elapsedAccrued`; `AndroidPlayerControl.pause()` clicks `playBtn`
   (position preserved). The native command API needs distinct `pause()` and `stopReset()`.

---

## 3. Open questions — two of five now resolved from the tree

- **Q4 (SDK range): RESOLVED.** `minSdk = 24`, `targetSdk = 36`, `compileSdk = 36`
  (`app/build.gradle.kts:14-19`). Consequences: `AudioTrack.Builder` + `ENCODING_PCM_FLOAT`
  available everywhere (API 23+/21+); `MediaExtractor`/`MediaCodec` fine; `AudioFocusRequest`
  needs an API 26 guard with the legacy `requestAudioFocus` fallback for 24-25. Android 16
  (targetSdk 36) tightens JobScheduler quotas and FGS-type declarations but adds **no timeout for
  `mediaPlayback`-type FGS**; the manifest already declares `foregroundServiceType="mediaPlayback"`
  and `FOREGROUND_SERVICE_MEDIA_PLAYBACK` (`AndroidManifest.xml:8-9,35-38`), so the app is
  compliant. (Verified against current Android docs, July 2026 — sources at end.)
- **Q3 (how common are type-2 WAV voices): PARTIALLY RESOLVED.** None of the 14 bundled
  `.gnaural` schedules in `assets/schedules/` contains a type-2 voice (observed types: 0, 1, 3,
  4, 5, 6 only), and the APK bundles no WAV files. Type-2 is therefore **user-imported content
  only**. Phase 3 can be deprioritized: ship the native path with type-2 falling back to
  "unsupported in background / skipped with a visible notice" and implement decode later. This
  removes the analysis's risk #2 from the critical path.
- **Q1 (overnight vs. sub-hour), Q2 (exact parity)** — still product-owner decisions. Default
  assumptions for the plan below: overnight/infinite is the target; parity standard is
  "indistinguishable in structured A/B listening", not sample-exact.
- **Q5 (logcat confirmation)** — still recommended before Phase 1 (not before Phase 0; the
  one-line fix is safe and cheap regardless).

---

## 4. New findings and risks the analysis missed

**R1 — FGS demotion/promotion trap (Android 12+).** `updateNotification()` calls
`stopForeground(STOP_FOREGROUND_DETACH)` whenever `isPlaying` is false
(`MediaForegroundService.kt:323-325`) and re-calls `startForeground(...)` on every state flip
(`:317-321`). On API 31+, `startForeground()` from a service whose app is in the background can
throw `ForegroundServiceStartNotAllowedException`. Today the media-session exemption usually
covers notification-initiated resumes, but **programmatic** restarts (loop boundary after a
pause, scheduled resume) are not covered. Rule for the port: once a session starts, **stay
foregrounded until explicit user stop**; wrap every `startForeground` in try/catch; never
`DETACH` mid-session.

**R2 — Notification controls silently die with the Activity.** The receiver for
`ACTION_PLAY/PAUSE/SEEK_FROM_SERVICE` is registered inside a Composable `DisposableEffect`
(`MainActivity.kt:134-166`). If the Activity is destroyed (swipe from recents, process
trim), media-session callbacks broadcast into the void while the service and notification live
on. This is an existing defect and a hard requirement for Phase 4: **MediaSession callbacks must
call the native engine directly in-process**, with no Activity or broadcast in the path.

**R3 — Dual-engine hazard during migration.** Between Phases 2 and 4 both engines exist. Without
an explicit switch the app can render audio twice (WebAudio + AudioTrack). Introduce a single
feature flag (`USE_NATIVE_AUDIO`, see Phase 4) checked in **one** place on the JS side, and make
the JS engine's `play()` a no-op when the flag is on.

**R4 — Dependency bloat (extends §2.4 cleanup).** `build.gradle.kts` declares Room (+KSP
compiler), Retrofit + Moshi converter, OkHttp + logging-interceptor, Firebase AI + AppCheck
(+ `google-services` plugin), and the secrets plugin. `grep` over `app/src/main/java` finds **zero
imports of any of them**. They add APK size, build time, and Play data-safety surface. Prune in
Phase 5. Exception: **Moshi may be kept deliberately** — Phase 1 below uses it for schedule JSON
parsing since it is already declared.

**R5 — Type-2 data path was undesigned.** The analysis says "you own decoding" but not how native
gets the bytes: WAVs arrive via the WebView file chooser as SAF URIs
(`WebViewManager.onShowFileChooser` → `filePickerLauncher`, `MainActivity.kt:108-117`), and today
only JS reads them. Design (Phase 3): capture `displayName → Uri` in Kotlin at pick time, decode
natively via `MediaExtractor`/`MediaCodec`; JS sends only the voice's filename. Avoid base64
bridging of megabyte payloads. Note: URIs from `OpenMultipleDocuments` are not persistable across
restarts unless `takePersistableUriPermission` is called — handle the "schedule references a WAV
from a previous session" case by prompting a re-pick.

**R6 — Sample-rate strategy unstated.** JS `AudioContext` runs at the device rate; golden vectors
must be rendered at a **fixed rate (44 100)** while the runtime engine renders at the device's
native rate. The pure core must therefore take `sampleRate` as a parameter, and all DSP must be
rate-independent (phase accumulators and time-based envelopes already are; the biquad recomputes
coefficients from rate).

**R7 — Settings state lives in JS `localStorage`.** L/R balance trim (index.html:1910-1967) and
the fadeOut30s toggle are JS-side state pushed into the engine. After the port, JS remains the
owner and must push these to native on startup and on change — the bridge spec in Phase 4
includes `setChannelGains` / `setFadeOut30s` / `setMasterVolume` for exactly this reason. Missing
any of them causes silent behavioral regressions that no unit test will catch.

**R8 — `MediaSession` (framework) vs `MediaSessionCompat`/Media3.** The service uses the
framework `android.media.session.MediaSession` directly — workable, but if any rewrite of the
service happens in Phase 2, prefer keeping the existing framework API rather than migrating to
Media3 in the same change. One migration at a time.

---

## 5. Enhanced implementation plan (agent-executable)

> **Ground rules for the implementing agent**
> - Execute phases **in order**; each phase must build (`gradlew :app:assembleDebug`) and pass
>   `gradlew :app:testDebugUnitTest` before starting the next.
> - One phase = one commit (or PR). Never combine the namespace rename with anything else.
> - Do **not** edit `docs/ANALYSIS_background-stop_and_native-audio-refactor.md`.
> - Do not touch UI/editor JS (Graph, Mass-Edit, dialogs, undo, export) in any phase.
> - **Stop and ask** if: a cited line no longer matches; a test you didn't write fails; you need
>   a new dependency not listed here; or observed JS behavior contradicts this document.

### Phase 0 — the one-line fix (ship immediately, independent of everything else)
1. `app/src/main/java/com/example/WebViewManager.kt:42`:
   change `setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)`
   to `setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)`.
   Keep the surrounding `SDK_INT >= O` guard. Add a comment: `// false: do NOT waive renderer
   priority when invisible — the renderer synthesizes all audio (see docs/REVIEW_...md)`.
2. Optional (small, safe): in `MainActivity.onCreate`, log whether
   `isIgnoringBatteryOptimizations` is granted so soak-test logs capture it.
3. **Acceptance:** debug build installs; play any bundled schedule, screen off, unplugged,
   ≥ 90 min; audio continuous; `adb logcat` shows no renderer kill. Bump `versionCode/Name` only
   if releasing.

### Phase 1 — pure Kotlin synth core (no Android imports)
Create package `com.example.synth` (do **not** rename the namespace now). All classes must
compile with zero `android.*` imports; tests are plain JUnit on the JVM.

**Files and porting sources (line refs = current `index.html`):**
- `ScheduleModel.kt` — data classes mirroring §3.5 of the analysis exactly
  (`Schedule`, `Voice`, `Point`); parse from JSON with Moshi (already a dependency). Include
  `norm()` volume rescale semantics (values > 1.0001 divided by 100).
- `Segments.kt` — port `buildSegments` (673-680) and `sampleVoice` (682-702). Keep the
  ramp-toward-next-segment linear interpolation semantics identical.
- `Oscillator.kt` — sine with a **per-instance phase accumulator**:
  `phase += 2π·f(t)/sampleRate` per sample; never recompute phase from `t`.
- `Noise.kt` — stateful per-sample generators: white (LCG, port `_generateWhiteBuffer` 896),
  brown (leaky random walk, port 911), pink (Paul Kellet, port `_noiseBuffer`). No buffers, no
  caching. Sample-exact parity with JS is **not** required (stochastic); match algorithm and
  amplitude scaling.
- `Biquad.kt` — RBJ bandpass (Audio EQ Cookbook), `Q=0.7`, coefficient recompute on frequency
  change; applied to pink noise only when the voice has any segment with `base > 1`, centre
  frequency `max(20, base)` followed per-block from `sampleVoice`.
- `IsoGate.kt` — port `_scheduleIsoGate` (1206-1242) as a **time-domain gate function
  `gate(t): Float`**, not an event list: cycle `1/rate`; smooth (type 3) vs gapped 0.66-duty
  burst (type 4); attack/release ramp `min(0.004, period·0.1)` s; `rate ≤ 0.01` ⇒ gate open.
  The 200 000-event guard is intentionally **not** ported (it exists only for WebAudio event
  scheduling).
- `SynthEngine.kt` — the block renderer:
  ```kotlin
  class SynthEngine(val sampleRate: Int) {
    fun setSchedule(s: Schedule)          // rebuilds segment cache; hot-apply entry point
    fun play(offsetSec: Double)           // sets time cursor, running=true
    fun pause()                           // running=false, keep cursor (pause semantics)
    fun stopReset()                       // cursor=0, played=0 (stopBtn semantics)
    fun seek(elapsedSec: Double)          // full-timeline seconds, maps to (played, cursor)
    fun render(out: FloatArray, frames: Int)  // interleaved stereo; the ONLY hot path
    // state pushed from JS:
    fun setMasterVolume(v: Double)        // master = v * ((ovL+ovR)/2), port of index.html:872-876
    fun setChannelGains(l: Double, r: Double) // clamp [0,1.5]
    fun setFadeOut30s(b: Boolean)
    fun setLoopsOverride(n: Int?)         // null = use schedule.loops (0 = infinite)
    val elapsedSeconds: Double            // played*totalTime + cursor — fix the infinite-loop
                                          // discrepancy (analysis §3.3); do NOT port the bug
  }
  ```
  Behaviors to replicate: loop as `t = cursor` with equal-power crossfade window over the last/first
  `0.18` s of the seam (render both loop tails and mix — no timers, no node teardown);
  `STOP_FADE = 0.12` s ramp on pause/stop/seek; `fadeOut30s` over the last `min(30,total)` s of the
  **final** loop only; `stereoswap` swaps output channels; `mono` (binaural) sums both tones into
  both channels; voice `muted` ⇒ skip.
- **Hot-apply contract:** `setSchedule` while playing must behave like JS `restartIfPlaying()`
  (index.html:1863): capture current offset, stop-fade, resume at same offset with new schedule.

**Tests (`app/src/test/java/com/example/synth/`):**
1. Binaural instantaneous frequency: render with `base=200, beat=10` constant; zero-crossing
   count over 10 s ≈ 195 Hz (L) / 205 Hz (R) ± 0.5.
2. Frequency ramp continuity: adjacent-sample delta bounded during a `base` sweep (no phase
   jump ⇒ no click).
3. Gain envelope matches `sampleVoice` at randomized `t` within 1e-3.
4. Loop-seam continuity: |x[n]−x[n−1]| below click threshold across the seam for a binaural
   schedule.
5. Isochronic pulse rate equals `beat`; type 4 duty ≈ 0.66; `rate ≤ 0.01` ⇒ constant gate.
6. No NaN/inf/|x|>1 over a full render of every bundled schedule (parse the real
   `assets/schedules/*.gnaural` files — port `parseGnaural` fallbacks only as far as these 14
   files and the analysis §3.6 description require, or parse in JS and feed JSON fixtures).
7. Allocation discipline: `render()` in a loop; assert steady-state throughput (coarse timing
   test) and, by code review, no allocations in the render path.
8. `elapsedSeconds` correct across loop boundaries for finite and infinite loops.

**Acceptance:** all tests green via `gradlew :app:testDebugUnitTest`; no `android.*` import in
`com.example.synth`; golden vectors (optional at this stage) generated per §2 correction 2c.

### Phase 2 — AudioTrack driver in the foreground service
- New `com.example.NativeAudioEngine` (Android-aware wrapper) owned by
  `MediaForegroundService`: render thread at `THREAD_PRIORITY_URGENT_AUDIO`; `AudioTrack`
  `MODE_STREAM`, `ENCODING_PCM_FLOAT`, stereo, `sampleRate = AudioTrack.getNativeOutputSampleRate(STREAM_MUSIC)`,
  buffer ≥ 4× `getMinBufferSize`; one preallocated `FloatArray`; loop
  `synth.render(buf); track.write(buf, ..., WRITE_BLOCKING)`.
- Audio focus: `AudioFocusRequest` (API 26+) with legacy fallback (API 24-25); pause on loss,
  duck optional (recommend pause-only, simpler). Register `ACTION_AUDIO_BECOMING_NOISY` ⇒ pause.
- **Apply R1:** service goes foreground when a session starts and stays foreground until
  `stopReset()`; remove the `STOP_FOREGROUND_DETACH`-on-pause behavior for native sessions;
  try/catch `ForegroundServiceStartNotAllowedException`.
- Wake lock: keep the existing partial wake lock semantics (acquire on play, release on
  stop/pause).
- Nothing JS-side changes yet; drive it with a temporary debug hook if needed.
- **Acceptance:** device test — start native playback of a bundled schedule via the debug hook,
  screen off ≥ 2 h, unplugged: no gap, no underrun logs, notification controls work.

### Phase 3 (deferrable — see §3/Q3) — type-2 WAV voices
- Capture `displayName → Uri` map in Kotlin when the file picker returns
  (`MainActivity.kt:108-117` / `WebViewManager`); normalize names exactly like JS `_normName`
  (index.html:804).
- Decode with `MediaExtractor` + `MediaCodec` → float PCM; linear-interpolation resampler to the
  engine rate (upgrade to windowed-sinc only if listening tests demand it); loop the buffer;
  L/R gain automation from `sampleVoice` (`volL/volR`), matching the JS explicit/discrete
  downmix (stereo source averaged equally into each gain path).
- Until implemented: native engine **skips type-2 voices** and reports
  `unsupportedVoices` in its state callback so JS can show a notice.
- **Acceptance:** mono 44.1 k, stereo 48 k, and 24-bit WAVs decode and loop seamlessly.

### Phase 4 — bridge redesign (native = clock and source of truth)
**Kotlin ⇒ add a second `addJavascriptInterface(..., "NativeAudio")`:**
```
loadSchedule(json: String)     // full schedule; if playing ⇒ hot-apply (restartIfPlaying contract)
play(offsetSec: Double); pause(); stopReset(); seekElapsed(sec: Double)
setMasterVolume(v: Double); setChannelGains(l: Double, r: Double)
setFadeOut30s(b: Boolean); setLoopsOverride(n: Int)   // -1 = clear override
isAvailable(): Boolean
```
**Native ⇒ JS events** (evaluateJavascript on main handler, ~4 Hz while playing):
`window.NativeAudioEvents.onTick(elapsedSec, played, loopsLeft)` and
`onState(isPlaying, unsupportedVoiceCount)`.

**JS changes (minimal, localized):**
- `const USE_NATIVE_AUDIO = !!(window.NativeAudio && window.NativeAudio.isAvailable());` —
  evaluated once at startup; **the only switch** (R3).
- Wrap the transport call sites: `playBtn`/`stopBtn` handlers, `#seek` oninput
  (index.html:2024-2044), `restartIfPlaying()` (1863), balance/volume/fade/loop setters — each
  routes to `NativeAudio` when the flag is on, else the existing engine. When the flag is on the
  JS engine must never construct an `AudioContext`.
- `onTick` keeps driving the existing playhead/label UI (index.html:1997-2022) from native ticks.
- **MediaSession callbacks call the native engine directly inside the service (R2)** — delete the
  broadcast → Activity → evaluateJavascript control path.
- **Acceptance:** all transport functions (play/pause/stop/seek/loop-count/balance/master/fade
  toggle/mute/solo/mass-edit hot-apply) behave identically to the WebAudio path in an A/B session;
  editing while playing restarts at the same offset; notification controls work with the Activity
  destroyed (swipe from recents, control from notification).

### Phase 5 — cleanup (only after Phase 4 is verified)
1. Delete keep-alive hacks: heartbeat + silent `AudioTrack` (`MediaForegroundService.kt:30-145`),
   `onPause/onStop` → `webView.onResume()` (`MainActivity.kt:83-91`), `FLAG_KEEP_SCREEN_ON`
   (`MainActivity.kt:48`), the Phase-0 renderer-priority line may stay (harmless, still correct).
2. Delete the four boilerplate test files (§3.8 of the analysis).
3. Centralize action strings in one `object PlaybackActions` (three files currently duplicate
   literals).
4. Prune unused dependencies (R4): Room + KSP room-compiler, Retrofit + converter-moshi, OkHttp +
   logging-interceptor, Firebase (bom, ai, appcheck) + `google-services` plugin, secrets plugin
   — **keep Moshi** (now used by `ScheduleModel`). Verify with a clean build after each removal.
5. Namespace normalization (`com.example` → `com.aistudio.gnauri.bvpmlc`) — separate, final
   commit; `applicationId` must not change.
6. UAT: structured A/B listening per analysis §2.3 risk 1; overnight (8 h) soak on the oldest
   available device.

---

## 6. Summary of plan deltas

| # | Delta | Type |
|---|---|---|
| 1 | Q4 resolved: minSdk 24 / targetSdk 36; Android 16 FGS compliance already satisfied | resolves unknown |
| 2 | Q3 largely resolved: no bundled type-2 content ⇒ Phase 3 deferrable off critical path | reprioritization |
| 3 | Golden-vector harness as written (node/vitest) is infeasible; use OfflineAudioContext in-app export or headless Chrome | correction |
| 4 | Noise parity is statistical, not sample-exact; scope golden vectors to deterministic voices | correction |
| 5 | Pink-noise biquad bandpass added to the DSP port checklist | gap |
| 6 | R1 FGS demotion/promotion trap ⇒ "stay foreground during session" rule | new risk |
| 7 | R2 notification controls die with Activity ⇒ MediaSession → native engine direct | new risk (existing defect) |
| 8 | R3 dual-engine flag `USE_NATIVE_AUDIO`, single switch point | new risk |
| 9 | R5 type-2 byte path designed (Kotlin URI capture, no base64 bridging) | gap |
| 10 | R6 fixed-rate golden vectors vs device-rate runtime; `sampleRate` as core parameter | gap |
| 11 | R7 full settings-push bridge surface enumerated (balance/fade/master/loops) | gap |
| 12 | R4 dependency bloat pruning added to cleanup; Moshi retained for schedule JSON | gap |
| 13 | Distinct `pause()` vs `stopReset()` semantics made explicit | precision |
| 14 | Explicit per-phase acceptance criteria and stop-and-ask triggers for the implementing agent | enhancement |

**Sources for the Android 16/FGS claims:**
[Behavior changes: apps targeting Android 16+](https://developer.android.com/about/versions/16/behavior-changes-16) ·
[Changes to foreground services](https://developer.android.com/develop/background-work/services/fgs/changes) ·
[Background playback with MediaSessionService](https://developer.android.com/media/media3/session/background-playback)
