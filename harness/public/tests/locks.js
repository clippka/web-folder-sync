import { Checks, sleep } from '../lib/util.js';

export const LOCK_NAME = 'foldersync-harness:binding:test';

/** Entry point for the popup opened with ?role=holder: takes the lock and never releases it. */
export function runHolder() {
  const ch = new BroadcastChannel('harness-locks');
  document.title = 'lock holder';
  document.body.textContent = 'Holding Web Lock ' + LOCK_NAME + ' (close this tab to release).';
  navigator.locks.request(LOCK_NAME, { mode: 'exclusive' }, () => {
    ch.postMessage({ type: 'held' });
    return new Promise(() => {});
  });
}

const probe = () => navigator.locks.request(LOCK_NAME, { ifAvailable: true }, (lock) => !!lock);

export default {
  id: 'locks',
  title: 'Exclusive binding lock across tabs (Web Locks), release on tab close',
  needsRoot: false,
  needsGesture: true,
  spec: '§7 step 1 exclusive browser lock; acceptance 10 (refresh / duplicate tabs)',
  async run() {
    const c = new Checks();
    const data = {};
    c.check('lock is initially free', await probe());
    const ch = new BroadcastChannel('harness-locks');
    const held = new Promise((res) => { ch.onmessage = (e) => e.data?.type === 'held' && res(true); });
    const pop = window.open(location.pathname + '?role=holder', 'harness-holder', 'width=420,height=160');
    if (!pop) { c.check('popup opened (allow popups for this origin and retry)', false); return { checks: c.items, status: 'fail' }; }
    const gotHeld = await Promise.race([held, sleep(8000).then(() => false)]);
    c.check('second tab acquired the exclusive lock', gotHeld);
    if (gotHeld) {
      c.check('this tab cannot acquire it while the other tab holds it (ifAvailable)', !(await probe()));
      const t0 = performance.now();
      pop.close();
      let freedAfter = null;
      for (let i = 0; i < 200; i++) { if (await probe()) { freedAfter = Math.round(performance.now() - t0); break; } await sleep(25); }
      data.releaseAfterTabCloseMs = freedAfter;
      c.check('lock is released after the holder tab closes', freedAfter !== null, freedAfter === null ? 'still held after 5 s' : `${freedAfter} ms`);
    } else { try { pop.close(); } catch {} }
    ch.close();
    return { checks: c.items, status: c.status, data };
  },
};
