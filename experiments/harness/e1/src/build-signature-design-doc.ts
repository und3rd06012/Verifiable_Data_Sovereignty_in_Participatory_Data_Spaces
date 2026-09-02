import { buildSync } from 'esbuild';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));

export interface DesignDoc {
  _id: string;
  validate_doc_update: string;
}

export function buildSignatureDesignDoc(): DesignDoc {
  const entry = join(here, 'signature-validator-payload.ts');

  const result = buildSync({
    entryPoints: [entry],
    bundle: true,
    write: false,
    format: 'iife',
    globalName: 'SignaturePayload',
    // es2020, not es5: this bundle uses @noble/ed25519, which uses BigInt
    // arithmetic that esbuild cannot lower to es5. deploy/couchdb-engine-probe.md
    // confirmed BigInt is supported directly by the live query server, so es5 is
    // not required here. The production packages/consent-couchdb bundle is
    // unaffected and stays at es5.
    target: 'es2020',
    minify: false,
    legalComments: 'none'
  });

  const rawBundle = result.outputFiles[0].text;

  // @noble/hashes' HashMD.update() (esm/_md.js) unconditionally calls the
  // shared toBytes() helper, which branches on typeof to also accept string
  // input via utf8ToBytes(), whose body contains `new TextEncoder()`. That
  // branch is provably dead in this bundle: @noble/ed25519 only ever calls
  // the injected hash with the Uint8Array output of its own concatBytes(),
  // and this module only ever calls it with utf8Encode()'s Uint8Array output
  // -- never a string. Tree-shaking cannot remove it (it's a reachable
  // runtime branch, not an unreferenced declaration), so it survives into
  // the bundle text verbatim and would trip the "no TextEncoder" constraint
  // that deploy/couchdb-engine-probe.md requires. Replacing the dead branch
  // with a throw makes that true structurally as well as behaviorally.
  const textEncoderBranch = 'return new Uint8Array(new TextEncoder().encode(str));';
  const occurrences = rawBundle.split(textEncoderBranch).length - 1;
  if (occurrences !== 1) {
    throw new Error(
      'expected exactly one dead TextEncoder branch in the @noble/hashes bundle, found ' + occurrences
    );
  }
  const bundle = rawBundle.replace(
    textEncoderBranch,
    'throw new Error("unreachable: string input to utf8ToBytes was stripped from the CouchDB bundle");'
  );

  const validate = [
    'function(newDoc, oldDoc, userCtx, secObj) {',
    '  var isAdmin = (userCtx.roles || []).indexOf("_admin") !== -1;',
    '  if (newDoc._deleted) {',
    '    if (!isAdmin && oldDoc && userCtx.name !== oldDoc.participantRef) {',
    '      throw({ forbidden: "participant-identity-mismatch" });',
    '    }',
    '    return;',
    '  }',
    '  if (!isAdmin && userCtx.name !== newDoc.participantRef) {',
    '    throw({ forbidden: "participant-identity-mismatch" });',
    '  }',
    '  ' + bundle.split('\n').join('\n  '),
    '  var decision = SignaturePayload.evaluate(newDoc, new Date().toISOString());',
    '  if (!decision.allowed) {',
    '    throw({ forbidden: decision.reason });',
    '  }',
    '  if (newDoc.type === "contribution") {',
    '    var sigOk = SignaturePayload.verifySignature(newDoc, newDoc.signature, newDoc.devicePublicKey);',
    '    if (!sigOk) {',
    '      throw({ forbidden: "invalid-signature" });',
    '    }',
    '  }',
    '}'
  ].join('\n');

  return { _id: '_design/consent-signature', validate_doc_update: validate };
}
