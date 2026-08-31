import { buildSync } from 'esbuild';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));

export interface DesignDoc {
  _id: string;
  validate_doc_update: string;
}

export function buildDesignDoc(): DesignDoc {
  const entry = join(here, '../../consent-core/src/evaluate.ts');

  const result = buildSync({
    entryPoints: [entry],
    bundle: true,
    write: false,
    format: 'iife',
    globalName: 'ConsentCore',
    target: 'es5',
    minify: false,
    legalComments: 'none'
  });

  const bundle = result.outputFiles[0].text;

  const validate = [
    'function(newDoc, oldDoc, userCtx, secObj) {',
    '  ' + bundle.split('\n').join('\n  '),
    '  if (newDoc._deleted) { return; }',
    '  var decision = ConsentCore.evaluateRecord(newDoc, new Date().toISOString());',
    '  if (!decision.allowed) {',
    '    throw({ forbidden: decision.reason });',
    '  }',
    '}'
  ].join('\n');

  return { _id: '_design/consent', validate_doc_update: validate };
}
