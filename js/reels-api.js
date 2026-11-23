import { deleteReelComment, getAllReelComments, getAllReelStates, putReelComment, putReelState } from "./db.js";

const PREFS_ID = "__prefs";

export const REEL_CATALOG = [
  {
    id: "reel-01",
    title: "Home lane at dawn",
    place: "Home lane",
    caption: "The street is still wet and the bicycle has not moved.",
    sound: "Quiet lane",
    video: "media/reels/reel-01.mp4",
    poster: "media/reels/reel-01.jpg",
    likes: 128,
    comments: [
      { id: "seed-01-a", author: "Mina", text: "That light only lasts a few minutes.", t: 1, mine: false },
      { id: "seed-01-b", author: "Arun", text: "I know this corner.", t: 2, mine: false },
    ],
    saves: 18,
    shares: 6,
    reshares: 2,
    views: 840,
  },
  {
    id: "reel-02",
    title: "Morning market",
    place: "Spice lane",
    caption: "Turmeric, chili, and the first customers.",
    sound: "Market murmur",
    video: "media/reels/reel-02.mp4",
    poster: "media/reels/reel-02.jpg",
    likes: 256,
    comments: [{ id: "seed-02-a", author: "Sita", text: "The colors are the whole story.", t: 3, mine: false }],
    saves: 40,
    shares: 14,
    reshares: 5,
    views: 1900,
  },
  {
    id: "reel-03",
    title: "Riverside",
    place: "River bank",
    caption: "The boat stays tied while the water moves.",
    sound: "River",
    video: "media/reels/reel-03.mp4",
    poster: "media/reels/reel-03.jpg",
    likes: 96,
    comments: [{ id: "seed-03-a", author: "Bikash", text: "Midday and still this calm.", t: 4, mine: false }],
    saves: 11,
    shares: 4,
    reshares: 1,
    views: 640,
  },
  {
    id: "reel-04",
    title: "Tea house",
    place: "Tea house",
    caption: "Cups out, kettle warm, nobody in a hurry.",
    sound: "Kettle",
    video: "media/reels/reel-04.mp4",
    poster: "media/reels/reel-04.jpg",
    likes: 310,
    comments: [{ id: "seed-04-a", author: "Nima", text: "Saving this for a slow afternoon.", t: 5, mine: false }],
    saves: 52,
    shares: 20,
    reshares: 7,
    views: 2400,
  },
  {
    id: "reel-05",
    title: "Temple courtyard",
    place: "Courtyard",
    caption: "Carved wood and a long afternoon shadow.",
    sound: "Courtyard",
    video: "media/reels/reel-05.mp4",
    poster: "media/reels/reel-05.jpg",
    likes: 174,
    comments: [{ id: "seed-05-a", author: "Kabita", text: "The bell is just out of frame.", t: 6, mine: false }],
    saves: 22,
    shares: 9,
    reshares: 3,
    views: 1100,
  },
  {
    id: "reel-06",
    title: "Rice fields",
    place: "Fields",
    caption: "A path through the green, hills still blue.",
    sound: "Open air",
    video: "media/reels/reel-06.mp4",
    poster: "media/reels/reel-06.jpg",
    likes: 402,
    comments: [{ id: "seed-06-a", author: "Ramesh", text: "Sunrise is the right hour for this.", t: 7, mine: false }],
    saves: 61,
    shares: 25,
    reshares: 8,
    views: 3200,
  },
  {
    id: "reel-07",
    title: "Night bus",
    place: "On the road",
    caption: "Rain on the glass and the town going by.",
    sound: "Rain",
    video: "media/reels/reel-07.mp4",
    poster: "media/reels/reel-07.jpg",
    likes: 88,
    comments: [{ id: "seed-07-a", author: "Pooja", text: "This feels like the ride home.", t: 8, mine: false }],
    saves: 9,
    shares: 3,
    reshares: 1,
    views: 510,
  },
  {
    id: "reel-08",
    title: "Rooftop",
    place: "Rooftop",
    caption: "The washing line catches the last sun.",
    sound: "Evening wind",
    video: "media/reels/reel-08.mp4",
    poster: "media/reels/reel-08.jpg",
    likes: 143,
    comments: [{ id: "seed-08-a", author: "Sunil", text: "Stay for the color change.", t: 9, mine: false }],
    saves: 16,
    shares: 7,
    reshares: 2,
    views: 980,
  },
  {
    id: "reel-09",
    title: "Night stall",
    place: "Food lane",
    caption: "Steam, warm bulbs, and a dark lane behind.",
    sound: "Night stall",
    video: "media/reels/reel-09.mp4",
    poster: "media/reels/reel-09.jpg",
    likes: 221,
    comments: [{ id: "seed-09-a", author: "Asha", text: "I can almost smell it.", t: 10, mine: false }],
    saves: 33,
    shares: 12,
    reshares: 4,
    views: 1500,
  },
  {
    id: "reel-10",
    title: "Forest bridge",
    place: "Forest path",
    caption: "A short bridge over clear water.",
    sound: "Stream",
    video: "media/reels/reel-10.mp4",
    poster: "media/reels/reel-10.jpg",
    likes: 267,
    comments: [{ id: "seed-10-a", author: "Dipak", text: "The green is doing all the work.", t: 11, mine: false }],
    saves: 28,
    shares: 11,
    reshares: 3,
    views: 1700,
  },
];

export function createMemoryReelStore() {
  const states = new Map();
  const comments = [];
  return {
    async allStates() {
      return [...states.values()].map((row) => ({ ...row }));
    },
    async putState(row) {
      states.set(row.id, { ...row });
    },
    async allComments() {
      return comments.map((row) => ({ ...row }));
    },
    async putComment(row) {
      comments.push({ ...row });
    },
    async deleteComment(id) {
      const index = comments.findIndex((row) => row.id === id);
      if (index >= 0) comments.splice(index, 1);
    },
  };
}

export function createIdbReelStore() {
  return {
    allStates: getAllReelStates,
    putState: putReelState,
    allComments: getAllReelComments,
    putComment: putReelComment,
    deleteComment: deleteReelComment,
  };
}

export function cleanComment(text) {
  const value = String(text ?? "").replace(/\s+/g, " ").trim();
  if (!value) return { ok: false, error: "Write a comment first." };
  if (value.length > 280) return { ok: false, error: "Keep the comment under 280 characters." };
  return { ok: true, text: value };
}

export function cleanReshareNote(text) {
  const value = String(text ?? "").replace(/\s+/g, " ").trim();
  if (value.length > 180) return { ok: false, error: "Keep the reshare note under 180 characters." };
  return { ok: true, text: value };
}

export function filterComments(comments, query) {
  const needle = String(query ?? "").trim().toLowerCase();
  if (!needle) return comments ?? [];
  return (comments ?? []).filter((comment) => `${comment.author} ${comment.text}`.toLowerCase().includes(needle));
}

export function clampReelIndex(index, count) {
  const total = Number(count) || 0;
  if (total <= 0) return 0;
  const next = Number(index) || 0;
  return Math.min(total - 1, Math.max(0, next));
}

export function formatReelCount(value) {
  const count = Math.max(0, Math.round(Number(value) || 0));
  if (count < 1000) return String(count);
  if (count < 10000) return `${(count / 1000).toFixed(1)}k`;
  return `${Math.round(count / 1000)}k`;
}

export function createReelsApi(store, catalog = REEL_CATALOG) {
  const byId = new Map(catalog.map((reel) => [reel.id, reel]));

  async function read() {
    const states = new Map((await store.allStates()).map((row) => [row.id, row]));
    const comments = await store.allComments();
    return { states, comments };
  }

  function present(reel, state, comments) {
    const mine = comments
      .filter((comment) => comment.reelId === reel.id)
      .map((comment) => ({ ...comment, mine: true }));
    const all = [...reel.comments, ...mine].sort((a, b) => (b.t ?? 0) - (a.t ?? 0));
    return {
      id: reel.id,
      title: reel.title,
      place: reel.place,
      caption: reel.caption,
      sound: reel.sound,
      video: reel.video,
      poster: reel.poster,
      liked: Boolean(state?.liked),
      saved: Boolean(state?.saved),
      reshared: Boolean(state?.reshared),
      note: state?.note || "",
      likes: state?.likes ?? reel.likes,
      saves: state?.saves ?? reel.saves,
      shares: state?.shares ?? reel.shares,
      reshares: state?.reshares ?? reel.reshares,
      views: state?.views ?? reel.views,
      comments: all,
    };
  }

  async function one(id) {
    const reel = byId.get(id);
    if (!reel) return null;
    const data = await read();
    return present(reel, data.states.get(id), data.comments);
  }

  async function writeState(id, patch) {
    const reel = byId.get(id);
    if (!reel) return { ok: false, error: "That reel is not on this device." };
    const data = await read();
    const current = data.states.get(id) || {
      id,
      liked: false,
      saved: false,
      reshared: false,
      likes: reel.likes,
      saves: reel.saves,
      shares: reel.shares,
      reshares: reel.reshares,
      views: reel.views,
      note: "",
    };
    const next = { ...current, ...patch, id };
    await store.putState(next);
    return { ok: true, reel: present(reel, next, data.comments) };
  }

  return {
    async feed() {
      const data = await read();
      return catalog.map((reel) => present(reel, data.states.get(reel.id), data.comments));
    },
    async get(id) {
      return one(id);
    },
    async toggleLike(id) {
      const reel = await one(id);
      if (!reel) return { ok: false, error: "That reel is not on this device." };
      const liked = !reel.liked;
      return writeState(id, { liked, likes: Math.max(0, reel.likes + (liked ? 1 : -1)) });
    },
    async toggleSave(id) {
      const reel = await one(id);
      if (!reel) return { ok: false, error: "That reel is not on this device." };
      const saved = !reel.saved;
      return writeState(id, { saved, saves: Math.max(0, reel.saves + (saved ? 1 : -1)) });
    },
    async recordView(id) {
      const reel = await one(id);
      if (!reel) return { ok: false, error: "That reel is not on this device." };
      return writeState(id, { views: reel.views + 1 });
    },
    async share(id) {
      const reel = await one(id);
      if (!reel) return { ok: false, error: "That reel is not on this device." };
      const saved = await writeState(id, { shares: reel.shares + 1 });
      return {
        ...saved,
        payload: {
          title: reel.title,
          text: `${reel.caption} ${reel.place}`,
          url: `location-journal://reel/${id}`,
        },
      };
    },
    async reshare(id, note) {
      const cleaned = cleanReshareNote(note);
      if (!cleaned.ok) return cleaned;
      const reel = await one(id);
      if (!reel) return { ok: false, error: "That reel is not on this device." };
      const reshares = reel.reshared ? reel.reshares : reel.reshares + 1;
      return writeState(id, { reshared: true, note: cleaned.text, reshares });
    },
    async undoReshare(id) {
      const reel = byId.get(id);
      const current = await one(id);
      if (!reel || !current) return { ok: false, error: "That reel is not on this device." };
      if (!current.reshared) return { ok: true, reel: current };
      return writeState(id, {
        reshared: false,
        note: "",
        reshares: Math.max(reel.reshares, current.reshares - 1),
      });
    },
    async addComment(id, text) {
      const cleaned = cleanComment(text);
      if (!cleaned.ok) return cleaned;
      if (!byId.has(id)) return { ok: false, error: "That reel is not on this device." };
      const comment = {
        id: `local:${Date.now()}:${Math.random().toString(36).slice(2, 8)}`,
        reelId: id,
        author: "You",
        text: cleaned.text,
        t: Date.now(),
      };
      await store.putComment(comment);
      return { ok: true, comment: { ...comment, mine: true }, reel: await one(id) };
    },
    async deleteComment(id) {
      const data = await read();
      const comment = data.comments.find((row) => row.id === id);
      if (!comment || comment.author !== "You") {
        return { ok: false, error: "You can remove only comments you added on this device." };
      }
      await store.deleteComment(id);
      return { ok: true, reel: await one(comment.reelId) };
    },
    async prefs() {
      const data = await read();
      const saved = data.states.get(PREFS_ID);
      return {
        index: clampReelIndex(saved?.index ?? 0, catalog.length),
        muted: saved?.muted !== false,
      };
    },
    async savePrefs(prefs) {
      const current = await this.prefs();
      const next = {
        id: PREFS_ID,
        index: clampReelIndex(prefs.index ?? current.index, catalog.length),
        muted: typeof prefs.muted === "boolean" ? prefs.muted : current.muted,
      };
      await store.putState(next);
      return next;
    },
  };
}
