import { describe, it, expect } from 'vitest';
import { buildSignatureDesignDoc } from '../src/build-signature-design-doc';
import { generateSyntheticRecord } from '../src/synthetic-records';
import { base64Decode } from '../src/portable-codec';

describe('buildSignatureDesignDoc', () => {
  it('produces a design doc with a distinct id from the production structural doc', () => {
    expect(buildSignatureDesignDoc()._id).toBe('_design/consent-signature');
  });

  it('contains no syntax the CouchDB query server rejects (object spread only, per the probe)', () => {
    const fn = buildSignatureDesignDoc().validate_doc_update;
    expect(fn).not.toMatch(/\{\s*\.\.\./);
  });

  it('does not reference TextEncoder, Buffer, or atob', () => {
    const fn = buildSignatureDesignDoc().validate_doc_update;
    expect(fn).not.toMatch(/TextEncoder/);
    expect(fn).not.toMatch(/\bBuffer\b/);
    expect(fn).not.toMatch(/\batob\b/);
  });

  it('accepts a fully consented, correctly signed record', () => {
    const fn = buildSignatureDesignDoc().validate_doc_update;
    // eslint-disable-next-line no-new-func
    const validate = new Function('return ' + fn)();
    const record = generateSyntheticRecord({ seed: 1, index: 0, fieldCount: 2, decoyCount: 2 });
    expect(() => validate(record, null, { name: record.participantRef, roles: [] }, {})).not.toThrow();
  });

  it('rejects a record with a tampered signature', () => {
    const fn = buildSignatureDesignDoc().validate_doc_update;
    // eslint-disable-next-line no-new-func
    const validate = new Function('return ' + fn)();
    const record = generateSyntheticRecord({ seed: 1, index: 0, fieldCount: 2, decoyCount: 2 });
    const tamperedSignatureBytes = base64Decode(record.signature);
    tamperedSignatureBytes[0] = tamperedSignatureBytes[0] ^ 0xff;
    const tampered = { ...record, signature: Buffer.from(tamperedSignatureBytes).toString('base64') };
    let caught: any;
    try {
      validate(tampered, null, { name: tampered.participantRef, roles: [] }, {});
    } catch (e) {
      caught = e;
    }
    expect(caught).toBeDefined();
    expect(caught.forbidden).toBe('invalid-signature');
  });

  it('still rejects an unconsented field even with a valid signature', () => {
    const fn = buildSignatureDesignDoc().validate_doc_update;
    // eslint-disable-next-line no-new-func
    const validate = new Function('return ' + fn)();
    const record = generateSyntheticRecord({ seed: 1, index: 0, fieldCount: 2, decoyCount: 2 });
    const unconsented = { ...record, consent: { ...record.consent, grants: [] } };
    // Note: mutating consent invalidates the original signature too, but the
    // structural check (evaluate) runs before the signature check in the
    // template, so this must fail with the structural reason, not
    // invalid-signature.
    let caught: any;
    try {
      validate(unconsented, null, { name: unconsented.participantRef, roles: [] }, {});
    } catch (e) {
      caught = e;
    }
    expect(caught).toBeDefined();
    expect(caught.forbidden).toMatch(/^no-grant:/);
  });
});
