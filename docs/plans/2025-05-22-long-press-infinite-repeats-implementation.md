# Long Press for Infinite Repeats Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Set the repeat count to infinite (0) when the minus button is long-pressed.

**Architecture:** Use `mousedown` and `touchstart` events on the `loopMinus` button to initiate a 600ms timer. If the timer completes without being interrupted by cancellation events (`mouseup`, `touchend`, etc.), call the existing `setLoopCount(0)` function.

**Tech Stack:** JavaScript (Vanilla), HTML5.

---

### Task 1: Implement Long-Press Logic in index.html

**Files:**
- Modify: `app/src/main/assets/index.html` (around line 1800)

**Step 1: Write minimal implementation**

We will add a long-press handler to the `loopMinus` button.

```javascript
// Add these variables before the loopMinus.onclick definition
let loopMinusTimer = null;
const loopMinusLongPressMs = 600;

function clearLoopMinusTimer() {
  if (loopMinusTimer) {
    clearTimeout(loopMinusTimer);
    loopMinusTimer = null;
  }
}

const startLoopMinusTimer = () => {
  clearLoopMinusTimer();
  loopMinusTimer = setTimeout(() => {
    setLoopCount(0);
    loopMinusTimer = null;
  }, loopMinusLongPressMs);
};

const minusBtn = $("loopMinus");
minusBtn.addEventListener("mousedown", startLoopMinusTimer);
minusBtn.addEventListener("touchstart", (e) => {
  // Prevent double triggers if both touch and mouse events are fired
  startLoopMinusTimer();
}, { passive: true });

["mouseup", "mouseleave", "touchend", "touchcancel"].forEach(ev => {
  window.addEventListener(ev, clearLoopMinusTimer);
});
```

**Step 2: Verification**

Since this is a UI interaction in a WebView, we will verify it manually on the device.

**Step 3: Commit**

```bash
git add app/src/main/assets/index.html
git commit -m "feat: add long press to minus button for infinite loops"
```
