const fs = require('fs');
let html = fs.readFileSync('app/src/main/assets/index.html', 'utf8');

// Insert Export / Scale Time button
const exportBtnRe = /<button id="exportBtn" title="Download the current \(edited\) schedule as a \.gnaural file">💾 Export<\/button>/;
html = html.replace(exportBtnRe, `<button id="exportBtn" title="Download the current (edited) schedule as a .gnaural file">💾 Export</button>
      <button id="scaleTimeBtn" title="Scale the schedule duration" style="margin-left: 6px;">⏳ Scale Time</button>`);

// Insert Dialog
const dialogInsertRe = /<div class="overlay" id="editDlg" style="display:none">/;
html = html.replace(dialogInsertRe, `<div class="overlay" id="massEditDlg" style="display:none">
  <div class="dlg">
    <h3>Mass Adjust Points</h3>
    <p id="massEditStats" class="sub" style="margin-bottom:12px;"></p>
    
    <div style="margin-bottom:10px;">
      <label>Mode:</label>
      <select id="massEditMode" style="width:100%; padding:6px; background:var(--panel2); color:var(--txt); border:1px solid var(--line); border-radius:4px; margin-top:4px;">
        <option value="shift">Shift (Add/Subtract)</option>
        <option value="ramp">Ramp (Gradual transition)</option>
      </select>
    </div>
    
    <div id="massEditShiftPane">
      <label>Delta (can be negative):</label>
      <input type="number" id="massEditDelta" step="0.1" value="0" style="width:100%; padding:6px; background:var(--panel2); color:var(--txt); border:1px solid var(--line); border-radius:4px; margin-top:4px;" />
    </div>
    
    <div id="massEditRampPane" style="display:none">
      <div style="display:flex; gap:8px;">
        <div style="flex:1">
          <label>Start Value:</label>
          <input type="number" id="massEditStart" step="0.1" value="0" style="width:100%; padding:6px; background:var(--panel2); color:var(--txt); border:1px solid var(--line); border-radius:4px; margin-top:4px;" />
        </div>
        <div style="flex:1">
          <label>End Value:</label>
          <input type="number" id="massEditEnd" step="0.1" value="0" style="width:100%; padding:6px; background:var(--panel2); color:var(--txt); border:1px solid var(--line); border-radius:4px; margin-top:4px;" />
        </div>
      </div>
    </div>

    <div class="row" style="margin-top:16px; justify-content:flex-end;">
      <button onclick="$('massEditDlg').style.display='none'" class="btn">Cancel</button>
      <button id="massEditApply" class="btn primary">Apply</button>
    </div>
  </div>
</div>

<div class="overlay" id="editDlg" style="display:none">`);

fs.writeFileSync('app/src/main/assets/index.html', html);
