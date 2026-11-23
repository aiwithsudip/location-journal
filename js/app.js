import {
  detectStays,
  formatClock,
  formatDistance,
  formatDuration,
  formatWhen,
  haversineMeters,
  hourKey,
  matchPhotos,
  pathLengthMeters,
} from "./geo.js";
import { deletePhoto, deletePoint, getAllPhotos, getAllPoints, getMeta, putPhoto, putPoint, setMeta } from "./db.js";
import { parseJpegExif } from "./exif.js";
import { buildMockTrip } from "./mock.js";

const SAMPLE_MS = 20 * 1000;
const GEO_OPTIONS = { enableHighAccuracy: true, maximumAge: 15 * 1000, timeout: 20 * 1000 };

const statusEl = document.querySelector("#status");
const nextEl = document.querySelector("#next-hour");
const chipEl = document.querySelector("#live-chip");
const logEl = document.querySelector("#hourly-log");
const staysEl = document.querySelector("#stays");
const looseEl = document.querySelector("#loose");
const routeEl = document.querySelector("#panel-route");
const trackBtn = document.querySelector("#toggle-tracking");
const recordBtn = document.querySelector("#record-now");
const photoInput = document.querySelector("#photo-input");
const folderInput = document.querySelector("#folder-input");
const exportBtn = document.querySelector("#export-log");
const clearPhotosBtn = document.querySelector("#clear-photos");
const followBtn = document.querySelector("#follow-map");
const replayBtn = document.querySelector("#replay");
const cameraSheet = document.querySelector("#camera-sheet");
const cameraPreview = document.querySelector("#camera-preview");

let map;
let stayLayer;
let pathLayer;
let replayMarker;
let tracking = false;
let follow = false;
let sampleTimer = 0;
let hourTimer = 0;
let photoTimer = 0;
let replayTimer = 0;
let points = [];
let photos = [];
let objectUrls = [];
let nameCache = {};
let cameraStream = null;
let mapReady = false;

function setStatus(text, kind) {
  statusEl.textContent = text;
  statusEl.dataset.kind = kind ?? "idle";
  chipEl.textContent = tracking ? "Tracking" : kind === "busy" ? "Working" : "Idle";
  chipEl.dataset.kind = tracking ? "ok" : kind ?? "idle";
}

function releaseUrls() {
  for (const url of objectUrls) URL.revokeObjectURL(url);
  objectUrls = [];
}

function nativeJournal() {
  return window.AndroidJournal || null;
}

function trailPoints() {
  const samples = points.filter((p) => p.kind === "sample");
  return (samples.length ? samples : points).slice().sort((a, b) => a.t - b.t);
}

function latestPoint() {
  const trail = trailPoints();
  return trail[trail.length - 1] ?? null;
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
    const saved = latestPoint();
    if (saved) setStatus(`Position saved. ${formatWhen(saved.t)}.`, "ok");
    else setStatus("Still waiting for a GPS fix. Keep location on.", "busy");
    if (tracking) nextEl.textContent = "A fix is saved about every 20 seconds, plus one each hour.";
    return;
  }
  setStatus("Finding your location…", "busy");
  try {
    const position = await currentPosition();
    const saved = await saveFix(position, { hourly: reason === "this hour" || reason === "now" });
    setStatus(`Position saved. ${formatWhen(saved.t)}.`, "ok");
    if (saved.kind !== "hourly") scheduleHourly();
    else scheduleHourly();
  } catch (error) {
    setStatus(error?.message || "Location was denied or unavailable.", "bad");
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
  nextEl.textContent = `Next hourly save at ${formatClock(next.getTime())}. Track points every 20 seconds.`;
  hourTimer = setTimeout(() => {
    if (tracking) void takeFix("this hour");
  }, Math.max(1000, next.getTime() - now.getTime()));
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
    follow = false;
    followBtn.setAttribute("aria-pressed", "false");
    nextEl.textContent = "Tracking is off. The route stays on this device.";
    setStatus("Tracking is paused.", "idle");
    return;
  }
  follow = true;
  followBtn.setAttribute("aria-pressed", "true");
  if (nativeJournal()) {
    nativeJournal().setTracking(true);
    document.querySelector("#stay-hint").textContent =
      "The phone stores a position every 20 seconds and one fix each hour, even after you leave this screen.";
    nextEl.textContent = "Waiting for the first fix…";
    sampleTimer = setInterval(() => {
      if (tracking) void syncNativePoints();
    }, SAMPLE_MS);
    await takeFix("this hour");
    return;
  }
  const key = hourKey(Date.now());
  const haveHour = points.some((p) => p.id === `hourly:${key}`);
  await takeFix(haveHour ? "sample" : "this hour");
  sampleTimer = setInterval(() => {
    if (tracking) void takeFix("sample");
  }, SAMPLE_MS);
}

function escapeHtml(value) {
  return String(value).replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;");
}
function escapeAttr(value) {
  return escapeHtml(value).replaceAll('"', "&quot;");
}

function slideshowHtml(items, label) {
  if (items.length === 0) {
    return `<p class="empty-photos">No photos for this stop yet. Use Take photo, auto photo, or the gallery.</p>`;
  }
  const slides = items
    .map((photo, index) => {
      const url = URL.createObjectURL(photo.blob);
      objectUrls.push(url);
      const when = photo.t ? formatWhen(photo.t) : photo.name;
      return `<figure class="slide${index === 0 ? " is-active" : ""}">
        <img src="${url}" alt="${escapeAttr(photo.name)}" />
        <figcaption>${escapeHtml(when)}</figcaption>
      </figure>`;
    })
    .join("");
  return `<div class="slideshow" aria-label="${escapeAttr(label)}">
    <div class="slides">${slides}</div>
    <div class="slide-controls">
      <button type="button" class="prev">Prev</button>
      <span class="slide-count">1 / ${items.length}</span>
      <button type="button" class="next">Next</button>
    </div>
  </div>`;
}

function wireSlideshows(root) {
  for (const show of root.querySelectorAll(".slideshow")) {
    const slides = [...show.querySelectorAll(".slide")];
    const count = show.querySelector(".slide-count");
    let index = 0;
    const showIndex = (next) => {
      if (slides.length === 0) return;
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
        const box = show.getBoundingClientRect();
        if (box.bottom < 0 || box.top > innerHeight) return;
        showIndex(index + 1);
      }, 3500);
    }
  }
}

function renderRoute(trail) {
  const recent = trail.slice(-12).reverse();
  if (recent.length === 0) {
    routeEl.innerHTML = `<article class="empty-card"><h3>No route yet</h3><p>Start tracking to draw where you go, or load the sample day to see a full path, stays, and photos.</p></article>`;
    return;
  }
  routeEl.innerHTML = recent
    .map(
      (point) => `<button type="button" class="route-item" data-lat="${point.lat}" data-lng="${point.lng}">
        <span class="dot"></span>
        <span><strong>${formatWhen(point.t)}</strong><br><span class="muted">${point.lat.toFixed(5)}, ${point.lng.toFixed(5)}</span></span>
        <span class="muted">±${point.accuracy ?? "?"} m</span>
      </button>`,
    )
    .join("");
  routeEl.querySelectorAll(".route-item").forEach((button) => {
    button.addEventListener("click", () => {
      focusMap(Number(button.dataset.lat), Number(button.dataset.lng));
    });
  });
}

function renderStays(stays, placed) {
  if (stays.length === 0) {
    staysEl.innerHTML = `<article class="empty-card"><h3>No stays of 30 minutes yet</h3><p>Stand in one area while tracking is on. After half an hour the place appears here with its photos.</p></article>`;
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
          <button type="button" class="ghost jump" data-lat="${stay.lat}" data-lng="${stay.lng}">Show on map</button>
        </header>
        ${slideshowHtml(photosHere, `Photos from ${name}`)}
      </article>`;
    })
    .join("");
  staysEl.querySelectorAll(".jump").forEach((button) => {
    button.addEventListener("click", () => focusMap(Number(button.dataset.lat), Number(button.dataset.lng)));
  });
  wireSlideshows(staysEl);
}

function renderLoose(loose, allPhotos) {
  const album = allPhotos.slice().sort((a, b) => (a.t ?? 0) - (b.t ?? 0));
  if (album.length === 0) {
    looseEl.innerHTML = `<article class="empty-card"><h3>No photos yet</h3><p>Take one with the camera, turn on auto photo, or add pictures from the gallery.</p></article>`;
    return;
  }
  looseEl.innerHTML = `<article class="stay">
      <header><p class="kicker">${album.length} saved</p><h3>All photos</h3></header>
      ${slideshowHtml(album, "All photos")}
    </article>
    ${
      loose.length
        ? `<article class="stay"><header><p class="kicker">${loose.length} unmatched</p><h3>Not tied to a stay</h3></header>${slideshowHtml(loose, "Unmatched photos")}</article>`
        : ""
    }`;
  wireSlideshows(looseEl);
}

function renderLog(hourly) {
  if (hourly.length === 0) {
    logEl.innerHTML = `<li class="empty">No hourly fixes yet.</li>`;
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

function focusMap(lat, lng) {
  if (!map || !Number.isFinite(lat)) return;
  follow = false;
  followBtn.setAttribute("aria-pressed", "false");
  map.setView([lat, lng], Math.max(map.getZoom(), 16));
}

function renderMap(stays, trail) {
  if (typeof L === "undefined") throw new Error("Map library failed to load.");
  if (!map) {
    map = L.map("map", { zoomControl: true, scrollWheelZoom: true });
    const tiles = L.tileLayer("https://tile.openstreetmap.org/{z}/{x}/{y}.png", {
      attribution: "&copy; OpenStreetMap",
      maxZoom: 19,
    }).addTo(map);
    const mapError = document.querySelector("#map-error");
    tiles.on("tileerror", () => {
      mapError.hidden = false;
    });
    tiles.on("tileload", () => {
      mapError.hidden = true;
    });
    pathLayer = L.layerGroup().addTo(map);
    stayLayer = L.layerGroup().addTo(map);
    map.on("dragstart", () => {
      follow = false;
      followBtn.setAttribute("aria-pressed", "false");
    });
    map.setView([27.663, 84.425], 13);
    requestAnimationFrame(() => map.invalidateSize());
  }
  pathLayer.clearLayers();
  stayLayer.clearLayers();
  const line = trail.map((p) => [p.lat, p.lng]);
  if (line.length > 1) {
    L.polyline(line, { color: "#24392c", weight: 4, opacity: 0.85 }).addTo(pathLayer);
  }
  const step = Math.max(1, Math.ceil(trail.length / 80));
  trail.forEach((point, index) => {
    if (index % step !== 0 && index !== trail.length - 1) return;
    L.circleMarker([point.lat, point.lng], {
      radius: index === trail.length - 1 ? 8 : 3,
      color: index === trail.length - 1 ? "#b5522a" : "#3d4a3a",
      weight: 2,
      fillColor: index === trail.length - 1 ? "#b5522a" : "#efe6d6",
      fillOpacity: 1,
    })
      .bindTooltip(formatWhen(point.t))
      .addTo(pathLayer);
  });
  for (const stay of stays) {
    const name = nameCache[stay.id] || "Stay";
    L.circle([stay.lat, stay.lng], {
      radius: 160,
      color: "#b5522a",
      weight: 2,
      fillColor: "#b5522a",
      fillOpacity: 0.16,
    })
      .bindPopup(`<strong>${escapeHtml(name)}</strong><br>${formatDuration(stay.duration)}<br>${formatWhen(stay.start)}`)
      .addTo(stayLayer);
  }
  const latest = trail[trail.length - 1];
  if (follow && latest) map.panTo([latest.lat, latest.lng]);
  else if (!mapReady && line.length) {
    map.fitBounds(line, { padding: [28, 28], maxZoom: 15 });
  }
  mapReady = true;
}

function latestSpeed(trail) {
  if (trail.length < 2) return null;
  const a = trail[trail.length - 2];
  const b = trail[trail.length - 1];
  const seconds = (b.t - a.t) / 1000;
  if (seconds <= 0 || seconds > 180) return null;
  return (haversineMeters(a, b) / seconds) * 3.6;
}

function render() {
  releaseUrls();
  const hourly = points.filter((p) => p.kind === "hourly").sort((a, b) => b.t - a.t);
  const trail = trailPoints();
  const stays = detectStays(points);
  const { placed, loose } = matchPhotos(stays, photos);
  renderRoute(trail);
  renderLog(hourly);
  renderStays(stays, placed);
  renderLoose(loose, photos);
  try {
    renderMap(stays, trail);
  } catch (error) {
    setStatus(error.message || "The map could not be drawn.", "bad");
  }
  document.querySelector("#stay-count").textContent = String(stays.length);
  document.querySelector("#fix-count").textContent = String(trail.length);
  document.querySelector("#photo-count").textContent = String(photos.length);
  document.querySelector("#distance-count").textContent = formatDistance(pathLengthMeters(trail));
  const speed = latestSpeed(trail);
  document.querySelector("#speed-count").textContent = speed == null ? "—" : `${speed.toFixed(1)} km/h`;
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
      const response = await fetch(
        `https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=${stay.lat}&lon=${stay.lng}&zoom=18`,
        { headers: { Accept: "application/json" } },
      );
      if (!response.ok) continue;
      const body = await response.json();
      const name = body.name || body.address?.suburb || body.address?.neighbourhood || body.display_name?.split(",")[0];
      if (!name) continue;
      nameCache[stay.id] = name;
      await setMeta(`name:${stay.id}`, name);
      changed = true;
      await new Promise((resolve) => setTimeout(resolve, 1100));
    } catch {
      /* Coordinates remain when a place name is unavailable. */
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
        const exif = parseJpegExif(await file.slice(0, Math.min(file.size, 512 * 1024)).arrayBuffer());
        if (exif.t) t = exif.t;
        if (exif.lat != null) {
          lat = exif.lat;
          lng = exif.lng;
        }
      } catch {
        /* Keep the file time when EXIF cannot be read. */
      }
    }
    const here = latestPoint();
    const photo = {
      id,
      name: file.name,
      t,
      lat: lat ?? here?.lat ?? null,
      lng: lng ?? here?.lng ?? null,
      type: file.type || "image/jpeg",
      blob: file,
    };
    await putPhoto(photo);
    photos.push(photo);
    added += 1;
  }
  setStatus(added === 0 ? "Those photos were already saved." : `Saved ${added} photo${added === 1 ? "" : "s"}.`, "ok");
  render();
}

function paintCard(title, subtitle, hue) {
  const canvas = document.createElement("canvas");
  canvas.width = 960;
  canvas.height = 600;
  const pen = canvas.getContext("2d");
  pen.fillStyle = hue;
  pen.fillRect(0, 0, 960, 600);
  pen.fillStyle = "#f6efe4";
  pen.font = "64px Georgia, serif";
  pen.fillText(title, 48, 280);
  pen.font = "28px Segoe UI, sans-serif";
  pen.fillText(subtitle, 48, 340);
  return new Promise((resolve) => canvas.toBlob(resolve, "image/jpeg", 0.86));
}

async function loadMock() {
  const real = points.filter((p) => !String(p.id).startsWith("mock:"));
  const anchor = real[real.length - 1] || latestPoint();
  const { points: mockPoints, visits } = buildMockTrip(anchor);
  setStatus("Drawing a sample day on the map…", "busy");
  for (const point of mockPoints) {
    if (points.some((have) => have.id === point.id)) continue;
    await putPoint(point);
    points.push(point);
  }
  const hues = ["#24392c", "#b5522a", "#3d4a3a", "#6e4b32"];
  for (const [index, visit] of visits.entries()) {
    const blob = await paintCard(visit.name, formatWhen(visit.start), hues[index % hues.length]);
    const photo = {
      id: `mock-photo:${visit.name}`,
      name: `${visit.name}.jpg`,
      t: visit.start + 10 * 60 * 1000,
      lat: visit.lat,
      lng: visit.lng,
      type: "image/jpeg",
      blob,
    };
    await putPhoto(photo);
    if (!photos.some((have) => have.id === photo.id)) photos.push(photo);
    const stays = detectStays(points);
    const stay = stays.find((item) => Math.abs(item.lat - visit.lat) < 0.001 && Math.abs(item.lng - visit.lng) < 0.001);
    if (stay) {
      nameCache[stay.id] = visit.name;
      await setMeta(`name:${stay.id}`, visit.name);
    }
  }
  mapReady = false;
  setStatus("Sample day loaded. It is practice data, not your real route.", "ok");
  render();
}

async function clearMock() {
  const mockPoints = points.filter((p) => String(p.id).startsWith("mock:"));
  const mockPhotos = photos.filter((p) => String(p.id).startsWith("mock-photo:"));
  for (const point of mockPoints) await deletePoint(point.id);
  for (const photo of mockPhotos) await deletePhoto(photo.id);
  points = points.filter((p) => !String(p.id).startsWith("mock:"));
  photos = photos.filter((p) => !String(p.id).startsWith("mock-photo:"));
  mapReady = false;
  setStatus("Sample day removed.", "ok");
  render();
}

async function ensureCameraPermission() {
  const native = nativeJournal();
  if (!native?.ensureCamera) return;
  await new Promise((resolve) => {
    const timer = setTimeout(resolve, 20000);
    window.__onCamera = () => {
      clearTimeout(timer);
      resolve();
    };
    native.ensureCamera();
  });
}

async function openCamera() {
  await ensureCameraPermission();
  if (!cameraStream) {
    cameraStream = await navigator.mediaDevices.getUserMedia({
      audio: false,
      video: { facingMode: { ideal: "environment" } },
    });
    cameraPreview.srcObject = cameraStream;
    await cameraPreview.play();
  }
  cameraSheet.hidden = false;
}

function closeCamera() {
  cameraSheet.hidden = true;
  if (photoTimer) return;
  cameraStream?.getTracks().forEach((track) => track.stop());
  cameraStream = null;
  cameraPreview.srcObject = null;
}

async function capturePhoto(source) {
  if (!cameraStream) await openCamera();
  const width = cameraPreview.videoWidth || 1280;
  const height = cameraPreview.videoHeight || 720;
  const canvas = document.createElement("canvas");
  canvas.width = width;
  canvas.height = height;
  canvas.getContext("2d").drawImage(cameraPreview, 0, 0, width, height);
  const blob = await new Promise((resolve) => canvas.toBlob(resolve, "image/jpeg", 0.86));
  if (!blob) throw new Error("The camera did not return a photo.");
  const here = latestPoint();
  const t = Date.now();
  const photo = {
    id: `shot:${t}`,
    name: `${source}-${t}.jpg`,
    t,
    lat: here?.lat ?? null,
    lng: here?.lng ?? null,
    type: "image/jpeg",
    blob,
  };
  await putPhoto(photo);
  photos.push(photo);
  setStatus(source === "auto" ? "Auto photo saved on the current spot." : "Photo saved on the current spot.", "ok");
  render();
}

function setAutoPhoto(on) {
  clearInterval(photoTimer);
  photoTimer = 0;
  document.querySelector("#auto-photo").setAttribute("aria-pressed", String(on));
  document.querySelector("#auto-photo").textContent = on ? "Stop auto photo" : "Auto photo 20s";
  if (!on) {
    closeCamera();
    setStatus("Auto photo is off.", "idle");
    return;
  }
  const shoot = () => {
    capturePhoto("auto").catch((error) => setStatus(error?.message || "Camera permission is required.", "bad"));
  };
  shoot();
  photoTimer = setInterval(shoot, SAMPLE_MS);
}

function replayRoute() {
  clearInterval(replayTimer);
  const trail = trailPoints();
  if (trail.length < 2 || !map) {
    setStatus("A route with at least two points is needed to replay.", "bad");
    return;
  }
  follow = false;
  followBtn.setAttribute("aria-pressed", "false");
  if (replayMarker) pathLayer.removeLayer(replayMarker);
  let index = 0;
  replayBtn.textContent = "Stop replay";
  replayTimer = setInterval(() => {
    const point = trail[index];
    if (!replayMarker) {
      replayMarker = L.circleMarker([point.lat, point.lng], {
        radius: 9,
        color: "#f6efe4",
        weight: 2,
        fillColor: "#24392c",
        fillOpacity: 1,
      }).addTo(pathLayer);
    } else replayMarker.setLatLng([point.lat, point.lng]);
    map.panTo([point.lat, point.lng]);
    index += 1;
    if (index >= trail.length) {
      clearInterval(replayTimer);
      replayTimer = 0;
      replayBtn.textContent = "Replay route";
    }
  }, 350);
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
  setStatus("Saved photo copies were removed. Your gallery is unchanged.", "ok");
  render();
}

document.querySelector("#more-toggle").addEventListener("click", () => {
  const panel = document.querySelector("#more-panel");
  const open = panel.hidden;
  panel.hidden = !open;
  document.querySelector("#more-toggle").setAttribute("aria-expanded", String(open));
});

document.querySelectorAll(".tab").forEach((tab) => {
  tab.addEventListener("click", () => {
    document.querySelectorAll(".tab").forEach((item) => item.classList.toggle("is-on", item === tab));
    for (const name of ["route", "stays", "photos", "log"]) {
      document.querySelector(`#panel-${name}`).hidden = name !== tab.dataset.tab;
    }
  });
});

trackBtn.addEventListener("click", () => setTracking(!tracking));
recordBtn.addEventListener("click", () => takeFix("now"));
followBtn.addEventListener("click", () => {
  follow = followBtn.getAttribute("aria-pressed") !== "true";
  followBtn.setAttribute("aria-pressed", String(follow));
  if (follow) {
    const last = latestPoint();
    if (last && map) map.panTo([last.lat, last.lng]);
  }
});
replayBtn.addEventListener("click", () => {
  if (replayTimer) {
    clearInterval(replayTimer);
    replayTimer = 0;
    replayBtn.textContent = "Replay route";
    return;
  }
  replayRoute();
});
document.querySelector("#open-camera").addEventListener("click", () => {
  openCamera().catch((error) => setStatus(error?.message || "Camera permission is required.", "bad"));
});
document.querySelector("#shutter").addEventListener("click", () => {
  capturePhoto("camera").catch((error) => setStatus(error?.message || "The photo could not be saved.", "bad"));
});
document.querySelector("#close-camera").addEventListener("click", () => {
  if (photoTimer) setAutoPhoto(false);
  else closeCamera();
});
document.querySelector("#auto-photo").addEventListener("click", () => {
  setAutoPhoto(document.querySelector("#auto-photo").getAttribute("aria-pressed") !== "true");
});
document.querySelector("#load-mock").addEventListener("click", () => loadMock());
document.querySelector("#clear-mock").addEventListener("click", () => clearMock());
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
    setStatus("Tracking is off. Load the sample day or start a real route.", "idle");
    nextEl.textContent = "While tracking is on, a position is saved every 20 seconds.";
  }
}

init().catch((error) => {
  setStatus(error.message || "The journal could not open its saved data.", "bad");
});
