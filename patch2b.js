const fs = require('fs');
let html = fs.readFileSync('app/src/main/assets/index.html', 'utf8');

const drawTipRe = /html\+=`<button class="editbtn" id="tipEditBtn">✎ Edit \$\{\(v\.desc\|\|\("Voice "\+v\.index\)\)\}.*?<\/button>`;/;
html = html.replace(drawTipRe, `html+=\`<button class="editbtn" id="tipEditBtn" style="margin-bottom:4px;">✎ Edit \${(v.desc||("Voice "+v.index))} point · \${label}</button>\`;
        html+=\`<button class="editbtn" id="tipMassEditBtn">🪄 Mass adjust whole voice</button>\`;`);

const btnWireRe = /if\(btn\) btn\.onclick=\(ev\)=>\{ ev\.stopPropagation\(\);\n        if\(this\.onEditRequest\) this\.onEditRequest\(editTarget\.voiceIndex, editTarget\.time\); \};/;
html = html.replace(btnWireRe, `if(btn) btn.onclick=(ev)=>{ ev.stopPropagation();
        if(this.onEditRequest) this.onEditRequest(editTarget.voiceIndex, editTarget.time); };
      const massBtn=this.tip.querySelector("#tipMassEditBtn");
      if(massBtn) massBtn.onclick=(ev)=>{ ev.stopPropagation();
        if(this.onMassEditVoiceRequest) this.onMassEditVoiceRequest(editTarget.voiceIndex, this.view); };`);

const newMassEditVoice = `graph.onMassEditVoiceRequest = (vi, view) => {
  if (!sched) return;
  const v = sched.voices[vi];
  let ptsInRect = [];
  let time = 0;
  for(let i=0; i<v.points.length; i++) {
    const p = v.points[i];
    let pv = 0;
    if (view === "beat") pv = p.beat;
    else if (view === "base") pv = p.base;
    else if (view === "volume") pv = p.volL;
    
    ptsInRect.push({index: i, time: time, origVal: pv});
    time += p.duration;
  }
  
  massEditState = {
    vi: vi,
    hits: ptsInRect,
    view: view
  };
  
  const vType = v.type;
  let viewName = view;
  if(vType !== 0 && view === "beat") viewName = "pulse";
  
  $("massEditStats").textContent = \`Selected ALL \${ptsInRect.length} points of voice \${vi+1} in \${viewName} view.\`;
  
  $("massEditStart").value = ptsInRect[0].origVal.toFixed(view === "volume" ? 2 : 1);
  $("massEditEnd").value = ptsInRect[ptsInRect.length-1].origVal.toFixed(view === "volume" ? 2 : 1);
  $("massEditDelta").value = 0;
  
  $("massEditDlg").style.display = "flex";
};

`;

const onMassEditReqRe = /graph\.onMassEditRequest =/;
html = html.replace(onMassEditReqRe, newMassEditVoice + "\ngraph.onMassEditRequest =");

fs.writeFileSync('app/src/main/assets/index.html', html);
