# Advanced Gnaural Editing: Mass Adjustments & Time Scaling

This document details the newly added advanced editor capabilities for managing large, complex schedules easily, including 2D Rectangular Selection, Mass Data Point Adjustments, and Dynamic Time Scaling.

---

## 1. 2D Rectangular Selection

Manually editing dense schedules node-by-node is tedious. The **2D Rectangular Selection** tool allows power users to select many data points quickly and comfortably by dragging diagonally.

### How it Works
1. **Interactive Dragging**: Click or touch anywhere on the canvas and drag diagonally. A clean, high-contrast dashed rectangle will draw live to define your selection boundaries.
2. **Robust Boundary Clamping**: Dragging beyond the left or right edges of the plot area automatically clamps to `t = 0` or the total schedule duration, preventing canvas errors or `NaN` coordinate calculations.
3. **Smart Voice Targeting**:
   - **Single-Voice Context (Highlight Lock)**: If a specific voice line is currently selected/highlighted, the selection rectangle is locked to that voice. Only data points belonging to the highlighted voice inside the rectangle will be selected.
   - **Auto-Detect Fallback**: If no voice is currently selected, the graph automatically evaluates all visible voices, identifies the one with the highest concentration of points in your selection, and automatically highlights it.

---

## 2. Mass Adjust Points Modal (Wizard)

Once a selection is made, the **🪄 Mass Adjust Points** dialog instantly appears, and the graph renders a **glowing teal accent ring** around every selected point to give clear visual feedback.

The dialog supports two advanced adjustment modes:

### A. Shift Mode (Relative Adjustments)
* **Description**: Adds or subtracts a uniform constant delta value to every selected point's parameter (Beat rate, Carrier frequency, or Volume).
* **Usage**: Ideal for shifting a block of data points up or down relative to their current levels (e.g., lowering carrier base frequency by 20Hz or boosting pink noise volume by 15%).

### B. Ramp Mode (Gradual Transitions)
* **Description**: Linearly interpolates the selected points' parameters from a custom **Start Value** to an **End Value** based on their relative times.
* **Usage**: Ideal for setting up smooth progressive shifts (e.g., gradually increasing binaural beat frequency from 4 Hz to 14 Hz over a selected 10-minute section).

---

## 3. Dynamic Time Scaling

The **⏳ Scale Schedule Time** feature allows stretching or compressing entire multi-track schedules proportionally in a single click.

### How it Works
1. Click the **Scale Time** button in the header (located adjacent to the Export button).
2. The modal displays the current total schedule duration.
3. Enter your target scaling value:
   - **Absolute Duration**: Specify a new duration directly in seconds (e.g., `1800` for a 30-minute session).
   - **Proportional Multiplier**: Specify a multiplier prefixing with an asterisk (e.g., `*1.5` to make it 50% longer, or `*0.5` to make it half as long).
4. Click **Apply**. The durations of all individual states across all voices are dynamically scaled, keeping their proportional relationships identical.
