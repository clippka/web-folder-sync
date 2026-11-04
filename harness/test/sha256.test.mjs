import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash, randomBytes } from 'node:crypto';
import { Sha256, sha256Hex } from '../public/lib/sha256.js';

const ref = (b) => createHash('sha256').update(b).digest('hex');

test('known vectors', () => {
  assert.equal(sha256Hex(new Uint8Array(0)), 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855');
  assert.equal(sha256Hex(new TextEncoder().encode('abc')), 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad');
  assert.equal(sha256Hex(new Uint8Array(1_000_000).fill(0x61)), 'cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0');
});

test('every length around block and padding boundaries', () => {
  for (let n = 0; n <= 200; n++) {
    const b = randomBytes(n);
    assert.equal(sha256Hex(b), ref(b), `length ${n}`);
  }
});

test('arbitrary chunk splits match one-shot hashing', () => {
  const b = randomBytes(100_003);
  for (const step of [1, 3, 55, 56, 63, 64, 65, 1000, 65536]) {
    const h = new Sha256();
    for (let i = 0; i < b.length; i += step) h.update(b.subarray(i, i + step));
    assert.equal(h.digestHex(), ref(b), `step ${step}`);
  }
});

test('length above 2^32 bits is encoded correctly', () => {
  // 600 MiB of zeros streamed in 1 MiB chunks: total bits exceed 2^32.
  const zero = new Uint8Array(1 << 20);
  const h = new Sha256();
  const r = createHash('sha256');
  for (let i = 0; i < 600; i++) { h.update(zero); r.update(zero); }
  assert.equal(h.digestHex(), r.digest('hex'));
});
