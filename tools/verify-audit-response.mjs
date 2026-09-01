import { leafHash, verifyInclusion, verifyChain } from '../packages/audit-verify/dist/index.js';

const url = process.argv[2];
const participantRef = process.argv[3];

const response = await fetch(`${url}/${participantRef}`);
if (!response.ok) {
  console.error(`audit endpoint returned ${response.status}`);
  process.exit(1);
}
const body = await response.json();

if (body.entries.length === 0) {
  console.error('expected at least one entry for participant ' + participantRef);
  process.exit(1);
}

const chainOk = await verifyChain(body.epochs);
if (!chainOk) {
  console.error('epoch chain did not verify');
  process.exit(1);
}

let sealedCount = 0;
for (const entry of body.entries) {
  if (entry.epoch === null) {
    continue;
  }
  sealedCount++;
  const proof = body.proofs[entry.seq];
  const epoch = body.epochs.find((e) => e.epochId === entry.epoch);
  if (!epoch) {
    console.error(`no epoch found for entry seq=${entry.seq} epoch=${entry.epoch}`);
    process.exit(1);
  }
  const leaf = await leafHash(entry);
  const ok = await verifyInclusion(leaf, proof, epoch.merkleRoot);
  if (!ok) {
    console.error(`inclusion proof failed for entry seq=${entry.seq}`);
    process.exit(1);
  }
}

if (sealedCount !== 2) {
  console.error(`expected exactly 2 sealed entries, got ${sealedCount}`);
  process.exit(1);
}

console.log(`verified ${sealedCount} sealed entr${sealedCount === 1 ? 'y' : 'ies'} across ${body.epochs.length} epoch(s)`);
