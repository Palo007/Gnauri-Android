const fs = require('fs');
let html = fs.readFileSync('app/src/main/assets/index.html', 'utf8');

const scaleBtnOld = /\$\("scaleTimeBtn"\)\.onclick = \(\) => \{([\s\S]*?)\};\n\n\/\/ Mass Edit feature/;
const scaleMatch = html.match(scaleBtnOld);

const newScaleBtn = `
// Scale Time feature
$("scaleTimeBtn").onclick = () => {
  if (!sched || !sched.totalTime) return;
  $("scaleTimeCurrent").textContent = "Current: " + fmt(sched.totalTime) + " (" + sched.totalTime.toFixed(1) + "s)";
  $("scaleTimeInput").value = sched.totalTime.toFixed(1);
  $("scaleTimeDlg").style.display = "flex";
};

$("scaleTimeApply").onclick = () => {
  const resp = $("scaleTimeInput").value.trim();
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
      p.duration *= factor;
    });
  });
  sched.totalTime = newTotal;
  
  // Update Engine and UI
  engine.setSchedule(sched);
  $("mDur").textContent=fmt(sched.totalTime)+" ("+Math.round(sched.totalTime)+"s)";
  $("timeLbl").innerHTML = "<strong style='font-size:1.15em'>" + fmtElapsed(engine._played*sched.totalTime, sched.totalTime, sched.loops) + "</strong>";
  graph.setPlay(engine._played*sched.totalTime); // approximate update
  graph.draw();
  $("scaleTimeDlg").style.display = "none";
};

// Mass Edit feature`;

html = html.replace(scaleMatch[0], newScaleBtn);

const editDlgHtml = /<div class="overlay" id="editDlg" style="display:none">/;
const scaleDlgHtml = `<div class="overlay" id="scaleTimeDlg" style="display:none">
  <div class="dlg">
    <h3>Scale Schedule Time</h3>
    <p id="scaleTimeCurrent" class="sub" style="margin-bottom:12px;"></p>
    
    <label>New Total Time (seconds) or Multiplier (e.g. *1.5):</label>
    <input type="text" id="scaleTimeInput" style="width:100%; padding:6px; background:var(--panel2); color:var(--txt); border:1px solid var(--line); border-radius:4px; margin-top:4px;" />

    <div class="row" style="margin-top:16px; justify-content:flex-end;">
      <button onclick="$('scaleTimeDlg').style.display='none'" class="btn">Cancel</button>
      <button id="scaleTimeApply" class="btn primary">Apply</button>
    </div>
  </div>
</div>

<div class="overlay" id="editDlg" style="display:none">`;

html = html.replace(editDlgHtml, scaleDlgHtml);

fs.writeFileSync('app/src/main/assets/index.html', html);
