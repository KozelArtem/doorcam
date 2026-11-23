import test from 'node:test';
import assert from 'node:assert/strict';
import * as peer from '../public/peer.js';

test('ordered frames reproduce the exact declared file', async () => {
  const file = new peer.FileBody(4, 'video/mp4');
  file.append(Uint8Array.of(1, 2, 3).buffer);
  file.append(Uint8Array.of(4).buffer);
  const result = file.finish();
  assert.equal(result.type, 'video/mp4');
  assert.deepEqual([...new Uint8Array(await result.arrayBuffer())], [1, 2, 3, 4]);
});

test('rejects a truncated, excess, or oversized transfer', () => {
  assert.throws(() => new peer.FileBody(2 ** 30, 'video/mp4'));
  const file = new peer.FileBody(2, 'video/mp4');
  file.append(Uint8Array.of(1).buffer);
  assert.throws(() => file.finish());
  assert.throws(() => file.append(Uint8Array.of(2, 3).buffer));
});
