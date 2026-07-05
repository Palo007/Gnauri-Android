const { JSDOM } = require("jsdom");
const dom = new JSDOM(`<!DOCTYPE html><html><body><select id="massEditVoiceSelect"></select></body></html>`);
global.document = dom.window.document;
const $ = id => document.getElementById(id);
const select = $("massEditVoiceSelect");
const hitsByVoice = new Map();
hitsByVoice.set(0, [1, 2]);
hitsByVoice.set(1, [3, 4]);

const sched = {
  voices: [
    { index: 0, desc: "Alpha" },
    { index: 1, desc: "Beta" }
  ]
};

const validVoiceIndices = Array.from(hitsByVoice.keys()).sort((a, b) => a - b);
validVoiceIndices.forEach((vIndexNum) => {
  const v = sched.voices[vIndexNum];
  const opt = document.createElement("option");
  opt.value = vIndexNum;
  const desc = (v.desc && v.desc !== '—') ? ` (${v.desc})` : '';
  opt.textContent = `voice ${vIndexNum + 1}${desc}`;
  select.appendChild(opt);
});

if (hitsByVoice.size > 1) {
  const optAll = document.createElement("option");
  optAll.value = "all";
  optAll.textContent = `all ${hitsByVoice.size} selected voices`;
  select.appendChild(optAll);
}

console.log(select.outerHTML);
