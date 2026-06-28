const CACHE = "gnauri-v31";
const ASSETS = [
  "./", "./index.html", "./manifest.webmanifest", "./icon.svg", "./schedules.json",
  // Bundled schedules (from schedules.json) — precached so they play offline once installed.
  "./schedules/baa_wind_and_waves.gnaural",
  "./schedules/caa_spring_rain.gnaural",
  "./schedules/daa_energize.gnaural",
  "./schedules/eaa_power_nap.gnaural",
  "./schedules/faa_problem_resolver.gnaural",
  "./schedules/gaa_dream.gnaural",
  "./schedules/haa_wakeup.gnaural",
  "./schedules/iaa_tibetan_bowls.gnaural",
  "./schedules/jaa_travel_aid.gnaural",
  "./schedules/kaa_study_time.gnaural",
  "./schedules/laa_rain_shower.gnaural",
  "./schedules/maa_babbling_brook.gnaural",
  "./schedules/naa_instant_nap.gnaural",
  "./schedules/zaa_default_schedule.gnaural"
];

self.addEventListener("install", e => {
  // Tolerant precache: cache each asset individually so one missing/404 file (e.g. a renamed
  // schedule) does not abort the whole install the way c.addAll() would. The core app shell is
  // first in ASSETS and effectively required; individual schedules are best-effort.
  e.waitUntil(
    caches.open(CACHE)
      .then(c => Promise.all(ASSETS.map(a => c.add(a).catch(() => {}))))
      .then(() => self.skipWaiting())
  );
});
self.addEventListener("activate", e => {
  e.waitUntil(
    caches.keys().then(keys => Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});
// Network-first so code edits always load when online; fall back to cache when offline.
self.addEventListener("fetch", e => {
  if (e.request.method !== "GET") return;
  e.respondWith(
    fetch(e.request).then(res => {
      const copy = res.clone();
      caches.open(CACHE).then(c => c.put(e.request, copy)).catch(() => {});
      return res;
    }).catch(() => caches.match(e.request).then(hit => hit || caches.match("./index.html")))
  );
});
