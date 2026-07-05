const fs = require('fs');
let html = fs.readFileSync('app/src/main/assets/index.html', 'utf8');

const pointerDownRe = /canvas\.addEventListener\("pointerdown",e=>\{([\s\S]*?)\}\);/;
const pointerDownMatch = html.match(pointerDownRe);
if (!pointerDownMatch) throw new Error("Could not find pointerdown");

const originalTapLogic = pointerDownMatch[1].trim();

const newPointerDown = `canvas.addEventListener("pointerdown",e=>{
      const t=timeAt(e.clientX);
      if(t==null){ this.lockT=null; this.lockY=null; this.selVoice=null; this._hideTip(); this.draw(); return; }
      canvas.setPointerCapture(e.pointerId);
      this.dragStart = { x: e.clientX, y: e.clientY, cssY: this._cssYOf(e.clientY), t: t };
      this.isDragging = false;
      this.dragCurrent = null;
    });
    
    canvas.addEventListener("pointerup",e=>{
      if(!this.dragStart) return;
      canvas.releasePointerCapture(e.pointerId);
      if(this.isDragging && this.dragCurrent){
        this._handleDragEnd();
      } else {
        // It was a tap
        const t = this.dragStart.t;
        const cssY = this.dragStart.cssY;
        const hit=this._voiceHitAt(t,cssY);     // {voiceIndex,dist} or null
        this.lockT=t; this.hoverT=t; this.lockY=cssY;
        this.selVoice = hit ? hit.voiceIndex : null;
        this._drawTip(t,true);
      }
      this.dragStart = null;
      this.dragCurrent = null;
      this.isDragging = false;
      this.draw();
    });
    
    canvas.addEventListener("pointercancel",e=>{
      if(!this.dragStart) return;
      canvas.releasePointerCapture(e.pointerId);
      this.dragStart = null;
      this.dragCurrent = null;
      this.isDragging = false;
      this.draw();
    });`;

html = html.replace(pointerDownRe, newPointerDown);

const pointerMoveRe = /canvas\.addEventListener\("pointermove",e=>\{([\s\S]*?)\}\);/;
const originalPointerMove = html.match(pointerMoveRe)[0];
const newPointerMove = `canvas.addEventListener("pointermove",e=>{
      if(this.dragStart){
        const dx = e.clientX - this.dragStart.x;
        const dy = e.clientY - this.dragStart.y;
        if(!this.isDragging && (Math.abs(dx)>5 || Math.abs(dy)>5)){
          this.isDragging = true;
          this._hideTip();
          this.lockT = null;
        }
        if(this.isDragging){
          this.dragCurrent = { x: e.clientX, y: e.clientY, cssY: this._cssYOf(e.clientY), t: timeAt(e.clientX) };
          this.draw();
          return;
        }
      }
      const t=timeAt(e.clientX);
      this.hoverT=t;
      if(this.lockT!=null){ this.draw(); return; }   // pinned: keep tooltip+button untouched
      if(t==null){ this._hideTip(); }
      else this._drawTip(t,false);
      this.draw();
    });`;
html = html.replace(originalPointerMove, newPointerMove);

// Add _handleDragEnd to Graph
const rowsForGeometryRe = /  _rowsForGeometry\(t\)\{/;
html = html.replace(rowsForGeometryRe, `  _handleDragEnd(){
    if(!this.dragStart || !this.dragCurrent) return;
    const t1 = Math.min(this.dragStart.t, this.dragCurrent.t);
    const t2 = Math.max(this.dragStart.t, this.dragCurrent.t);
    const y1 = Math.min(this.dragStart.cssY, this.dragCurrent.cssY);
    const y2 = Math.max(this.dragStart.cssY, this.dragCurrent.cssY);
    
    if(this.onMassEditRequest){
      this.onMassEditRequest(t1, t2, y1, y2, this.view);
    }
  }
  _rowsForGeometry(t){`);
  
// Draw selection box in draw()
// Find end of draw() grid drawing
const drawGridRe = /const isVolume = this\.view==="volume";/;
html = html.replace(drawGridRe, `const isVolume = this.view==="volume";
    if(this.isDragging && this.dragStart && this.dragCurrent){
      const x1 = l + (this.dragStart.t / total) * gw;
      const x2 = l + (this.dragCurrent.t / total) * gw;
      const y1 = this.dragStart.cssY;
      const y2 = this.dragCurrent.cssY;
      c.fillStyle = "rgba(90, 209, 196, 0.2)";
      c.strokeStyle = "rgba(90, 209, 196, 0.8)";
      c.lineWidth = 1;
      c.fillRect(Math.min(x1,x2), Math.min(y1,y2), Math.abs(x2-x1), Math.abs(y2-y1));
      c.strokeRect(Math.min(x1,x2), Math.min(y1,y2), Math.abs(x2-x1), Math.abs(y2-y1));
    }`);

fs.writeFileSync('app/src/main/assets/index.html', html);
