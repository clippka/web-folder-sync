// JSON API client. Every state-changing request echoes the CSRF cookie as X-XSRF-TOKEN. Errors carry the server's
// stable machine-readable code so callers branch on it (replan, recovery, retry) instead of parsing messages.
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly retryable = false,
    readonly details: Record<string, unknown> = {},
    readonly path?: string,
  ) {
    super(message);
    this.name = "ApiError";
  }
  get replan(): boolean { return this.details["replan"] === true; }
  get recovery(): boolean { return this.details["recovery"] === true; }
}

function csrfToken(): string {
  if (typeof document === "undefined") return "";
  const m = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
  return m ? decodeURIComponent(m[1]!) : "";
}

export async function parseError(res: Response): Promise<ApiError> {
  let body: { code?: string; message?: string; retryable?: boolean; details?: Record<string, unknown>; path?: string } = {};
  try { body = await res.json(); } catch { /* not JSON */ }
  return new ApiError(res.status, body.code ?? `http_${res.status}`, body.message ?? res.statusText, body.retryable ?? res.status >= 500, body.details ?? {}, body.path);
}

export interface RequestOptions {
  body?: unknown;
  raw?: BodyInit;
  headers?: Record<string, string>;
  signal?: AbortSignal | undefined;
  query?: Record<string, string | number | undefined>;
}

function networkOrAbort(e: unknown): never {
  if ((e as Error).name === "AbortError") throw Object.assign(new Error("cancelled"), { cancelled: true });
  throw new ApiError(0, "network_error", `Could not reach the server: ${(e as Error).message}`, true);
}

export class Api {
  constructor(private readonly fetchImpl: typeof fetch = (...a) => fetch(...a), private readonly token: () => string = csrfToken) {}

  async request<T>(method: string, url: string, opts: RequestOptions = {}): Promise<T> {
    const q = opts.query
      ? Object.entries(opts.query).filter(([, v]) => v !== undefined).map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(String(v))}`).join("&")
      : "";
    const headers: Record<string, string> = { Accept: "application/json", ...opts.headers };
    if (method !== "GET") headers["X-XSRF-TOKEN"] = this.token();
    let body: BodyInit | undefined;
    if (opts.raw !== undefined) { body = opts.raw; headers["Content-Type"] = "application/octet-stream"; }
    else if (opts.body !== undefined) { body = JSON.stringify(opts.body); headers["Content-Type"] = "application/json"; }
    let res: Response;
    try {
      res = await this.fetchImpl(q ? `${url}?${q}` : url, {
        method, headers, credentials: "same-origin",
        ...(body !== undefined ? { body } : {}),
        ...(opts.signal ? { signal: opts.signal } : {}),
      });
    } catch (e) { networkOrAbort(e); }
    if (!res.ok) throw await parseError(res);
    if (res.status === 204) return undefined as T;
    return (await res.json()) as T;
  }

  get<T>(url: string, query?: RequestOptions["query"], signal?: AbortSignal): Promise<T> {
    return this.request("GET", url, { ...(query ? { query } : {}), signal });
  }
  post<T>(url: string, body?: unknown, opts: RequestOptions = {}): Promise<T> {
    return this.request("POST", url, { ...opts, ...(body !== undefined ? { body } : {}) });
  }
  put<T>(url: string, raw: BodyInit, opts: RequestOptions = {}): Promise<T> { return this.request("PUT", url, { ...opts, raw }); }
  delete<T>(url: string, opts: RequestOptions = {}): Promise<T> { return this.request("DELETE", url, opts); }

  /** The raw response for streamed downloads; the caller owns the body. */
  async stream(url: string, signal?: AbortSignal): Promise<Response> {
    let res: Response;
    try { res = await this.fetchImpl(url, { credentials: "same-origin", ...(signal ? { signal } : {}) }); }
    catch (e) { networkOrAbort(e); }
    if (!res.ok) throw await parseError(res);
    return res;
  }
}

export function newKey(): string {
  return crypto.randomUUID();
}
