// Browser port of the server's IgnorePolicy (app.foldersync.policy). Both are verified against
// conformance/ignore-fixtures.json; change them together.

export interface IgnoreRule {
  readonly sourceFile: string;
  readonly line: number;
  readonly text: string;
  readonly negated: boolean;
  matches(relative: string, isDirectory: boolean): boolean;
}

export interface DirectoryRuleFiles {
  gitignore?: string | null;
  syncignore?: string | null;
}

export interface IgnoreDecision {
  ignored: boolean;
  rule?: IgnoreRule;
  ruleDirectory?: string;
  matchedPath?: string;
  builtin?: boolean;
}

export const GIT_DIR = ".git";
export const CONTROL_FILE_NAMES: ReadonlySet<string> = new Set([".gitignore", ".syncignore"]);

export function isControlFile(path: string): boolean {
  return CONTROL_FILE_NAMES.has(path.slice(path.lastIndexOf("/") + 1));
}

const lit = (c: string): string => (/[\\^$.*+?()[\]{}|/-]/.test(c) ? "\\" + c : c);

function findClassEnd(p: string, open: number): number {
  let j = open + 1;
  if (j < p.length && (p[j] === "!" || p[j] === "^")) j++;
  if (j < p.length && p[j] === "]") j++;
  while (j < p.length) {
    if (p[j] === "\\") { j += 2; continue; }
    if (p[j] === "]") return j;
    j++;
  }
  return -1;
}

function charClass(body: string): string {
  let b = body;
  const neg = b.startsWith("!") || b.startsWith("^");
  if (neg) b = b.slice(1);
  let out = neg ? "[^/" : "(?!/)[";
  for (let k = 0; k < b.length; ) {
    const ch = b[k]!;
    if (ch === "\\" && k + 1 < b.length) { out += lit(b[k + 1]!); k += 2; }
    else if (ch === "-" && k > 0 && k < b.length - 1) { out += "-"; k++; }
    else { out += lit(ch); k++; }
  }
  return out + "]";
}

function globToRegex(p: string): string {
  let out = "";
  let i = 0;
  while (i < p.length) {
    const c = p[i]!;
    if (c === "\\") {
      if (i + 1 < p.length) { out += lit(p[i + 1]!); i += 2; } else { out += lit("\\"); i++; }
    } else if (c === "*" && p[i + 1] === "*") {
      const atStart = i === 0 || p[i - 1] === "/";
      const end = i + 2;
      if (atStart && end === p.length) { out += i === 0 ? ".*" : ".+"; i = end; }
      else if (atStart && p[end] === "/") { out += "(?:.*/)?"; i = end + 1; }
      else { out += "[^/]*"; i = end; }
    } else if (c === "*") { out += "[^/]*"; i++; }
    else if (c === "?") { out += "[^/]"; i++; }
    else if (c === "[") {
      const close = findClassEnd(p, i);
      if (close < 0) { out += lit("["); i++; } else { out += charClass(p.slice(i + 1, close)); i = close + 1; }
    } else { out += lit(c); i++; }
  }
  return out;
}

export function parseIgnoreLine(sourceFile: string, line: number, raw: string): IgnoreRule | null {
  let p = raw;
  if (p.startsWith("#")) return null;
  while (p.endsWith(" ") && !p.endsWith("\\ ")) p = p.slice(0, -1);
  if (p === "") return null;
  const negated = p.startsWith("!");
  if (negated) p = p.slice(1);
  const dirOnly = p.endsWith("/");
  if (dirOnly) p = p.slice(0, -1);
  if (p === "") return null;
  const anchored = p.includes("/");
  if (p.startsWith("/")) p = p.slice(1);
  if (p === "") return null;
  const regex = new RegExp("^(?:" + globToRegex(p) + ")$", "su");
  const basenameOnly = !anchored;
  return {
    sourceFile, line, text: raw, negated,
    matches(relative, isDirectory) {
      if (dirOnly && !isDirectory) return false;
      return regex.test(basenameOnly ? relative.slice(relative.lastIndexOf("/") + 1) : relative);
    },
  };
}

export function parseIgnoreFile(sourceFile: string, content: string): IgnoreRule[] {
  const rules: IgnoreRule[] = [];
  content.split("\n").forEach((raw, idx) => {
    const rule = parseIgnoreLine(sourceFile, idx + 1, raw.endsWith("\r") ? raw.slice(0, -1) : raw);
    if (rule) rules.push(rule);
  });
  return rules;
}

export class IgnorePolicy {
  private readonly rulesByDir = new Map<string, IgnoreRule[]>();

  constructor(ruleFiles: Record<string, DirectoryRuleFiles> = {}) {
    for (const [dir, f] of Object.entries(ruleFiles)) this.setDirectory(dir, f);
  }

  /** Registers (or replaces) the rule files found in one directory. Used while scanning, directory by directory. */
  setDirectory(dir: string, f: DirectoryRuleFiles): void {
    const prefix = dir === "" ? "" : dir + "/";
    const rules: IgnoreRule[] = [];
    if (f.gitignore != null) rules.push(...parseIgnoreFile(prefix + ".gitignore", f.gitignore));
    if (f.syncignore != null) rules.push(...parseIgnoreFile(prefix + ".syncignore", f.syncignore));
    this.rulesByDir.set(dir, rules);
  }

  evaluate(path: string, isDirectory: boolean): IgnoreDecision {
    const segments = path.split("/");
    if (segments.includes(GIT_DIR)) return { ignored: true, builtin: true, matchedPath: path };
    for (let i = 1; i <= segments.length; i++) {
      const prefix = segments.slice(0, i).join("/");
      const isDir = i < segments.length || isDirectory;
      const hit = this.lastMatch(prefix, isDir);
      if (hit && !hit.rule.negated) {
        if (i === segments.length && CONTROL_FILE_NAMES.has(segments[segments.length - 1]!)) return { ignored: false };
        return { ignored: true, rule: hit.rule, ruleDirectory: hit.dir, matchedPath: prefix };
      }
    }
    return { ignored: false };
  }

  private lastMatch(path: string, isDir: boolean): { dir: string; rule: IgnoreRule } | null {
    const slash = path.lastIndexOf("/");
    const parent = slash < 0 ? "" : path.slice(0, slash);
    const dirs = [""];
    if (parent !== "") {
      let acc = "";
      for (const s of parent.split("/")) { acc = acc === "" ? s : acc + "/" + s; dirs.push(acc); }
    }
    let result: { dir: string; rule: IgnoreRule } | null = null;
    for (const d of dirs) {
      const rules = this.rulesByDir.get(d);
      if (!rules) continue;
      const rel = d === "" ? path : path.slice(d.length + 1);
      for (const r of rules) if (r.matches(rel, isDir)) result = { dir: d, rule: r };
    }
    return result;
  }
}
