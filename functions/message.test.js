const test = require("node:test");
const assert = require("node:assert/strict");
const { buildEmergencyMessage } = require("./message");

test("push is data-only and matches the receiver schema", () => {
  const result = buildEmergencyMessage({ fromUid: "sender", toUid: "receiver", fromName: "Spoofed", latitude: 0, longitude: 77,
    voiceUrl: "https://res.cloudinary.com/dqrb7jasx/raw/upload/v1/sos_voice_memos/file.3gp" }, "alert-id", { username: "Mom", phone: "+919876543210" }, "token");
  assert.equal(result.data.fromName, "Mom");
  assert.equal(result.data.hasLocation, "true");
  assert.equal(result.data.latitude, "0");
  assert.ok(result.data.voiceUrl);
  assert.equal(result.android.notification, undefined);
  assert.equal(result.data.toUid, "receiver");
  assert.ok(Object.values(result.data).every(value => typeof value === "string"));
});
test("invalid coordinates and voice hosts are omitted", () => {
  const result = buildEmergencyMessage({ fromUid: "sender", toUid: "receiver", latitude: 120, longitude: 0, voiceUrl: "http://localhost/secret" },
    "alert-id", {}, "token");
  assert.equal(result.data.hasLocation, "false");
  assert.equal(result.data.voiceUrl, undefined);
});
test("v1 trigger loads with the installed Functions SDK", () => {
  assert.equal(typeof require("./index").sendEmergencyPush, "function");
});

test("legacy alert reservation writes durable history exactly once before FCM", async () => {
  const { reserveLegacyAlert } = require("./legacyHistory");
  const rows = new Map();
  const db = { collection: name => ({ doc: id => ({path:name+"/"+id}) }), runTransaction: async fn => fn({
    get: async ref => ({ exists:rows.has(ref.path),get:key=>rows.get(ref.path)?.[key] }),
    set: (ref,value) => rows.set(ref.path,value), update: (ref,value) => rows.set(ref.path,{...rows.get(ref.path),...value})
  }) };
  const alert = {fromUid:"sender",toUid:"receiver",hasLocation:true,latitude:0,longitude:77};
  const sender = {username:"Mom",phone:"123"};
  assert.equal(await reserveLegacyAlert(db,alert,"legacy-id",sender,123,null),true);
  assert.equal(await reserveLegacyAlert(db,alert,"legacy-id",sender,456,null),false);
  const history=rows.get("history/legacy-id_received");
  assert.equal(history.timestamp,123); assert.equal(history.counterpartName,"Mom");
  assert.equal(history.ownerUid,"receiver"); assert.equal(history.latitude,0);
  assert.equal(rows.get("users/receiver").historyNeedsTrim,true);
});
