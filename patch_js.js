const fs = require('fs');
let html = fs.readFileSync('app/src/main/assets/index.html', 'utf8');

const anchor = '/* ============================ Export to .gnaural ============================';

const newJs = `
// Scale Time feature
$("scaleTimeBtn").onclick = () => {
  if (!sched || !sched.totalTime) return;
  const currentMins = (sched.totalTime / 60).toFixed(2);
  const resp = prompt(\`Current total time is \${fmt(sched.totalTime)} (\${sched.totalTime.toFixed(1)}s).\\nEnter new total time in SECONDS:\\n(or multiply factor like *1.5)\`, sched.totalTime.toFixed(1));
  if (!resp) return;
  let newTotal = 0;
  if (resp.startsWith("*")) {
    const factor = parseFloat(resp.substring(1));
    if (isNaN(factor) || factor <= 0) return alert("Invalid factor");
    newTotal = sched.totalTime * factor;
  } else {
    newTotal = parseFloat(resp);
    if (isNaN(newTotal) || newTotal <= 0) return alert("Invalid time in seconds");
  }
  
  const factor = newTotal / sched.totalTime;
  sched.voices.forEach(v => {
    v.points.forEach(p => {
      p.dur *= factor;
    });
  });
  sched.totalTime = newTotal;
  
  // Update Engine and UI
  engine.setSchedule(sched);
  $("mDur").textContent=fmt(sched.totalTime)+" ("+Math.round(sched.totalTime)+"s)";
  $("timeLbl").innerHTML = "<strong style='font-size:1.15em'>" + fmtElapsed(engine._played*sched.totalTime, sched.totalTime, sched.loops) + "</strong>";
  graph.setPlay(engine._played*sched.totalTime); // approximate update
  graph.draw();
};

// Mass Edit feature
let massEditState = null;

$("massEditMode").onchange = (e) => {
  const isRamp = e.target.value === "ramp";
  $("massEditShiftPane").style.display = isRamp ? "none" : "block";
  $("massEditRampPane").style.display = isRamp ? "block" : "none";
};

graph.onMassEditRequest = (t1, t2, y1, y2, view) => {
  if (!sched) return;
  
  const hitsByVoice = new Map();
  const axisMax = view === "beat" ? graph.maxBeat : view === "base" ? graph.maxBase : 1;
  const H = graph._h || graph.cv.height;
  const {t:pt, b} = graph.pad;
  const gh = H - pt - b;
  
  const val1 = (pt + gh - y1) / gh * (axisMax||1);
  const val2 = (pt + gh - y2) / gh * (axisMax||1);
  const minVal = Math.min(val1, val2);
  const maxVal = Math.max(val1, val2);
  
  sched.voices.forEach(v => {
    if (!v.visible) return;
    let ptsInRect = [];
    let time = 0;
    for(let i=0; i<v.points.length; i++) {
      const p = v.points[i];
      if (time >= t1 && time <= t2) {
        let pv = 0;
        if (view === "beat") pv = p.beat;
        else if (view === "base") pv = p.base;
        else if (view === "volume") pv = p.volL;
        
        if (pv >= minVal - (axisMax * 0.05) && pv <= maxVal + (axisMax * 0.05)) { // small margin
          ptsInRect.push({index: i, time: time, origVal: pv});
        }
      }
      time += p.dur;
    }
    if (ptsInRect.length > 0) {
      hitsByVoice.set(v.index, ptsInRect);
    }
  });
  
  if (hitsByVoice.size === 0) return;
  
  let bestVoice = -1;
  let maxHits = 0;
  for(let [vi, hits] of hitsByVoice.entries()) {
    if (hits.length > maxHits) {
      maxHits = hits.length;
      bestVoice = vi;
    }
  }
  
  const selectedHits = hitsByVoice.get(bestVoice);
  massEditState = {
    vi: bestVoice,
    hits: selectedHits,
    view: view
  };
  
  const vType = sched.voices[bestVoice].type;
  let viewName = view;
  if(vType !== 0 && view === "beat") viewName = "pulse";
  
  $("massEditStats").textContent = \`Selected \${selectedHits.length} points of voice \${bestVoice+1} in \${viewName} view.\`;
  
  $("massEditStart").value = selectedHits[0].origVal.toFixed(view === "volume" ? 2 : 1);
  $("massEditEnd").value = selectedHits[selectedHits.length-1].origVal.toFixed(view === "volume" ? 2 : 1);
  $("massEditDelta").value = 0;
  
  $("massEditDlg").style.display = "flex";
};

$("massEditApply").onclick = () => {
  if(!massEditState || !sched) return;
  const { vi, hits, view } = massEditState;
  const v = sched.voices[vi];
  
  const mode = $("massEditMode").value;
  
  if (mode === "shift") {
    const delta = parseFloat($("massEditDelta").value);
    if(isNaN(delta)) return;
    
    hits.forEach(h => {
      const p = v.points[h.index];
      if (view === "beat") p.beat = Math.max(0, p.beat + delta);
      else if (view === "base") p.base = Math.max(0, p.base + delta);
      else if (view === "volume") {
        p.volL = clamp(p.volL + delta, 0, 1);
        p.volR = clamp(p.volR + delta, 0, 1); // shift both channels identically
      }
    });
  } else if (mode === "ramp") {
    const startVal = parseFloat($("massEditStart").value);
    const endVal = parseFloat($("massEditEnd").value);
    if(isNaN(startVal) || isNaN(endVal)) return;
    
    // time-based ramp
    const tStart = hits[0].time;
    const tEnd = hits[hits.length-1].time;
    const tSpan = tEnd - tStart;
    
    hits.forEach(h => {
      const p = v.points[h.index];
      const progress = tSpan > 0 ? (h.time - tStart) / tSpan : 0;
      const newVal = startVal + (endVal - startVal) * progress;
      
      if (view === "beat") p.beat = Math.max(0, newVal);
      else if (view === "base") p.base = Math.max(0, newVal);
      else if (view === "volume") {
        p.volL = clamp(newVal, 0, 1);
        p.volR = clamp(newVal, 0, 1);
      }
    });
  }
  
  engine.setSchedule(sched); // rebuild cache
  graph.draw();
  $("massEditDlg").style.display = "none";
};

`;

html = html.replace(anchor, newJs + anchor);
fs.writeFileSync('app/src/main/assets/index.html', html);
