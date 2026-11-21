const {test, before, after, beforeEach} = require('node:test');
const {readFileSync} = require('node:fs');
const {initializeTestEnvironment, assertSucceeds, assertFails} = require('@firebase/rules-unit-testing');
const {doc, setDoc, getDoc, getDocs, updateDoc, deleteDoc, collection, query, where, orderBy, limit, serverTimestamp} = require('firebase/firestore');

let env;
before(async () => {
  env = await initializeTestEnvironment({projectId:'demo-dmm-v2', firestore:{host:'127.0.0.1',port:8087,rules:readFileSync('../firestore.rules','utf8')}});
});
after(async () => { if (env) await env.cleanup(); });
beforeEach(async () => {
  await env.clearFirestore();
  await env.withSecurityRulesDisabled(async context => {
    const db = context.firestore();
    const rows = {
      'users/alice': {username:'Alice',phone:'+919876543210',email:'private@example.test',fcmToken:'private-token'},
      'users/bob': {username:'Bob',phone:'+919876543211',fcmToken:'bob-token'},
      'users/alice/trustedContacts/bob': {username:'Bob',phone:'+919876543211',blocked:false},
      'users/bob/trustedContacts/alice': {username:'Alice',phone:'+919876543210',blocked:false},
      'requests/incoming': {fromUid:'alice',toUid:'bob',status:'pending'},
      'alerts/emergency': {fromUid:'alice',toUid:'bob',timestamp:100},
      'history/alice-entry': {ownerUid:'alice',direction:'received',timestamp:100},
      'history/bob-entry': {ownerUid:'bob',direction:'received',timestamp:100},
      'history/bob-sent': {ownerUid:'bob',direction:'sent',timestamp:101},
      'deliveries/alert': {fromUid:'alice',toUid:'bob',status:'sent'}
    };
    await Promise.all(Object.entries(rows).map(([path,value])=>setDoc(doc(db,path),value)));
  });
});
const user = uid => env.authenticatedContext(uid).firestore();

test('unauthenticated clients cannot read or alter profiles, requests, or server state', async () => {
  const db=env.unauthenticatedContext().firestore();
  await assertFails(getDoc(doc(db,'users/alice')));
  await assertFails(getDocs(collection(db,'users')));
  await assertFails(deleteDoc(doc(db,'users/alice')));
  await assertFails(setDoc(doc(db,'requests/forged'),{fromUid:'alice',toUid:'bob',status:'accepted'}));
  await assertFails(getDoc(doc(db,'deliveries/alert')));
});
test('profiles and token updates are restricted to the account owner', async () => {
  const alice=user('alice');
  await assertSucceeds(getDoc(doc(alice,'users/alice')));
  await assertFails(getDoc(doc(alice,'users/bob')));
  await assertFails(getDocs(query(collection(alice,'users'),where('phone','==','+919876543211'))));
  await assertSucceeds(updateDoc(doc(alice,'users/alice'),{fcmToken:'new-token'}));
  await assertFails(updateDoc(doc(alice,'users/alice'),{phone:'+919000000000'}));
  await assertFails(updateDoc(doc(alice,'users/bob'),{fcmToken:'stolen-token'}));
});
test('contacts cannot be forged or unblocked by the other person', async () => {
  const alice=user('alice');
  await assertSucceeds(getDocs(collection(alice,'users/alice/trustedContacts')));
  await assertSucceeds(getDoc(doc(alice,'users/bob/trustedContacts/alice')));
  await assertFails(getDoc(doc(user('stranger'),'users/bob/trustedContacts/alice')));
  await assertFails(setDoc(doc(alice,'users/alice/trustedContacts/stranger'),{blocked:false}));
  await assertSucceeds(updateDoc(doc(alice,'users/alice/trustedContacts/bob'),{blocked:true}));
  await assertFails(updateDoc(doc(alice,'users/bob/trustedContacts/alice'),{blocked:false}));
  await assertSucceeds(deleteDoc(doc(alice,'users/bob/trustedContacts/alice')));
});
test('request lists are private and only the recipient can decline', async () => {
  const alice=user('alice'), bob=user('bob');
  await assertSucceeds(getDocs(query(collection(bob,'requests'),where('toUid','==','bob'),where('status','==','pending'))));
  await assertFails(getDoc(doc(user('stranger'),'requests/incoming')));
  await assertFails(updateDoc(doc(alice,'requests/incoming'),{status:'rejected'}));
  await assertFails(updateDoc(doc(bob,'requests/incoming'),{status:'accepted'}));
  await assertSucceeds(updateDoc(doc(bob,'requests/incoming'),{status:'rejected'}));
});
test('alert creation requires both directions of trust and cannot spoof the sender', async () => {
  const alice=user('alice');
  await assertSucceeds(setDoc(doc(alice,'alerts/new'),{fromUid:'alice',toUid:'bob',timestamp:1}));
  await assertFails(setDoc(doc(alice,'alerts/spoof'),{fromUid:'bob',toUid:'alice'}));
  await assertFails(setDoc(doc(user('stranger'),'alerts/untrusted'),{fromUid:'stranger',toUid:'bob'}));
  await assertSucceeds(updateDoc(doc(user('bob'),'users/bob/trustedContacts/alice'),{blocked:true}));
  await assertFails(setDoc(doc(alice,'alerts/blocked'),{fromUid:'alice',toUid:'bob'}));
});
test('recipient can mark an alert handled but cannot change its payload', async () => {
  const bob=user('bob');
  await assertSucceeds(getDocs(query(collection(bob,'alerts'),where('toUid','==','bob'))));
  await assertSucceeds(updateDoc(doc(bob,'alerts/emergency'),{handled:true}));
  await assertFails(updateDoc(doc(bob,'alerts/emergency'),{fromUid:'stranger'}));
  await assertFails(updateDoc(doc(user('alice'),'alerts/emergency'),{handled:true}));
});
test('history is private and server bookkeeping cannot be edited by app users', async () => {
  const alice=user('alice');
  await assertSucceeds(getDoc(doc(alice,'history/alice-entry')));
  await assertFails(getDoc(doc(alice,'history/bob-entry')));
  for (const path of ['deliveries/alert','phoneDirectory/919876543210','sendLimits/alice','lookupLimits/alice','requestLimits/alice','voiceAssets/asset','voiceCleanup/asset']) {
    await assertFails(setDoc(doc(alice,path),{uid:'alice',count:0,status:'sent'}));
  }
});
test('reports can only be created through the authenticated backend', async () => {
  const alice=user('alice');
  await assertFails(setDoc(doc(alice,'reports/forged'),{reportedBy:'alice',reportedUserUid:'bob',reason:'Fake',requestId:'incoming'}));
  await env.withSecurityRulesDisabled(async context => setDoc(doc(context.firestore(),'reports/real'),{reportedBy:'bob',reportedUserUid:'alice',requestId:'incoming',emailStatus:'queued'}));
  await assertSucceeds(getDoc(doc(user('bob'),'reports/real')));
  await assertFails(getDoc(doc(alice,'reports/real')));
  await assertFails(updateDoc(doc(user('bob'),'reports/real'),{emailStatus:'sent'}));
});
test('combined history query includes both directions and only receipt metadata is writable', async () => {
  const bob=user('bob');
  const rows = await assertSucceeds(getDocs(query(collection(bob,'history'),where('ownerUid','==','bob'),orderBy('timestamp','desc'),limit(51))));
  require('node:assert/strict').deepEqual(rows.docs.map(doc=>doc.data().direction),['sent','received']);
  await assertFails(getDocs(collection(bob,'history')));
  await assertSucceeds(updateDoc(doc(bob,'history/bob-entry'),{sirenStatus:'ringing',receivedAt:serverTimestamp()}));
  await assertSucceeds(updateDoc(doc(bob,'history/bob-entry'),{sirenStatus:'timed_out',sirenStoppedAt:serverTimestamp()}));
  await assertFails(updateDoc(doc(user('alice'),'history/bob-entry'),{sirenStatus:'stopped'}));
  for (const fields of [{sirenStatus:'forged'},{voiceUrl:'https://evil.test'},{timestamp:101},{ownerUid:'alice'},{receivedAt:1}]) {
    await assertFails(updateDoc(doc(bob,'history/bob-entry'),fields));
  }
  await assertFails(deleteDoc(doc(bob,'history/bob-entry')));
  await assertFails(updateDoc(doc(bob,'history/bob-sent'),{sirenStatus:'stopped'}));
  await assertFails(updateDoc(doc(bob,'history/bob-entry'),{voiceExpired:true,voiceUrl:null}));
  await assertSucceeds(updateDoc(doc(bob,'users/bob'),{fcmToken:'fresh',fcmTokenUpdatedAt:serverTimestamp()}));
  await assertFails(updateDoc(doc(bob,'users/bob'),{fcmTokenUpdatedAt:1}));
});
