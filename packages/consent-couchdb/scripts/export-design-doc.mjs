import { writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { buildDesignDoc } from '../dist/build-design-doc.js';

const here = dirname(fileURLToPath(import.meta.url));
const outPath = join(here, '../../../connector/extensions/benefits-privacy/src/main/resources/consent-design-doc.json');

const designDoc = buildDesignDoc();
writeFileSync(outPath, JSON.stringify(designDoc, null, 2) + '\n');
console.log(`wrote ${outPath}`);
