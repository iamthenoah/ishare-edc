#!/usr/bin/env python3
import csv
import math
import re
import sys
from pathlib import Path

if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8')

import lizard

REPO_ROOT = Path(__file__).resolve().parent.parent

SOP_FILES = [
    ("AR transport handler", "apps/ar/src/main/java/eu/example/ishare/apps/ar/HttpArTransportHandler.java"),
    ("Consumer EDC gateway", "apps/common/src/main/java/eu/example/ishare/apps/common/HttpConsumerEdcGateway.java"),
    ("Provider EDC gateway", "apps/common/src/main/java/eu/example/ishare/apps/common/HttpProviderEdcGateway.java"),
    ("Consumer transport handler", "apps/consumer/src/main/java/eu/example/ishare/apps/consumer/HttpConsumerTransportHandler.java"),
    ("Provider transport handler", "apps/provider/src/main/java/eu/example/ishare/apps/provider/HttpProviderTransportHandler.java"),
    ("Identity AR handler", "extensions/ishare-identity/src/main/java/eu/example/ishare/extension/identity/HttpIShareIdentityServiceHandler.java"),

    ("Issuer transport handler", "apps/issuer/src/main/java/eu/example/ishare/apps/issuer/HttpIssuerTransportHandler.java"),
    ("Wallet transport handler", "apps/wallet/src/main/java/eu/example/ishare/apps/wallet/HttpWalletTransportHandler.java"),
    ("Issuer gateway (wallet-side)", "apps/common/src/main/java/eu/example/ishare/apps/common/HttpIssuerGateway.java"),
    ("Identity wallet handler", "extensions/ishare-identity/src/main/java/eu/example/ishare/extension/identity/HttpWalletServiceHandler.java"),
]

AOP_FILES = [
    ("AR transport handler", "apps/ar/src/main/java/eu/example/ishare/apps/ar/ActorArTransportHandler.java"),
    ("Shared ask/discovery plumbing", "apps/common/src/main/java/eu/example/ishare/apps/common/ManagementActorClient.java"),
    ("Consumer EDC gateway", "apps/common/src/main/java/eu/example/ishare/apps/common/ActorConsumerEdcGateway.java"),
    ("Provider EDC gateway", "apps/common/src/main/java/eu/example/ishare/apps/common/ActorProviderEdcGateway.java"),
    ("Consumer transport handler", "apps/consumer/src/main/java/eu/example/ishare/apps/consumer/ActorConsumerTransportHandler.java"),
    ("Provider transport handler", "apps/provider/src/main/java/eu/example/ishare/apps/provider/ActorProviderTransportHandler.java"),
    ("Identity AR handler", "extensions/ishare-identity/src/main/java/eu/example/ishare/extension/identity/ActorIShareIdentityServiceHandler.java"),

    ("Issuer transport handler", "apps/issuer/src/main/java/eu/example/ishare/apps/issuer/ActorIssuerTransportHandler.java"),
    ("Wallet transport handler", "apps/wallet/src/main/java/eu/example/ishare/apps/wallet/ActorWalletTransportHandler.java"),
    ("Issuer gateway (wallet-side)", "apps/common/src/main/java/eu/example/ishare/apps/common/ActorIssuerGateway.java"),
    ("Identity wallet handler", "extensions/ishare-identity/src/main/java/eu/example/ishare/extension/identity/ActorWalletServiceHandler.java"),
]

HOTSPOT_CCN = 5

JAVA_KEYWORDS = {
    "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
    "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
    "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
    "interface", "long", "native", "new", "package", "private", "protected", "public",
    "record", "return", "short", "static", "strictfp", "super", "switch", "synchronized",
    "this", "throw", "throws", "transient", "try", "var", "void", "volatile", "while",
    "yield", "sealed", "permits",
}

OPERATOR_PATTERN = re.compile(
    r"->|::|\+\+|--|<<=|>>=|>>>|<<|>>|<=|>=|==|!=|&&|\|\||\+=|-=|\*=|/=|%=|&=|\|=|\^=|"
    r"[-+*/%=<>!&|^~?:;,.(){}\[\]@]"
)
IDENTIFIER_PATTERN = re.compile(r"[A-Za-z_$][A-Za-z0-9_$]*")
NUMBER_PATTERN = re.compile(r"\b\d[\d_]*(?:\.[\d_]+)?(?:[eE][+-]?\d+)?[fFdDlL]?\b")

LITERAL_PATTERN = re.compile(r'"(?:[^"\\\n]|\\.)*"|\'(?:[^\'\\\n]|\\.)*\'')

def read_source(path: Path) -> str:
    raw = path.read_bytes().replace(b"\x00", b"")
    return raw.decode("utf-8", errors="replace")

def strip_comments(source: str) -> str:
    source = LITERAL_PATTERN.sub('""', source)
    source = re.sub(r"/\*.*?\*/", " ", source, flags=re.DOTALL)
    source = re.sub(r"//[^\n]*", " ", source)
    return source

def line_stats(source: str):
    physical = comment = blank = 0
    in_block = False
    for line in source.splitlines():
        physical += 1
        stripped = line.strip()
        if in_block:
            comment += 1
            if "*/" in stripped:
                in_block = False
            continue
        if not stripped:
            blank += 1
        elif stripped.startswith("//"):
            comment += 1
        elif stripped.startswith("/*"):
            comment += 1
            if "*/" not in stripped[2:]:
                in_block = True
    code = physical - comment - blank
    return physical, code, comment, blank

def halstead(source_no_comments: str):
    operators, operands = {}, {}
    working = source_no_comments
    for match in NUMBER_PATTERN.finditer(working):
        operands[match.group()] = operands.get(match.group(), 0) + 1
    for match in IDENTIFIER_PATTERN.finditer(working):
        word = match.group()
        target = operators if word in JAVA_KEYWORDS else operands
        target[word] = target.get(word, 0) + 1
    no_words = IDENTIFIER_PATTERN.sub(" ", NUMBER_PATTERN.sub(" ", working))
    for match in OPERATOR_PATTERN.finditer(no_words):
        operators[match.group()] = operators.get(match.group(), 0) + 1

    n1, n2 = len(operators), len(operands)
    N1, N2 = sum(operators.values()), sum(operands.values())
    vocabulary = n1 + n2
    length = N1 + N2
    volume = length * math.log2(vocabulary) if vocabulary > 1 else 0.0
    difficulty = (n1 / 2) * (N2 / n2) if n2 else 0.0
    effort = volume * difficulty
    return vocabulary, length, volume, difficulty, effort

def maintainability_index(volume: float, avg_ccn: float, code_lines: int) -> float:
    if code_lines <= 0:
        return 100.0
    mi = (171 - 5.2 * math.log(max(volume, 1.0))
          - 0.23 * avg_ccn
          - 16.2 * math.log(max(code_lines, 1)))
    return max(0.0, min(100.0, mi * 100 / 171))

def max_nesting_depth(source_no_comments: str) -> int:
    depth = max_depth = 0
    for ch in source_no_comments:
        if ch == "{":
            depth += 1
            max_depth = max(max_depth, depth)
        elif ch == "}":
            depth = max(0, depth - 1)
    return max(0, max_depth - 2)

def import_coupling(source: str):
    imports = re.findall(r"^import\s+(?:static\s+)?([\w.]+)", source, flags=re.MULTILINE)
    total = len(imports)
    project = sum(1 for i in imports if i.startswith("eu.example.ishare"))
    transport = sum(1 for i in imports if i.startswith(("akka.", "okhttp3", "com.typesafe")))
    jdk = sum(1 for i in imports if i.startswith(("java.", "javax.", "com.sun.")))
    other = total - project - transport - jdk
    return total, project, transport, jdk, other

def run_lizard(filepath: Path):
    info = lizard.analyze_file.analyze_source_code(str(filepath), read_source(filepath))
    return [{
        "nloc": fn.nloc, "ccn": fn.cyclomatic_complexity, "tokens": fn.token_count,
        "params": len(fn.parameters), "function": fn.name,
        "start": fn.start_line, "end": fn.end_line,
    } for fn in info.function_list]

def analyse(component: str, rel_path: str, side: str):
    path = REPO_ROOT / rel_path
    source = read_source(path)
    source_nc = strip_comments(source)
    physical, code, comment, blank = line_stats(source)
    functions = run_lizard(path)

    ccns = [f["ccn"] for f in functions] or [0]
    params = [f["params"] for f in functions] or [0]
    fn_nlocs = sorted(f["nloc"] for f in functions) or [0]
    tokens = sum(f["tokens"] for f in functions)
    vocabulary, length, volume, difficulty, effort = halstead(source_nc)
    total_imp, proj_imp, transport_imp, jdk_imp, other_imp = import_coupling(source)

    return {
        "side": side, "component": component, "file": Path(rel_path).name, "path": rel_path,
        "functions": functions,
        "nloc": sum(f["nloc"] for f in functions), "fn_count": len(functions),
        "wmc": sum(ccns),
        "avg_ccn": sum(ccns) / len(ccns),
        "max_ccn": max(ccns),
        "hotspots": sum(1 for c in ccns if c > HOTSPOT_CCN),
        "tokens": tokens,
        "avg_tokens_fn": tokens / len(functions) if functions else 0,
        "avg_params": sum(params) / len(params),
        "max_fn_nloc": fn_nlocs[-1],
        "median_fn_nloc": fn_nlocs[len(fn_nlocs) // 2],
        "physical": physical, "code_lines": code, "comment": comment, "blank": blank,
        "hal_vocab": vocabulary, "hal_length": length, "hal_volume": volume,
        "hal_difficulty": difficulty, "hal_effort": effort,
        "mi": maintainability_index(volume, sum(ccns) / len(ccns), code),
        "nesting": max_nesting_depth(source_nc),
        "imports": total_imp, "imports_project": proj_imp, "imports_transport": transport_imp,
        "imports_jdk": jdk_imp, "imports_other": other_imp,
    }

def aggregate(rows):
    fn_count = sum(r["fn_count"] for r in rows)
    all_fn_nlocs = sorted(n for r in rows for n in [f["nloc"] for f in r["functions"]])
    total_code = sum(r["code_lines"] for r in rows)
    weighted_ccn = (sum(r["avg_ccn"] * r["fn_count"] for r in rows) / fn_count) if fn_count else 0
    volume = sum(r["hal_volume"] for r in rows)
    return {
        "classes": len(rows),
        "nloc": sum(r["nloc"] for r in rows),
        "fn_count": fn_count,
        "wmc": sum(r["wmc"] for r in rows),
        "avg_ccn": weighted_ccn,
        "max_ccn": max(r["max_ccn"] for r in rows),
        "hotspots": sum(r["hotspots"] for r in rows),
        "tokens": sum(r["tokens"] for r in rows),
        "avg_tokens_fn": (sum(r["tokens"] for r in rows) / fn_count) if fn_count else 0,
        "max_fn_nloc": max(r["max_fn_nloc"] for r in rows),
        "median_fn_nloc": all_fn_nlocs[len(all_fn_nlocs) // 2] if all_fn_nlocs else 0,
        "physical": sum(r["physical"] for r in rows),
        "code_lines": total_code,
        "comment": sum(r["comment"] for r in rows),
        "blank": sum(r["blank"] for r in rows),
        "hal_volume": volume,
        "hal_difficulty": sum(r["hal_difficulty"] for r in rows) / len(rows),
        "hal_effort": sum(r["hal_effort"] for r in rows),
        "mi": maintainability_index(volume / len(rows), weighted_ccn, max(1, total_code // len(rows))),
        "nesting": max(r["nesting"] for r in rows),
        "imports": sum(r["imports"] for r in rows),
        "imports_project": sum(r["imports_project"] for r in rows),
        "imports_transport": sum(r["imports_transport"] for r in rows),
    }

def delta(sop_value, aop_value):
    if isinstance(sop_value, (int, float)) and sop_value:
        return f"{(aop_value - sop_value) / sop_value * 100:+.1f}%"
    return "n/a"

def fmt(value):
    return f"{value:,.1f}" if isinstance(value, float) else f"{value:,}"

def main():

    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sop = [analyse(c, p, "SOP") for c, p in SOP_FILES]
    aop = [analyse(c, p, "AOP") for c, p in AOP_FILES]

    out = []
    w = out.append
    w("# SOP vs AOP glue-code metrics report")
    w("")
    w("Comparison unit: the classes implementing the shared per-transport interfaces")
    w("(`*TransportHandler`, `*EdcGateway`, `IShareIdentityServiceHandler`). Shared domain")
    w("logic, wire-protocol message definitions and cluster bootstrap are excluded (treated")
    w("as library code). Hotspot threshold: CCN > %d. Generated by scripts/lizard_report.py." % HOTSPOT_CCN)
    w("")

    for side_name, rows in (("SOP", sop), ("AOP", aop)):
        w(f"## {side_name} — per class")
        w("")
        w("| Class | NLOC | Funcs | WMC | Avg CCN | Max CCN | Max nest | Hal. volume | Hal. difficulty | MI | Imports (proj/transport) | Avg params | Median fn NLOC | Max fn NLOC |")
        w("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
        for r in rows:
            w(f"| {r['file'].removesuffix('.java')} | {r['nloc']} | {r['fn_count']} | {r['wmc']} "
              f"| {r['avg_ccn']:.1f} | {r['max_ccn']} | {r['nesting']} "
              f"| {r['hal_volume']:,.0f} | {r['hal_difficulty']:.1f} | {r['mi']:.0f} "
              f"| {r['imports']} ({r['imports_project']}/{r['imports_transport']}) "
              f"| {r['avg_params']:.1f} | {r['median_fn_nloc']} | {r['max_fn_nloc']} |")
        w("")

    s, a = aggregate(sop), aggregate(aop)
    w("## Totals and deltas (AOP vs SOP)")
    w("")
    w("| Metric | SOP | AOP | Δ (AOP vs SOP) | Reading |")
    w("|---|---|---|---|---|")
    rows_spec = [
        ("Classes", "classes", "comparison scope"),
        ("Total NLOC", "nloc", "size of transport glue"),
        ("Function count", "fn_count", "decomposition granularity"),
        ("WMC (Σ CCN)", "wmc", "total decision complexity"),
        ("Avg CCN / function", "avg_ccn", "branching per function"),
        ("Max CCN (any function)", "max_ccn", "worst hotspot"),
        ("Hotspot functions (CCN>%d)" % HOTSPOT_CCN, "hotspots", "refactor candidates"),
        ("Total tokens", "tokens", "lexical size"),
        ("Avg tokens / function", "avg_tokens_fn", "function bulk"),
        ("Median function NLOC", "median_fn_nloc", "typical function size"),
        ("Max function NLOC", "max_fn_nloc", "largest function"),
        ("Max nesting depth", "nesting", "structural depth"),
        ("Halstead volume (Σ)", "hal_volume", "information content"),
        ("Halstead difficulty (avg)", "hal_difficulty", "how error-prone to write"),
        ("Halstead effort (Σ)", "hal_effort", "estimated mental effort"),
        ("Maintainability Index (agg)", "mi", "0-100, higher is better"),
        ("Imports (fan-out)", "imports", "total coupling"),
        ("… project-internal", "imports_project", "coupling to own code"),
        ("… transport library", "imports_transport", "coupling to akka/okhttp"),
        ("Comment lines", "comment", "documentation load"),
        ("Physical lines", "physical", "file size incl. comments/blanks"),
    ]
    for label, key, reading in rows_spec:
        w(f"| {label} | {fmt(s[key])} | {fmt(a[key])} | {delta(s[key], a[key])} | {reading} |")
    w("")

    w("## Per-component pairing (SOP vs AOP implementation of the same interface)")
    w("")
    w("| Component | SOP class (NLOC / WMC / MI) | AOP class (NLOC / WMC / MI) | NLOC Δ |")
    w("|---|---|---|---|")
    sop_by_component = {r["component"]: r for r in sop}
    aop_by_component = {r["component"]: r for r in aop}
    for component in dict.fromkeys([c for c, _ in SOP_FILES] + [c for c, _ in AOP_FILES]):
        sr, ar = sop_by_component.get(component), aop_by_component.get(component)
        s_cell = f"{sr['file'].removesuffix('.java')} ({sr['nloc']} / {sr['wmc']} / {sr['mi']:.0f})" if sr else "—"
        a_cell = f"{ar['file'].removesuffix('.java')} ({ar['nloc']} / {ar['wmc']} / {ar['mi']:.0f})" if ar else "—"
        d_cell = delta(sr["nloc"], ar["nloc"]) if sr and ar else "n/a"
        w(f"| {component} | {s_cell} | {a_cell} | {d_cell} |")
    w("")

    w("## Complexity hotspots (CCN > %d)" % HOTSPOT_CCN)
    w("")
    hotspot_lines = []
    for r in sop + aop:
        for f in r["functions"]:
            if f["ccn"] > HOTSPOT_CCN:
                hotspot_lines.append(
                    f"- [{r['side']}] `{r['file']}::{f['function']}` — CCN {f['ccn']}, "
                    f"NLOC {f['nloc']}, {f['params']} params (lines {f['start']}-{f['end']})")
    out.extend(hotspot_lines if hotspot_lines else ["- none"])
    w("")

    w("## Method notes")
    w("")
    w("- NLOC/CCN/tokens/params come from lizard; Halstead, MI, nesting depth and import")
    w("  fan-out are computed by this script (Halstead uses the standard keyword-operator /")
    w("  identifier-operand approximation; MI is the SEI formula normalised to 0-100).")
    w("- `ManagementActorClient` (shared receptionist/ask plumbing used by both actor")
    w("  gateways) is counted entirely on the AOP side — it exists only because of the actor")
    w("  transport, so it appears unpaired in the per-component table.")
    w("- The `Identity AR handler` pair lives in the EDC connector (ishare-identity")
    w("  extension) rather than the apps, but implements the same one-interface-per-transport")
    w("  pattern, so it is included in the comparison scope.")
    w("- LCOM/cohesion is deliberately omitted: these classes are thin adapters with 1-3")
    w("  fields, where LCOM values are dominated by noise rather than design signal.")
    w("")

    print("\n".join(out))

    metrics_dir = REPO_ROOT / "results" / "metrics"
    metrics_dir.mkdir(parents=True, exist_ok=True)
    with open(metrics_dir / "functions.csv", "w", newline="", encoding="utf-8") as fh:
        writer = csv.writer(fh)
        writer.writerow(["side", "component", "file", "function", "nloc", "ccn", "tokens", "params", "start", "end"])
        for r in sop + aop:
            for f in r["functions"]:
                writer.writerow([r["side"], r["component"], r["file"], f["function"],
                                 f["nloc"], f["ccn"], f["tokens"], f["params"], f["start"], f["end"]])
    file_fields = ["side", "component", "file", "nloc", "fn_count", "wmc", "avg_ccn", "max_ccn",
                   "hotspots", "tokens", "avg_tokens_fn", "avg_params", "median_fn_nloc",
                   "max_fn_nloc", "physical", "code_lines", "comment", "blank", "hal_vocab",
                   "hal_length", "hal_volume", "hal_difficulty", "hal_effort", "mi", "nesting",
                   "imports", "imports_project", "imports_transport", "imports_jdk", "imports_other"]
    with open(metrics_dir / "files.csv", "w", newline="", encoding="utf-8") as fh:
        writer = csv.writer(fh)
        writer.writerow(file_fields)
        for r in sop + aop:
            writer.writerow([f"{r[k]:.2f}" if isinstance(r[k], float) else r[k] for k in file_fields])
    print(f"[csv] per-function and per-file metrics written to {metrics_dir}", file=sys.stderr)

if __name__ == "__main__":
    main()
