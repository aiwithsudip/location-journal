import {
  detectStays,
  formatClock,
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
import { formatDistanceUnits, isNightTime, mergeSettings } from "./settings.js";
import { mountReels, pauseReels } from "./reels-ui.js";
import {
  buildBackup,
  buildGpx,
  filterPhotos,
  groupPhotos,
  photoCopyDeleteWarning,
  planPhotoImport,
  planRestore,
  torchView,
} from "./backup.js";

const SAMPLE_MS = 20 * 1000;
const TORCH_VISIBILITY_CHECK_MS = 60 * 1000;
const GEO_OPTIONS = { enableHighAccuracy: true, maximumAge: 15 * 1000, timeout: 20 * 1000 };

const statusEl = document.querySelector("#status");
const nextEl = document.querySelector("#next-hour");
const chipEl = document.querySelector("#live-chip");
const logEl = document.querySelector("#hourly-log");
const staysEl = document.querySelector("#stays");
const looseEl = document.querySelector("#loose");
const routeEl = document.querySelector("#panel-route");
const photoGrid = document.querySelector("#photo-grid");
const trackBtn = document.querySelector("#toggle-tracking");
const recordBtn = document.querySelector("#record-now");
const photoInput = document.querySelector("#photo-input");
const folderInput = document.querySelector("#folder-input");
const exportBtn = document.querySelector("#export-log");
const clearPhotosBtn = document.querySelector("#clear-photos");
const followBtn = document.querySelector("#follow-map");
const torchQuick = document.querySelector("#torch-quick");
const torchCamera = document.querySelector("#torch-camera");
const torchDetail = document.querySelector("#torch-detail");
const replayBtn = document.querySelector("#replay");
const cameraSheet = document.querySelector("#camera-sheet");
const cameraPreview = document.querySelector("#camera-preview");
const morePanel = document.querySelector("#more-panel");
const moreToggle = document.querySelector("#more-toggle");
const viewer = document.querySelector("#viewer");
const settingsScreen = document.querySelector("#settings-screen");
const searchInput = document.querySelector("#photo-search");

let map;
let stayLayer;
let pathLayer;
let replayMarker;
let tracking = false;
let follow = false;
let locationBlocked = false;
let sampleTimer = 0;
let hourTimer = 0;
let photoTimer = 0;
let replayTimer = 0;
let points = [];
let photos = [];
let settings = mergeSettings();
let annotations = {};
let objectUrls = [];
let galleryUrls = [];
let viewerUrl = "";
let nameCache = {};
let cameraStream = null;
let mapReady = false;
let selecting = false;
let selectedIds = new Set();
let visiblePhotos = [];
let viewerIndex = -1;

function setStatus(text, kind) {
  statusEl.textContent = text;
  statusEl.dataset.kind = kind ?? "idle";
  paintChip();
}

function paintChip() {
  chipEl.textContent = tracking ? "Tracking" : "Paused";
  chipEl.dataset.kind = tracking ? "ok" : "idle";
}

function releaseUrls() {
  for (const url of objectUrls) URL.revokeObjectURL(url);
  objectUrls = [];
  releaseGallery();
}

function releaseGallery() {
  for (const url of galleryUrls) URL.revokeObjectURL(url);
  galleryUrls = [];
}

function nativeJournal() {
  return window.AndroidJournal || null;
}

function trailPoints() {
  const samples = points.filter((point) => point.kind === "sample");
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
  const existing = points.find((point) => point.id === hourlyId);
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
  const byId = new Map(points.map((point) => [point.id, point]));
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
  const byId = new Map(points.map((point) => [point.id, point]));
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
  if (!native && locationBlocked) {
    setStatus("Location permission is off. Allow it in the browser site settings, then start tracking again.", "bad");
    if (tracking) await setTracking(false);
    return false;
  }
  if (native) {
    setStatus("Finding your location…", "busy");
    native.requestFix(reason);
    await new Promise((resolve) => {
      const restore = () => {
        window.__onNativePoints = () => syncNativePoints();
      };
      const timer = setTimeout(() => {
        restore();
        resolve();
      }, 30000);
      window.__onNativePoints = async () => {
        clearTimeout(timer);
        restore();
        await syncNativePoints();
        resolve();
      };
    });
    const saved = latestPoint();
    if (saved) setStatus(`Position saved. ${formatWhen(saved.t)}.`, "ok");
    else setStatus("Still waiting for a GPS fix. Keep location on.", "busy");
    if (tracking) nextEl.textContent = "A fix is saved about every 20 seconds, plus one each hour.";
    return Boolean(saved);
  }
  setStatus("Finding your location…", "busy");
  try {
    const position = await currentPosition();
    const saved = await saveFix(position, { hourly: reason === "this hour" || reason === "now" });
    setStatus(`Position saved. ${formatWhen(saved.t)}.`, "ok");
    scheduleHourly();
    return Boolean(saved);
  } catch (error) {
    if (error?.code === 1) {
      locationBlocked = true;
      setStatus("Location permission is off. Allow it in the browser site settings, then start tracking again.", "bad");
      if (tracking) await setTracking(false);
      return false;
    }
    setStatus(error?.message || "Location was denied or unavailable.", "bad");
    return false;
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
  paintChip();
  if (!on) {
    if (nativeJournal()) nativeJournal().setTracking(false);
    follow = false;
    followBtn.setAttribute("aria-pressed", "false");
    nextEl.textContent = "Tracking is off. The route stays on this device.";
    setStatus("Tracking is paused.", "idle");
    return;
  }
  follow = settings.followOnStart;
  followBtn.setAttribute("aria-pressed", String(follow));
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
  const haveHour = points.some((point) => point.id === `hourly:${key}`);
  await takeFix(haveHour ? "sample" : "this hour");
  if (!tracking) return;
  sampleTimer = setInterval(() => {
    if (tracking) void takeFix("sample");
  }, SAMPLE_MS);
}

function escapeHtml(value) {
  return String(value ?? "").replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;");
}
function escapeAttr(value) {
  return escapeHtml(value).replaceAll('"', "&quot;");
}

function placeName(stay) {
  const note = annotations[stay.id] || {};
  return note.title || nameCache[stay.id] || `${stay.lat.toFixed(4)}, ${stay.lng.toFixed(4)}`;
}

function photoLabel(photo) {
  const when = photo.t ? formatWhen(photo.t) : "Undated photo";
  const place = photo.place || (photo.lat != null && photo.lng != null ? `${Number(photo.lat).toFixed(4)}, ${Number(photo.lng).toFixed(4)}` : "");
  return [photo.caption, photo.label, when, place].filter(Boolean).join(", ");
}

function slideshowHtml(items, label) {
  if (items.length === 0) {
    return `<p class="empty-photos">No photos for this stop yet. Use Add photo, or choose pictures. The app keeps a copy.</p>`;
  }
  const slides = items
    .map((photo, index) => {
      const url = URL.createObjectURL(photo.blob);
      objectUrls.push(url);
      const when = photo.caption || (photo.t ? formatWhen(photo.t) : photo.name);
      return `<figure class="slide${index === 0 ? " is-active" : ""}">
        <img src="${url}" alt="${escapeAttr(photoLabel(photo))}" />
        <figcaption>${escapeHtml(when)}</figcaption>
      </figure>`;
    })
    .join("");
  return `<div class="slideshow" aria-label="${escapeAttr(label)}">
    <div class="slides">${slides}</div>
    <div class="slide-controls">
      <button type="button" class="prev">Previous</button>
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
        <span class="dot" aria-hidden="true"></span>
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
    staysEl.innerHTML = `<article class="empty-card"><h3>No stays of 30 minutes yet</h3><p>Stay in one area while tracking is on. After half an hour the place appears here with its photos.</p></article>`;
    return;
  }
  staysEl.innerHTML = stays
    .slice()
    .reverse()
    .map((stay) => {
      const photosHere = placed.get(stay.id) ?? [];
      const name = placeName(stay);
      const note = annotations[stay.id] || {};
      return `<article class="stay" id="${stay.id}">
        <header>
          <p class="kicker">${formatDuration(stay.duration)} · ${stay.samples} fixes${note.favorite ? " · Favorite" : ""}</p>
          <h3>${escapeHtml(name)}</h3>
          <p class="when">${formatWhen(stay.start)} – ${formatClock(stay.end)}</p>
          <button type="button" class="ghost jump" data-lat="${stay.lat}" data-lng="${stay.lng}">Show on map</button>
        </header>
        <label>Title<input class="stay-title" data-stay="${escapeAttr(stay.id)}" type="text" maxlength="80" value="${escapeAttr(note.title || "")}" /></label>
        <label>Note<input class="stay-note" data-stay="${escapeAttr(stay.id)}" type="text" maxlength="160" value="${escapeAttr(note.note || "")}" /></label>
        <label>Label<input class="stay-label" data-stay="${escapeAttr(stay.id)}" type="text" maxlength="40" value="${escapeAttr(note.label || "")}" /></label>
        <button type="button" class="ghost stay-fav" data-stay="${escapeAttr(stay.id)}" aria-pressed="${note.favorite ? "true" : "false"}">${note.favorite ? "Favorited" : "Favorite"}</button>
        ${slideshowHtml(photosHere, `Photos from ${name}`)}
      </article>`;
    })
    .join("");
  staysEl.querySelectorAll(".jump").forEach((button) => {
    button.addEventListener("click", () => focusMap(Number(button.dataset.lat), Number(button.dataset.lng)));
  });
  staysEl.querySelectorAll(".stay-title, .stay-note, .stay-label").forEach((input) => {
    input.addEventListener("change", () => {
      const id = input.dataset.stay;
      const field = input.classList.contains("stay-title") ? "title" : input.classList.contains("stay-note") ? "note" : "label";
      annotations[id] = { ...(annotations[id] || {}), [field]: input.value.trim() };
      void setMeta("annotations", annotations);
      if (field === "title") {
        const heading = input.closest(".stay")?.querySelector("h3");
        if (heading) heading.textContent = input.value.trim() || nameCache[id] || heading.textContent;
      }
    });
  });
  staysEl.querySelectorAll(".stay-fav").forEach((button) => {
    button.addEventListener("click", () => {
      const id = button.dataset.stay;
      const favorite = button.getAttribute("aria-pressed") !== "true";
      annotations[id] = { ...(annotations[id] || {}), favorite };
      button.setAttribute("aria-pressed", String(favorite));
      button.textContent = favorite ? "Favorited" : "Favorite";
      void setMeta("annotations", annotations);
    });
  });
  wireSlideshows(staysEl);
}

function renderPhotos() {
  releaseGallery();
  const filtered = filterPhotos(photos, searchInput.value);
  visiblePhotos = filtered;
  if (photos.length === 0) {
    photoGrid.innerHTML = `<article class="empty-card"><h3>No photos yet</h3><p>Use Add photo, or choose pictures. This gallery stores a copy. Photos already in your gallery stay there.</p></article>`;
    looseEl.innerHTML = "";
    return;
  }
  if (filtered.length === 0) {
    photoGrid.innerHTML = `<article class="empty-card"><h3>No matching photos</h3><p>Try another date, caption, or label.</p></article>`;
    looseEl.innerHTML = "";
    return;
  }
  photoGrid.innerHTML = groupPhotos(filtered)
    .map(
      (group) => `<section class="photo-day"><h3>${escapeHtml(group.day)}</h3><div class="photo-row">${group.items
        .map((photo) => {
          const url = photo.blob ? URL.createObjectURL(photo.blob) : "";
          if (url) galleryUrls.push(url);
          const selected = selectedIds.has(photo.id);
          return `<button type="button" class="photo-tile${selected ? " is-selected" : ""}" data-id="${escapeAttr(photo.id)}" aria-pressed="${selected ? "true" : "false"}" aria-label="${escapeAttr(photoLabel(photo))}">
            ${url ? `<img src="${url}" alt="" />` : ""}
            <span>${escapeHtml(photo.caption || photo.label || (photo.t ? formatClock(photo.t) : "Photo"))}${photo.favorite ? " · Favorite" : ""}</span>
          </button>`;
        })
        .join("")}</div></section>`,
    )
    .join("");
  photoGrid.querySelectorAll(".photo-tile").forEach((button) => {
    button.addEventListener("click", () => {
      const id = button.dataset.id;
      if (selecting) {
        if (selectedIds.has(id)) selectedIds.delete(id);
        else selectedIds.add(id);
        const on = selectedIds.has(id);
        button.setAttribute("aria-pressed", String(on));
        button.classList.toggle("is-selected", on);
        return;
      }
      openViewer(id);
    });
  });
  const loose = matchPhotos(detectStays(points), filtered).loose;
  looseEl.innerHTML = loose.length
    ? `<p class="hint">${loose.length} shown photo${loose.length === 1 ? " is" : "s are"} not tied to a 30-minute stay. ${loose.length === 1 ? "It remains" : "They remain"} in this gallery.</p>`
    : "";
}

function renderLog(hourly) {
  if (hourly.length === 0) {
    logEl.innerHTML = `<li class="empty">No hourly fixes yet. Start tracking and a fix is stored for each hour.</li>`;
    return;
  }
  logEl.innerHTML = hourly
    .slice(0, 48)
    .map(
      (point) => `<li>
        <time datetime="${new Date(point.t).toISOString()}">${formatWhen(point.t)}</time>
        <span>${point.lat.toFixed(5)}, ${point.lng.toFixed(5)}</span>
        <span class="muted">±${point.accuracy ?? "?"} m</span>
      </li>`,
    )
    .join("");
}

function focusMap(lat, lng) {
  if (!map || !Number.isFinite(lat) || !Number.isFinite(lng)) {
    setStatus("That photo has no location to show.", "bad");
    return;
  }
  follow = false;
  followBtn.setAttribute("aria-pressed", "false");
  map.setView([lat, lng], Math.max(map.getZoom(), 16));
}

function renderMap(stays, trail) {
  if (typeof L === "undefined") throw new Error("Map library failed to load.");
  if (!map) {
    map = L.map("map", { zoomControl: false, scrollWheelZoom: true });
    L.control.zoom({ position: "topleft" }).addTo(map);
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
  const line = trail.map((point) => [point.lat, point.lng]);
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
    const name = placeName(stay);
    const marker = settings.showStayRings
      ? L.circle([stay.lat, stay.lng], {
          radius: 160,
          color: "#b5522a",
          weight: 2,
          fillColor: "#b5522a",
          fillOpacity: 0.16,
        })
      : L.circleMarker([stay.lat, stay.lng], {
          radius: 8,
          color: "#b5522a",
          weight: 2,
          fillColor: "#efe6d6",
          fillOpacity: 1,
        });
    marker.bindPopup(`<strong>${escapeHtml(name)}</strong><br>${formatDuration(stay.duration)}<br>${formatWhen(stay.start)}`).addTo(stayLayer);
  }
  const latest = trail[trail.length - 1];
  if (follow && latest) map.panTo([latest.lat, latest.lng]);
  else if (!mapReady && line.length) map.fitBounds(line, { padding: [28, 28], maxZoom: 15 });
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

function formatSpeed(kmh) {
  if (kmh == null) return "—";
  if (settings.units === "mi") return `${(kmh * 0.621371).toFixed(1)} mph`;
  return `${kmh.toFixed(1)} km/h`;
}

function render() {
  releaseUrls();
  const hourly = points.filter((point) => point.kind === "hourly").sort((a, b) => b.t - a.t);
  const trail = trailPoints();
  const stays = detectStays(points);
  const { placed } = matchPhotos(stays, photos);
  renderRoute(trail);
  renderLog(hourly);
  renderStays(stays, placed);
  renderPhotos();
  try {
    renderMap(stays, trail);
  } catch (error) {
    setStatus(error.message || "The map could not be drawn.", "bad");
  }
  document.querySelector("#stay-count").textContent = String(stays.length);
  document.querySelector("#fix-count").textContent = String(trail.length);
  document.querySelector("#photo-count").textContent = String(photos.length);
  document.querySelector("#distance-count").textContent = formatDistanceUnits(pathLengthMeters(trail), settings.units);
  document.querySelector("#elapsed-count").textContent = trail.length < 2 ? "—" : formatDuration(trail[trail.length - 1].t - trail[0].t);
  document.querySelector("#speed-count").textContent = formatSpeed(latestSpeed(trail));
  if (viewerIndex >= 0 && !viewer.hidden) paintViewer();
  void labelStays(stays);
}

let labelToken = 0;
async function labelStays(stays) {
  const token = ++labelToken;
  let changed = false;
  for (const stay of stays) {
    if (nameCache[stay.id] || annotations[stay.id]?.title) continue;
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

async function prepareImage(file) {
  if (!file || file.size < 4 * 1024 * 1024 || file.type === "image/gif") return file;
  try {
    const bitmap = await createImageBitmap(file);
    const maxSide = 2000;
    const scale = Math.min(1, maxSide / Math.max(bitmap.width, bitmap.height));
    const canvas = document.createElement("canvas");
    canvas.width = Math.max(1, Math.round(bitmap.width * scale));
    canvas.height = Math.max(1, Math.round(bitmap.height * scale));
    canvas.getContext("2d").drawImage(bitmap, 0, 0, canvas.width, canvas.height);
    bitmap.close?.();
    const blob = await new Promise((resolve) => canvas.toBlob(resolve, "image/jpeg", 0.85));
    if (!blob) return file;
    return new File([blob], file.name.replace(/\.\w+$/, "") + ".jpg", { type: "image/jpeg", lastModified: file.lastModified });
  } catch {
    return file;
  }
}

async function importPhotos(fileList) {
  const plan = planPhotoImport(new Set(photos.map((photo) => photo.id)), [...fileList]);
  if (plan.status === "cancel") {
    setStatus("No photos were chosen.", "idle");
    return;
  }
  if (plan.status === "empty") {
    setStatus("No images were in that selection.", "bad");
    return;
  }
  const files = [...fileList];
  setStatus(`Reading ${plan.ids.length} photo${plan.ids.length === 1 ? "" : "s"}…`, "busy");
  let added = 0;
  let failed = 0;
  for (const file of files) {
    const id = `${file.name}:${file.size}:${file.lastModified}`;
    if (!plan.ids.includes(id)) continue;
    try {
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
      const stored = await prepareImage(file);
      const here = latestPoint();
      const photo = {
        id,
        name: file.name,
        t,
        lat: lat ?? here?.lat ?? null,
        lng: lng ?? here?.lng ?? null,
        type: stored.type || file.type || "image/jpeg",
        blob: stored,
        caption: "",
        label: "",
        favorite: false,
      };
      await putPhoto(photo);
      photos.push(photo);
      added += 1;
    } catch {
      failed += 1;
    }
  }
  if (failed && added === 0) setStatus("Those photos could not be read.", "bad");
  else if (added === 0) setStatus("Those photos were already saved.", "ok");
  else setStatus(`Saved ${added} photo ${added === 1 ? "copy" : "copies"}${plan.duplicate ? `, ${plan.duplicate} already saved` : ""}. Your gallery is unchanged.`, "ok");
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
  const real = points.filter((point) => !String(point.id).startsWith("mock:"));
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
      caption: visit.name,
      label: "sample",
      favorite: false,
      place: visit.name,
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
  const mockPoints = points.filter((point) => String(point.id).startsWith("mock:"));
  const mockPhotos = photos.filter((photo) => String(photo.id).startsWith("mock-photo:"));
  for (const point of mockPoints) await deletePoint(point.id);
  for (const photo of mockPhotos) await deletePhoto(photo.id);
  points = points.filter((point) => !String(point.id).startsWith("mock:"));
  photos = photos.filter((photo) => !String(photo.id).startsWith("mock-photo:"));
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
  if (!navigator.mediaDevices?.getUserMedia) throw new Error("This device has no camera preview.");
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
  if (nativeJournal()?.setTorch) {
    try {
      nativeJournal().setTorch(false);
    } catch {
      /* The activity also turns the light off when it pauses. */
    }
  }
  if (photoTimer) return;
  cameraStream?.getTracks().forEach((track) => track.stop());
  cameraStream = null;
  cameraPreview.srcObject = null;
}

async function capturePhoto(source) {
  if (!cameraStream) await openCamera();
  const width = cameraPreview.videoWidth || 1280;
  const height = cameraPreview.videoHeight || 720;
  if (!width || !height) throw new Error("The camera preview is not ready.");
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
    caption: "",
    label: "",
    favorite: false,
    place: here ? `${here.lat.toFixed(4)}, ${here.lng.toFixed(4)}` : "",
  };
  await putPhoto(photo);
  photos.push(photo);
  setStatus(source === "auto" ? "Auto photo saved on the current spot." : "Photo saved on the current spot.", "ok");
  render();
}

function paintAuto(on) {
  const button = document.querySelector("#auto-photo");
  button.setAttribute("aria-pressed", String(on));
  button.textContent = on ? "Auto photo on" : "Auto photo off";
  document.querySelector("#set-auto").checked = on;
}

async function setAutoPhoto(on) {
  clearInterval(photoTimer);
  photoTimer = 0;
  settings = { ...settings, autoPhoto: on };
  await setMeta("settings", settings);
  paintAuto(on);
  if (!on) {
    closeCamera();
    setStatus("Auto photo is off.", "idle");
    return;
  }
  try {
    await capturePhoto("auto");
  } catch (error) {
    settings = { ...settings, autoPhoto: false };
    await setMeta("settings", settings);
    paintAuto(false);
    closeCamera();
    setStatus(error?.message || "Camera permission is required. Auto photo stayed off.", "bad");
    return;
  }
  photoTimer = setInterval(() => {
    capturePhoto("auto").catch((error) => {
      clearInterval(photoTimer);
      photoTimer = 0;
      settings = { ...settings, autoPhoto: false };
      paintAuto(false);
      void setMeta("settings", settings);
      closeCamera();
      setStatus(error?.message || "Auto photo stopped because the camera is unavailable.", "bad");
    });
  }, SAMPLE_MS);
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

function download(name, contents, type) {
  const blob = contents instanceof Blob ? contents : new Blob([contents], { type });
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = name;
  link.click();
  URL.revokeObjectURL(url);
}

function exportLog() {
  const payload = {
    exportedAt: new Date().toISOString(),
    points: points.map(({ id, t, lat, lng, accuracy, kind, hour }) => ({ id, t, lat, lng, accuracy, kind, hour })),
  };
  download("location-journal.json", JSON.stringify(payload, null, 2), "application/json");
  setStatus("Route JSON saved. It stays on this device until you move the file.", "ok");
}

function exportGpx() {
  if (points.length === 0) {
    setStatus("There is no route to export yet.", "bad");
    return;
  }
  download("location-journal.gpx", buildGpx(points), "application/gpx+xml");
  setStatus("GPX route saved.", "ok");
}

async function clearPhotos() {
  for (const photo of photos) await deletePhoto(photo.id);
  photos = [];
  selectedIds = new Set();
  closeViewer();
  setStatus("Saved photo copies were removed. Your gallery is unchanged.", "ok");
  render();
}

function applyTorch(state) {
  const view = torchView(state);
  for (const button of [torchQuick, torchCamera]) {
    button.disabled = view.disabled;
    button.setAttribute("aria-pressed", String(view.pressed));
    button.textContent = view.text;
  }
  torchDetail.textContent = view.detail;
}

function setTorchVisible(visible) {
  for (const element of [torchQuick, torchCamera, torchDetail]) element.hidden = !visible;
}

function switchTorchOff() {
  const native = nativeJournal();
  if (!native?.setTorch || torchQuick.getAttribute("aria-pressed") !== "true") return;
  let result = "off";
  try {
    result = native.setTorch(false) || "off";
  } catch {
    result = "denied";
  }
  applyTorch(result);
}

function updateTorchVisibility() {
  const night = isNightTime();
  setTorchVisible(night);
  if (!night) switchTorchOff();
}

function toggleTorch() {
  if (!isNightTime()) return;
  const native = nativeJournal();
  if (!native?.setTorch) {
    applyTorch("unavailable");
    return;
  }
  const turningOn = torchQuick.getAttribute("aria-pressed") !== "true";
  let result = "unavailable";
  try {
    result = native.setTorch(turningOn);
  } catch {
    result = "denied";
  }
  applyTorch(result || "unavailable");
}

function initTorch() {
  const native = nativeJournal();
  if (!native?.torchState) {
    applyTorch("unavailable");
  } else {
    try {
      applyTorch(native.torchState() || "unsupported");
    } catch {
      applyTorch("unavailable");
    }
  }
  updateTorchVisibility();
}

function openViewer(id) {
  const index = visiblePhotos.findIndex((photo) => photo.id === id);
  if (index < 0) return;
  viewerIndex = index;
  viewer.hidden = false;
  paintViewer();
  document.querySelector("#viewer-close").focus();
}

function closeViewer() {
  viewer.hidden = true;
  viewerIndex = -1;
  if (viewerUrl) URL.revokeObjectURL(viewerUrl);
  viewerUrl = "";
}

function paintViewer() {
  const photo = visiblePhotos[viewerIndex];
  if (!photo) {
    closeViewer();
    return;
  }
  if (viewerUrl) URL.revokeObjectURL(viewerUrl);
  viewerUrl = photo.blob ? URL.createObjectURL(photo.blob) : "";
  const image = document.querySelector("#viewer-img");
  image.src = viewerUrl;
  image.alt = photoLabel(photo);
  document.querySelector("#viewer-title").textContent = photo.caption || photo.name || "Photo";
  const when = photo.t ? formatWhen(photo.t) : "Time unknown";
  const where = photo.place || (photo.lat != null ? `${Number(photo.lat).toFixed(5)}, ${Number(photo.lng).toFixed(5)}` : "Place unknown");
  document.querySelector("#viewer-meta").textContent = `${when}. ${where}. This is the copy saved in the journal.`;
  document.querySelector("#viewer-caption").value = photo.caption || "";
  document.querySelector("#viewer-label").value = photo.label || "";
  const favorite = document.querySelector("#viewer-fav");
  favorite.setAttribute("aria-pressed", photo.favorite ? "true" : "false");
  favorite.textContent = photo.favorite ? "Favorited" : "Favorite";
}

async function saveViewerFields() {
  const photo = visiblePhotos[viewerIndex];
  if (!photo) return;
  photo.caption = document.querySelector("#viewer-caption").value.trim();
  photo.label = document.querySelector("#viewer-label").value.trim();
  await putPhoto(photo);
  document.querySelector("#viewer-title").textContent = photo.caption || photo.name || "Photo";
  document.querySelector("#viewer-img").alt = photoLabel(photo);
}

function stepViewer(delta) {
  if (visiblePhotos.length === 0) return;
  void saveViewerFields();
  viewerIndex = (viewerIndex + delta + visiblePhotos.length) % visiblePhotos.length;
  paintViewer();
}

function applyTheme() {
  document.documentElement.dataset.theme = settings.theme;
}

function syncSettingsForm() {
  document.querySelector("#set-follow").checked = settings.followOnStart;
  document.querySelector("#set-units").value = settings.units;
  document.querySelector("#set-rings").checked = settings.showStayRings;
  document.querySelector("#set-theme").value = settings.theme;
  paintAuto(settings.autoPhoto);
}

async function saveSettings(patch) {
  settings = mergeSettings({ ...settings, ...patch });
  await setMeta("settings", settings);
  applyTheme();
  syncSettingsForm();
  render();
}

function blobToBase64(blob) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => {
      const text = String(reader.result || "");
      const comma = text.indexOf(",");
      resolve(comma >= 0 ? text.slice(comma + 1) : text);
    };
    reader.onerror = () => reject(reader.error);
    reader.readAsDataURL(blob);
  });
}

async function exportBackup() {
  setStatus("Preparing a backup on this device…", "busy");
  const serialPhotos = [];
  for (const photo of photos) {
    if (!photo.blob) continue;
    serialPhotos.push({
      id: photo.id,
      name: photo.name,
      t: photo.t,
      lat: photo.lat,
      lng: photo.lng,
      type: photo.type,
      caption: photo.caption || "",
      label: photo.label || "",
      favorite: Boolean(photo.favorite),
      place: photo.place || "",
      data: await blobToBase64(photo.blob),
    });
  }
  const payload = buildBackup({ settings, annotations, points, photos: serialPhotos });
  download("location-journal-backup.json", JSON.stringify(payload), "application/json");
  setStatus("Backup saved. It includes the route, notes, settings, and photo copies.", "ok");
}

function bytesFromBase64(data) {
  const binary = atob(data);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

async function restoreBackup(payload) {
  const nextPoints = payload.points.filter(
    (point) => point && point.id && Number.isFinite(point.lat) && Number.isFinite(point.lng) && Number.isFinite(point.t),
  );
  const nextPhotos = [];
  let skipped = 0;
  for (const photo of payload.photos || []) {
    if (!photo?.id || !photo.data) {
      skipped += 1;
      continue;
    }
    try {
      const blob = new Blob([bytesFromBase64(photo.data)], { type: photo.type || "image/jpeg" });
      nextPhotos.push({
        id: photo.id,
        name: photo.name || "photo",
        t: photo.t,
        lat: photo.lat ?? null,
        lng: photo.lng ?? null,
        type: photo.type || "image/jpeg",
        caption: photo.caption || "",
        label: photo.label || "",
        favorite: Boolean(photo.favorite),
        place: photo.place || "",
        blob,
      });
    } catch {
      skipped += 1;
    }
  }
  for (const point of nextPoints) await putPoint(point);
  for (const photo of nextPhotos) await putPhoto(photo);
  for (const point of points) {
    if (!nextPoints.some((item) => item.id === point.id)) await deletePoint(point.id);
  }
  for (const photo of photos) {
    if (!nextPhotos.some((item) => item.id === photo.id)) await deletePhoto(photo.id);
  }
  points = nextPoints;
  photos = nextPhotos;
  settings = mergeSettings(payload.settings);
  annotations = payload.annotations && typeof payload.annotations === "object" && !Array.isArray(payload.annotations) ? payload.annotations : {};
  await setMeta("settings", settings);
  await setMeta("annotations", annotations);
  applyTheme();
  syncSettingsForm();
  selectedIds = new Set();
  closeViewer();
  mapReady = false;
  setStatus(skipped ? `Backup restored. ${skipped} photo ${skipped === 1 ? "copy was" : "copies were"} skipped.` : "Backup restored on this device.", "ok");
  render();
}

async function deleteJournal() {
  if (tracking) await setTracking(false);
  for (const point of points) await deletePoint(point.id);
  for (const photo of photos) await deletePhoto(photo.id);
  points = [];
  photos = [];
  annotations = {};
  nameCache = {};
  selectedIds = new Set();
  await setMeta("annotations", {});
  closeViewer();
  mapReady = false;
  setStatus("The journal on this device was deleted. Your gallery is unchanged.", "ok");
  render();
}

function setMenu(open) {
  morePanel.hidden = !open;
  moreToggle.setAttribute("aria-expanded", String(open));
}

function showTab(name) {
  document.body.classList.toggle("is-reels", name === "reels");
  document.querySelectorAll(".tab").forEach((item) => {
    const on = item.dataset.tab === name;
    item.classList.toggle("is-on", on);
    if (on) item.setAttribute("aria-current", "page");
    else item.removeAttribute("aria-current");
  });
  for (const panel of ["route", "stays", "photos", "log"]) {
    document.querySelector(`#panel-${panel}`).hidden = panel !== name;
  }
  if (name === "reels") {
    setMenu(false);
    const camera = document.querySelector("#camera-sheet");
    if (camera && !camera.hidden) document.querySelector("#close-camera").click();
    void mountReels();
  } else pauseReels();
}

moreToggle.addEventListener("click", () => setMenu(morePanel.hidden));
document.querySelectorAll(".tab").forEach((tab) => {
  tab.addEventListener("click", () => showTab(tab.dataset.tab));
});

trackBtn.addEventListener("click", () => {
  locationBlocked = false;
  void setTracking(!tracking);
});
recordBtn.addEventListener("click", () => {
  locationBlocked = false;
  void takeFix("now");
});
followBtn.addEventListener("click", () => {
  follow = followBtn.getAttribute("aria-pressed") !== "true";
  followBtn.setAttribute("aria-pressed", String(follow));
  if (follow) {
    const last = latestPoint();
    if (last && map) map.panTo([last.lat, last.lng]);
  }
});
replayBtn.addEventListener("click", () => {
  setMenu(false);
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
  if (photoTimer) void setAutoPhoto(false);
  else closeCamera();
});
document.querySelector("#auto-photo").addEventListener("click", () => {
  void setAutoPhoto(document.querySelector("#auto-photo").getAttribute("aria-pressed") !== "true");
});
document.querySelector("#load-mock").addEventListener("click", () => {
  setMenu(false);
  void loadMock();
});
document.querySelector("#clear-mock").addEventListener("click", () => {
  setMenu(false);
  void clearMock();
});
for (const input of [photoInput, folderInput]) {
  input.addEventListener("change", () => {
    void importPhotos(input.files ? [...input.files] : []);
    input.value = "";
  });
  input.addEventListener("cancel", () => setStatus("No photos were chosen.", "idle"));
}
exportBtn.addEventListener("click", () => {
  setMenu(false);
  exportLog();
});
document.querySelector("#export-gpx").addEventListener("click", () => {
  setMenu(false);
  exportGpx();
});
clearPhotosBtn.addEventListener("click", () => {
  setMenu(false);
  if (photos.length === 0) {
    setStatus("There are no photo copies to remove.", "idle");
    return;
  }
  if (confirm(photoCopyDeleteWarning(photos.length))) void clearPhotos();
});
document.querySelector("#torch-quick").addEventListener("click", toggleTorch);
document.querySelector("#torch-camera").addEventListener("click", toggleTorch);
searchInput.addEventListener("input", () => renderPhotos());
document.querySelector("#photo-select-mode").addEventListener("click", () => {
  selecting = !selecting;
  const button = document.querySelector("#photo-select-mode");
  button.setAttribute("aria-pressed", String(selecting));
  button.textContent = selecting ? "Selecting" : "Select";
  document.querySelector("#photo-delete-selected").hidden = !selecting;
  document.querySelector("#photo-share-selected").hidden = !selecting;
  if (!selecting) selectedIds = new Set();
  renderPhotos();
});
document.querySelector("#photo-delete-selected").addEventListener("click", async () => {
  const chosen = photos.filter((photo) => selectedIds.has(photo.id));
  if (chosen.length === 0) {
    setStatus("Select one or more photo copies first.", "idle");
    return;
  }
  if (!confirm(photoCopyDeleteWarning(chosen.length))) return;
  for (const photo of chosen) await deletePhoto(photo.id);
  photos = photos.filter((photo) => !selectedIds.has(photo.id));
  selectedIds = new Set();
  closeViewer();
  setStatus("Selected photo copies were removed. Your gallery is unchanged.", "ok");
  render();
});
document.querySelector("#photo-share-selected").addEventListener("click", async () => {
  const chosen = photos.filter((photo) => selectedIds.has(photo.id) && photo.blob);
  if (chosen.length === 0) {
    setStatus("Select one or more photo copies first.", "idle");
    return;
  }
  const files = chosen.map((photo) => new File([photo.blob], photo.name || "photo.jpg", { type: photo.type || "image/jpeg" }));
  if (navigator.canShare?.({ files })) {
    try {
      await navigator.share({ files, title: "Location journal photos" });
      setStatus("Shared the copies saved in this app.", "ok");
      return;
    } catch (error) {
      if (error?.name === "AbortError") return;
    }
  }
  for (const file of files) download(file.name, file, file.type);
  setStatus("Downloaded the copies saved in this app. Gallery originals were not changed.", "ok");
});

document.querySelector("#viewer-close").addEventListener("click", () => {
  void saveViewerFields().finally(() => {
    closeViewer();
    renderPhotos();
  });
});
document.querySelector("#viewer-prev").addEventListener("click", () => stepViewer(-1));
document.querySelector("#viewer-next").addEventListener("click", () => stepViewer(1));
document.querySelector("#viewer-caption").addEventListener("change", () => void saveViewerFields());
document.querySelector("#viewer-label").addEventListener("change", () => void saveViewerFields());
document.querySelector("#viewer-fav").addEventListener("click", async () => {
  const photo = visiblePhotos[viewerIndex];
  if (!photo) return;
  photo.favorite = !photo.favorite;
  await putPhoto(photo);
  paintViewer();
});
document.querySelector("#viewer-map").addEventListener("click", async () => {
  const photo = visiblePhotos[viewerIndex];
  if (!photo) return;
  await saveViewerFields();
  closeViewer();
  showTab("route");
  focusMap(Number(photo.lat), Number(photo.lng));
});

document.querySelector("#settings-open").addEventListener("click", () => {
  setMenu(false);
  syncSettingsForm();
  settingsScreen.hidden = false;
  document.querySelector("#settings-close").focus();
});
document.querySelector("#settings-close").addEventListener("click", () => {
  settingsScreen.hidden = true;
  document.querySelector("#settings-open").focus();
});
document.querySelector("#set-follow").addEventListener("change", (event) => void saveSettings({ followOnStart: event.target.checked }));
document.querySelector("#set-auto").addEventListener("change", (event) => void setAutoPhoto(event.target.checked));
document.querySelector("#set-units").addEventListener("change", (event) => void saveSettings({ units: event.target.value }));
document.querySelector("#set-rings").addEventListener("change", (event) => void saveSettings({ showStayRings: event.target.checked }));
document.querySelector("#set-theme").addEventListener("change", (event) => void saveSettings({ theme: event.target.value }));
document.querySelector("#backup-export").addEventListener("click", () => void exportBackup());
document.querySelector("#backup-file").addEventListener("change", async (event) => {
  const file = event.target.files?.[0];
  event.target.value = "";
  if (!file) {
    setStatus("No backup was chosen.", "idle");
    return;
  }
  let payload = null;
  try {
    payload = JSON.parse(await file.text());
  } catch {
    setStatus("That file is not a journal backup.", "bad");
    return;
  }
  const checked = planRestore(payload, false);
  if (checked.message) {
    setStatus(checked.message, "bad");
    return;
  }
  const confirmed = confirm("Replace the journal on this device with this backup? The current route, notes, and photo copies will be replaced. Your gallery is not changed.");
  const decision = planRestore(payload, confirmed);
  if (!decision.apply) return;
  try {
    await restoreBackup(payload);
  } catch (error) {
    setStatus(error?.message || "The backup could not be restored. Existing records were left in place where possible.", "bad");
  }
});
document.querySelector("#backup-file").addEventListener("cancel", () => setStatus("No backup was chosen.", "idle"));
document.querySelector("#delete-journal").addEventListener("click", () => {
  if (!confirm("Delete the route, notes, and photo copies on this device? Your gallery photos stay where they are. This cannot be undone.")) return;
  void deleteJournal();
});

document.addEventListener("keydown", (event) => {
  if (event.key === "Escape") {
    if (!viewer.hidden) document.querySelector("#viewer-close").click();
    else if (!settingsScreen.hidden) document.querySelector("#settings-close").click();
    else if (!cameraSheet.hidden) document.querySelector("#close-camera").click();
    else if (!morePanel.hidden) setMenu(false);
    return;
  }
  if (viewer.hidden) return;
  if (event.key === "ArrowLeft") stepViewer(-1);
  if (event.key === "ArrowRight") stepViewer(1);
});

document.addEventListener("visibilitychange", () => {
  if (document.visibilityState === "visible") updateTorchVisibility();
  if (document.visibilityState === "visible" && tracking) {
    const key = hourKey(Date.now());
    if (!points.some((point) => point.id === `hourly:${key}`)) void takeFix("this hour");
    else scheduleHourly();
  }
});

window.__onNativePoints = () => syncNativePoints();
window.__onTorch = (state) => applyTorch(state);
window.__nativeTrackingResult = (ok, message) => {
  if (!ok) {
    tracking = false;
    clearInterval(sampleTimer);
    clearTimeout(hourTimer);
    trackBtn.textContent = "Start tracking";
    trackBtn.setAttribute("aria-pressed", "false");
    follow = false;
    followBtn.setAttribute("aria-pressed", "false");
    nextEl.textContent = "Tracking is paused.";
    void setMeta("tracking", false);
    setStatus(message || "Location permission is required. Allow location, then start tracking again.", "bad");
  }
};

// Hide night-only controls immediately, before saved journal data is loaded.
updateTorchVisibility();

async function init() {
  points = await getAllPoints();
  photos = await getAllPhotos();
  settings = mergeSettings(await getMeta("settings"));
  const savedNotes = await getMeta("annotations");
  annotations = savedNotes && typeof savedNotes === "object" ? savedNotes : {};
  applyTheme();
  syncSettingsForm();
  initTorch();
  setInterval(updateTorchVisibility, TORCH_VISIBILITY_CHECK_MS);
  if (nativeJournal()) await syncNativePoints();
  tracking = nativeJournal() ? Boolean(nativeJournal().isTracking()) : Boolean(await getMeta("tracking"));
  trackBtn.textContent = tracking ? "Stop tracking" : "Start tracking";
  trackBtn.setAttribute("aria-pressed", String(tracking));
  render();
  if (tracking) await setTracking(true);
  else {
    setStatus("Tracking is paused. Load the sample day or start a route.", "idle");
    nextEl.textContent = "While tracking is on, a position is saved every 20 seconds.";
  }
  if (settings.autoPhoto && !photoTimer) await setAutoPhoto(true);
}

init().catch((error) => {
  setStatus(error.message || "The journal could not open its saved data.", "bad");
});
