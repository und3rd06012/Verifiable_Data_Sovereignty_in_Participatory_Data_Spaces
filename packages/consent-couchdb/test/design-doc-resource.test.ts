import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { buildDesignDoc } from '../src/build-design-doc';

const here = dirname(fileURLToPath(import.meta.url));
const resourcePath = join(here, '../../../connector/extensions/benefits-privacy/src/main/resources/consent-design-doc.json');

describe('consent-design-doc.json (checked-in export for the Java connector)', () => {
  it('matches buildDesignDoc()\'s current output exactly', () => {
    const checkedIn = JSON.parse(readFileSync(resourcePath, 'utf-8'));
    const fresh = buildDesignDoc();
    expect(checkedIn).toEqual(fresh);
  });
});
