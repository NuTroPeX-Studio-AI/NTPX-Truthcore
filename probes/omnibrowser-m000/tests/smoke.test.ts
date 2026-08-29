import test from 'node:test';
import assert from 'node:assert/strict';

type EnvironmentCheck = {
  name: string;
  version: number;
};

test('Node 24 executes erasable TypeScript syntax', () => {
  const check: EnvironmentCheck = {
    name: 'NTPX OmniBrowser M000',
    version: 1,
  };

  assert.equal(check.version, 1);
  assert.equal(check.name, 'NTPX OmniBrowser M000');
});
