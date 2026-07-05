# Gnauri: Binaural-Beat Schedule Player & Multi-Track Editor

Welcome to **Gnauri**, a highly responsive, offline-first multi-track binaural beat synthesizer, schedule player, and interactive graph editor for Android and Web. Built to be lightweight, precise, and fully compatible with the standard Gnaural XML schema (`.gnaural`), Gnauri empowers you to experience, create, and refine complex brainwave entrainment sessions on the go.
<img width="921" height="2048" alt="image" src="https://github.com/user-attachments/assets/535b455d-0c37-4137-8899-fd69405e80c0" />
<img width="921" height="2048" alt="image" src="https://github.com/user-attachments/assets/0da27796-81bb-422d-a938-b0281e942cc9" />

---

## 🎨 What is Gnauri?

Gnauri is a specialized audio synthesizer designed for brainwave entrainment using **binaural beats**, **isochronic tones**, and **custom audio file voices**. 

Unlike simple static frequency players, Gnauri utilizes dynamic multi-voice schedules that evolve over time. This allows you to program progressive transitions—such as gradually lowering a carrier frequency or shifting a binaural beat from a beta state (focus) down to theta (deep relaxation or sleep).

---

## 🚀 Core Features & Capabilities

When you download and run the compiled version of Gnauri, you can expect a comprehensive set of premium features:

### 1. File Compatibility & Smart Loading
* **Flexible Input formats**: Load your own custom `.gnaural` or XML schedules effortlessly.
* **Audio-Voice Synchronization**: Drop or select your background `.wav` sound files alongside your `.gnaural` files. The player will automatically synchronize and mix the audio tracks with the synthesized beats.
* **Bundled Presets Library**: Comes built-in with a curation of high-quality bundled schedules such as:
  * 🌊 *Wind and Waves* (Calming summer sounds)
  * 🌧️ *Spring Rain* (Gentle ambient focus)
  * ⚡ *Energize* (Mental pick-me-up)
  * 😴 *Instant Nap* (Deep delta relaxation)
  * ⚙️ *Default Schedule* (A versatile baseline)
* **Metadata Insight**: Upon loading a file, Gnauri instantly displays the title and author description of the session with clean, zero-margin left-aligned styling.

### 2. Interactive Multi-Track Timeline & Graph Visualizer
* **Three-Way Parameter Toggle**: Instantly switch the graph visualizer between:
  * **Beat**: Visualize and edit binaural or isochronic frequencies (Hz).
  * **Base**: View the carrier frequency (Hz) sent to your ears.
  * **Volume**: Track amplitude levels and sound fading over time.
* **Responsive Visual Feedback**: Watch the playhead glide across color-coded voice tracks representing different acoustic channels.
* **Intuitive Timeline Seeking**: Tap anywhere on the timeline to immediately seek to that specific moment in the schedule.

### 3. Voice & Track Orchestration
* **Individual Mute & Solo**: Isolate specific frequency tracks with instant `Mute` / `Solo` toggles.
* **Visibility Control**: Hide individual voices from the graph to declutter your view while editing.
* **Bulk Controls**: One-click buttons to `View All` or `Mute All` voices to rapidly orchestrate dense, multi-voice sessions.

### 4. Direct Point-by-Point Editing
* **Timeline Pinning**: Click or tap any line on the graph to pin a coordinate tooltip.
* **Pre-filled Dialogs**: Tap "Edit Point" on any pinned element to open a precise dialog showing current carrier frequencies, beat frequencies, and individual left/right volumes. Apply changes instantly to hear them reflected live in the playback engine.

---

## 🪄 Advanced Power-User Editing Tools

Gnauri goes beyond simple playback, packing powerful editing tools directly into the interface:

### 🧠 2D Rectangular Selection
Quickly select groups of data points by clicking and dragging diagonally over the timeline canvas.
* **Smart Voice Targeting**: Automatically highlights and targets the voice with the highest density of points within your selection box, or respects your active voice selection lock.
* **Edge Clamping**: Safe dragging mechanism ensures boundary coordinates clamp gracefully to `t = 0` or the max duration without coordinate corruption.

### ⚡ Mass Adjust Points Wizard
Once a selection is highlighted, a glowing teal ring marks your target points, and the **Mass Adjust Points Dialog** appears:
* **Shift Mode**: Apply relative adjustments (e.g., raise all selected frequencies by exactly `5Hz` or lower volume by `20%`).
* **Ramp Mode**: Linearly interpolate parameters between custom start and end targets (e.g., draw a perfect linear ramp from `4Hz` to `10Hz` over a selected 15-minute window).

### ⏳ Dynamic Time Scaling
Stretching or compressing a schedule to fit your lifestyle is incredibly simple. Click **Scale Time** in the header to:
* Input an absolute target duration in seconds (e.g., `1800` for an exact 30-minute session).
* Input a proportional multiplier (e.g., `*1.5` to make the session 50% longer, or `*0.5` to compress it to half the time).
* All individual intervals, ramps, and points scale proportionally in a single click.

### ↩️ Robust Undo/Redo Engine
* **50-Level Edit History**: Gnauri retains a local backup of your edit states so you never lose work.
* **High-Contrast Undo Control**: Use the beautifully bright, white `↺` undo button directly on the timeline panel.
* **Keyboard Shortcuts**: Supports full desktop ergonomics, including `Ctrl + Z` / `Cmd + Z` key bindings.

---

## 🎛️ Audio Customization & System Tuning

* **Infinite & Counted Loops**: Set the session to run exactly once, repeat a specific number of times, or loop infinitely for overnight sleep sessions.
* **Dynamic Stereo Balance**: Tap the Balance indicator to open a dedicated slider popover. Fine-tune your left/right mix (perfect for off-balance headphones) and reset it back to center instantly with a single tap.
* **Seamless Android Integration**: Gnauri runs via a dedicated system bridge. When running on Android, playback state, current elapsed progress, and total playback duration are communicated directly with the OS, ensuring responsive integration with background sound services and notification controls.
