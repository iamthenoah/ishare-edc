#!/usr/bin/env python3
import argparse
import csv
import io
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
RESULTS_DIR = REPO_ROOT / "results" / "perf"
DATA_DIR = REPO_ROOT / "thesis" / "figures" / "data"

SCENARIOS = [1, 2, 3, 4]
SCENARIO_LABELS = {1: "S1 SOP", 2: "S2 AOP", 3: "S3 AR-actor", 4: "S4 Apps-actor"}
MODES = ["ping", "catalog", "run"]

def load_rows(scenario: int):
    path = RESULTS_DIR / f"scenario-{scenario}" / "summary.csv"
    raw = path.read_bytes().replace(b"\x00", b"")
    text = raw.decode("utf-8", errors="replace")
    reader = csv.DictReader(io.StringIO(text))
    return [r for r in reader if r.get("timestamp", "").startswith("20")]

def latest_n(rows, mode, concurrency, n):
    matching = [r for r in rows if r["mode"] == mode and r["concurrency"] == str(concurrency)]
    matching.sort(key=lambda r: r["timestamp"])
    return matching[-n:]

def avg(vals):
    return sum(vals) / len(vals) if vals else 0.0

def summarize(rows, mode, concurrency, repeats):
    latest = latest_n(rows, mode, concurrency, repeats)
    if not latest:
        return None
    rps = avg([float(r["throughput_rps"]) for r in latest])
    nonzero = [r for r in latest if int(r["requests"]) > 0]
    p50 = avg([float(r["p50_ms"]) for r in nonzero]) if nonzero else 0.0
    p95 = avg([float(r["p95_ms"]) for r in nonzero]) if nonzero else 0.0
    total_req = sum(int(r["requests"]) for r in latest)
    total_err = sum(int(r["errors"]) for r in latest)
    return {"rps": rps, "p50": p50, "p95": p95, "requests": total_req, "errors": total_err, "n": len(latest)}

def fmt_thousands(x, decimals=0):
    return f"{x:,.{decimals}f}".replace(",", "{,}")

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--repeats", type=int, default=3)
    parser.add_argument("--concurrency-levels", type=str, default=",".join(str(i) for i in range(1, 17)))
    args = parser.parse_args()
    levels = [int(x) for x in args.concurrency_levels.split(",")]
    checkpoint_levels = [1, 4, 16]

    DATA_DIR.mkdir(parents=True, exist_ok=True)

    all_data = {}
    for s in SCENARIOS:
        rows = load_rows(s)
        all_data[s] = {}
        for mode in MODES:
            all_data[s][mode] = {}
            for c in levels:
                summ = summarize(rows, mode, c, args.repeats)
                if summ is not None:
                    all_data[s][mode][c] = summ

    for mode in MODES:
        out_path = DATA_DIR / f"sweep-{mode}.csv"
        fieldnames = ["concurrency"]
        for s in SCENARIOS:
            fieldnames += [f"S{s}_rps", f"S{s}_p50", f"S{s}_p95"]
        with open(out_path, "w", newline="") as f:
            writer = csv.writer(f)
            writer.writerow(fieldnames)
            for c in levels:
                row = [c]
                for s in SCENARIOS:
                    d = all_data[s][mode].get(c)
                    row += [d["rps"], d["p50"], d["p95"]] if d else ["", "", ""]
                writer.writerow(row)
        print(f"wrote {out_path}", file=sys.stderr)

    print("\n% ---- checkpoint rows (c=1,4,16) for thesis tables ----\n")
    for mode in MODES:
        print(f"% mode = {mode}")
        for s in SCENARIOS:
            for c in checkpoint_levels:
                d = all_data[s][mode].get(c)
                label = SCENARIO_LABELS[s]
                if d is None:
                    print(f"S{s} {mode} c={c}: NO DATA")
                    continue
                if mode == "run" and c == 16:
                    p50_str = "---" if d["requests"] == 0 else fmt_thousands(d["p50"])
                    print(f"{label:<14}& {c:<3}& {d['rps']:.2f} & {p50_str} "
                          f"& {d['requests']} flows completed total across {d['n']} repeats, {d['errors']} errors \\\\")
                elif mode == "run":
                    print(f"{label:<14}& {c:<3}& {d['rps']:.2f} & {fmt_thousands(d['p50'])}  & \\\\")
                else:
                    rps_dec = 2 if mode == "catalog" else 0
                    lat_dec = 2 if mode == "ping" else 0
                    print(f"{label:<14}& {c:<3}& {fmt_thousands(d['rps'], rps_dec)} & "
                          f"{fmt_thousands(d['p50'], lat_dec)} & {fmt_thousands(d['p95'], lat_dec)} \\\\")
        print()

if __name__ == "__main__":
    main()
