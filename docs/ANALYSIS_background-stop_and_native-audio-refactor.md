# Gnauri Android — Analysis: Background-Stop Bug & Native Audio Refactor

> **Purpose of this document:** Hand-off for another agent/engineer to thoroughly review.
> It contains (1) the diagnosis of the "app stops after ~40 min with screen off" bug,
> (2) a lead-developer complexity evaluation of moving audio synthesis from the WebView
> into a native Kotlin engine, and (3) the concrete source inventory both conclusions rest on.
>
> **Status:** analysis only. No code has been changed.
> **Reviewer note:** claims below cite file paths and line numbers as of this analysis; verify
> against the current tree before acting, as line numbers drift with edits.

---

## 0. Architecture in one paragraph

Gnauri is a WebView-hosted app. Almost all product logic — the `.gnaural` XML parser, the
audio synthesizer (Web Audio API), and the entire UI/editor — lives in a single asset,
`app/src/main/assets/index.html` (2944 lines). The native Kotlin layer is a thin shell
(728 lines total): `MainActivity` hosts the WebView, `WebViewManager` sets up the JS bridge,
and `MediaForegroundService` provides a media-session notification plus background keep-alive
scaffolding. **The sound is generated inside the WebView renderer process, not natively.**
This single fact is the root of the background-stop bug and the reason a native port is on the table.

### File sizes
| File | Lines |
|---|---|
| `app/src/main/assets/index.html` | 2944 |
| `app/src/main/java/com/example/MediaForegroundService.kt` | 339 |
| `app/src/main/java/com/example/MainActivity.kt` | 174 |
| `app/src/main/java/com/example/WebViewManager.kt` | 116 |
| `ui/theme/*` (Theme/Type/Color) | 99 |
| **Total Kotlin under app/src/main/java** | **728** |

Of the 2944 HTML/JS lines: audio engine ≈ 620, parser ≈ 185, and the remaining ≈ 2100 are
markup/CSS plus non-audio UI logic (Graph ≈ 450, Mass-Edit wizard ≈ 430, dialogs, undo/redo,
file load, export, wiring).

---

## 1. Diagnosis — why the app stops after ~40 min with screen off

**Core reason:** all sound is synthesized by the Web Audio API inside the WebView renderer
process. `MediaForegroundService` does **not** produce audio — it only plays a *silent*
`AudioTrack`, holds a partial wake lock, and runs a 10s "heartbeat" that calls `ctx.resume()`.
So the foreground-service protection (`mediaPlayback` type) keeps the **service** alive, but the
thing that actually makes noise — the WebView renderer — is unprotected. When Android freezes or
reclaims that renderer, audio stops even though the service is perfectly healthy. The ~40-minute
timing is consistent with the renderer being deprioritized and then reclaimed under memory
pressure once the screen is off.

### Concrete trigger (a real bug)
`WebViewManager.kt:42`
```kotlin
setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
```
The second argument is `waivedWhenNotVisible`. Set to `true`, it means: *when the WebView is not
visible (screen off), drop the renderer to the lowest priority.* That makes the audio-producing
process a prime target for the low-memory killer. **Fix: change to `false`.** This is the single
most likely cause and the cheapest fix.

### Contributing factors, ranked
1. **Renderer priority waived when screen off** (above) — the audio process gets killed/frozen.
2. **The heartbeat can't save it.** The 10s `evaluateJavascript` / `ctx.resume()` rescue
   (`MediaForegroundService.kt:103-138`) runs *inside* the same renderer. Once the renderer is
   frozen, the rescue code can't run either — it defeats itself.
3. **Looping schedules depend on a JS Web Worker timer** (`_loopTimer` → `_onBoundary` →
   `play()`, index.html ~1173/1278). WebView throttles worker timers in the background. If a
   loop boundary lands while backgrounded, the next loop may never start — fatal for
   overnight/infinite loops.
4. **Doze mode.** After ~30 min screen-off + unplugged + stationary, Doze begins. The
   battery-optimization exemption is *requested* (`MainActivity.kt:50-62`), but (a) you can't be
   sure the user granted it, and (b) it doesn't protect a deprioritized renderer.
5. **`FLAG_KEEP_SCREEN_ON`** (`MainActivity.kt:48`) only helps while the activity is foregrounded;
   useless once the user turns the screen off, and it wastes battery.
6. **Partial wake lock is fine** (no timeout, CPU stays on, `MediaForegroundService.kt:182`) — but
   CPU-on doesn't help when the stall is app-lifecycle freezing, not CPU sleep.

### Immediate remediation (cheap, high impact)
- Change `waivedWhenNotVisible` → `false` (one line). Likely resolves most cases.
- Verify the battery-optimization exemption is actually **granted**, don't assume.
- Lean on the existing silent `AudioTrack` to keep the native audio path warm.

### Robust remediation (architectural — see Section 2)
Move audio generation into a native `AudioTrack` rendered in the foreground service, so playback
survives regardless of WebView renderer state.

### Diagnosis: assumptions / unknowns
- Analyzed source only; not built or run — no logcat/repro. Confirming the killer needs an
  `adb logcat` capture during a screen-off session (look for renderer process death / "Killing …
  cached").
- Could not tie "40 minutes" to a specific schedule length; `.gnaural` files derive `totalTime`
  from datapoint durations rather than a literal attribute. Cause is architectural regardless.
- Whether the user granted the battery-optimization exemption is unknown and matters.

---

## 2. Lead-dev complexity evaluation — native Kotlin audio engine

### 2.1 The scope decision that governs everything
"Full Kotlin refactoring" here means one specific thing: **move audio synthesis out of the
WebView into a native renderer, and keep the entire UI/editor/parser in the WebView.** That split
is not negotiable — the UI is ~2100 lines (Graph ~450, Mass-Edit wizard ~430, dialogs, undo/redo,
file load, export). Porting that to Compose would be a multi-month rewrite with no benefit to the
actual problem. The audio engine to port is only **~620 lines of JS** (`GnauriEngine` + noise
generators + segment helpers).

**Counterintuitive finding:** porting the DSP to native is, in several dimensions, *simpler* than
the current WebAudio implementation, because a native block-renderer deletes accidental complexity:
- The precomputed-automation model (`_scheduleParam`, and especially `_scheduleIsoGate` with its
  **200,000-event guard hack**) exists only because WebAudio schedules parameter events ahead of
  time. A native per-block renderer just evaluates `sampleVoice(t)` for the current block — no
  event lists, no guard, no `cancelScheduledValues` rebuilds on every edit/seek.
- Looping/crossfade currently needs node teardown, `_prevNodes`/`_prevBus`, a web-worker
  `ReliableTimer`, and boundary timers fired 180 ms early. Native looping is `t = elapsed %
  totalTime` with an equal-power window across the seam — no timers, no teardown, no worker.
- Seeking currently rebuilds the entire node graph. Native seek is one assignment to a time cursor.
- Noise voices currently cache up to **600-second stereo buffers** (white/brown ≈ 200 MB each at
  44.1k/f32). Native generates noise per-sample — that memory disappears.

### 2.2 Complexity verdict
Overall: **moderate, well-bounded, ~6–9 engineer-weeks** to a UAT-ready state, dominated by
correctness/parity work rather than volume of code.

Genuinely hard/risky parts are narrow:
- **Phase-continuous oscillators** (binaural = two sines at `base ± beat/2`) need a per-oscillator
  phase accumulator so frequency changes don't click.
- **Type-2 audio-file voices** are the fiddliest: WebAudio's `decodeAudioData` silently handled
  arbitrary WAV formats and sample-rate conversion; natively you own decoding
  (MediaExtractor/MediaCodec) and resampling.
- Everything else — pink (Paul Kellet), white (LCG), brown (random walk), isochronic gate,
  balance trim, master/overall volume, `stereoswap`, `mono`, `fadeOut30s` — is arithmetic in a
  mix loop and ports almost line-for-line.

The rest of the effort is plumbing, not DSP: the AudioTrack driver in the foreground service,
audio-focus / becoming-noisy handling, buffer/underrun robustness, and a bridge redesign.

### 2.3 Risk areas to mitigate (ranked)
1. **Perceptual parity / audio regression** — highest, subjective; current engine has tuned
   constants (equal-power 180 ms crossfade, 120 ms stop-fade, 4 ms anti-click ramps, `norm()`
   0–100 volume rescale, forced-stereo destination). Mitigate: golden-vector unit tests +
   structured A/B listening in UAT.
2. **Type-2 WAV decode + resample** — arbitrary formats/sample rates. Mitigate: robust decoder +
   explicit resampler; test bundled WAVs and odd inputs (mono, 48k, 24-bit).
3. **Click/pop artifacts** at loop seams, param changes, start/stop/seek. Mitigate: continuity
   assertions in unit tests (adjacent-sample delta below threshold) — machine-checkable.
4. **AudioTrack lifecycle in background** — Doze, audio-focus loss, `ACTION_AUDIO_BECOMING_NOISY`,
   underruns. Mitigate: `AudioManager` focus handling + noisy receiver + generous `MODE_STREAM`
   buffering.
5. **Real-time thread discipline** — no allocation/boxing/GC in the render loop. Mitigate:
   preallocated primitive buffers; verify with an allocation/benchmark test.
6. **JS↔native state sync** — native becomes the clock; JS still draws playhead/seek/elapsed.
   (Note the existing `_elapsedSeconds()` infinite-loop discrepancy — do not port the bug.)
   Mitigate: native pushes position ticks; single authoritative time model.
7. **Edit hot-apply path** — every Mass-Edit/point-edit/scale-time/mute-solo action currently
   calls `restartIfPlaying()` + `buildSegments`. That contract must survive the new bridge or
   live editing regresses.

### 2.4 Cleanup plan for the codebase
- **Namespace mismatch**: package `com.example` vs `applicationId com.aistudio.gnauri.bvpmlc`.
  Normalize before adding a real module.
- **Delete the boilerplate tests** — all four test files are unmodified template code (`2+2`, a
  screenshot of a `Greeting` composable the app never renders, a package-name assert against the
  wrong package). They give false confidence.
- **Remove WebView keep-alive hacks after the port**: `MainActivity.onPause/onStop` calling
  `webView.onResume()`; the `MediaForegroundService` silent-`AudioTrack` + 10s heartbeat; the
  `resumeTimers()` pings. All become dead code once audio leaves the renderer.
- **Centralize intent-action strings** (`UPDATE_PLAYBACK_STATE_ACTION`, `ACTION_PLAY_FROM_SERVICE`,
  etc.) — currently duplicated literals across three files.
- **Structure new code as pure core + thin driver**: a side-effect-free `synth` package (schedule
  model → PCM, no Android imports) wrapped by an `AudioTrack`/service driver. This is what makes
  it testable; cheap up front, expensive to retrofit.
- **`WebViewManager` static WebView leak** (already `@SuppressLint("StaticFieldLeak")`) —
  acceptable for a single-activity app; document ownership, don't worsen it.

### 2.5 Testability — the strongest argument for the port
Today the audio engine is JavaScript, so the fully-configured Kotlin test stack (JUnit 4.13.2,
Robolectric 4.16, Compose UI test, Roborazzi 1.59) provides **zero coverage of any real audio
logic**. You cannot get a fast test→fix loop on the thing that's broken.

If the DSP is pure Kotlin with no Android dependencies, it runs on the JVM in milliseconds — no
emulator, no APK build:
- **Golden-vector tests**: given a schedule fixture, render N samples and assert against a
  reference generated once from the current JS engine (headless node/vitest), pinning parity
  numerically.
- **Invariant tests** (no golden data needed): instantaneous binaural freqs = `base ± beat/2` at
  time t; gain envelopes match `sampleVoice`; loop-seam continuity below a click threshold; no
  NaN/denormal/clip; isochronic pulse rate = `beatfreq`.
- **Parser tests**: the ~185-line defensive parser is trivially unit-testable if ported to Kotlin,
  or keep it in JS and feed the engine schedule JSON fixtures.

**Build the pure core with its golden vectors first, before wiring any AudioTrack.** UAT then
reduces to lifecycle/hardware behavior and subjective sound — not logic-bug hunting on a device.
Interim option: a node-based JS test harness around the existing engine — but that's a second
toolchain and doesn't help background reliability.

### 2.6 Minimal resource utilization
The native path is strictly lighter: no ~200 MB noise buffers (per-sample generation), no
silent-audio-track busywork, no 10s heartbeat waking the JS VM, no WebView renderer kept warm for
audio. Once native owns sound, the **WebView can be fully paused/frozen when backgrounded** (drop
the `onResume` hack), saving CPU/battery with the screen off. Still need FGS + partial wake lock
for CPU. Standard `AudioTrack` `MODE_STREAM` is sufficient and simpler than Oboe/AAudio for this
latency-insensitive use case; a lower sample rate is viable for pure-tone schedules.

### 2.7 Recommended sequencing
1. **Now, cheap:** ship `waivedWhenNotVisible=false` to stop the bleeding while the port is built.
2. **Phase 1:** pure Kotlin `synth` core (oscillators, noise, isochronic gate, mixer,
   loop/crossfade, fades) + golden-vector and invariant tests. No Android. Retires parity risk.
3. **Phase 2:** AudioTrack driver in the foreground service; focus/noisy/underrun handling; delete
   keep-alive hacks.
4. **Phase 3:** type-2 WAV decode/resample.
5. **Phase 4:** bridge redesign (native = clock/source of truth; JS = editor + playhead) + edit
   hot-apply path.
6. **Phase 5:** cleanup (namespace, dead tests, string constants) + UAT with structured A/B
   listening.

### 2.8 Evaluation: assumptions / unknowns
- Assumes the requirement is **reliable indefinite/overnight background playback**. If the real
  need is only "survive a single sub-hour session with screen off," the Phase-1 one-line fix may
  suffice and the full port is over-engineering. *This is the answer that most changes the
  recommendation.*
- Whether **exact audio parity** with the JS engine is a hard requirement (vs. "close enough")
  drives golden-vector effort and risk #1.
- How often users load **custom WAV (type-2) voices** determines whether risk #2 is critical-path.
- `minSdk`/target device range not captured; minor, but affects codec/resampler choices.
- Effort range assumes one experienced Android/audio engineer; the pure core and service driver
  parallelize after Phase 1.

---

## 3. Source inventory (evidence base)

> This section is the detailed inventory the conclusions above rest on. Line numbers are as of
> analysis time. Engine class `GnauriEngine` defined at line 744, instantiated line 1789.

### 3.1 Voice types — rendering in `play()` (lines 923–1174)
`play()` iterates `this.schedule.voices.forEach((voice, vi)=>{...})` (line 965), skipping if
`voice.muted` (966) or `!segs.length` (968). `swap = this.schedule.stereoswap` (952) flips L/R
routing throughout.

- **Type 2 — Audio file** (970–993): `_lookupBuffer(voice.desc)` fallback `voice.file`; skipped
  silently if missing. `AudioBufferSourceNode` `loop=true`; `ChannelMergerNode(2)`; two `GainNode`s
  `gL/gR` init 0, each `channelCount=1, channelCountMode="explicit", channelInterpretation="discrete"`
  so a stereo buffer is downmixed identically into each gain. `_scheduleParam` on `gL.gain`/`volL`
  and `gR.gain`/`volR`. No frequency automation. Nodes `[src,gL,gR,merger]`.
- **Type 1/5/6 — Noise** and unknown fallback (996–1050): white/brown build 2-ch buffers of length
  `min(total+1,600)` s, cached in `_whiteCache`/`_brownCache` via `WhiteNoiseGenerator` (LCG) /
  `BrownNoiseGenerator` (random walk). Pink/unknown use mono `_noiseBuffer(min(total+1,60))` (Paul
  Kellet) with optional `BiquadFilter` bandpass (`Q=0.7`) only if `isPink && segs.some(s=>s.base>1)`;
  filter freq via `_scheduleParam(..., s=>Math.max(20, s.base||200))`. Common tail: `gL/gR` init 0,
  explicit/discrete; merger routing with `swap`; `_scheduleParam` on gains for `volL`/`volR`.
- **Type 3/4 — Isochronic** (1052–1084): `gapped = type===4`. Single sine carrier `oscC`
  (`_vi`,`_side='C'`); carrier freq `_scheduleParam(oscC.frequency, ..., s=>Math.max(1,s.base||200))`.
  Pulse gain node; `gL/gR` explicit/discrete; merger with `swap`; `_scheduleParam` on gains for
  volL/volR; pulse envelope `_scheduleIsoGate(pulse.gain, segs, offset, total, t0, gapped)`. Not a
  stereo illusion — same carrier to both channels.
- **Type 0 — Binaural** (1086–1112): two sine oscillators `oscL/oscR` (`_side='L'/'R'`);
  `oscL.frequency` via `s=>s.base - s.beat/2`, `oscR.frequency` via `s=>s.base + s.beat/2`; gains
  `volL/volR`. If `voice.mono`: `gL→bus`, `gR→bus` (both tones summed to both channels). Else two
  `StereoPannerNode`s hard L/R (sign flipped by `swap`).
- **Common**: every voice connects into the per-instance `bus` GainNode (960), never directly to
  `master`. `_ensureCtx()` (816–842) forces stereo destination and builds
  `master→splitter(2)→[trimL,trimR]→merger(2)→destination` for global L/R balance.

### 3.2 Scheduling model
- **`buildSegments(voice)`** (673–680): points (duration-based) → absolute-time segments
  `{t0,t1,beat,base,volL,volR}`. Cached in `setSchedule()` (855) as `segCache`.
- **`sampleVoice(segs, t)`** (682–702): linear-interpolates `{beat,base,volL,volR}` at time t,
  ramping toward the *next* segment's start value (Gnaural per-datapoint ramp semantics).
- **`_scheduleParam(param, segs, offset, total, t0, fn)`** (1181–1196): writes linear automation
  for one AudioParam; `cancelScheduledValues` then `setValueAtTime` + per-segment
  `linearRampToValueAtTime`. Reused for freq/gain/filter via a per-call accessor `fn`.
- **`_scheduleIsoGate(...)`** (1206–1242): isochronic on/off envelope, walks schedule time in
  cycles of `1/rate`; smooth (type 3) vs gapped burst 0.66 duty (type 4); anti-click ramp
  `min(0.004, period*0.1)`; **200,000-event guard** (1212–1216); `rate<=0.01` holds gate open
  (steady tone), advancing 0.10 s.

### 3.3 Playback lifecycle
- **`play(offset=0, isLoopContinuation=false)`** (923–1174): ensures ctx/resume; clears
  `_loopTimer`; on loop-continuation crossfade banks elapsed into `_elapsedAccrued` and stashes
  `_prevNodes/_prevBus`, else hard `stop()`. Builds fresh `bus` (`gain = xfade?0:1`). Crossfade
  scheduling (1120–1139) equal-power via `setValueCurveAtTime` with linear fallback, teardown timer
  `cf*1000+40` ms. `fadeOut30s` (1141–1164) only on the final (non-looping) instance, over last
  `min(30,total)` s, on the bus. Arms boundary: `lead = willLoop && (total-2cf)>cf ? cf : 0`;
  `timeUntilBoundary = max(0.02, (total-offset)-lead)`; via `reliableTimer.setTimeout(_onBoundary)`.
- **`stop()`** (1292–1321): banks position/elapsed; clears timer/rAF; flushes prev instance;
  STOP_FADE (120 ms) bus ramp to 0 then teardown at `fade*1000+40` ms.
- **`_onBoundary()`** (1278–1291): if `loopsLeft>1` decrement, `_played++`, `play(0,true)`, notify
  `AndroidBridge.playbackStateChanged`; else `stop()` + `onTick(totalTime)`.
- **`_loop(total)`** (1269–1277): `requestAnimationFrame` UI tick (not audio scheduling).
- Constants: `CROSSFADE=0.18`, `STOP_FADE=0.12`. `_xfadeCurve(up)` (792–796) 64-pt sin/cos.
  `_teardownInstance` (799–802) null-safe stop/disconnect.
- Loop counting: `schedule.loops` (0=infinite), `loopsLeft` (Infinity for infinite), `_played`,
  `_elapsedAccrued`. **Known discrepancy**: `_elapsedSeconds()` (781–788) for infinite loops
  returns only current-span `t`, not prior iterations — differs from the loop-total display math.
- Balance trim: `_trimL/_trimR`, `setChannelGains(l,r)` (844–849) clamp `[0,1.5]`, no ramp.
- Master vol: `_uiVol`, `setMasterVolume(v)` (872–876) writes `master.gain = v*((ovL+ovR)/2)`.
- `stereoswap`: read once per `play()`; flips merger output indices and panner signs at graph-build
  time — changing it live requires restart.
- **`ReliableTimer`** (516–555): web-worker-backed `setTimeout`/`clearTimeout` (inline Blob) to
  avoid main-thread background throttling; single global `reliableTimer` used by `_loopTimer` and
  teardown timers.

### 3.4 JS↔Kotlin bridge surface (small — key for a redesign)
**`window.AndroidBridge` (JS → Kotlin)**, registered in `WebViewManager.kt:88-105`:
- `exportFile(xml: String, filename: String)` — called index.html:2925; Kotlin stores `exportData`,
  launches SAF save dialog.
- `playbackStateChanged(isPlaying: Boolean, positionSec: Float, durationSec: Float)` — called
  index.html:1286 (`_onBoundary`) and :1979 (`updateTransportUI`); Kotlin broadcasts
  `UPDATE_PLAYBACK_STATE_ACTION` (extras isPlaying, position ms, duration ms) consumed by
  `MediaForegroundService` for the media-session notification.

**`window.AndroidPlayerControl` (Kotlin → JS)**, defined index.html:1982–1994:
- `play()`, `pause()`, `stop()` — simulate clicks on `playBtn`/`stopBtn` (no-op unless a schedule
  is loaded / in the right state).
- `seek(targetTime)` — `targetTime` in seconds over the full loop-multiplied timeline; maps to the
  0–1000 `#seek` slider and dispatches a synthetic `input` event.
Called from `MainActivity.kt:141-145` (play/pause/seek) on service broadcasts;
`MediaForegroundService.kt:118-125` only probes for existence (heartbeat), does not invoke.

### 3.5 Schedule data model (produced by `parseGnaural`, line 669)
```js
{
  title, author, desc,
  voices: [
    { index, type, desc, file, muted, visible, mono,
      points: [ { duration, beat, base, volL, volR, state } ] }
  ],
  totalTime, ovL, ovR, loops, stereoswap
}
```
- `type`: 0 binaural, 1 pink, 2 audio file, 3 isochronic, 4 alt isochronic (gapped), 5 white,
  6 brown; unknown → pink fallback.
- `loops`: 0 = infinite, N>0 = play N times.
- `volL/volR` pass through `norm(x)= x>1.0001 ? x/100 : x` (tolerates 0–100 legacy scale).
- `totalTime` = max(sum of per-voice point durations, declared `<totaltime>`).
- Runtime: `engine.segCache` = `voices.map(buildSegments)`.

### 3.6 Parser (index.html 557–741; core 571–670)
Helpers `num`, `attr`, `childText`, `directChild`; main `parseGnaural(xmlString)` (589–670, ~82
lines): `DOMParser`, throws on `parsererror`; multi-name attribute/child fallbacks for every field;
3-tier voice discovery (direct children → `<voices>` container → global `getElementsByTagName`);
datapoint containers `entries`/`datapoints`/self; tags `entry`/`datapoint`; volume handles split
L/R, single `volume`/`vol`, or mutual derivation; negative-duration points filtered; `state`
defaults 1. Complexity is breadth of fallback branching, not algorithmic difficulty. The schedule
object is `JSON.stringify`'d for the undo stack (1797) and shared by engine, graph, exporter, UI.

### 3.7 WebView-only (non-audio) features — would remain in the WebView
| Feature | Lines | ~Size |
|---|---|---|
| `Graph` class (timeline/canvas UI, hit-test, tooltip, draw) | 1325–1774 | ~450 |
| 2D rectangular drag-selection (in Graph + wiring) | 1360–1463, 2742–2792 | ~60 |
| Timeline seek wiring (`#seek` oninput, loop-aware) | 1975–2059 | ~90 |
| Point-edit modal | 2303–2403 | ~100 |
| Time-scaling dialog | 2404–2446 | ~43 |
| Mass-adjust-points wizard | 2448–2880 | ~430 |
| Mute/solo/visibility toggles | 1864–1892 | ~30 |
| Undo/redo (50-level, JSON snapshots) | 1793–1822 | ~30 |
| File load / multi-file (schedule + WAV decode) | 2138–2279 | ~140 |
| `load(xml,name)` orchestration | 1824–1862 | ~39 |
| XML export (`buildGnauralXML`, `exportGnaural`) | 2882–2934 | ~53 |
| L/R balance UI (localStorage) | 1910–1967 | ~58 |
| Loop/repeat-count UI | 2070–2136 | ~67 |
| Android bridge glue | 1977–1994 | ~18 |
| Misc formatting/lookup helpers | 1777–1787 | ~11 |

### 3.8 Test infrastructure (fully wired, zero real coverage)
Deps (from `gradle/libs.versions.toml` + `app/build.gradle.kts`): JUnit 4.13.2; androidx-junit
1.3.0; androidx.test core 1.6.1 / runner 1.6.2; Espresso 3.7.0; Robolectric 4.16.1; Roborazzi
1.59.0 (+compose,+junit-rule) with Gradle plugin; Compose UI test via BOM 2024.09.00;
kotlinx-coroutines-test 1.10.2. `testOptions { unitTests { isIncludeAndroidResources = true } }`.

Existing tests are all template boilerplate:
- `ExampleUnitTest.kt` — asserts `2+2==4`.
- `ExampleRobolectricTest.kt` — asserts `getString(R.string.app_name)=="Gnauri"`.
- `GreetingScreenshotTest.kt` — Roborazzi screenshot of `MyApplicationTheme { Text("Gnauri") }`
  (a composable the app never renders; real MainActivity hosts a WebView).
- `ExampleInstrumentedTest.kt` — asserts package `com.example` (note: actual `applicationId` is
  `com.aistudio.gnauri.bvpmlc`).

None test the parser, audio engine, bridge, or services. The JS layer is invisible to this stack.

---

## 4. Open questions for the reviewer to resolve
1. Is the target **indefinite/overnight** background playback, or just single sub-hour sessions?
   (Decides whether the one-line fix suffices vs. the full native port.)
2. Is **exact audio parity** with the JS engine a hard requirement?
3. How common are **custom WAV (type-2)** voices in real use?
4. What are `minSdk`/target device range? (Codec/resampler choices.)
5. Confirm the bug with an `adb logcat` screen-off repro before committing to the port.
