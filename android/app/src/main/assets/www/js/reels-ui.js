import { iconSvg } from "./hugeicons.js";
import { clampReelIndex, createIdbReelStore, createReelsApi, filterComments, formatReelCount } from "./reels-api.js";

const screen = document.querySelector("#reels-screen");
const feedEl = document.querySelector("#reels-feed");
const statusEl = document.querySelector("#reels-status");
const commentsEl = document.querySelector("#reels-comments");
const shareEl = document.querySelector("#reels-share");

let api;
let feed = [];
let active = 0;
let booted = false;
let seen = new Set();
let observer;
let tapTimer = 0;
let lastTap = 0;

function escapeHtml(value) {
  return String(value ?? "").replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;");
}
function escapeAttr(value) {
  return escapeHtml(value).replaceAll('"', "&quot;");
}

function setReelStatus(text) {
  statusEl.textContent = text;
}

function current() {
  return feed[active] || null;
}

function actionButton(name, label, count, pressed) {
  return `<button type="button" class="reel-action" data-action="${name}" aria-pressed="${pressed ? "true" : "false"}" aria-label="${escapeAttr(label)}">
    ${iconSvg(iconFor(name, pressed))}
    <span class="reel-action-label sr-only">${escapeHtml(label)}</span>
    <span class="reel-action-count">${escapeHtml(formatReelCount(count))}</span>
  </button>`;
}

function iconFor(name, pressed) {
  if (name === "like") return "FavouriteIcon";
  if (name === "comment") return "Comment01Icon";
  if (name === "share") return "Share08Icon";
  if (name === "reshare") return pressed ? "Tick02Icon" : "RepeatIcon";
  if (name === "save") return "Bookmark01Icon";
  return "MoreHorizontalIcon";
}

function renderFeed() {
  feedEl.innerHTML = feed
    .map((reel, index) => {
      const label = `${reel.title}, ${reel.place}`;
      return `<article class="reel is-paused" data-index="${index}" data-id="${escapeAttr(reel.id)}" aria-label="${escapeAttr(label)}">
        <video poster="${escapeAttr(reel.poster)}" src="${escapeAttr(reel.video)}" playsinline loop muted preload="${index < 2 ? "auto" : "metadata"}"></video>
        <p class="reel-fallback" hidden>This reel could not play. Swipe to the next one.</p>
        <button type="button" class="reel-play" aria-label="Play or pause">${iconSvg("PlayIcon")}<span class="sr-only">Play</span></button>
        <label class="reel-scrub"><span class="sr-only">Seek</span>
          <span class="reel-time">0:00</span>
          <input type="range" min="0" max="1000" value="0" aria-valuemin="0" aria-valuemax="1000" />
          <span class="reel-duration">0:00</span>
        </label>
        <div class="reel-copy">
          <p class="reel-place">${iconSvg("Location01Icon")}<span>${escapeHtml(reel.place)}</span></p>
          <h2>${escapeHtml(reel.title)}</h2>
          <p>${escapeHtml(reel.caption)}</p>
          <p class="reel-sound">${iconSvg("MusicNote01Icon")}<span>${escapeHtml(reel.sound)} · Silent clip</span></p>
          <p class="reel-views">${iconSvg("EyeIcon")}<span>${escapeHtml(formatReelCount(reel.views))} views</span></p>
        </div>
        <div class="reel-rail">
          ${actionButton("like", reel.liked ? "Unlike" : "Like", reel.likes, reel.liked)}
          ${actionButton("comment", "Comments", reel.comments.length, false)}
          ${actionButton("save", reel.saved ? "Saved" : "Save", reel.saves, reel.saved)}
          ${actionButton("share", "Share", reel.shares, false)}
          ${actionButton("reshare", reel.reshared ? "Reshared" : "Reshare", reel.reshares, reel.reshared)}
        </div>
      </article>`;
    })
    .join("");
  document.querySelector("#reels-count").textContent = `${feed.length ? active + 1 : 0} / ${feed.length}`;
  placeChrome();
  feedEl.querySelectorAll(".reel").forEach((card) => observer?.observe(card));
  wireCards();
}

function clock(seconds) {
  if (!Number.isFinite(seconds) || seconds < 0) return "0:00";
  const total = Math.round(seconds);
  return `${Math.floor(total / 60)}:${String(total % 60).padStart(2, "0")}`;
}

function placeChrome() {
  const bar = document.querySelector(".appbar");
  if (bar) document.documentElement.style.setProperty("--appbar-bottom", `${Math.ceil(bar.getBoundingClientRect().bottom)}px`);
  sizeReels();
}

function sizeReels() {
  const height = feedEl.clientHeight;
  if (!height) return;
  const ideal = Math.round(height * (9 / 16));
  const column = Math.min(feedEl.clientWidth, ideal);
  const letterbox = feedEl.clientWidth > 700 && feedEl.clientWidth - column > 24;
  feedEl.querySelectorAll(".reel").forEach((card) => {
    card.style.height = `${height}px`;
    card.style.width = letterbox ? `${column}px` : "100%";
    card.style.marginInline = letterbox ? "auto" : "0";
  });
}

window.addEventListener("resize", () => {
  if (!screen.hidden) placeChrome();
});

function wireCards() {
  feedEl.querySelectorAll(".reel").forEach((card) => {
    const video = card.querySelector("video");
    const play = card.querySelector(".reel-play");
    const range = card.querySelector("input");
    video.addEventListener("error", () => {
      card.querySelector(".reel-fallback").hidden = false;
      play.querySelector("span").textContent = "Unavailable";
    });
    video.addEventListener("play", () => {
      card.classList.remove("is-paused");
      play.querySelector("span").textContent = "Pause";
      play.querySelector("svg")?.replaceWith(svgNode("PauseIcon"));
    });
    video.addEventListener("pause", () => {
      card.classList.add("is-paused");
      play.querySelector("span").textContent = "Play";
      play.querySelector("svg")?.replaceWith(svgNode("PlayIcon"));
    });
    const paintTime = () => {
      card.querySelector(".reel-time").textContent = clock(video.currentTime);
      card.querySelector(".reel-duration").textContent = clock(video.duration);
    };
    video.addEventListener("loadedmetadata", paintTime);
    video.addEventListener("timeupdate", () => {
      paintTime();
      if (range.dataset.scrubbing === "true" || !video.duration) return;
      range.value = String(Math.round((video.currentTime / video.duration) * 1000));
    });
    range.addEventListener("pointerdown", () => {
      range.dataset.scrubbing = "true";
    });
    range.addEventListener("pointerup", () => {
      range.dataset.scrubbing = "false";
    });
    range.addEventListener("change", () => {
      if (!video.duration) return;
      video.currentTime = (Number(range.value) / 1000) * video.duration;
      range.dataset.scrubbing = "false";
    });
    play.addEventListener("click", (event) => {
      event.stopPropagation();
      togglePlay(video);
    });
    video.addEventListener("pointerup", (event) => onVideoTap(event, card, video));
    card.querySelectorAll(".reel-action").forEach((button) => {
      button.addEventListener("click", (event) => {
        event.stopPropagation();
        void onAction(card.dataset.id, button.dataset.action);
      });
    });
  });
}

function svgNode(name) {
  const holder = document.createElement("span");
  holder.innerHTML = iconSvg(name);
  return holder.firstChild;
}

function togglePlay(video) {
  if (video.paused) video.play().catch(() => setReelStatus("Tap Play to start this reel."));
  else video.pause();
}

function onVideoTap(event, card, video) {
  const now = Date.now();
  if (now - lastTap < 280) {
    clearTimeout(tapTimer);
    lastTap = 0;
    burst(card, event.clientX, event.clientY);
    if (!feed.find((reel) => reel.id === card.dataset.id)?.liked) void onAction(card.dataset.id, "like");
    return;
  }
  lastTap = now;
  tapTimer = setTimeout(() => togglePlay(video), 280);
}

function burst(card, x, y) {
  const heart = document.createElement("span");
  heart.className = "reel-burst";
  heart.innerHTML = iconSvg("FavouriteIcon");
  const box = card.getBoundingClientRect();
  heart.style.left = `${x - box.left - 24}px`;
  heart.style.top = `${y - box.top - 24}px`;
  card.appendChild(heart);
  setTimeout(() => heart.remove(), 700);
}

async function onAction(id, action) {
  if (action === "comment") {
    openComments(id);
    return;
  }
  if (action === "share" || action === "reshare") {
    const reel = await api.get(id);
    if (!reel) {
      setReelStatus("That reel is not on this device.");
      return;
    }
    openShare(id, { title: reel.title, text: `${reel.caption} ${reel.place}`, url: `location-journal://reel/${id}` });
    return;
  }
  const result = action === "like" ? await api.toggleLike(id) : await api.toggleSave(id);
  if (!result.ok) {
    setReelStatus(result.error);
    return;
  }
  replaceReel(result.reel);
}

function replaceReel(reel) {
  const index = feed.findIndex((item) => item.id === reel.id);
  if (index < 0) return;
  feed[index] = reel;
  const card = feedEl.querySelector(`[data-id="${CSS.escape(reel.id)}"]`);
  if (!card) return;
  paintRail(card, reel);
  card.querySelector(".reel-views span").textContent = `${formatReelCount(reel.views)} views`;
}

function paintRail(card, reel) {
  const map = {
    like: [reel.liked ? "Unlike" : "Like", reel.likes, reel.liked],
    comment: ["Comments", reel.comments.length, false],
    save: [reel.saved ? "Saved" : "Save", reel.saves, reel.saved],
    share: ["Share", reel.shares, false],
    reshare: [reel.reshared ? "Reshared" : "Reshare", reel.reshares, reel.reshared],
  };
  card.querySelectorAll(".reel-action").forEach((button) => {
    const [label, count, pressed] = map[button.dataset.action];
    button.setAttribute("aria-pressed", pressed ? "true" : "false");
    button.setAttribute("aria-label", label);
    button.querySelector(".reel-action-label").textContent = label;
    button.querySelector(".reel-action-count").textContent = formatReelCount(count);
    const icon = iconFor(button.dataset.action, pressed);
    button.querySelector("svg")?.replaceWith(svgNode(icon));
  });
}

function playActive() {
  document.querySelector("#reels-count").textContent = `${feed.length ? active + 1 : 0} / ${feed.length}`;
  feedEl.querySelectorAll(".reel").forEach((card, index) => {
    const video = card.querySelector("video");
    if (index === active && !screen.hidden) {
      video.play().catch(() => setReelStatus("Tap Play if the reel does not start."));
      if (!seen.has(card.dataset.id)) {
        seen.add(card.dataset.id);
        api.recordView(card.dataset.id).then((result) => {
          if (result.ok) replaceReel(result.reel);
        });
      }
    } else video.pause();
  });
  void api.savePrefs({ index: active });
}

function openComments(id) {
  const reel = feed.find((item) => item.id === id) || current();
  if (!reel) return;
  active = Math.max(0, feed.findIndex((item) => item.id === reel.id));
  drawComments(reel, "");
  commentsEl.hidden = false;
  shareEl.hidden = true;
  commentsEl.querySelector("#reels-comment-text")?.focus();
}

function drawComments(reel, query) {
  const shown = filterComments(reel.comments, query);
  commentsEl.innerHTML = `<header class="reels-sheet-bar">
      <h2 id="reels-comments-title">Comments</h2>
      <button type="button" id="reels-comments-close">Close</button>
    </header>
    <label class="reels-search"><span class="sr-only">Search comments</span>
      <input id="reels-comment-search" type="search" value="${escapeAttr(query)}" placeholder="Search comments" />
    </label>
    <div class="reels-comment-list" tabindex="0">
      ${
        shown.length
          ? shown
              .map((comment) => {
                const letter = escapeHtml(String(comment.author || "?").trim().charAt(0).toUpperCase() || "?");
                return `<article class="reels-comment">
                  <span class="reels-avatar" aria-hidden="true">${letter}</span>
                  <div>
                    <p><strong>${escapeHtml(comment.author)}</strong>${escapeHtml(comment.text)}</p>
                  </div>
                  ${comment.mine ? `<button type="button" data-delete="${escapeAttr(comment.id)}">Delete</button>` : ""}
                </article>`;
              })
              .join("")
          : `<p class="reel-note">No matching comments. Add one below. Comments stay on this device.</p>`
      }
    </div>
    <form class="reels-composer" id="reels-comment-form">
      <label><span class="sr-only">Add a comment</span>
        <input id="reels-comment-text" type="text" maxlength="280" placeholder="Add a comment" />
      </label>
      <button type="submit">Send</button>
    </form>`;
  commentsEl.querySelector("#reels-comments-close").addEventListener("click", closeDrawers);
  commentsEl.querySelector("#reels-comment-search").addEventListener("input", (event) => {
    const caret = event.target.selectionStart;
    drawComments(reel, event.target.value);
    const next = commentsEl.querySelector("#reels-comment-search");
    next.focus();
    next.setSelectionRange(caret, caret);
  });
  commentsEl.querySelectorAll("[data-delete]").forEach((button) => {
    button.addEventListener("click", async () => {
      const result = await api.deleteComment(button.dataset.delete);
      if (!result.ok) {
        setReelStatus(result.error);
        return;
      }
      replaceReel(result.reel);
      drawComments(result.reel, commentsEl.querySelector("#reels-comment-search")?.value || "");
    });
  });
  commentsEl.querySelector("#reels-comment-form").addEventListener("submit", async (event) => {
    event.preventDefault();
    const input = commentsEl.querySelector("#reels-comment-text");
    const result = await api.addComment(reel.id, input.value);
    if (!result.ok) {
      setReelStatus(result.error);
      return;
    }
    replaceReel(result.reel);
    drawComments(result.reel, "");
    setReelStatus("Comment saved on this device.");
  });
}

function openShare(id, payload) {
  const reel = feed.find((item) => item.id === id);
  if (!reel) return;
  const link = payload?.url || `location-journal://reel/${id}`;
  shareEl.innerHTML = `<header class="reels-sheet-bar">
      <h2 id="reels-share-title">Share</h2>
      <button type="button" id="reels-share-close">Close</button>
    </header>
    <p class="reel-note">${escapeHtml(reel.title)}. Stays on this device.</p>
    <p class="reels-link">${escapeHtml(link)}</p>
    <div class="reels-share-actions">
      <button type="button" id="reels-copy">${iconSvg("Copy01Icon")}<span>Copy link</span></button>
      <button type="button" id="reels-system">${iconSvg("Share08Icon")}<span>Share</span></button>
    </div>
    <form id="reels-reshare-form">
      <label><span class="sr-only">Reshare note</span>
        <input id="reels-reshare-note" type="text" maxlength="180" value="${escapeAttr(reel.note || "")}" placeholder="Add a note" />
      </label>
      <button type="submit">${reel.reshared ? "Update reshare" : "Reshare"}</button>
      <button type="button" id="reels-undo" ${reel.reshared ? "" : "hidden"}>Undo reshare</button>
    </form>`;
  shareEl.hidden = false;
  commentsEl.hidden = true;
  shareEl.querySelector("#reels-share-close").addEventListener("click", closeDrawers);
  const countShare = async () => {
    const shared = await api.share(id);
    if (!shared.ok) {
      setReelStatus(shared.error);
      return false;
    }
    replaceReel(shared.reel);
    return true;
  };
  shareEl.querySelector("#reels-copy").addEventListener("click", async () => {
    const counted = await countShare();
    if (!counted) return;
    try {
      await navigator.clipboard.writeText(link);
      setReelStatus("Link copied on this device.");
    } catch {
      setReelStatus(link);
    }
  });
  shareEl.querySelector("#reels-system").addEventListener("click", async () => {
    const data = { title: reel.title, text: reel.caption, url: link };
    if (navigator.share) {
      try {
        await navigator.share(data);
        await countShare();
        setReelStatus("Share sheet opened.");
        return;
      } catch (error) {
        if (error?.name === "AbortError") return;
      }
    }
    const counted = await countShare();
    if (!counted) return;
    try {
      await navigator.clipboard.writeText(`${reel.title} ${link}`);
      setReelStatus("Sharing is not available here. The link was copied instead.");
    } catch {
      setReelStatus("Sharing is not available on this device.");
    }
  });
  shareEl.querySelector("#reels-reshare-form").addEventListener("submit", async (event) => {
    event.preventDefault();
    const result = await api.reshare(id, shareEl.querySelector("#reels-reshare-note").value);
    if (!result.ok) {
      setReelStatus(result.error);
      return;
    }
    replaceReel(result.reel);
    setReelStatus(result.reel.note ? "Reshared with your note on this device." : "Reshared on this device.");
    openShare(id, { url: link });
  });
  shareEl.querySelector("#reels-undo")?.addEventListener("click", async () => {
    const result = await api.undoReshare(id);
    if (!result.ok) {
      setReelStatus(result.error);
      return;
    }
    replaceReel(result.reel);
    setReelStatus("Reshare removed on this device.");
    openShare(id, { url: link });
  });
}

function closeDrawers() {
  commentsEl.hidden = true;
  shareEl.hidden = true;
  feedEl.focus();
}

function drawersOpen() {
  return !commentsEl.hidden || !shareEl.hidden;
}

export async function mountReels() {
  screen.hidden = false;
  if (!booted) {
    setReelStatus("Opening reels…");
    api = createReelsApi(createIdbReelStore());
    observer = new IntersectionObserver(
      (entries) => {
        const best = entries
          .filter((entry) => entry.isIntersecting && entry.intersectionRatio >= 0.6)
          .sort((a, b) => b.intersectionRatio - a.intersectionRatio)[0];
        if (!best) return;
        const index = Number(best.target.dataset.index);
        if (index === active) return;
        active = index;
        playActive();
      },
      { root: feedEl, threshold: [0.6, 0.85] },
    );
    try {
      feed = await api.feed();
      const prefs = await api.prefs();
      active = clampReelIndex(prefs.index, feed.length);
      renderFeed();
      booted = true;
      const card = feedEl.querySelector(`[data-index="${active}"]`);
      card?.scrollIntoView();
      setReelStatus("Nothing is uploaded.");
      requestAnimationFrame(placeChrome);
    } catch (error) {
      setReelStatus(error?.message || "The reels could not be opened.");
      return;
    }
  }
  playActive();
}

export function pauseReels() {
  screen.hidden = true;
  closeDrawers();
  feedEl.querySelectorAll("video").forEach((video) => video.pause());
}

document.addEventListener("keydown", (event) => {
  if (!document.body.classList.contains("is-reels")) return;
  if (event.key === "Escape" && drawersOpen()) {
    event.preventDefault();
    closeDrawers();
    return;
  }
  if (drawersOpen() || event.target instanceof HTMLInputElement || event.target instanceof HTMLTextAreaElement) return;
  if (event.key !== "ArrowDown" && event.key !== "ArrowUp") return;
  event.preventDefault();
  active = clampReelIndex(active + (event.key === "ArrowDown" ? 1 : -1), feed.length);
  feedEl.querySelector(`[data-index="${active}"]`)?.scrollIntoView({ block: "start" });
  playActive();
});
