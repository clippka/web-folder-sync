import { Checks, attempt, freshScratch, writeBytes, readBytes, bytesEqual } from '../lib/util.js';
import { idbGet, idbPut } from '../lib/idb.js';

export const STORED_KEY = 'binding:harness';

export const handlePersistence = {
  id: 'handle-persist',
  title: 'Store the root handle in IndexedDB; identity and overlap checks',
  needsRoot: true,
  spec: '§2 persist handles; §3 overlap detection; §4 folder identity',
  async run(ctx) {
    const c = new Checks();
    await idbPut('handles', STORED_KEY, { handle: ctx.root, name: ctx.root.name, storedAt: new Date().toISOString() });
    const back = await idbGet('handles', STORED_KEY);
    c.check('handle structured-clones into IndexedDB and back', back?.handle?.kind === 'directory');
    c.check('stored handle isSameEntry(original)', await back.handle.isSameEntry(ctx.root));
    c.note('permission on stored handle (readwrite)', await back.handle.queryPermission({ mode: 'readwrite' }));

    const scratch = await freshScratch(ctx.root);
    const sub = await scratch.getDirectoryHandle('child', { create: true });
    const viaPath = await ctx.root.getDirectoryHandle(scratch.name).then((d) => d.getDirectoryHandle('child'));
    c.check('two independently obtained handles for one directory are isSameEntry', await sub.isSameEntry(viaPath));
    c.check('root.resolve(descendant) gives the relative path', JSON.stringify(await ctx.root.resolve(sub)) === JSON.stringify([scratch.name, 'child']));
    c.check('descendant.resolve(root) is null (ancestor not detectable downward)', (await sub.resolve(ctx.root)) === null);
    c.check('file handle: isSameEntry across handles', await (async () => {
      await writeBytes(scratch, 'x.txt', new Uint8Array([1]));
      const a = await scratch.getFileHandle('x.txt');
      const b = await (await ctx.root.getDirectoryHandle(scratch.name)).getFileHandle('x.txt');
      return a.isSameEntry(b);
    })());
    c.note('handle.name is not an identity (only the leaf name)', ctx.root.name);
    return { checks: c.items, status: c.status };
  },
};

export const identityOther = {
  id: 'identity-other',
  title: 'Pick a second folder: distinct-folder and overlap detection',
  needsPicker: true,
  spec: '§3 reject overlapping bindings where handles allow detection',
  hint: 'Pick (1) an unrelated folder, then repeat after reloading with (2) the PARENT of the selected folder and (3) a CHILD folder of it. Record each outcome via the scenario label.',
  async run(ctx) {
    const other = await window.showDirectoryPicker({ mode: 'readwrite', id: 'harness-other' });
    const c = new Checks();
    const same = await ctx.root.isSameEntry(other);
    const down = await ctx.root.resolve(other); // other inside root
    const up = await other.resolve(ctx.root); // root inside other
    c.note('isSameEntry(root, other)', same);
    c.note('root.resolve(other) (other is inside root)', down);
    c.note('other.resolve(root) (root is inside other)', up);
    c.note('relationship', same ? 'identical' : down ? 'other is a descendant of root' : up ? 'other is an ancestor of root' : 'unrelated or undetectable');
    return { checks: c.items, status: 'info' };
  },
};

export const handleReload = {
  id: 'handle-reload',
  title: 'Reconnect: load the stored handle (run after page reload / browser restart)',
  needsRoot: false,
  needsGesture: true,
  spec: '§2 check access on every reconnect; explicit Reconnect when permission must be renewed',
  hint: 'First run "Store the root handle" above. Then reload the page, restart the browser, or clear site data, and run this without picking a folder.',
  async run() {
    const c = new Checks();
    const rec = await idbGet('handles', STORED_KEY);
    if (!rec) { c.note('stored handle', 'none (expected after clearing site data: fresh enrollment)'); return { checks: c.items, status: 'info' }; }
    const h = rec.handle;
    c.note('stored handle name/kind', `${h.name} (${h.kind}), stored ${rec.storedAt}`);
    const before = await h.queryPermission({ mode: 'readwrite' });
    c.note('permission before any prompt', before);
    let after = before;
    if (before !== 'granted') {
      const r = await attempt(() => h.requestPermission({ mode: 'readwrite' }));
      after = r.ok ? r.value : r.error;
      c.note('permission after requestPermission() from this click', after);
    }
    if (after === 'granted') {
      const probe = await attempt(async () => {
        const names = [];
        for await (const [n] of h.entries()) { names.push(n); if (names.length >= 5) break; }
        return names;
      });
      c.check('can enumerate the folder after reconnect', probe.ok, probe.ok ? probe.value.join(', ') : probe.error);
      const w = await attempt(async () => {
        const d = await h.getDirectoryHandle('__harness_scratch__', { create: true });
        await writeBytes(d, 'reconnect.txt', new Uint8Array([7]));
        return bytesEqual(await readBytes(d, 'reconnect.txt'), new Uint8Array([7]));
      });
      c.check('can write after reconnect', w.ok && w.value, w.ok ? '' : w.error);
    }
    return { checks: c.items, status: after === 'granted' ? 'pass' : 'info' };
  },
};
