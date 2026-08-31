# CouchDB query server capability probe

Recorded on 2026-08-30, against the digest pinned in
`deploy/docker-compose.yml`.

- CouchDB version reported by `GET /`: 3.5.2
- Image digest: sha256:9ea24cbd76522fe845d1c32c7fd1dcfc8a3ba73dcc4817d62f8a7f7f1dfaffe3
- Raw capability string as returned by the original probe, verbatim, unedited: CAPS:typedarray,map,bigint

## Correction: the arrow result above is a false negative

The original probe tested arrow function support by running
`eval("var f = () => 1;")` inside `validate_doc_update` and appending
`"arrow"` to the capability list only if that `eval` call succeeded. It
did not succeed, so `arrow` is missing from the raw CAPS line above. That
absence is not evidence the engine lacks arrow functions. CouchDB 3.5.2's
query server blocks both `eval` and `new Function()` outright, reporting
`EvalError: call to Function() blocked by CSP`. The probe's method
conflated "arrow syntax is unsupported" with "dynamic code generation is
blocked," and only the second is true. Arrow functions themselves work
when written directly in the validation function source, outside of
`eval`.

The raw CAPS line is preserved above exactly as the server reported it,
and is not to be read as a statement that the engine lacks ES2015 arrow
function support.

## Verified capability matrix

Method: one construct tested per design document (rather than the
original single combined probe), each committed directly in
`validate_doc_update` source rather than through `eval`, with a
`compilation_error` response from CouchDB treated as unsupported for that
construct.

- Supported: arrow functions, `const`, `let`, template literals, array
  destructuring, `for-of`, `Map`, `Set`, `Uint8Array`, `BigInt`,
  `Object.assign`, `Array.prototype.includes`, `String.prototype.repeat`,
  `JSON`, `Date.prototype.toISOString`
- Not supported: object spread (`{...o}`). CouchDB's own source parser
  rejects this before the query server engine ever sees it, reporting a
  parse error (`Line 1: Unexpected token ...`) attributed to
  `./share/server/main.js`.
- Blocked by CSP (not a language-support question): `eval`,
  `new Function()`

## What this decides

`typedarray` is present in the raw probe output and confirmed by the
verified matrix. The signature verifying arm of experiment E1 is
feasible: it is not ruled out, and E1 can measure the cost of pure
JavaScript Ed25519 verification per document inside
`validate_doc_update`.

The query server is a modern JavaScript engine, not classic SpiderMonkey.
Arrow functions, `const`/`let`, template literals, destructuring,
`for-of`, `Map`, `Set`, and `BigInt` are all supported directly in
validation function source. This means the project's ES5 build
constraint on code bundled into `validate_doc_update` is precautionary
rather than load bearing on the grounds originally recorded. It stays in
force anyway, for a narrower and still real reason: an ES5 build target
provably cannot emit object spread, which is the one construct that
actually fails on this engine (rejected by CouchDB's own source parser,
not the JavaScript engine). Object spread is therefore the single real
syntax prohibition for anything bundled into `validate_doc_update`.

No dynamic code generation is possible inside the validator under any
circumstance: `eval` and `new Function` are blocked by CSP regardless of
what syntax or language level is used to reach them.
