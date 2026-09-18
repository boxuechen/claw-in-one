import assert from 'node:assert/strict';
import {test} from 'node:test';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {checkLinks, headingIds} from '../scripts/check-docs.mjs';

test('heading punctuation, Unicode, duplicates and fenced examples', () => {
  assert.deepEqual(headingIds('# A5.5 — Workflow\n## 中文\n## Same\n## Same\n```md\n# Example\n```\n'), ['a55--workflow', '中文', 'same', 'same-1']);
});
test('local files and anchors are checked, external links are not fetched', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'claw-docs-'));
  try {
    fs.writeFileSync(path.join(root, 'index.md'), '[ok](other.md#title) [missing](absent.md) [bad](other.md#absent) [external](https://example.invalid)\n```\n[x](not-a-link.md)\n```');
    fs.writeFileSync(path.join(root, 'other.md'), '# Title\n');
    const result = checkLinks(root, ['index.md']);
    assert.equal(result.checked, 1);
    assert.equal(result.errors.length, 2);
    assert.match(result.errors[0], /missing file/);
    assert.match(result.errors[1], /missing heading/);
  } finally { fs.rmSync(root, {recursive: true, force: true}); }
});
