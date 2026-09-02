import { runChainVerifyTime } from './verify-chain-time.js';

runChainVerifyTime({
  seed: 1,
  fixturesPath: '../e3/fixtures/chain-fixtures.json',
  csvPath: '../../results/e3/verify-chain-time.csv',
  throttleCpus: process.env.E3_THROTTLE_CPUS ?? '',
  throttleMemoryMb: process.env.E3_THROTTLE_MEMORY_MB ?? ''
}).then(() => {
  console.log('chain verify-time run complete');
}).catch((err) => {
  console.error(err);
  process.exitCode = 1;
});
