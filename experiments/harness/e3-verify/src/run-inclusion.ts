import { runInclusionVerifyTime } from './verify-inclusion-time.js';

runInclusionVerifyTime({
  seed: 1,
  fixturesPath: '../e3/fixtures/inclusion-fixtures.json',
  csvPath: '../../results/e3/verify-inclusion-time.csv',
  throttleCpus: 1,
  throttleMemoryMb: 1024
}).then(() => {
  console.log('inclusion verify-time run complete');
}).catch((err) => {
  console.error(err);
  process.exitCode = 1;
});
