import { runTrial } from './trial.js';
import { median, iqr } from './statistics.js';

export interface SweepPointResult {
  seed: number;
  onlineProbability: number;
  trials: number;
  propagationMedianTicks: number;
  propagationIqrTicks: number;
  propagationDataPoints: number;
  consistencyMedianTicks: number;
  consistencyIqrTicks: number;
  consistencyDataPoints: number;
  leakedRecordCount: number;
  unresolvedConflictCount: number;
}

export async function runSweepPoint(
  seed: number,
  onlineProbability: number,
  onlineProbabilityIndex: number,
  trials: number,
  remoteUrl: string
): Promise<SweepPointResult> {
  const propagationTicks: number[] = [];
  const consistencyTicks: number[] = [];
  let leakedRecordCount = 0;
  let unresolvedConflictCount = 0;

  for (let trialIndex = 0; trialIndex < trials; trialIndex++) {
    const result = await runTrial(seed, onlineProbability, onlineProbabilityIndex, trialIndex, remoteUrl);
    propagationTicks.push(...result.propagationTicks);
    consistencyTicks.push(...result.consistencyTicks);
    leakedRecordCount += result.leakedRecordCount;
    unresolvedConflictCount += result.unresolvedConflictCount;
  }

  return {
    seed,
    onlineProbability,
    trials,
    propagationMedianTicks: median(propagationTicks),
    propagationIqrTicks: iqr(propagationTicks),
    propagationDataPoints: propagationTicks.length,
    consistencyMedianTicks: median(consistencyTicks),
    consistencyIqrTicks: iqr(consistencyTicks),
    consistencyDataPoints: consistencyTicks.length,
    leakedRecordCount,
    unresolvedConflictCount
  };
}
