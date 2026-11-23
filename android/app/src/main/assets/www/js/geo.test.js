import assert from "node:assert/strict";
import { detectStays, haversineMeters, hourKey, matchPhotos } from "./geo.js";
import { parseExifDate, parseJpegExif } from "./exif.js";

const home = { lat: 37.77, lng: -122.42 };
const park = { lat: 37.8, lng: -122.42 };

assert.ok(haversineMeters(home, park) > 2000);

const t0 = Date.parse("2026-04-01T15:00:00");
const stayPoints = [];
for (let i = 0; i < 8; i += 1) {
  stayPoints.push({ t: t0 + i * 5 * 60 * 1000, lat: home.lat + i * 0.00001, lng: home.lng, accuracy: 20 });
}
stayPoints.push({ t: t0 + 50 * 60 * 1000, lat: park.lat, lng: park.lng, accuracy: 15 });
stayPoints.push({ t: t0 + 90 * 60 * 1000, lat: park.lat, lng: park.lng, accuracy: 15 });

const stays = detectStays(stayPoints);
assert.equal(stays.length, 2);
assert.ok(stays[0].duration >= 30 * 60 * 1000);
assert.ok(stays[0].duration < 50 * 60 * 1000);

const oneHourApart = [
  { t: t0, lat: home.lat, lng: home.lng, accuracy: 30 },
  { t: t0 + 60 * 60 * 1000, lat: home.lat + 0.0002, lng: home.lng, accuracy: 30 },
];
assert.equal(detectStays(oneHourApart).length, 1);

const tooShort = [
  { t: t0, lat: home.lat, lng: home.lng, accuracy: 10 },
  { t: t0 + 10 * 60 * 1000, lat: home.lat, lng: home.lng, accuracy: 10 },
];
assert.equal(detectStays(tooShort).length, 0);

const coarse = [
  { t: t0, lat: home.lat, lng: home.lng, accuracy: 900 },
  { t: t0 + 60 * 60 * 1000, lat: home.lat, lng: home.lng, accuracy: 900 },
];
assert.equal(detectStays(coarse).length, 0);

const dayLater = [
  { t: t0, lat: home.lat, lng: home.lng, accuracy: 10 },
  { t: t0 + 3 * 60 * 60 * 1000, lat: home.lat, lng: home.lng, accuracy: 10 },
];
assert.equal(detectStays(dayLater).length, 0);

const photoTime = stays[0].start + 10 * 60 * 1000;
const { placed, loose } = matchPhotos(stays, [
  { id: "a", t: photoTime, lat: null, lng: null },
  { id: "b", t: t0 - 5 * 60 * 60 * 1000, lat: park.lat, lng: park.lng },
  { id: "c", t: t0 - 5 * 60 * 60 * 1000, lat: 10, lng: 10 },
]);
assert.deepEqual(placed.get(stays[0].id).map((p) => p.id), ["a"]);
assert.deepEqual(placed.get(stays[1].id).map((p) => p.id), ["b"]);
assert.deepEqual(loose.map((p) => p.id), ["c"]);

assert.equal(hourKey(Date.parse("2026-04-01T09:15:00")), hourKey(Date.parse("2026-04-01T09:59:00")));
assert.notEqual(hourKey(Date.parse("2026-04-01T09:15:00")), hourKey(Date.parse("2026-04-01T10:00:00")));
assert.equal(parseExifDate("2024:06:05 14:03:02"), new Date(2024, 5, 5, 14, 3, 2).getTime());
assert.equal(parseExifDate("nope"), null);

function u16(n) {
  return [n & 255, (n >> 8) & 255];
}
function u32(n) {
  return [n & 255, (n >> 8) & 255, (n >> 16) & 255, (n >> 24) & 255];
}
const date = "2024:06:05 14:03:02\0";
const dateBytes = [...date].map((ch) => ch.charCodeAt(0));
const ifd0Size = 8 + 2 + 12 * 2 + 4;
const exifPointer = ifd0Size;
const datePointer = exifPointer + 2 + 12 + 4;
const gpsPointer = datePointer + dateBytes.length;
const tiff = [
  0x49, 0x49, 0x2a, 0x00, 0x08, 0x00, 0x00, 0x00,
  ...u16(2),
  ...u16(0x8769), ...u16(4), ...u32(1), ...u32(exifPointer),
  ...u16(0x8825), ...u16(4), ...u32(1), ...u32(gpsPointer),
  ...u32(0),
];
assert.equal(tiff.length, ifd0Size);
const exifIfd = [
  ...u16(1),
  ...u16(0x9003), ...u16(2), ...u32(dateBytes.length), ...u32(datePointer),
  ...u32(0),
  ...dateBytes,
];
assert.equal(datePointer, exifPointer + 2 + 12 + 4);
const rationals = [];
const rats = [37, 1, 46, 1, 12, 1, 122, 1, 25, 1, 30, 1];
for (const n of rats) rationals.push(...u32(n));
const gpsIfdStart = gpsPointer;
const gpsData = gpsIfdStart + 2 + 12 * 4 + 4;
const gpsIfd = [
  ...u16(4),
  ...u16(0x0001), ...u16(2), ...u32(2), ...[0x4e, 0, 0, 0],
  ...u16(0x0002), ...u16(5), ...u32(3), ...u32(gpsData),
  ...u16(0x0003), ...u16(2), ...u32(2), ...[0x57, 0, 0, 0],
  ...u16(0x0004), ...u16(5), ...u32(3), ...u32(gpsData + 24),
  ...u32(0),
  ...rationals,
];
const tiffBytes = [...tiff, ...exifIfd, ...gpsIfd];
assert.equal(tiff.length + exifIfd.length, gpsPointer);
const app1Body = [0x45, 0x78, 0x69, 0x66, 0x00, 0x00, ...tiffBytes];
const app1Size = app1Body.length + 2;
const jpeg = [0xff, 0xd8, 0xff, 0xe1, (app1Size >> 8) & 255, app1Size & 255, ...app1Body, 0xff, 0xd9];
const parsed = parseJpegExif(new Uint8Array(jpeg).buffer);
assert.equal(parsed.t, new Date(2024, 5, 5, 14, 3, 2).getTime());
assert.ok(Math.abs(parsed.lat - (37 + 46 / 60 + 12 / 3600)) < 1e-6);
assert.ok(Math.abs(parsed.lng - -(122 + 25 / 60 + 30 / 3600)) < 1e-6);

console.log("geo and exif tests passed");
