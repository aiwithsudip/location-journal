import assert from "node:assert/strict";
import { upgradePlan } from "./settings.js";
import { iconSvg } from "./hugeicons.js";
import {
  clampReelIndex,
  cleanComment,
  cleanReshareNote,
  createMemoryReelStore,
  createReelsApi,
  filterComments,
  formatReelCount,
  REEL_CATALOG,
} from "./reels-api.js";

assert.equal(REEL_CATALOG.length, 10);
assert.equal(upgradePlan(2).createReelState, true);
assert.equal(upgradePlan(2).createReelComments, true);
assert.equal(upgradePlan(2).deletesStores, false);
assert.equal(upgradePlan(3).createReelState, false);
assert.equal(upgradePlan(3).deletesStores, false);
assert.equal(iconSvg("FavouriteIcon").includes("<svg"), true);
assert.equal(iconSvg("Share08Icon").includes("stroke-width"), true);
assert.equal(clampReelIndex(-4, 10), 0);
assert.equal(clampReelIndex(20, 10), 9);
assert.equal(formatReelCount(1280), "1.3k");
assert.equal(cleanComment("  ").ok, false);
assert.equal(cleanComment("hello").text, "hello");
assert.equal(cleanReshareNote("x".repeat(181)).ok, false);

const api = createReelsApi(createMemoryReelStore());
const feed = await api.feed();
assert.equal(feed.length, 10);
assert.equal(feed.some((reel) => reel.id === "__prefs"), false);

const liked = await api.toggleLike(feed[0].id);
assert.equal(liked.ok, true);
assert.equal(liked.reel.liked, true);
assert.equal(liked.reel.likes, feed[0].likes + 1);
const unliked = await api.toggleLike(feed[0].id);
assert.equal(unliked.reel.liked, false);
assert.equal(unliked.reel.likes, feed[0].likes);

const empty = await api.addComment(feed[1].id, "   ");
assert.equal(empty.ok, false);
const added = await api.addComment(feed[1].id, "On the lane");
assert.equal(added.ok, true);
assert.equal(added.comment.author, "You");
assert.equal(added.reel.comments[0].text, "On the lane");
assert.equal(filterComments(added.reel.comments, "lane").length, 1);
const removed = await api.deleteComment(added.comment.id);
assert.equal(removed.ok, true);
assert.equal(removed.reel.comments.some((comment) => comment.id === added.comment.id), false);
const seeded = await api.deleteComment(feed[1].comments[0].id);
assert.equal(seeded.ok, false);

const shared = await api.share(feed[2].id);
assert.equal(shared.ok, true);
assert.equal(shared.reel.shares, feed[2].shares + 1);
assert.equal(shared.payload.url.includes(feed[2].id), true);
const again = await api.reshare(feed[2].id, "Take another look");
assert.equal(again.reel.reshared, true);
assert.equal(again.reel.reshares, feed[2].reshares + 1);
const repeat = await api.reshare(feed[2].id, "Updated");
assert.equal(repeat.reel.reshares, feed[2].reshares + 1);
const undone = await api.undoReshare(feed[2].id);
assert.equal(undone.reel.reshared, false);
assert.equal(undone.reel.reshares, feed[2].reshares);

const missing = await api.toggleLike("nope");
assert.equal(missing.ok, false);
const viewed = await api.recordView(feed[3].id);
assert.equal(viewed.reel.views, feed[3].views + 1);
const prefs = await api.savePrefs({ index: 8, muted: true });
assert.equal(prefs.index, 8);
const listed = await api.feed();
assert.equal(listed.length, 10);

console.log("reels tests passed");
