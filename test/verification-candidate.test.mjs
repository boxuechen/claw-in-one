import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {execFileSync} from 'node:child_process';
import test from 'node:test';
import {APP_PACKAGE, FIXTURE_PACKAGE, buildArtifact, fileHash, fingerprintFiles, pinnedVersions, readCandidate, sourceFiles} from '../scripts/verification/candidate.mjs';

function fixture(t) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'claw-candidate-'));
  t.after(() => fs.rmSync(root, {recursive: true, force: true}));
  const write = (file, text) => {
    const target = path.join(root, file);
    fs.mkdirSync(path.dirname(target), {recursive: true});
    fs.writeFileSync(target, text);
  };
  execFileSync('git', ['init', '-q', root]);
  write('apps/android/app/src/main/java/ai/openclaw/app/bootstrap/OpenClawRelease.kt', 'version = "2026.9.4"\nnodeVersion = "24.19.0"');
  write('product-plugins/android-use/runtime.mjs', 'export const v = 1;');
  write('docs/notes.md', 'not product code');
  write('.gitignore', '/candidate/\n');
  const directory = path.join(root, 'candidate');
  const candidate = {schema: 1, source: fingerprintFiles(root, sourceFiles(root)), pinned: pinnedVersions(root), artifacts: []};
  for (const [file, packageName, versionCode] of [['app.apk', APP_PACKAGE, 1], ['fixture-v1.apk', FIXTURE_PACKAGE, 1], ['fixture-v2.apk', FIXTURE_PACKAGE, 2]]) {
    write(`candidate/${file}`, file);
    candidate.artifacts.push({file, packageName, versionCode, sha256: fileHash(path.join(directory, file))});
  }
  const save = () => write('candidate/candidate.json', JSON.stringify(candidate));
  save();
  return {root, directory, candidate, write, save};
}

test('candidate reuses unchanged product sources and ignores docs; new source invalidates it', t => {
  const f = fixture(t);
  assert.equal(readCandidate(f.root, f.directory).schema, 1);
  f.write('docs/notes.md', 'updated');
  assert.equal(readCandidate(f.root, f.directory).schema, 1);
  f.write('product-plugins/android-use/new.mjs', 'new');
  assert.throws(() => readCandidate(f.root, f.directory), /source changed/);
});

test('modified APK, wrong fixture version, and missing artifact never pass', t => {
  const f = fixture(t);
  f.write('candidate/app.apk', 'different');
  assert.throws(() => readCandidate(f.root, f.directory), /artifact changed/);
  f.write('candidate/app.apk', 'app.apk');
  f.candidate.artifacts[2].versionCode = 1;
  f.save();
  assert.throws(() => readCandidate(f.root, f.directory), /Unexpected candidate artifact/);
  f.candidate.artifacts.pop();
  f.save();
  assert.throws(() => readCandidate(f.root, f.directory), /Incomplete candidate/);
});

test('candidate inventory cannot differ from the checked source fingerprint', t => {
  const f = fixture(t);
  f.candidate.source.files['product-plugins/android-use/runtime.mjs'] = 'changed';
  f.save();
  assert.throws(() => readCandidate(f.root, f.directory), /source changed/);
});

test('source fingerprints reject traversal and symlink files', t => {
  const f = fixture(t);
  assert.throws(() => fingerprintFiles(f.root, ['../other']), /Invalid candidate source path/);
  fs.symlinkSync(path.join(f.directory, 'app.apk'), path.join(f.root, 'product-plugins/link'));
  assert.throws(() => fingerprintFiles(f.root, ['product-plugins/link']), /regular source file/);
});

test('build metadata resolves renamed APK and rejects wrong version or multiple outputs', t => {
  const f = fixture(t);
  const metadata = {applicationId: FIXTURE_PACKAGE, elements: [{type: 'SINGLE', outputFile: 'fixture-v2.apk', versionCode: 2, versionName: '2.0'}]};
  const save = () => f.write('candidate/output-metadata.json', JSON.stringify(metadata));
  save();
  assert.equal(buildArtifact(f.directory, FIXTURE_PACKAGE, 2).file, path.join(f.directory, 'fixture-v2.apk'));
  assert.throws(() => buildArtifact(f.directory, FIXTURE_PACKAGE, 1), /Unexpected build metadata/);
  metadata.elements.push(metadata.elements[0]);
  save();
  assert.throws(() => buildArtifact(f.directory, FIXTURE_PACKAGE, 2), /Unexpected build metadata/);
});
