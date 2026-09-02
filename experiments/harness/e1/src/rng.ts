// mulberry32: a standard small, fast, deterministic PRNG. Used only to generate
// synthetic experiment data (record content, device keys) reproducibly from a
// seed, never for anything requiring cryptographic randomness.
export function mulberry32(seed: number): () => number {
  var a = seed >>> 0;
  return function () {
    a |= 0;
    a = (a + 0x6d2b79f5) | 0;
    var t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

export function randomBytes(rand: () => number, length: number): Uint8Array {
  var bytes = new Uint8Array(length);
  for (var i = 0; i < length; i++) {
    bytes[i] = Math.floor(rand() * 256);
  }
  return bytes;
}
