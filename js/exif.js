/**
 * Read the capture time and GPS from a JPEG EXIF block.
 * Other formats return whatever the file's lastModified can stand in for later.
 */
export function parseJpegExif(buffer) {
  const view = new DataView(buffer);
  if (view.byteLength < 4 || view.getUint16(0) !== 0xffd8) return {};

  let offset = 2;
  while (offset + 4 < view.byteLength) {
    if (view.getUint8(offset) !== 0xff) break;
    const marker = view.getUint8(offset + 1);
    if (marker === 0xda || marker === 0xd9) break;
    const size = view.getUint16(offset + 2);
    if (size < 2 || offset + 2 + size > view.byteLength) break;
    if (marker === 0xe1) return parseApp1(view, offset + 4, offset + 2 + size);
    offset += 2 + size;
  }
  return {};
}

function parseApp1(view, start, end) {
  if (end - start < 14) return {};
  const header = String.fromCharCode(
    view.getUint8(start),
    view.getUint8(start + 1),
    view.getUint8(start + 2),
    view.getUint8(start + 3),
    view.getUint8(start + 4),
    view.getUint8(start + 5),
  );
  if (header !== "Exif\0\0") return {};
  return parseTiff(view, start + 6, end);
}

function parseTiff(view, tiffStart, end) {
  const byteOrder = view.getUint16(tiffStart);
  const little = byteOrder === 0x4949;
  if (!little && byteOrder !== 0x4d4d) return {};
  const u16 = (at) => view.getUint16(at, little);
  const u32 = (at) => view.getUint32(at, little);
  if (u16(tiffStart + 2) !== 0x002a) return {};
  const ifd0 = tiffStart + u32(tiffStart + 4);
  if (ifd0 < tiffStart || ifd0 >= end) return {};

  const tags = readIfd(view, ifd0, end, tiffStart, little, u16, u32);
  const out = {};
  const exifPtr = tags.get(0x8769);
  const gpsPtr = tags.get(0x8825);
  const rootDate = tags.get(0x0132);
  if (typeof rootDate === "string") out.t = parseExifDate(rootDate);

  if (typeof exifPtr === "number") {
    const exif = readIfd(view, tiffStart + exifPtr, end, tiffStart, little, u16, u32);
    const original = exif.get(0x9003) ?? exif.get(0x9004) ?? exif.get(0x0132);
    if (typeof original === "string") out.t = parseExifDate(original) ?? out.t;
  }

  if (typeof gpsPtr === "number") {
    const gps = readIfd(view, tiffStart + gpsPtr, end, tiffStart, little, u16, u32);
    const lat = gpsDegrees(gps.get(0x0002));
    const lng = gpsDegrees(gps.get(0x0004));
    const latRef = gps.get(0x0001);
    const lngRef = gps.get(0x0003);
    if (lat != null && lng != null) {
      out.lat = latRef === "S" ? -lat : lat;
      out.lng = lngRef === "W" ? -lng : lng;
    }
  }
  return out;
}

function readIfd(view, offset, end, tiffStart, little, u16, u32) {
  const tags = new Map();
  if (offset + 2 > end) return tags;
  const count = u16(offset);
  let cursor = offset + 2;
  for (let i = 0; i < count; i += 1) {
    if (cursor + 12 > end) break;
    const tag = u16(cursor);
    const type = u16(cursor + 2);
    const n = u32(cursor + 4);
    const value = readValue(view, cursor + 8, type, n, tiffStart, end, little, u16, u32);
    tags.set(tag, value);
    cursor += 12;
  }
  return tags;
}

function readValue(view, valueOffset, type, count, tiffStart, end, little, u16, u32) {
  const sizes = { 1: 1, 2: 1, 3: 2, 4: 4, 5: 8, 7: 1 };
  const size = sizes[type];
  if (!size) return null;
  const total = size * count;
  let at = valueOffset;
  if (total > 4) {
    const ptr = u32(valueOffset);
    at = tiffStart + ptr;
    if (at < tiffStart || at + total > end) return null;
  }

  if (type === 2) {
    let text = "";
    for (let i = 0; i < count; i += 1) {
      const code = view.getUint8(at + i);
      if (code === 0) break;
      text += String.fromCharCode(code);
    }
    return text;
  }
  if (type === 5) {
    const parts = [];
    for (let i = 0; i < count; i += 1) {
      const num = u32(at + i * 8);
      const den = u32(at + i * 8 + 4);
      parts.push(den === 0 ? 0 : num / den);
    }
    return parts;
  }
  if (type === 3 && count === 1) return u16(at);
  if (type === 4 && count === 1) return u32(at);
  if (type === 1 && count === 1) return view.getUint8(at);
  return null;
}

function gpsDegrees(parts) {
  if (!Array.isArray(parts) || parts.length < 3) return null;
  const [d, m, s] = parts;
  if (![d, m, s].every((n) => Number.isFinite(n))) return null;
  return d + m / 60 + s / 3600;
}

/** EXIF timestamps have no zone. Treat them as the computer's local time. */
export function parseExifDate(text) {
  const match = /^(\d{4}):(\d{2}):(\d{2})[ T](\d{2}):(\d{2}):(\d{2})/.exec(text ?? "");
  if (!match) return null;
  const [, y, mo, d, h, mi, s] = match;
  const date = new Date(Number(y), Number(mo) - 1, Number(d), Number(h), Number(mi), Number(s));
  const t = date.getTime();
  return Number.isFinite(t) ? t : null;
}
