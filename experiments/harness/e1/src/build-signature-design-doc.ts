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
    // arithmetic that esbuild cannot lower to es5 (and, per below, cannot
    // lower to anything below es2020 at all -- esbuild hard-fails the build
    // on BigInt literals under an older target rather than leaving them for
    // post-processing). CouchDB 3.5.2's real query server (confirmed by
    // Task 4's live spike against a live instance, not just Node) accepts
    // the `BigInt(...)` constructor call form and evaluates it correctly,
    // but rejects `n`-suffix BigInt literal syntax (e.g. `123n`) at parse
    // time -- so the bundle must build at target es2020 to get literal
    // syntax out of esbuild at all, then have that literal syntax rewritten
    // to constructor calls below. The production packages/consent-couchdb
    // bundle is unaffected and stays at es5.
    target: 'es2020',
    // The same real query server also rejects the ES2020 nullish-coalescing
    // (`??`) and optional-chaining (`?.`) operators at parse time with the
    // same class of compilation_error, even though target es2020 natively
    // supports (and so does not lower) either. Forcing `supported` to false
    // for just these two features -- independent of `target`, which must
    // stay es2020 for BigInt literals to build at all -- makes esbuild
    // downlevel only `??`/`?.` to compatible ternary/temp-variable code,
    // leaving BigInt literals untouched for the post-processing below.
    // Lowering `target` itself instead was tried and hard-fails the build
    // (esbuild refuses to emit BigInt literals under an older target).
    supported: {
      'nullish-coalescing': false,
      'optional-chain': false
    },
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
  const withTextEncoderFixed = rawBundle.replace(
    textEncoderBranch,
    'throw new Error("unreachable: string input to utf8ToBytes was stripped from the CouchDB bundle");'
  );

  // CouchDB 3.5.2's real query server rejects BigInt `n`-suffix literal
  // syntax (e.g. `123n`) at compile time with a compilation_error, even
  // though it accepts and correctly evaluates the `BigInt(...)` constructor
  // call form (confirmed by direct probing against a live instance -- see
  // deploy/couchdb-engine-probe.md and Task 4's live spike). Node's engine,
  // which this file's own vitest suite runs against via `new Function`,
  // accepts the literal syntax fine, so that suite alone cannot catch this;
  // only a real query server does. @noble/ed25519's source uses `n`-suffix
  // literals pervasively for curve constants, so every one of them must be
  // rewritten to the constructor-call form before this bundle can compile on
  // the real engine. The replacement passes the literal's digits as a
  // string, not a bare number, since a bare numeric argument above 2^53
  // would silently lose precision and corrupt curve constants.
  const bigIntLiteralPattern = /\b(0[xX][0-9a-fA-F]+|0[bB][01]+|0[oO][0-7]+|\d+)n\b/g;
  const bundle = withTextEncoderFixed.replace(bigIntLiteralPattern, 'BigInt("$1")');

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
