# FolderSync design tokens and UI notes

The stylesheet is `frontend/src/app.css`. Every value below is a custom property on `:root`; dark values are set under `@media (prefers-color-scheme: dark)`.

## Colour

Neutrals are slightly cool. Cobalt is the only accent, and only marks what the person can act on or what is running. Green, amber and red are reserved for status.

| Token | Light | Dark |
|---|---|---|
| `--bg` | `#f5f6f8` | `#121419` |
| `--surface` | `#ffffff` | `#1a1d24` |
| `--surface-2` | `#f0f1f4` | `#22252d` |
| `--surface-3` | `#e6e8ed` | `#2c303a` |
| `--border` | `#dfe2e8` | `#2e333d` |
| `--border-strong` | `#b9bec9` | `#4a515f` |
| `--control-border` | `#868c9a` | `#737a89` |
| `--text` | `#16181d` | `#eceef2` |
| `--text-2` | `#4a505e` | `#b6bcc8` |
| `--text-3` | `#5f6675` | `#9aa1af` |
| `--accent` | `#2148e0` | `#3d63ee` |
| `--accent-hover` | `#1a3bc2` | `#3558dc` |
| `--accent-active` | `#15319f` | `#2d4bbd` |
| `--accent-text` | `#1f43d2` | `#a6b8ff` |
| `--accent-surface` | `#ebeffe` | `#1c2647` |
| `--accent-border` | `#b9c7f9` | `#38498f` |
| `--on-accent` | `#ffffff` | `#ffffff` |
| `--focus` | `#2148e0` | `#8ea5ff` |
| `--ok-text` | `#0f6b3b` | `#74d9a0` |
| `--ok-surface` | `#e5f5ec` | `#12291e` |
| `--ok-border` | `#a5d9b8` | `#27563c` |
| `--ok-solid` | `#1c8a50` | `#2fa36b` |
| `--warn-text` | `#7f4d00` | `#edbe63` |
| `--warn-surface` | `#fdf2dc` | `#2b2212` |
| `--warn-border` | `#e8c883` | `#5e481a` |
| `--warn-solid` | `#c98a12` | `#d49a2c` |
| `--bad-text` | `#a82015` | `#ff9f95` |
| `--bad-surface` | `#fdeeec` | `#321715` |
| `--bad-border` | `#f0b5af` | `#72302a` |
| `--bad-solid` | `#c4281c` | `#cf3528` |
| `--bad-solid-hover` | `#a82015` | `#b52c21` |
| `--on-bad` | `#ffffff` | `#ffffff` |
| `--idle-text` | `#4a505e` | `#b6bcc8` |
| `--idle-surface` | `#eef0f3` | `#252932` |
| `--idle-border` | `#d3d7de` | `#3b414d` |

Measured contrast (text needs 4.5:1; control borders and focus rings need 3:1):

| Pair | Light | Dark |
|---|---|---|
| `text` on `bg` | 16.4:1 | 15.9:1 |
| `text` on `surface` | 17.8:1 | 14.5:1 |
| `text-2` on `surface` | 8.1:1 | 8.8:1 |
| `text-2` on `surface-2` | 7.2:1 | 8.0:1 |
| `text-3` on `surface` | 5.8:1 | 6.5:1 |
| `text-3` on `bg` | 5.3:1 | 7.1:1 |
| `text-3` on `surface-2` | 5.1:1 | 5.9:1 |
| `accent-text` on `surface` | 7.5:1 | 8.8:1 |
| `accent-text` on `accent-surface` | 6.6:1 | 7.7:1 |
| `on-accent` on `accent` | 6.8:1 | 5.0:1 |
| `on-accent` on `accent-hover` | 8.6:1 | 5.9:1 |
| `ok-text` on `ok-surface` | 5.8:1 | 8.9:1 |
| `warn-text` on `warn-surface` | 6.4:1 | 9.1:1 |
| `warn-text` on `bg` | 6.6:1 | 10.7:1 |
| `bad-text` on `bad-surface` | 6.5:1 | 8.4:1 |
| `text` on `bad-surface` | 15.7:1 | 14.2:1 |
| `on-bad` on `bad-solid` | 5.7:1 | 5.0:1 |
| `on-bad` on `bad-solid-hover` | 7.3:1 | 6.3:1 |
| `idle-text` on `idle-surface` | 7.1:1 | 7.6:1 |
| `control-border` on `surface` | 3.4:1 | 3.9:1 |
| `focus` on `surface` | 6.8:1 | 7.2:1 |
| `focus` on `bg` | 6.3:1 | 7.9:1 |

## Status tones

The tones are the ones the code emits. Each one sets `--fg`, `--fill` and `--line`, and the dot differs by shape as well as colour (idle is hollow).

| Tone | Badge labels | File chips |
|---|---|---|
| `ok` | Up to date | done, identical |
| `warn` | Permission required, Conflicts, Needs review, Paused, Offline | needs review, blocked |
| `bad` | Failed | failed |
| `idle` | Not connected, Ready | planned, skipped |
| `idle`, active | Scanning, Syncing | reading, uploading, downloading |

"Active" is not a fifth tone. The code sends `idle` while a run is going; the CSS shows cobalt and a pulse exactly while Cancel is visible, using `:has()`. Chips use the cobalt without the pulse, because rows are rebuilt on every repaint.

## Type

System stacks only: `--font-sans` and `--font-mono`. Mono is for paths, hashes and timestamps.

| Token | Size | Use |
|---|---|---|
| `--fs-xs` | 12px | hints, chips, table heads |
| `--fs-sm` | 13px | notes, paths, timestamps |
| `--fs-md` | 14px | body, controls |
| `--fs-lg` | 16px | card titles, Help text |
| `--fs-xl` | 20px | section titles, deletion heading |
| `--fs-2xl` | 28px | page titles |

Line height: `--lh-tight` 1.25, `--lh-body` 1.5, `--lh-prose` 1.65.

## Spacing, shape, depth, motion

- **Spacing:** `--sp-05` 4, `--sp-1` 8, `--sp-15` 12, `--sp-2` 16, `--sp-3` 24, `--sp-4` 32, `--sp-5` 40, `--sp-6` 48, `--sp-8` 64 (px).
- **Radii:** `--r-sm` 6 (chips), `--r-md` 8 (controls), `--r-lg` 12 (cards), `--r-pill`.
- **Controls:** `--control-h` 36px, `--control-h-sm` 30px, top bar `--bar-h` 52px.
- **Shadows:** `--shadow-xs` on buttons, `--shadow-sm` only on the space console and the sign-in card, `--shadow-lg` only on the user menu. Other surfaces use a border.
- **Motion:** `--t-fast` 120ms, `--t-med` 240ms, `--ease`. All of it is switched off under `prefers-reduced-motion`.

## Constraints this CSS works around

- **No `data:` URLs.** `default-src 'self'` also covers images, so CSS icons are borders or generated text. Other icons are inline SVG in the templates.
- **`[hidden]` is forced.** `.actions { display: flex }` used to override it, so the connect and run controls could both show.
- **Elements the renderers overwrite.** `className` on the badge and on `[data-field="binding"]`, and `textContent` on the sync button. They cannot hold extra classes or child icons, so the decoration sits in pseudo-elements and on the `.binding` wrapper.
- **Current page in the top bar.** HTMX swaps only `#content`, so each page sets `data-nav` on `main` and CSS marks the link with `body:has()`.
- **User menu.** A native `popover`, so it needs no script. Account is a button with `hx-get` because a boosted link would leave the popover open after the swap.
- **Quota bar.** A `<meter>`, because a width would need an inline style.

## Renderer notes

No TypeScript change is required: the UI works with `status.ts` and `review.ts` as they are. These are worth doing separately.

1. **A failed run can show a calm badge.** When a run throws, `coordinator.ts` sets the phase to `Failed: ...` and `outcome` to null, so `deriveStatus` returns Ready next to a failure message. Add a `failed` flag to `PanelState` and `StatusInput`.
2. **Focus is lost in the conflict review.** `renderConflicts` rebuilds the cards on every paint, so pressing "Show text preview" drops keyboard focus. The same rebuild sits inside `aria-live="polite"`, so screen readers can hear the whole review again. Reuse nodes by conflict id and put `aria-live` on `.conflict-note` only.
3. **File rows are rebuilt on every frame during a run.** `list.replaceChildren()` limits rows to static chips and can disturb hover. Reusing `li` elements by path would remove that cost and allow a pulse on active rows.
4. **Scan previews show raw operation names.** `planned` keys render as "adopt" and "propose server deletion". Map them to the words used elsewhere ("already identical", "deletion to review").
5. **Long badge text.** Offline and Paused carry a full sentence in the badge. It wraps safely, but moving the part in brackets to `data-field="phase"` would keep the badge short.
6. **Screen readers do not get the current page.** The top bar highlight is CSS only. Setting `aria-current="page"` in the existing `htmx:afterSettle` handler would fix that.
7. **Confirmations are native.** Irreversible deletions use `window.confirm`, which cannot be styled and can be suppressed by the browser. A `<dialog>` is CSP-safe if you want it to match.

The brief lists a Cancelled badge, but `state.ts` has no such label: a cancelled run reads Paused, with Resume sync. The design follows the code.
