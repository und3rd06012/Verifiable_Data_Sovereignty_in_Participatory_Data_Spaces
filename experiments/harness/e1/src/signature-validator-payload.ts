import { evaluateRecord } from '../../../../packages/consent-core/src/evaluate';
import { signedPayload } from './canonicalize';
import { utf8Encode, base64Decode } from './portable-codec';
import * as ed from '@noble/ed25519';
import { sha512 } from '@noble/hashes/sha512.js';

ed.hashes.sha512 = sha512;

export function evaluate(record: any, nowIso: string) {
  return evaluateRecord(record, nowIso);
}

export function verifySignature(record: any, signatureBase64: string, publicKeyBase64: string): boolean {
  if (!signatureBase64 || !publicKeyBase64) {
    return false;
  }
  var message = utf8Encode(signedPayload(record));
  var signature = base64Decode(signatureBase64);
  var publicKey = base64Decode(publicKeyBase64);
  try {
    return ed.verify(signature, message, publicKey);
  } catch (e) {
    return false;
  }
}
