import * as ed from '@noble/ed25519';
import { sha512 } from '@noble/hashes/sha512.js';
import { randomBytes } from './rng';
import { signedPayload, SignableRecord } from './canonicalize';
import { utf8Encode } from './portable-codec';

ed.hashes.sha512 = sha512;

export interface KeyPair {
  privateKey: Uint8Array;
  publicKeyBase64: string;
}

export function generateKeyPair(rand: () => number): KeyPair {
  const privateKey = randomBytes(rand, 32);
  const publicKey = ed.getPublicKey(privateKey);
  return { privateKey, publicKeyBase64: Buffer.from(publicKey).toString('base64') };
}

export function signRecord(record: SignableRecord, privateKey: Uint8Array): string {
  const message = utf8Encode(signedPayload(record));
  const signature = ed.sign(message, privateKey);
  return Buffer.from(signature).toString('base64');
}
