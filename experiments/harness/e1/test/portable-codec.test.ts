import { describe, it, expect } from 'vitest';
import { utf8Encode, base64Decode } from '../src/portable-codec';

describe('utf8Encode', () => {
  it('encodes plain ASCII identically to Buffer', () => {
    const input = 'hello world 123';
    expect(Array.from(utf8Encode(input))).toEqual(Array.from(Buffer.from(input, 'utf8')));
  });

  it('encodes multi-byte characters identically to Buffer', () => {
    const input = 'grant purpose: éè âê öü café';
    expect(Array.from(utf8Encode(input))).toEqual(Array.from(Buffer.from(input, 'utf8')));
  });

  it('encodes a surrogate pair (emoji) identically to Buffer', () => {
    const input = 'field-😀-end';
    expect(Array.from(utf8Encode(input))).toEqual(Array.from(Buffer.from(input, 'utf8')));
  });

  it('encodes JSON containing quotes and braces identically to Buffer', () => {
    const input = '{"a":1,"b":"x,y"}';
    expect(Array.from(utf8Encode(input))).toEqual(Array.from(Buffer.from(input, 'utf8')));
  });
});

describe('base64Decode', () => {
  it('decodes a value produced by Buffer.toString("base64")', () => {
    const original = new Uint8Array([0, 1, 2, 253, 254, 255, 42, 7]);
    const encoded = Buffer.from(original).toString('base64');
    expect(Array.from(base64Decode(encoded))).toEqual(Array.from(original));
  });

  it('decodes a value with padding', () => {
    const original = new TextEncoder().encode('a');
    const encoded = Buffer.from(original).toString('base64');
    expect(encoded.endsWith('=')).toBe(true);
    expect(Array.from(base64Decode(encoded))).toEqual(Array.from(original));
  });

  it('round-trips 64 bytes of pseudo-random data against Buffer', () => {
    const original = new Uint8Array(64);
    for (let i = 0; i < 64; i++) {
      original[i] = (i * 37 + 11) % 256;
    }
    const encoded = Buffer.from(original).toString('base64');
    expect(Array.from(base64Decode(encoded))).toEqual(Array.from(original));
  });
});
