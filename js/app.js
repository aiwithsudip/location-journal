import {
  detectStays,
  formatClock,
  formatDuration,
  formatWhen,
  hourKey,
  matchPhotos,
} from "./geo.js";
import { deletePhoto, getAllPhotos, getAllPoints, getMeta, putPhoto, putPoint, setMeta } from "./db.js";
import { parseJpegExif } from "./exif.js";

const SAMPLE_MS = 5 * 60 * 1000;
const GEO_OPTIONS = { enableHighAccuracy: true, maximumAge: 60 * 1000, timeout: 25 * 1000 };

const statusEl = document.querySelector("#status");
const nextEl = document.querySelector("#next-hour");
const logEl = document.querySelector("#hourly-log");
const staysEl = document.querySelector("#stays");
const looseEl = document.querySelector("#loose");
const trackBtn = document.querySelector("#toggle-tracking");
const recordBtn = document.querySelector("#record-now");
const photoInput = document.querySelector("#photo-input");
const folderInput = document.querySelector("#folder-input");
const exportBtn = document.querySelector("#export-log");
const clearPhotosBtn = document.querySelector("#clear-photos");

let map;
let stayLayer;
let pathLayer;
let tracking = false;
let sampleTimer = 0;
let hourTimer = 0;
let points = [];
let photos = [];
let objectUrls = [];
let nameCache = {};

function setStatus(text, kind) {
  statusEl.textContent = text;
  statusEl.dataset.kind = kind ?? "idle";
}

function releaseUrls() {
  for (const url of objectUrls) URL.revokeObjectURL(url);
  objectUrls = [];
}

function currentPosition() {
  return new Promise((resolve, reject) => {
    if (!navigator.geolocation) {
      reject(new Error("This browser has no location service."));
      return;
    }
    navigator.geolocation.getCurrentPosition(resolve, reject, GEO_OPTIONS);
  });
}

async function saveFix(position, { hourly }) {
  const t = position.timestamp || Date.now();
  const { latitude, longitude, accuracy } = position.coords;
  const sample = {
    id: `sample:${t}`,
    t,
    lat: latitude,
    lng: longitude,
    accuracy: Math.round(accuracy),
    kind: "sample",
  };
  await putPoint(sample);

  const key = hourKey(t);
  const hourlyId = `hourly:${key}`;
  const existing = points.find((p) => p.id === hourlyId);
  let hourlyPoint = existing;
  if (hourly || !existing) {
    hourlyPoint = {
      id: hourlyId,
      t,
      lat: latitude,
      lng: longitude,
      accuracy: Math.round(accuracy),
      kind: "hourly",
      hour: key,
    };
    await putPoint(hourlyPoint);
  }

  const byId = new Map(points.map((p) => [p.id, p]));
  byId.set(sample.id, sample);
  if (hourlyPoint) byId.set(hourlyPoint.id, hourlyPoint);
  points = [...byId.values()];
  render();
  return hourlyPoint ?? sample;
}

function nativeJournal() {
  return window.AndroidJournal || null;
}

function syncNativePoints() {
  const native = nativeJournal();
  if (!native) return Promise.resolve();
  let incoming = [];
  try {
    incoming = JSON.parse(native.pullFixes() || "[]");
  } catch {
    incoming = [];
  }
  const byId = new Map(points.map((p) => [p.id, p]));
  const writes = [];
  for (const point of incoming) {
    if (!point || !point.id || byId.has(point.id)) continue;
    byId.set(point.id, point);
    writes.push(putPoint(point));
  }
  points = [...byId.values()];
  return Promise.all(writes).then(() => {
    if (writes.length) render();
  });
}

async function takeFix(reason) {
  const native = nativeJournal();
  if (native) {
    setStatus("Finding your location…", "busy");
    native.requestFix(reason);
    await new Promise((resolve) => {
      const timer = setTimeout(resolve, 30000);
      window.__onNativePoints = async () => {
        clearTimeout(timer);
        await syncNativePoints();
        resolve();
      };
    });
    const key = hourKey(Date.now());
    const saved = points.find((p) => p.id === `hourly:${key}`) || points[points.length - 1];
    if (saved) setStatus(`Position saved. ${formatWhen(saved.t)}.`, "ok");
    else setStatus("Still waiting for a GPS fix. Keep location on.", "busy");
    if (tracking) nextEl.textContent = "The phone saves the next hour while tracking stays on.";
    return;
  }
  setStatus(`Finding your location (${reason})…`, "busy");
  try {
    const position = await currentPosition();
    const saved = await saveFix(position, { hourly: reason === "this hour" || reason === "now" });
    const hourlyNote = saved.kind === "hourly" ? "Hourly fix saved." : "Position saved.";
    setStatus(`${hourlyNote} ${formatWhen(saved.t)}.`, "ok");
    scheduleHourly();
  } catch (error) {
    const message = error && error.message ? error.message : "Location was denied or unavailable.";
    setStatus(message, "bad");
  }
}

function scheduleHourly() {
  clearTimeout(hourTimer);
  if (!tracking) {
    nextEl.textContent = "Tracking is off.";
    return;
  }
  const now = new Date();
  const next = new Date(now);
  next.setMinutes(0, 0, 0);
  next.setHours(now.getHours() + 1);
  const wait = Math.max(1000, next.getTime() - now.getTime());
  nextEl.textContent = `Next hourly save at ${formatClock(next.getTime())}.`;
  hourTimer = setTimeout(async () => {
    if (!tracking) return;
    await takeFix("this hour");
  }, wait);
}

async function setTracking(on) {
  tracking = on;
  await setMeta("tracking", on);
  trackBtn.textContent = on ? "Stop tracking" : "Start tracking";
  trackBtn.setAttribute("aria-pressed", String(on));
  clearInterval(sampleTimer);
  clearTimeout(hourTimer);
  if (!on) {
    if (nativeJournal()) nativeJournal().setTracking(false);
    nextEl.textContent = "Tracking is off. Saved places stay on this device.";
    setStatus("Tracking is paused.", "idle");
    return;
  }
  if (nativeJournal()) {
    nativeJournal().setTracking(true);
    document.querySelector("#stay-hint").textContent =
      "The phone records about every five minutes and keeps one fix each hour, including after you leave this screen. A notification stays up while tracking is on.";
    nextEl.textContent = "Waiting for the first fix…";
    await takeFix("this hour");
    return;
  }
  const key = hourKey(Date.now());
  const haveHour = points.some((p) => p.id === `hourly:${key}`);
  await takeFix(haveHour ? "sample" : "this hour");
  sampleTimer = setInterval(() => {
    if (tracking) takeFix("sample");
  }, SAMPLE_MS);
}

function renderLog(hourly) {
  if (hourly.length === 0) {
    logEl.innerHTML = `<li class="empty">No hourly fixes yet. Start tracking and leave this tab open.</li>`;
    return;
  }
  logEl.innerHTML = hourly
    .slice(0, 48)
    .map(
      (p) => `<li>
        <time datetime="${new Date(p.t).toISOString()}">${formatWhen(p.t)}</time>
        <span>${p.lat.toFixed(5)}, ${p.lng.toFixed(5)}</span>
        <span class="muted">±${p.accuracy ?? "?"} m</span>
      </li>`,
    )
    .join("");
}

function slideshowHtml(items, label) {
  if (items.length === 0) {
    return `<p class="empty-photos">No gallery photos match this stay. Photos need a capture time, or a location, that lines up with it.</p>`;
  }
  const slides = items
    .map((photo, index) => {
      const url = URL.createObjectURL(photo.blob);
      objectUrls.push(url);
      const when = photo.t ? formatWhen(photo.t) : photo.name;
      return `<figure class="slide${index === 0 ? " is-active" : ""}" data-index="${index}">
        <img src="${url}" alt="${escapeAttr(photo.name)}" />
        <figcaption>${escapeHtml(when)}</figcaption>
      </figure>`;
    })
    .join("");
  return `<div class="slideshow" data-count="${items.length}" aria-label="${escapeAttr(label)}">
    <div class="slides">${slides}</div>
    <div class="slide-controls">
      <button type="button" class="prev" aria-label="Previous photo">Prev</button>
      <span class="slide-count">1 / ${items.length}</span>
      <button type="button" class="next" aria-label="Next photo">Next</button>
    </div>
  </div>`;
}

function escapeHtml(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;");
}

function escapeAttr(value) {
  return escapeHtml(value).replaceAll('"', "&quot;");
}

function renderStays(stays, placed) {
  if (stays.length === 0) {
    staysEl.innerHTML = `<article class="empty-card">
      <h3>No stays of 30 minutes yet</h3>
      <p>A stay appears when two or more fixes, inside about 200 meters, span at least 30 minutes. Leave this page open while you are somewhere.</p>
    </article>`;
    return;
  }

  staysEl.innerHTML = stays
    .slice()
    .reverse()
    .map((stay) => {
      const photosHere = placed.get(stay.id) ?? [];
      const name = nameCache[stay.id] || `${stay.lat.toFixed(4)}, ${stay.lng.toFixed(4)}`;
      return `<article class="stay" id="${stay.id}">
        <header>
          <p class="kicker">${formatDuration(stay.duration)} · ${stay.samples} fixes</p>
          <h3>${escapeHtml(name)}</h3>
          <p class="when">${formatWhen(stay.start)} – ${formatClock(stay.end)}</p>
        </header>
        ${slideshowHtml(photosHere, `Photos from ${name}`)}
      </article>`;
    })
    .join("");
  wireSlideshows(staysEl);
}

function renderLoose(loose) {
  if (loose.length === 0) {
    looseEl.hidden = true;
    looseEl.innerHTML = "";
    return;
  }
  looseEl.hidden = false;
  looseEl.innerHTML = `<article class="stay">
    <header>
      <p class="kicker">${loose.length} photo${loose.length === 1 ? "" : "s"}</p>
      <h3>Not tied to a stay</h3>
      <p class="when">These did not fall inside a 30-minute place, by time or by map location.</p>
    </header>
    ${slideshowHtml(loose, "Photos not tied to a stay")}
  </article>`;
  wireSlideshows(looseEl);
}

function wireSlideshows(root) {
  for (const show of root.querySelectorAll(".slideshow")) {
    const slides = [...show.querySelectorAll(".slide")];
    const count = show.querySelector(".slide-count");
    let index = 0;
    const showIndex = (next) => {
      index = (next + slides.length) % slides.length;
      slides.forEach((slide, i) => slide.classList.toggle("is-active", i === index));
      if (count) count.textContent = `${index + 1} / ${slides.length}`;
    };
    show.querySelector(".prev")?.addEventListener("click", () => showIndex(index - 1));
    show.querySelector(".next")?.addEventListener("click", () => showIndex(index + 1));
    if (slides.length > 1) {
      const timer = setInterval(() => {
        if (!show.isConnected) {
          clearInterval(timer);
          return;
        }
        if (show.getBoundingClientRect().bottom < 0 || show.getBoundingClientRect().top > innerHeight) return;
        showIndex(index + 1);
      }, 3500);
    }
  }
}

function renderMap(stays, hourly) {
  if (!map) {
    map = L.map("map", { zoomControl: true, scrollWheelZoom: true });
    L.tileLayer("https://{s}.basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}{r}.png", {
      attribution: '&copy; OpenStreetMap &copy; CARTO',
      subdomains: "abcd",
      maxZoom: 19,
    }).addTo(map);
    pathLayer = L.layerGroup().addTo(map);
    stayLayer = L.layerGroup().addTo(map);
    map.setView([20, 0], 2);
  }
  pathLayer.clearLayers();
  stayLayer.clearLayers();

  const trail = hourly.map((p) => [p.lat, p.lng]);
  if (trail.length > 1) {
    L.polyline(trail, { color: "#8d5a3c", weight: 3, opacity: 0.75 }).addTo(pathLayer);
  }
  for (const point of hourly) {
    L.circleMarker([point.lat, point.lng], {
      radius: 4,
      color: "#3d4a3a",
      weight: 1,
      fillColor: "#efe6d6",
      fillOpacity: 1,
    })
      .bindTooltip(formatWhen(point.t))
      .addTo(pathLayer);
  }

  const bounds = [];
  for (const stay of stays) {
    const name = nameCache[stay.id] || "Stay";
    L.circle([stay.lat, stay.lng], {
      radius: 180,
      color: "#b5522a",
      weight: 2,
      fillColor: "#b5522a",
      fillOpacity: 0.18,
    }).addTo(stayLayer);
    L.marker([stay.lat, stay.lng])
      .bindPopup(
        `<strong>${escapeHtml(name)}</strong><br>${formatDuration(stay.duration)}<br>${formatWhen(stay.start)}`,
      )
      .addTo(stayLayer);
    bounds.push([stay.lat, stay.lng]);
  }
  for (const point of hourly) bounds.push([point.lat, point.lng]);
  if (bounds.length === 1) map.setView(bounds[0], 15);
  else if (bounds.length > 1) map.fitBounds(bounds, { padding: [32, 32], maxZoom: 15 });
}

function render() {
  releaseUrls();
  const hourly = points.filter((p) => p.kind === "hourly").sort((a, b) => b.t - a.t);
  const stays = detectStays(points);
  const { placed, loose } = matchPhotos(stays, photos);
  renderLog(hourly);
  renderStays(stays, placed);
  renderLoose(loose);
  renderMap(stays, [...hourly].reverse());
  document.querySelector("#stay-count").textContent = String(stays.length);
  document.querySelector("#hour-count").textContent = String(hourly.length);
  document.querySelector("#photo-count").textContent = String(photos.length);
  void labelStays(stays);
}

let labelToken = 0;
async function labelStays(stays) {
  const token = ++labelToken;
  let changed = false;
  for (const stay of stays) {
    if (nameCache[stay.id]) continue;
    const cached = await getMeta(`name:${stay.id}`);
    if (token !== labelToken) return;
    if (cached) {
      nameCache[stay.id] = cached;
      changed = true;
      continue;
    }
    try {
      const url = `https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=${stay.lat}&lon=${stay.lng}&zoom=18`;
      const response = await fetch(url, { headers: { Accept: "application/json" } });
      if (!response.ok) continue;
      const body = await response.json();
      const name = body.name || body.address?.suburb || body.address?.neighbourhood || body.display_name?.split(",")[0];
      if (!name) continue;
      nameCache[stay.id] = name;
      await setMeta(`name:${stay.id}`, name);
      changed = true;
      await new Promise((resolve) => setTimeout(resolve, 1100));
    } catch {
      /* Place names are optional. Coordinates remain. */
    }
  }
  if (changed && token === labelToken) render();
}

async function importPhotos(fileList) {
  const files = [...fileList].filter((file) => file.type.startsWith("image/") || /\.(jpe?g|png|heic|webp|gif)$/i.test(file.name));
  if (files.length === 0) {
    setStatus("No images were in that selection.", "bad");
    return;
  }
  setStatus(`Reading ${files.length} photo${files.length === 1 ? "" : "s"}…`, "busy");
  let added = 0;
  for (const file of files) {
    const id = `${file.name}:${file.size}:${file.lastModified}`;
    if (photos.some((photo) => photo.id === id)) continue;
    let t = file.lastModified;
    let lat = null;
    let lng = null;
    if (/jpe?g$/i.test(file.name) || file.type === "image/jpeg") {
      try {
        const buffer = await file.slice(0, Math.min(file.size, 512 * 1024)).arrayBuffer();
        const exif = parseJpegExif(buffer);
        if (exif.t) t = exif.t;
        if (exif.lat != null) {
          lat = exif.lat;
          lng = exif.lng;
        }
      } catch {
        /* Keep the file time when EXIF cannot be read. */
      }
    }
    const photo = { id, name: file.name, t, lat, lng, type: file.type || "image/jpeg", blob: file };
    await putPhoto(photo);
    photos.push(photo);
    added += 1;
  }
  setStatus(added === 0 ? "Those photos were already saved." : `Saved ${added} photo${added === 1 ? "" : "s"}.`, "ok");
  render();
}

async function exportLog() {
  const payload = {
    exportedAt: new Date().toISOString(),
    points: points.map(({ id, t, lat, lng, accuracy, kind, hour }) => ({ id, t, lat, lng, accuracy, kind, hour })),
  };
  const blob = new Blob([JSON.stringify(payload, null, 2)], { type: "application/json" });
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = "location-journal.json";
  link.click();
  URL.revokeObjectURL(url);
}

async function clearPhotos() {
  for (const photo of photos) await deletePhoto(photo.id);
  photos = [];
  setStatus("Gallery copies were removed from this app.", "ok");
  render();
}

trackBtn.addEventListener("click", () => setTracking(!tracking));
recordBtn.addEventListener("click", () => takeFix("now"));
for (const input of [photoInput, folderInput]) {
  input.addEventListener("change", () => {
    if (input.files?.length) void importPhotos(input.files);
    input.value = "";
  });
}
exportBtn.addEventListener("click", () => exportLog());
clearPhotosBtn.addEventListener("click", () => {
  if (photos.length === 0) return;
  if (confirm("Remove saved photo copies from this app? Your gallery is not changed.")) void clearPhotos();
});

document.addEventListener("visibilitychange", () => {
  if (document.visibilityState === "visible" && tracking) {
    const key = hourKey(Date.now());
    if (!points.some((p) => p.id === `hourly:${key}`)) void takeFix("this hour");
    else scheduleHourly();
  }
});

window.__onNativePoints = () => syncNativePoints();
window.__nativeTrackingResult = (ok, message) => {
  if (!ok) {
    tracking = false;
    trackBtn.textContent = "Start tracking";
    trackBtn.setAttribute("aria-pressed", "false");
    setStatus(message || "Location permission is required.", "bad");
  }
};

async function init() {
  points = await getAllPoints();
  photos = await getAllPhotos();
  if (nativeJournal()) await syncNativePoints();
  tracking = nativeJournal() ? nativeJournal().isTracking() : Boolean(await getMeta("tracking"));
  trackBtn.textContent = tracking ? "Stop tracking" : "Start tracking";
  trackBtn.setAttribute("aria-pressed", String(tracking));
  render();
  if (tracking) await setTracking(true);
  else {
    setStatus("Tracking is off until you start it.", "idle");
    nextEl.textContent = "Hourly saves run while this tab stays open.";
  }
}

init().catch((error) => {
  setStatus(error.message || "The journal could not open its saved data.", "bad");
});
