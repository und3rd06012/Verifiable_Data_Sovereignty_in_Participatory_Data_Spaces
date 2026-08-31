# CouchDB query server capability probe

Recorded on 2026-08-30, against the digest pinned in
`deploy/docker-compose.yml`.

- CouchDB version reported by `GET /`: 3.5.2
- Image digest: sha256:9ea24cbd76522fe845d1c32c7fd1dcfc8a3ba73dcc4817d62f8a7f7f1dfaffe3
- Capabilities reported by the probe: CAPS:typedarray,map,bigint

## What this decides

If `typedarray` is absent, the signature verifying arm of experiment E1
cannot run inside `validate_doc_update` at all, and E1 reports that as its
result rather than as a failure. If `typedarray` is present, E1 measures
the cost of pure JavaScript Ed25519 verification per document.

If `arrow` and `map` are absent, the query server is classic SpiderMonkey
and the ES5 constraint in the plan's global constraints is load bearing
rather than precautionary.

`typedarray`, `map`, and `bigint` were reported; `arrow` was not. The
signature verifying arm of E1 can run: `typedarray` is present. The query
server does not support ES6 arrow functions, so the ES5 constraint on
`validate_doc_update` code is load bearing, not precautionary. Note this
result is a mixed reading against the two decision rules stated above:
`map` is present alongside a missing `arrow`, which the brief's second
rule does not anticipate as a combination.
