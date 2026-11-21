"use strict";
function buildEmergencyMessage(alert, alertId, sender, token) {
  const hasLocation = Number.isFinite(alert.latitude) && Number.isFinite(alert.longitude) &&
    Math.abs(alert.latitude) <= 90 && Math.abs(alert.longitude) <= 180 &&
    (alert.hasLocation === true || alert.latitude !== 0 || alert.longitude !== 0);
  const data = {
    type: "EMERGENCY_CALL", alertId, fromUid: alert.fromUid, toUid: alert.toUid,
    fromName: String(sender.username || "A trusted contact").slice(0, 120),
    fromPhone: String(sender.phone || "").slice(0, 32),
    latitude: String(hasLocation ? alert.latitude : 0), longitude: String(hasLocation ? alert.longitude : 0),
    hasLocation: String(hasLocation), sentAt: String(Date.now())
  };
  if (typeof alert.voiceUrl === "string" && /^https:\/\/res\.cloudinary\.com\/dqrb7jasx\/(raw|video)\/upload\//.test(alert.voiceUrl)) data.voiceUrl = alert.voiceUrl;
  return { token, android: { priority: "high", ttl: 120000 }, data };
}
module.exports = { buildEmergencyMessage };
