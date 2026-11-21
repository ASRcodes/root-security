"use strict";
const crypto = require("node:crypto");

// The legacy alerts trigger feeds the same received history as the HTTP backend.
async function reserveLegacyAlert(db, alert, alertId, sender, timestamp, voiceUrl) {
  const ref = db.collection("history").doc(alertId + "_received");
  const delivery = db.collection("deliveries").doc(alertId);
  const assetRef = voiceUrl ? db.collection("voiceAssets").doc(crypto.createHash("sha256").update(voiceUrl).digest("hex")) : null;
  return db.runTransaction(async tx => {
    const previous = await tx.get(delivery);
    if (previous.exists) return false; // Never replay an ambiguous emergency push.
    const asset = assetRef ? await tx.get(assetRef) : null;
    const receiver = await tx.get(db.collection("users").doc(alert.toUid));
    const attachVoice = !asset?.exists || (asset.get("status") !== "deleting" && asset.get("status") !== "deleted" &&
      (!asset.get("ownerUid") || asset.get("ownerUid") === alert.fromUid));
    tx.set(delivery, { fromUid: alert.fromUid, toUid: alert.toUid, status: "sending", updatedAtMs: Date.now() });
    const received = { direction: "received", ownerUid: alert.toUid, counterpartUid: alert.fromUid,
      counterpartName: sender.username || "A trusted contact", counterpartPhone: sender.phone || "",
      fromUid: alert.fromUid, toUid: alert.toUid, alertId, timestamp,
      latitude: alert.hasLocation ? alert.latitude : null, longitude: alert.hasLocation ? alert.longitude : null,
      voiceUrl: attachVoice ? voiceUrl || null : null, deliveryStatus: "unconfirmed" };
    tx.set(ref, received);
    tx.set(db.collection("history").doc(alertId + "_sent"), { ...received, direction: "sent",
      ownerUid: alert.fromUid, counterpartUid: alert.toUid,
      counterpartName: receiver.get("username") || "Contact", counterpartPhone: receiver.get("phone") || "", voiceUrl: null });
    tx.update(db.collection("users").doc(alert.toUid), { historyNeedsTrim: true });
    tx.update(db.collection("users").doc(alert.fromUid), { historyNeedsTrim: true });
    if (voiceUrl && attachVoice) tx.set(assetRef,
      { voiceUrl, ownerUid: alert.fromUid, status: "active", pinnedUntilMs: Date.now() + 10 * 60_000 }, { merge: true });
    return true;
  });
}
async function trimLegacyHistory(db, uid, now = Date.now) {
  // Same per-owner limits as the Render path; necessary even when Render is asleep.
  let more;
  do {
    more = await db.runTransaction(async tx => {
      const owner = db.collection("users").doc(uid);
      const [profile, rows] = await Promise.all([tx.get(owner), tx.get(db.collection("history")
        .where("ownerUid", "==", uid).orderBy("timestamp", "desc"))]);
      let receivedVoices = 0;
      const excess = rows.docs.filter(doc => doc.get("voiceUrl") &&
        (doc.get("direction") !== "received" || ++receivedVoices > 10));
      const chunk = excess.slice(0, 150);
      const alerts = await Promise.all(chunk.filter(doc => doc.get("direction") === "received" && doc.get("alertId"))
        .map(doc => tx.get(db.collection("alerts").doc(doc.get("alertId")))));
      alerts.filter(doc => doc.exists && doc.get("toUid") === uid)
        .forEach(doc => tx.update(doc.ref, { voiceUrl: null, voiceExpired: true }));
      for (const doc of chunk) {
        tx.update(doc.ref, { voiceUrl: null, voiceExpired: true });
        const voiceUrl = doc.get("voiceUrl");
        if (voiceUrl) tx.set(db.collection("voiceCleanup").doc(crypto.createHash("sha256").update(voiceUrl).digest("hex")),
          { voiceUrl, notBeforeMs: now() + 10 * 60_000 }, { merge: true });
      }
      if (profile.exists) tx.update(owner, { historyNeedsTrim: excess.length > chunk.length });
      return excess.length > chunk.length;
    });
  } while (more);
}
module.exports = { reserveLegacyAlert, trimLegacyHistory };
