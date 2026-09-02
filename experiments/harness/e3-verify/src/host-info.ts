import { readFileSync } from 'node:fs';
import { cpus } from 'node:os';

export function readHostCpuModel(): string {
  try {
    const cpuinfo = readFileSync('/proc/cpuinfo', 'utf8');
    const match = cpuinfo.match(/^model name\s*:\s*(.+)$/m);
    if (match) {
      return match[1].trim();
    }
  } catch (e) {
    // /proc/cpuinfo not present on this platform; fall through to os.cpus().
  }
  const list = cpus();
  return list.length > 0 ? list[0].model : 'unknown';
}
