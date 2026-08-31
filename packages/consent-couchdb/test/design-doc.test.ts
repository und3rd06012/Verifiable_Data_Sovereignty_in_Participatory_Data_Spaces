import { describe, it, expect } from 'vitest';
import { buildDesignDoc } from '../src/build-design-doc';

describe('buildDesignDoc', () => {
  it('produces a design doc with the expected id', () => {
    expect(buildDesignDoc()._id).toBe('_design/consent');
  });

  it('contains no syntax the CouchDB query server rejects', () => {
    const fn = buildDesignDoc().validate_doc_update;
    expect(fn).not.toMatch(/=>/);
    expect(fn).not.toMatch(/\blet\b/);
    expect(fn).not.toMatch(/\bconst\b/);
    expect(fn).not.toMatch(/`/);
    expect(fn).not.toMatch(/\bnew Map\b/);
    expect(fn).not.toMatch(/\bnew Set\b/);
    expect(fn).not.toMatch(/\{\s*\.\.\./);
  });

  it('evaluates as a function that forbids an unconsented write', () => {
    const fn = buildDesignDoc().validate_doc_update;
    // eslint-disable-next-line no-new-func
    const validate = new Function('return ' + fn)();
    const doc = {
      type: 'contribution',
      participantRef: 'p-001',
      siteId: 'athens',
      collectedAt: '2026-08-30T11:00:00.000Z',
      channel: 'app',
      declaredPurpose: 'service-improvement',
      declaredRecipientClass: 'statistics',
      fields: { 'health.condition': 'asthma' },
      consent: { participantRef: 'p-001', assertedAt: '2026-08-01T00:00:00.000Z', grants: [] }
    };
    // CouchDB's validate_doc_update contract requires throwing a plain object
    // with a `forbidden` key, not an Error. vitest's toThrowError(regex) only
    // matches Error.message, so a plain-object throw is inspected directly.
    let caught: any;
    try {
      validate(doc, null, { name: 'p-001', roles: [] }, {});
    } catch (e) {
      caught = e;
    }
    expect(caught).toBeDefined();
    expect(caught.forbidden).toMatch(/no-grant:health.condition/);
  });

  it('permits a fully consented write', () => {
    const fn = buildDesignDoc().validate_doc_update;
    // eslint-disable-next-line no-new-func
    const validate = new Function('return ' + fn)();
    const doc = {
      type: 'contribution',
      participantRef: 'p-001',
      siteId: 'athens',
      collectedAt: '2026-08-30T11:00:00.000Z',
      channel: 'app',
      declaredPurpose: 'service-improvement',
      declaredRecipientClass: 'statistics',
      fields: { 'service.satisfaction': 4 },
      consent: {
        participantRef: 'p-001',
        assertedAt: '2026-08-01T00:00:00.000Z',
        grants: [{
          field: 'service.satisfaction',
          purpose: 'service-improvement',
          recipientClass: 'statistics',
          grantedAt: '2026-08-01T00:00:00.000Z',
          expiresAt: null
        }]
      }
    };
    expect(() => validate(doc, null, { name: 'p-001', roles: [] }, {})).not.toThrow();
  });
});
