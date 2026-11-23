const FORMAT = "location-journal-backup";

export function validateBackup(payload) {
  if (!payload || typeof payload !== "object") return "That file is not a journal backup.";
  if (payload.format !== FORMAT) return "That file is not a Location journal backup.";
  if (payload.version !== 1) return "This app cannot read that backup version.";
  if (!Array.isArray(payload.points)) return "The backup has no route.";
  if (payload.photos != null && !Array.isArray(payload.photos)) return "The backup photos are unreadable.";
  return "";
}

export function buildBackup({ settings, annotations, points, photos }) {
  return {
    format: FORMAT,
    version: 1,
    exportedAt: new Date().toISOString(),
    settings: settings ?? {},
    annotations: annotations ?? {},
    points: (points ?? []).map(({ id, t, lat, lng, accuracy, kind, hour }) => ({ id, t, lat, lng, accuracy, kind, hour })),
    photos: photos ?? [],
  };
}

export function buildGpx(points) {
  const sorted = (points ?? [])
    .filter((point) => Number.isFinite(point.lat) && Number.isFinite(point.lng))
    .slice()
    .sort((a, b) => a.t - b.t);
  const rows = sorted
    .map((point) => {
      const time = Number.isFinite(point.t) ? `<time>${new Date(point.t).toISOString()}</time>` : "";
      return `<trkpt lat="${point.lat}" lon="${point.lng}">${time}</trkpt>`;
    })
    .join("");
  return `<?xml version="1.0" encoding="UTF-8"?>\n<gpx version="1.1" creator="Location journal"><trk><name>Location journal</name><trkseg>${rows}</trkseg></trk></gpx>`;
}

export function groupPhotos(photos) {
  const groups = new Map();
  const sorted = (photos ?? []).slice().sort((a, b) => (b.t ?? 0) - (a.t ?? 0));
  for (const photo of sorted) {
    const day = photo.t ? new Date(photo.t).toLocaleDateString(undefined, { year: "numeric", month: "short", day: "numeric" }) : "Undated";
    if (!groups.has(day)) groups.set(day, []);
    groups.get(day).push(photo);
  }
  return [...groups.entries()].map(([day, items]) => ({ day, items }));
}

export function filterPhotos(photos, query) {
  const needle = String(query ?? "").trim().toLowerCase();
  if (!needle) return photos ?? [];
  return (photos ?? []).filter((photo) => {
    const haystack = [photo.name, photo.caption, photo.label, photo.place].filter(Boolean).join(" ").toLowerCase();
    return haystack.includes(needle);
  });
}

export function planPhotoImport(existingIds, files) {
  const list = Array.isArray(files) ? files : [];
  if (list.length === 0) return { status: "cancel", ids: [], duplicate: 0, skipped: 0 };
  const ids = [];
  const seen = new Set(existingIds ?? []);
  let duplicate = 0;
  let skipped = 0;
  for (const file of list) {
    const name = String(file?.name ?? "");
    const type = String(file?.type ?? "");
    const image = type.startsWith("image/") || /\.(jpe?g|png|heic|webp|gif)$/i.test(name);
    if (!image) {
      skipped += 1;
      continue;
    }
    const id = `${name}:${Number(file?.size) || 0}:${Number(file?.lastModified) || 0}`;
    if (seen.has(id)) {
      duplicate += 1;
      continue;
    }
    seen.add(id);
    ids.push(id);
  }
  return { status: ids.length || duplicate ? "ready" : "empty", ids, duplicate, skipped };
}

export function photoCopyDeleteWarning(count) {
  const copies = count === 1 ? "1 photo copy" : `${count} photo copies`;
  return `Delete ${copies} saved in this app? Photos in your gallery stay there.`;
}

export function planRestore(payload, confirmed) {
  const error = validateBackup(payload);
  if (error) return { apply: false, message: error };
  if (!confirmed) return { apply: false, message: "" };
  return { apply: true, message: "" };
}

export function torchView(state) {
  switch (state) {
    case "on":
      return { pressed: true, disabled: false, text: "Flashlight on", detail: "The light is on." };
    case "off":
      return { pressed: false, disabled: false, text: "Flashlight", detail: "The light is off." };
    case "requested":
      return { pressed: false, disabled: false, text: "Flashlight", detail: "Waiting for the camera light." };
    case "busy":
      return { pressed: false, disabled: false, text: "Flashlight busy", detail: "The light is busy. Close the camera preview and try again." };
    case "denied":
      return { pressed: false, disabled: false, text: "Flashlight", detail: "Camera permission is required for the flashlight." };
    case "unsupported":
      return { pressed: false, disabled: true, text: "No flashlight", detail: "This device has no flashlight." };
    default:
      return { pressed: false, disabled: true, text: "Flashlight", detail: "Flashlight control is available in the Android app." };
  }
}
