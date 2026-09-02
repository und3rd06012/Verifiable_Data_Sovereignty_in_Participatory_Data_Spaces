import { writeFileSync, mkdirSync } from 'node:fs';
import { dirname } from 'node:path';

function csvField(value: string | number): string {
  const str = String(value);
  if (str.includes(',') || str.includes('"') || str.includes('\n')) {
    return '"' + str.replace(/"/g, '""') + '"';
  }
  return str;
}

export function writeCsv(path: string, header: string[], rows: (string | number)[][]): void {
  mkdirSync(dirname(path), { recursive: true });
  const lines = [header.join(',')];
  for (const row of rows) {
    lines.push(row.map(csvField).join(','));
  }
  writeFileSync(path, lines.join('\n') + '\n');
}
