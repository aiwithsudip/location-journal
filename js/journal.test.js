import assert from "node:assert/strict";
import { formatDistanceUnits, isNightTime, mergeSettings, nearestSheetStop, upgradePlan } from "./settings.js";
import {
  buildBackup,
  buildGpx,
  filterPhotos,
  groupPhotos,
  photoCopyDeleteWarning,
  planPhotoImport,
  planRestore,
  torchView,
  validateBackup,
} from "./backup.js";

assert.deepEqual(mergeSettings(undefined).units, "km");
assert.equal(mergeSettings({ units: "mi", theme: "dark", followOnStart: false, autoPhoto: true }).autoPhoto, true);
assert.equal(mergeSettings({ units: "furlongs", theme: "neon" }).units, "km");
assert.equal(mergeSettings({ units: "furlongs", theme: "neon" }).theme, "system");

assert.equal(isNightTime(new Date(2026, 0, 1, 17, 25)), false);
assert.equal(isNightTime(new Date(2026, 0, 1, 19, 59)), false);
assert.equal(isNightTime(new Date(2026, 0, 1, 20, 0)), true);
assert.equal(isNightTime(new Date(2026, 0, 1, 23, 59)), true);
assert.equal(isNightTime(new Date(2026, 0, 1, 0, 0)), true);
assert.equal(isNightTime(new Date(2026, 0, 1, 5, 59)), true);
assert.equal(isNightTime(new Date(2026, 0, 1, 6, 0)), false);
assert.equal(isNightTime(new Date(2026, 0, 1, 12, 0)), false);
assert.equal(nearestSheetStop(40, { map: 48, half: 360, full: 640 }), "map");
assert.equal(nearestSheetStop(300, { map: 48, half: 360, full: 640 }), "half");
assert.equal(nearestSheetStop(600, { map: 48, half: 360, full: 640 }), "full");

assert.equal(formatDistanceUnits(500, "km"), "500 m");
assert.equal(formatDistanceUnits(1609.344, "mi"), "1.00 mi");

const fresh = upgradePlan(0);
assert.equal(fresh.createPoints, true);
assert.equal(fresh.indexPhotosByTime, true);
assert.equal(fresh.deletesStores, false);
const existing = upgradePlan(1);
assert.equal(existing.createPoints, false);
assert.equal(existing.indexPhotosByTime, true);
assert.equal(existing.deletesStores, false);
const current = upgradePlan(2);
assert.equal(current.createPoints, false);
assert.equal(current.indexPhotosByTime, false);
assert.equal(current.deletesStores, false);

assert.equal(validateBackup(null).length > 0, true);
assert.equal(validateBackup({ format: "location-journal-backup", version: 1, points: [] }), "");
assert.equal(validateBackup({ format: "location-journal-backup", version: 2, points: [] }).includes("version"), true);
assert.equal(validateBackup({ format: "other", version: 1, points: [] }).length > 0, true);

const backup = buildBackup({ points: [{ id: "a", t: 1, lat: 2, lng: 3, accuracy: 4, kind: "sample" }], photos: [] });
assert.equal(validateBackup(backup), "");
assert.equal(backup.version, 1);

const gpx = buildGpx([{ t: Date.parse("2026-01-01T00:00:00Z"), lat: 1.5, lng: 2.5 }]);
assert.equal(gpx.includes(`lat="1.5"`), true);
assert.equal(gpx.includes(`lon="2.5"`), true);
assert.equal(gpx.includes("<time>"), true);

const photos = [
  { id: "1", t: Date.parse("2026-05-01T10:00:00"), name: "market.jpg", caption: "Spice stall", label: "food" },
  { id: "2", t: Date.parse("2026-05-02T10:00:00"), name: "river.jpg", caption: "Bridge" },
];
assert.equal(groupPhotos(photos).length, 2);
assert.deepEqual(filterPhotos(photos, "spice").map((photo) => photo.id), ["1"]);
assert.equal(filterPhotos(photos, "").length, 2);

assert.equal(torchView("on").pressed, true);
assert.equal(torchView("off").pressed, false);
assert.equal(torchView("unsupported").disabled, true);
assert.equal(torchView("busy").text.includes("busy"), true);
assert.equal(torchView("unavailable").disabled, true);
assert.equal(torchView("denied").detail.includes("permission"), true);
assert.equal(torchView("on").detail.includes("on"), true);

const known = new Set(["market.jpg:10:20"]);
assert.equal(planPhotoImport(known, []).status, "cancel");
assert.equal(planPhotoImport(known, [{ name: "notes.txt", type: "text/plain", size: 4, lastModified: 1 }]).status, "empty");
const ready = planPhotoImport(known, [
  { name: "market.jpg", type: "image/jpeg", size: 10, lastModified: 20 },
  { name: "river.jpg", type: "image/jpeg", size: 12, lastModified: 3 },
]);
assert.equal(ready.duplicate, 1);
assert.deepEqual(ready.ids, ["river.jpg:12:3"]);
assert.equal(photoCopyDeleteWarning(1).includes("gallery"), true);
assert.equal(photoCopyDeleteWarning(2).includes("2 photo copies"), true);
assert.equal(planRestore({ format: "nope" }, true).apply, false);
assert.equal(planRestore({ format: "location-journal-backup", version: 1, points: [] }, false).apply, false);
assert.equal(planRestore({ format: "location-journal-backup", version: 1, points: [] }, true).apply, true);

console.log("journal tests passed");
