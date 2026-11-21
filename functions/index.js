"use strict";
const functions = require("firebase-functions/v1");
const { initializeApp, getApps } = require("firebase-admin/app");
const { getFirestore, FieldValue } = require("firebase-admin/firestore");
const { getMessaging } = require("firebase-admin/messaging");
const { buildEmergencyMessage } = require("./message");
const { reserveLegacyAlert, trimLegacyHistory } = require("./legacyHistory");
if (!getApps().length) initializeApp();

exports.sendEmergencyPush = functions.runWith({ failurePolicy: true }).firestore.document("alerts/{alertId}").onCreate(async (snapshot, context) => {
  const alert = snapshot.data();
  if (typeof alert.fromUid !== "string" || typeof alert.toUid !== "string" || alert.fromUid === alert.toUid) return;
  const db = getFirestore();
  const senderRef = db.collection("users").doc(alert.fromUid);
  const receiverRef = db.collection("users").doc(alert.toUid);
  const [sender, receiver, outgoing, incoming] = await Promise.all([
    senderRef.get(), receiverRef.get(),
    senderRef.collection("trustedContacts").doc(alert.toUid).get(),
    receiverRef.collection("trustedContacts").doc(alert.fromUid).get()
  ]);
  if (!sender.exists || !receiver.exists || !outgoing.exists || !incoming.exists ||
      outgoing.get("blocked") || incoming.get("blocked")) return;
  const message = buildEmergencyMessage(alert, context.params.alertId, sender.data(), receiver.get("fcmToken"));
  const alertId = context.params.alertId;
  const validLocation = message.data.hasLocation === "true";
  const reserved = await reserveLegacyAlert(db, { ...alert, hasLocation: validLocation }, alertId, sender.data(), FieldValue.serverTimestamp(), message.data.voiceUrl);
  async function trimBothOwners() {
    await Promise.all([alert.fromUid, alert.toUid].map(uid => trimLegacyHistory(db, uid)));
  }
  if (!reserved) { await trimBothOwners(); return; }
  let status = "unavailable";
  if (message.token) {
    try { await getMessaging().send(message); status = "accepted"; }
    catch (error) {
      status = "unconfirmed";
      if (["messaging/registration-token-not-registered", "messaging/invalid-registration-token"].includes(error.code)) {
        status = "unavailable";
        await db.runTransaction(async tx => {
          const current = await tx.get(receiverRef);
          if (current.get("fcmToken") === message.token) tx.update(receiverRef, { fcmToken: null });
        });
      }
    }
  }
  await db.collection("deliveries").doc(alertId).update({ status: status === "accepted" ? "sent" : "unconfirmed", updatedAtMs: Date.now() });
  await Promise.all(["_received", "_sent"].map(suffix => db.collection("history").doc(alertId + suffix).update({ deliveryStatus: status }).catch(error => {
    if (error.code !== 5) throw error; // The owner may have explicitly deleted the record.
  })));
  await trimBothOwners();
});
