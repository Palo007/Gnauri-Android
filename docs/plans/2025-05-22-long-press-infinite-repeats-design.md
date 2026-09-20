# Design Doc: Long Press for Infinite Repeats

Allow users to quickly set the repeat count to "Infinite" (0) by long-pressing the minus (`-`) button in the Repeat card.

## Goals
- Simplify the UX for setting infinite loops.
- Avoid accidental triggers of infinite loops during normal tapping.

## Proposed Changes

### UI/JavaScript (index.html)
- Modify the `loopMinus` button event handling.
- Implement a timer-based long-press detector.
- **Duration**: 600ms.
- **Action**: Call `setLoopCount(0)` when the timer expires.
- **Cancel conditions**: `mouseup`, `touchend`, `mouseleave`, and `touchcancel`.
- Ensure normal `onclick` functionality remains intact for short taps.

## Verification Plan

### Manual Verification
- **Short Tap**: Tap the `-` button; verify repeats decrease by 1.
- **Long Press**: Hold the `-` button for ~600ms; verify it switches to "∞" and shows "plays forever".
- **Interruption**: Press and slide finger off the button before 600ms; verify repeat count does not change to 0.
- **Touch and Mouse**: Verify behavior on both desktop (mouse) and Android (touch).
