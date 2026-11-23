/** A sample day near the last real fix, or near the saved journal area. */
export function buildMockTrip(anchor, now = Date.now()) {
  const baseLat = anchor?.lat ?? 27.66313;
  const baseLng = anchor?.lng ?? 84.42509;
  const places = [
    { name: "Home lane", dLat: 0, dLng: 0, minutes: 50 },
    { name: "Morning market", dLat: 0.0046, dLng: 0.0034, minutes: 40 },
    { name: "Riverside", dLat: 0.0021, dLng: -0.0056, minutes: 36 },
    { name: "Tea house", dLat: -0.0036, dLng: 0.002, minutes: 42 },
  ];
  const points = [];
  let t = now - 5.5 * 60 * 60 * 1000;
  let prev = null;
  const visits = [];

  for (const place of places) {
    const lat = baseLat + place.dLat;
    const lng = baseLng + place.dLng;
    if (prev) {
      const steps = 16;
      for (let step = 1; step <= steps; step += 1) {
        const f = step / steps;
        points.push(sample(t, prev.lat + (lat - prev.lat) * f, prev.lng + (lng - prev.lng) * f));
        t += 20 * 1000;
      }
    }
    const stayStart = t;
    const count = Math.round((place.minutes * 60) / 30);
    for (let i = 0; i < count; i += 1) {
      const wobble = ((i % 5) - 2) * 0.00003;
      points.push(sample(t, lat + wobble, lng - wobble));
      t += 30 * 1000;
    }
    visits.push({ name: place.name, lat, lng, start: stayStart, end: t - 30 * 1000 });
    prev = { lat, lng };
  }

  const hourlySeen = new Set();
  for (const point of points) {
    const hour = hourStamp(point.t);
    if (hourlySeen.has(hour)) continue;
    hourlySeen.add(hour);
    points.push({
      id: `mock:hourly:${hour}`,
      t: point.t,
      lat: point.lat,
      lng: point.lng,
      accuracy: 12,
      kind: "hourly",
      hour,
      mock: true,
    });
  }
  return { points, visits };
}

function sample(t, lat, lng) {
  return {
    id: `mock:sample:${t}`,
    t,
    lat,
    lng,
    accuracy: 14,
    kind: "sample",
    mock: true,
  };
}

function hourStamp(ms) {
  const d = new Date(ms);
  const p = (n) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}-${p(d.getHours())}`;
}
