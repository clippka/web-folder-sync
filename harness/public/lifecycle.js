// Lifecycle panel: demonstrates that reopening or focusing the page never restarts work.
// A "job" ticks every 100 ms and checkpoints to IndexedDB. If the page was closed/reloaded mid-job, the stored
// state is turned into "interrupted" on load and stays that way until the user clicks Resume.
import { idbGet, idbPut, idbAll, idbClear } from './lib/idb.js';

const JOB = 'job:lifecycle';
const TICKS = 300; // 30 s at 100 ms

export async function mountLifecycle(el, record) {
  const sessionId = crypto.randomUUID();
  let timer = null;
  const log = [];
  const evt = async (type, extra = {}) => {
    const e = { t: new Date().toISOString(), hidden: document.hidden, type, session: sessionId.slice(0, 8), ...extra };
    log.push(e);
    await idbPut('events', `${Date.now()}-${Math.random()}`, e);
    render();
  };

  let job = (await idbGet('jobs', JOB)) ?? null;
  let autoStarted = 0; // must stay 0 for the whole session
  const priorEvents = (await idbAll('events')).slice(-25);
  if (job?.state === 'running') {
    job = { ...job, state: 'interrupted', interruptedAt: new Date().toISOString() };
    await idbPut('jobs', JOB, job);
  }
  await evt('page-load', { storedJobState: job?.state ?? 'none', autoStarted });

  for (const t of ['visibilitychange', 'focus', 'blur', 'pageshow', 'pagehide', 'online', 'offline']) {
    (t === 'visibilitychange' ? document : window).addEventListener(t, () => evt(t, { visibility: document.visibilityState }));
  }
  document.addEventListener('freeze', () => evt('freeze'));
  document.addEventListener('resume', () => evt('resume'));

  async function start(resume) {
    if (timer) return;
    job = { state: 'running', done: resume ? job.done : 0, startedBySession: sessionId, resumed: !!resume };
    await idbPut('jobs', JOB, job);
    await evt(resume ? 'explicit-resume-click' : 'explicit-start-click', { done: job.done });
    let last = performance.now(), maxDrift = 0;
    const drifts = [];
    timer = setInterval(async () => {
      const now = performance.now();
      const drift = now - last - 100;
      last = now;
      maxDrift = Math.max(maxDrift, drift);
      if (document.hidden) drifts.push(Math.round(drift));
      job.done++;
      job.maxDriftMs = Math.round(maxDrift);
      if (job.done % 10 === 0) await idbPut('jobs', JOB, job);
      if (job.done >= TICKS) {
        clearInterval(timer); timer = null;
        job.state = 'complete';
        await idbPut('jobs', JOB, job);
        const hiddenDrifts = drifts.length ? { samples: drifts.length, maxMs: Math.max(...drifts), medianMs: drifts.sort((a, b) => a - b)[drifts.length >> 1] } : null;
        await evt('job-complete', { maxDriftMs: job.maxDriftMs, hiddenDrifts });
        record('lifecycle', { status: 'info', checks: [{ name: 'job result', ok: null, detail: JSON.stringify({ maxDriftMs: job.maxDriftMs, hiddenDrifts, autoStarted }) }] });
      }
      render();
    }, 100);
  }

  function render() {
    const running = !!timer;
    el.innerHTML = `
      <p>State: <b>${job?.state ?? 'none'}</b>${job ? ` — ${job.done ?? 0}/${TICKS} ticks, max timer drift ${job.maxDriftMs ?? 0} ms` : ''}.
      Auto-started in this session: <b>${autoStarted}</b> (must be 0).</p>
      <div class="row">
        <button id="lc-start" ${running ? 'disabled' : ''}>Start 30 s job</button>
        <button id="lc-resume" ${running || job?.state !== 'interrupted' ? 'disabled' : ''}>Resume interrupted job</button>
        <button id="lc-pause" ${running ? '' : 'disabled'}>Pause</button>
        <button id="lc-clear">Clear event log</button>
      </div>
      <ol class="small">
        <li>Start the job, then: switch tabs for 1–5 min / minimise / lock the screen / close the browser / reload.</li>
        <li>Reopen or refocus the page: the job must show <b>interrupted</b> and stay idle until you click Resume.</li>
        <li>Check the event log for <code>visibilitychange</code>, <code>freeze</code>, <code>pagehide</code> and timer drift while hidden.</li>
      </ol>
      <details><summary>Event log (${priorEvents.length} from earlier sessions + ${log.length} now)</summary>
      <pre>${[...priorEvents, ...log].map((e) => JSON.stringify(e)).join('\n').replace(/</g, '&lt;')}</pre></details>`;
    el.querySelector('#lc-start').onclick = () => start(false);
    el.querySelector('#lc-resume').onclick = () => start(true);
    el.querySelector('#lc-pause').onclick = async () => { clearInterval(timer); timer = null; job.state = 'paused'; await idbPut('jobs', JOB, job); await evt('explicit-pause-click'); };
    el.querySelector('#lc-clear').onclick = async () => { await idbClear('events'); priorEvents.length = 0; log.length = 0; render(); };
  }
  render();
}
