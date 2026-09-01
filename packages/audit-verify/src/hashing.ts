// Byte-identical to AuditHashing.java (connector/extensions/benefits-audit) by
// construction, and proven so by the shared conformance vectors under
// conformance/audit-vectors/. The escape rule matches KAnonymity.KEY_ESCAPE in
// benefits-privacy: replace \ with \\, then | with \|.

export interface AuditEntry {
  seq: number;
  epoch: string | null;
  timestamp: string;
  eventType: string;
  actor: string;
  datasetRef: string;
  statedPurpose: string;
  plainLanguage: string;
}

export function escape(value: string): string {
  return value.replace(/\\/g, '\\\\').replace(/\|/g, '\\|');
}

export function canonicalBytes(entry: AuditEntry): Uint8Array {
  const joined = [
    escape(String(entry.seq)),
    escape(entry.epoch ?? ''),
    escape(entry.timestamp),
    escape(entry.eventType),
    escape(entry.actor),
    escape(entry.datasetRef),
    escape(entry.statedPurpose),
    escape(entry.plainLanguage),
  ].join('|');
  return new TextEncoder().encode(joined);
}

function toHex(buffer: ArrayBuffer): string {
  return Array.from(new Uint8Array(buffer)).map((b) => b.toString(16).padStart(2, '0')).join('');
}

function hexToBytes(hex: string): Uint8Array {
  const bytes = new Uint8Array(hex.length / 2);
  for (let i = 0; i < bytes.length; i++) {
    bytes[i] = parseInt(hex.substring(i * 2, i * 2 + 2), 16);
  }
  return bytes;
}

async function sha256Hex(data: Uint8Array): Promise<string> {
  return toHex(await crypto.subtle.digest('SHA-256', data));
}

export async function leafHash(entry: AuditEntry): Promise<string> {
  const body = canonicalBytes(entry);
  const domain = new Uint8Array(body.length + 1);
  domain[0] = 0x00;
  domain.set(body, 1);
  return sha256Hex(domain);
}

export async function nodeHash(left: string, right: string): Promise<string> {
  const leftBytes = hexToBytes(left);
  const rightBytes = hexToBytes(right);
  const combined = new Uint8Array(1 + leftBytes.length + rightBytes.length);
  combined[0] = 0x01;
  combined.set(leftBytes, 1);
  combined.set(rightBytes, 1 + leftBytes.length);
  return sha256Hex(combined);
}

export async function prevEpochHash(epochId: string, merkleRoot: string, sealedAt: string): Promise<string> {
  const joined = [escape(epochId), escape(merkleRoot), escape(sealedAt)].join('|');
  return sha256Hex(new TextEncoder().encode(joined));
}

export const GENESIS_PREV_EPOCH_HASH: Promise<string> = sha256Hex(new Uint8Array(0));
