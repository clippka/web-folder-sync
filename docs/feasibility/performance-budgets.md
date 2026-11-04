# Performance budgets (Roadmap Step 1)

**Status: no measurements exist, so no budgets are set.** Per the specification, performance is not claimed before
measurement and the targets are derived from the feasibility results, not guessed.

## Method

1. Run `perf` on the 1k, 10k and 25k fixtures (and the 25k / 1 GiB fixture) in all six combinations, at least three runs each (the first is cold).
2. Take the median of warm runs and the worst of all runs as the observed range.
3. Set each budget with headroom over the median of the slowest supported combination (proposal: 1.5×), and record the headroom used.
4. Step 10 benchmarks are judged against these budgets (initial, no-change, 1%-changed).

## Budgets to derive

| Metric | Fixture | Source field | Budget | Basis |
|---|---|---|---|---|
| Time to first progress | 25k | `timeToFirstProgressMs` | _pending_ | |
| Enumeration time (pruned) | 1k / 10k / 25k | `enumerationDoneMs` | _pending_ | |
| Names-only enumeration | 25k | `namesOnly.enumerationDoneMs` | _pending_ | |
| Full enumerate + hash | 25k / 1 GiB | `totalMs` | _pending_ | |
| Hash throughput | 100 MiB boundary file | `hashing` MiB/s | _pending_ | |
| Peak JS heap growth | 25k / 1 GiB | `heapPeakGrowthBytes` | _pending_ | |
| Max main-thread lag | 25k | `maxMainThreadLagMs` | _pending_ | |
| Cancel latency | 25k | `cancelLatencyMs` | _pending_ | |
| Process memory (task manager) | 25k / 1 GiB | manual | _pending_ | |

Provisional pass thresholds in the harness (`< 64 MiB` JS heap growth, `< 500 ms` cancel) are tripwires only.
