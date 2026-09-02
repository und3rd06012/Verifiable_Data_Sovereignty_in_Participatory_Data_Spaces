// Neither function may use TextEncoder, Buffer, or atob: both get bundled directly
// into the CouchDB query server (Task 3), and deploy/couchdb-engine-probe.md's
// verified-supported construct list does not include any of the three.

export function utf8Encode(input: string): Uint8Array {
  var bytes: number[] = [];
  for (var i = 0; i < input.length; i++) {
    var code = input.charCodeAt(i);
    if (code >= 0xd800 && code <= 0xdbff && i + 1 < input.length) {
      var next = input.charCodeAt(i + 1);
      if (next >= 0xdc00 && next <= 0xdfff) {
        code = 0x10000 + ((code - 0xd800) << 10) + (next - 0xdc00);
        i++;
      }
    }
    if (code < 0x80) {
      bytes.push(code);
    } else if (code < 0x800) {
      bytes.push(0xc0 | (code >> 6), 0x80 | (code & 0x3f));
    } else if (code < 0x10000) {
      bytes.push(0xe0 | (code >> 12), 0x80 | ((code >> 6) & 0x3f), 0x80 | (code & 0x3f));
    } else {
      bytes.push(
        0xf0 | (code >> 18),
        0x80 | ((code >> 12) & 0x3f),
        0x80 | ((code >> 6) & 0x3f),
        0x80 | (code & 0x3f)
      );
    }
  }
  return new Uint8Array(bytes);
}

var BASE64_CHARS = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';

export function base64Decode(input: string): Uint8Array {
  var cleaned = input.replace(/=+$/, '');
  var bytes: number[] = [];
  var buffer = 0;
  var bitsCollected = 0;
  for (var i = 0; i < cleaned.length; i++) {
    var value = BASE64_CHARS.indexOf(cleaned.charAt(i));
    if (value === -1) {
      continue;
    }
    buffer = (buffer << 6) | value;
    bitsCollected += 6;
    if (bitsCollected >= 8) {
      bitsCollected -= 8;
      bytes.push((buffer >> bitsCollected) & 0xff);
    }
  }
  return new Uint8Array(bytes);
}
