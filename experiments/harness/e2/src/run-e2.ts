import { runSweep } from './sweep.js';

runSweep({
  dbName: 'e2-sweep',
  seed: 1,
  onlineProbabilities: [0.05, 0.1, 0.3, 0.5, 0.9],
  trials: 30,
  outDir: '../../results/e2',
  throttleCpus: 1,
  throttleMemoryMb: 1024
}).then(() => {
  console.log('E2 sweep complete');
}).catch((err) => {
  console.error(err);
  process.exitCode = 1;
});
