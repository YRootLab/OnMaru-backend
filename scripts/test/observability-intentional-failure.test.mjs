import test from 'node:test';
import assert from 'node:assert/strict';

test('intentional CI failure probe for issue 555', () => {
  assert.fail('intentional failure: verify CI observability failure round trip');
});
