"use strict";
if (process.env.FIRESTORE_EMULATOR_HOST !== "127.0.0.1:8087" || process.env.GCLOUD_PROJECT !== "demo-dmm-v2") {
  throw new Error("Use the isolated demo-dmm-v2 emulator only");
}
const test = require("node:test");
const assert = require("node:assert/strict");
const { initializeApp, deleteApp } = require("firebase-admin/app");
const { getFirestore, FieldValue, Timestamp } = require("firebase-admin/firestore");
const { reserveLegacyAlert, trimLegacyHistory } = require("./legacyHistory");

test("legacy alerts preserve both histories and expire only audio beyond ten received voice notes", async t => {
  const app = initializeApp({projectId:"demo-dmm-v2"}); const db = getFirestore(app);
  t.after(async () => { await db.terminate(); await deleteApp(app); });
  const uid = "legacy_" + Date.now(); const sender = uid + "_sender";
  await db.collection("users").doc(uid).set({username:"Receiver"});
  await db.collection("users").doc(sender).set({username:"Sender"});
  const batch = db.batch();
  for (let i = 0; i < 16; i++) {
    const id = uid + "_" + i;
    batch.set(db.collection("history").doc(id + "_received"), { ownerUid:uid, direction:"received", alertId:id,
      timestamp:Timestamp.fromMillis(1000+i), voiceUrl:`https://res.cloudinary.com/dqrb7jasx/raw/upload/v1/sos_voice_memos/${id}.3gp` });
    batch.set(db.collection("alerts").doc(id),{toUid:uid,fromUid:sender});
  }
  await batch.commit();
  const alertId=uid+"_new";
  const args=[db,{fromUid:sender,toUid:uid,hasLocation:true,latitude:28.6,longitude:77.2},alertId,{username:"Mom",phone:"123"},FieldValue.serverTimestamp(),null];
  assert.equal(await reserveLegacyAlert(...args),true); assert.equal(await reserveLegacyAlert(...args),false);
  await trimLegacyHistory(db,uid);
  const history=await db.collection("history").where("ownerUid","==",uid).get();
  assert.equal(history.size,17); assert.ok(history.docs.some(doc=>doc.get("alertId")===alertId));
  assert.equal(history.docs.filter(doc=>doc.get("voiceUrl")).length,10);
  assert.equal(history.docs.filter(doc=>doc.get("voiceExpired")).length,6);
  const sent = await db.collection("history").doc(alertId+"_sent").get();
  assert.equal(sent.get("ownerUid"),sender); assert.equal(sent.get("voiceUrl"),null);
  const alerts=await db.collection("alerts").where("toUid","==",uid).get();
  assert.equal(alerts.size,16);
  assert.equal(alerts.docs.filter(doc=>doc.get("voiceExpired")).length,6);
  assert.equal((await db.collection("users").doc(uid).get()).get("historyNeedsTrim"),false);
});
