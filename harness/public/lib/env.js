/** Environment snapshot attached to every result file. */
export async function collectEnv() {
  const uad = navigator.userAgentData;
  let high = {};
  try { high = uad ? await uad.getHighEntropyValues(['platformVersion', 'architecture', 'fullVersionList']) : {}; } catch {}
  const brands = (high.fullVersionList ?? uad?.brands ?? []).filter((b) => !/not.?a.?brand/i.test(b.brand));
  const edge = brands.find((b) => /edge/i.test(b.brand));
  const chrome = brands.find((b) => /^(google )?chrome$/i.test(b.brand));
  return {
    userAgent: navigator.userAgent,
    platform: uad?.platform ?? navigator.platform,
    platformVersion: high.platformVersion ?? null,
    architecture: high.architecture ?? null,
    browser: edge ? 'edge' : chrome ? 'chrome' : 'other',
    browserVersion: (edge ?? chrome)?.version ?? null,
    brands,
    secureContext: window.isSecureContext,
    origin: location.origin,
    hardwareConcurrency: navigator.hardwareConcurrency,
    deviceMemoryGiB: navigator.deviceMemory ?? null,
    hasPerformanceMemory: !!performance.memory,
    timestamp: new Date().toISOString(),
  };
}

export function osKey(env) {
  const p = (env.platform ?? '').toLowerCase();
  if (p.includes('win')) return 'windows';
  if (p.includes('mac')) return 'macos';
  if (p.includes('linux') || p.includes('chrome os') || p.includes('cros')) return 'linux';
  return p || 'unknown';
}
