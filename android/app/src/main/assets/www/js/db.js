import { upgradePlan } from "./settings.js";

const DB_NAME = "location-journal";
const DB_VERSION = 2;

function openDb() {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, DB_VERSION);
    request.onupgradeneeded = (event) => {
      const db = request.result;
      const plan = upgradePlan(event.oldVersion);
      if (plan.createPoints && !db.objectStoreNames.contains("points")) {
        const points = db.createObjectStore("points", { keyPath: "id" });
        points.createIndex("byTime", "t");
      }
      if (plan.createPhotos && !db.objectStoreNames.contains("photos")) {
        db.createObjectStore("photos", { keyPath: "id" });
      }
      if (plan.createMeta && !db.objectStoreNames.contains("meta")) {
        db.createObjectStore("meta", { keyPath: "key" });
      }
      if (plan.indexPhotosByTime && db.objectStoreNames.contains("photos")) {
        const photos = request.transaction.objectStore("photos");
        if (!photos.indexNames.contains("byTime")) photos.createIndex("byTime", "t");
      }
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

function requestToPromise(request) {
  return new Promise((resolve, reject) => {
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

async function withStore(name, mode, fn) {
  const db = await openDb();
  try {
    const tx = db.transaction(name, mode);
    const store = tx.objectStore(name);
    const result = await fn(store);
    await new Promise((resolve, reject) => {
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error);
      tx.onabort = () => reject(tx.error);
    });
    return result;
  } finally {
    db.close();
  }
}

export function putPoint(point) {
  return withStore("points", "readwrite", (store) => {
    store.put(point);
  });
}

export function deletePoint(id) {
  return withStore("points", "readwrite", (store) => {
    store.delete(id);
  });
}

export function getAllPoints() {
  return withStore("points", "readonly", (store) => requestToPromise(store.getAll()));
}

export function putPhoto(photo) {
  return withStore("photos", "readwrite", (store) => {
    store.put(photo);
  });
}

export function getAllPhotos() {
  return withStore("photos", "readonly", (store) => requestToPromise(store.getAll()));
}

export function deletePhoto(id) {
  return withStore("photos", "readwrite", (store) => {
    store.delete(id);
  });
}

export async function getMeta(key) {
  const row = await withStore("meta", "readonly", (store) => requestToPromise(store.get(key)));
  return row ? row.value : undefined;
}

export function setMeta(key, value) {
  return withStore("meta", "readwrite", (store) => {
    store.put({ key, value });
  });
}
