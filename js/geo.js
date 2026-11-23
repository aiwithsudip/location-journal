/** Distance in meters between two {lat, lng} points. */
export function haversineMeters(a, b) {
  const R = 6371000;
  const toRad = (d) => (d * Math.PI) / 180;
  const dLat = toRad(b.lat - a.lat);
  const dLng = toRad(b.lng - a.lng);
  const lat1 = toRad(a.lat);
  const lat2 = toRad(b.lat);
  const h =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLng / 2) ** 2;
  return 2 * R * Math.asin(Math.min(1, Math.sqrt(h)));
}

export const STAY_RADIUS_M = 200;
export const STAY_MIN_MS = 30 * 60 * 1000;
/** Same place across hourly fixes still counts as one stay. Longer gaps start a new one. */
export const STAY_MAX_GAP_MS = 70 * 60 * 1000;
export const CLUSTER_MAX_ACCURACY_M = 250;

function centroid(points) {
  let lat = 0;
  let lng = 0;
  for (const p of points) {
    lat += p.lat;
    lng += p.lng;
  }
  return { lat: lat / points.length, lng: lng / points.length };
}

function usable(point) {
  return (
    Number.isFinite(point.lat) &&
    Number.isFinite(point.lng) &&
    Number.isFinite(point.t) &&
    (point.accuracy == null || point.accuracy <= CLUSTER_MAX_ACCURACY_M)
  );
}

/**
 * Group consecutive fixes that stay inside STAY_RADIUS_M into visits of at least 30 minutes.
 * A visit's duration is the time from its first fix to its last.
 */
export function detectStays(points) {
  const sorted = points.filter(usable).sort((a, b) => a.t - b.t);
  const stays = [];
  let cluster = [];

  const flush = () => {
    if (cluster.length < 2) {
      cluster = [];
      return;
    }
    const start = cluster[0].t;
    const end = cluster[cluster.length - 1].t;
    const duration = end - start;
    if (duration >= STAY_MIN_MS) {
      const center = centroid(cluster);
      stays.push({
        id: `stay-${start}`,
        start,
        end,
        duration,
        lat: center.lat,
        lng: center.lng,
        samples: cluster.length,
      });
    }
    cluster = [];
  };

  for (const point of sorted) {
    if (cluster.length === 0) {
      cluster = [point];
      continue;
    }
    const previous = cluster[cluster.length - 1];
    const gap = point.t - previous.t;
    const dist = haversineMeters(centroid(cluster), point);
    if (gap > STAY_MAX_GAP_MS || dist > STAY_RADIUS_M) flush();
    cluster.push(point);
  }
  flush();
  return stays;
}

/**
 * Attach each photo to the stay it belongs to.
 * A photo matches when its time falls inside the stay, or its GPS is within 250 m.
 * Time inside the stay wins over a merely nearby place.
 */
export function matchPhotos(stays, photos) {
  const placed = new Map();
  const loose = [];
  const timeSlack = 5 * 60 * 1000;
  const nearM = 250;

  for (const photo of photos) {
    let best = null;
    let bestScore = Infinity;
    for (const stay of stays) {
      const inTime =
        photo.t != null &&
        photo.t >= stay.start - timeSlack &&
        photo.t <= stay.end + timeSlack;
      const dist =
        photo.lat != null && photo.lng != null
          ? haversineMeters(photo, stay)
          : null;
      const near = dist != null && dist <= nearM;
      if (!inTime && !near) continue;
      const score = (inTime ? 0 : 100000) + (dist ?? 800);
      if (score < bestScore) {
        bestScore = score;
        best = stay;
      }
    }
    if (!best) {
      loose.push(photo);
      continue;
    }
    if (!placed.has(best.id)) placed.set(best.id, []);
    placed.get(best.id).push(photo);
  }

  for (const list of placed.values()) list.sort((a, b) => (a.t ?? 0) - (b.t ?? 0));
  loose.sort((a, b) => (a.t ?? 0) - (b.t ?? 0));
  return { placed, loose };
}

export function hourKey(date) {
  const d = new Date(date);
  const p = (n) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}-${p(d.getHours())}`;
}

export function formatWhen(ms) {
  return new Date(ms).toLocaleString(undefined, {
    weekday: "short",
    month: "short",
    day: "numeric",
    hour: "numeric",
    minute: "2-digit",
  });
}

export function formatDuration(ms) {
  const total = Math.max(0, Math.round(ms / 60000));
  const h = Math.floor(total / 60);
  const m = total % 60;
  if (h === 0) return `${m} min`;
  if (m === 0) return `${h} h`;
  return `${h} h ${m} min`;
}

export function formatClock(ms) {
  return new Date(ms).toLocaleTimeString(undefined, {
    hour: "numeric",
    minute: "2-digit",
  });
}
