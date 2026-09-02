import { runSweep } from './sweep';

const SEED = 1;
const BATCH_SIZES = [10, 50, 100, 500, 1000, 5000, 10000];
const FIELD_COUNTS = [1, 5, 20];
const GRANT_SET_SIZES = [1, 10, 50];
const TRIALS = 3;

runSweep({
  baseUrl: 'http://localhost:5986',
  adminUser: 'admin',
  adminPassword: 'benefits',
  seed: SEED,
  batchSizes: BATCH_SIZES,
  fieldCounts: FIELD_COUNTS,
  grantSetSizes: GRANT_SET_SIZES,
  trials: TRIALS,
  outDir: '../../results/e1',
  throttleCpus: 1,
  throttleMemoryMb: 1024
}).then(() => {
  console.log('E1 sweep complete');
}).catch((err) => {
  console.error(err);
  process.exitCode = 1;
});
