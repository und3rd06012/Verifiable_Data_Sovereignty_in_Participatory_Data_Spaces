// Parameterized rather than hard-coded (spec section 3): phase 7's sweep
// varies onlineProbability against connectivity duty cycles matched to
// the target population without touching this class. The rng is
// injectable so tests (and phase 7's fixed-seed reproducibility
// requirement) are deterministic, the parent spec requires every
// experiment to run from fixed seeds.
export class DutyCycle {
  private online = false;

  constructor(private readonly onlineProbability: number, private readonly rng: () => number = Math.random) {
    if (onlineProbability < 0 || onlineProbability > 1) {
      throw new Error(`onlineProbability must be in [0, 1], got ${onlineProbability}`);
    }
  }

  tick(): void {
    this.online = this.rng() < this.onlineProbability;
  }

  isOnline(): boolean {
    return this.online;
  }
}
