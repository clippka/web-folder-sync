import { Checks, freshScratch, bytesEqual, readBytes, writeBytes } from '../lib/util.js';

export default {
  id: 'capabilities',
  title: 'Capability detection and real read/write check',
  needsRoot: true,
  spec: '§2 mandatory feature detection + actual read/write capability check',
  async run(ctx) {
    const c = new Checks();
    const w = window;
    c.check('secure context', w.isSecureContext);
    c.check('showDirectoryPicker', typeof w.showDirectoryPicker === 'function');
    c.check('FileSystemHandle.queryPermission', typeof FileSystemHandle?.prototype?.queryPermission === 'function');
    c.check('FileSystemHandle.requestPermission', typeof FileSystemHandle?.prototype?.requestPermission === 'function');
    c.check('FileSystemHandle.isSameEntry', typeof FileSystemHandle?.prototype?.isSameEntry === 'function');
    c.check('FileSystemFileHandle.createWritable', typeof FileSystemFileHandle?.prototype?.createWritable === 'function');
    c.check('FileSystemDirectoryHandle.resolve', typeof FileSystemDirectoryHandle?.prototype?.resolve === 'function');
    c.check('FileSystemDirectoryHandle.entries (async iteration)', typeof FileSystemDirectoryHandle?.prototype?.entries === 'function');
    c.check('navigator.locks (Web Locks)', !!navigator.locks);
    c.check('IndexedDB', !!w.indexedDB);
    c.check('module Worker', typeof Worker === 'function');
    c.check('crypto.subtle', !!crypto.subtle);
    c.check('BroadcastChannel', typeof BroadcastChannel === 'function');
    c.note('performance.memory (JS heap sampling)', !!performance.memory);
    c.note('FileSystemObserver (not required)', typeof w.FileSystemObserver === 'function');
    c.note('document.onfreeze (Page Lifecycle)', 'onfreeze' in document);
    try { c.note('storage.persisted()', await navigator.storage.persisted()); } catch (e) { c.note('storage.persisted()', e.message); }

    if (ctx.rootKind === 'picked') {
      c.note('root permission (read)', await ctx.root.queryPermission({ mode: 'read' }));
      c.note('root permission (readwrite)', await ctx.root.queryPermission({ mode: 'readwrite' }));
    }

    // Real capability check: round-trip exact bytes including CRLF, NUL, and high bytes.
    const dir = await freshScratch(ctx.root);
    const data = new Uint8Array([0x0d, 0x0a, 0, 0xff, 0x80, 0x41, 0x0a, 0x0d]);
    await writeBytes(dir, 'rw-check.bin', data);
    const back = await readBytes(dir, 'rw-check.bin');
    c.check('write then read returns identical bytes', bytesEqual(data, back));
    await dir.removeEntry('rw-check.bin');
    c.check('removeEntry on a file', !(await dir.getFileHandle('rw-check.bin').then(() => true, () => false)));
    return { checks: c.items, status: c.status };
  },
};
