import { runInclusionVerifyTime } from './verify-inclusion-time.js';

runInclusionVerifyTime({
  seed: 1,
  fixturesPath: '../e3/fixtures/inclusion-fixtures.json',
  csvPath: '../../results/e3/verify-inclusion-time.csv',
  throttleCpus: process.env.E3_THROTTLE_CPUS ?? '',
  throttleMemoryMb: process.env.E3_THROTTLE_MEMORY_MB ?? ''
}).then(() => {
  console.log('inclusion verify-time run complete');
}).catch((err) => {
  console.error(err);
  process.exitCode = 1;
});
