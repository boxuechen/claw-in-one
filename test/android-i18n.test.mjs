import assert from 'node:assert/strict';
import test from 'node:test';

import {collectNativeSources} from '../scripts/android-i18n.mjs';

test('collectNativeSources finds direct and conditional resource sources', () => {
  assert.deepEqual(
    [...collectNativeSources(`
      nativeString("Settings")
      nativeText(if (ready) "Ready" else "Needs attention")
      nativeString("Step \\$phase of \\$count", phase, count)
      nativeString(dynamic)
    `)].sort(),
    ['Needs attention', 'Ready', 'Settings', 'Step $phase of $count'],
  );
});
