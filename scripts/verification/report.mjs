import fs from 'node:fs';
import {randomUUID} from 'node:crypto';

// One observer owns the report. Readers see the previous or next complete JSON,
// never the writer's truncation window. Publication stays on the same filesystem.
export function publishReport(output, report) {
  const json = JSON.stringify(report);
  const temporary = `${output}.${randomUUID()}.tmp`;
  fs.writeFileSync(temporary, json, {flag: 'wx', mode: 0o600});
  try {
    fs.renameSync(temporary, output);
  } finally {
    try { fs.unlinkSync(temporary); } catch (error) { if (error.code !== 'ENOENT') throw error; }
  }
}
