export const DEFAULT_SETTINGS = {
  units: "km",
  theme: "system",
  followOnStart: true,
  autoPhoto: false,
  showStayRings: true,
};

export function mergeSettings(saved) {
  const next = { ...DEFAULT_SETTINGS };
  if (!saved || typeof saved !== "object") return next;
  if (saved.units === "mi" || saved.units === "km") next.units = saved.units;
  if (saved.theme === "light" || saved.theme === "dark" || saved.theme === "system") next.theme = saved.theme;
  if (typeof saved.followOnStart === "boolean") next.followOnStart = saved.followOnStart;
  if (typeof saved.autoPhoto === "boolean") next.autoPhoto = saved.autoPhoto;
  if (typeof saved.showStayRings === "boolean") next.showStayRings = saved.showStayRings;
  return next;
}

export function formatDistanceUnits(meters, units) {
  const value = Number(meters) || 0;
  if (units === "mi") {
    const miles = value / 1609.344;
    if (miles < 0.1) return `${Math.round(value * 3.28084)} ft`;
    return `${miles.toFixed(miles < 10 ? 2 : 1)} mi`;
  }
  if (value < 1000) return `${Math.round(value)} m`;
  return `${(value / 1000).toFixed(value < 10000 ? 2 : 1)} km`;
}

export function upgradePlan(oldVersion) {
  const version = Number(oldVersion) || 0;
  return {
    createPoints: version < 1,
    createPhotos: version < 1,
    createMeta: version < 1,
    indexPhotosByTime: version < 2,
    deletesStores: false,
  };
}
