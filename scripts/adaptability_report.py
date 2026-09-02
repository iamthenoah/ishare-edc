from pathlib import Path

import lizard

ROOT = Path(__file__).resolve().parent.parent

ND_EXTENSIONS = lizard.get_extensions(["nd"])
ND_ANALYZER = lizard.FileAnalyzer(ND_EXTENSIONS)

PAIRS = [
    ("Issuer transport handler",
     "apps/issuer/src/main/java/eu/example/ishare/apps/issuer/HttpIssuerTransportHandler.java",
     "apps/issuer/src/main/java/eu/example/ishare/apps/issuer/ActorIssuerTransportHandler.java"),
    ("Wallet transport handler",
     "apps/wallet/src/main/java/eu/example/ishare/apps/wallet/HttpWalletTransportHandler.java",
     "apps/wallet/src/main/java/eu/example/ishare/apps/wallet/ActorWalletTransportHandler.java"),
    ("Issuer gateway",
     "apps/common/src/main/java/eu/example/ishare/apps/common/HttpIssuerGateway.java",
     "apps/common/src/main/java/eu/example/ishare/apps/common/ActorIssuerGateway.java"),
    ("Wallet service handler",
     "extensions/ishare-identity/src/main/java/eu/example/ishare/extension/identity/HttpWalletServiceHandler.java",
     "extensions/ishare-identity/src/main/java/eu/example/ishare/extension/identity/ActorWalletServiceHandler.java"),
    ("External AR adapter",
     "extensions/ishare-identity/src/main/java/eu/example/ishare/extension/identity/HttpExternalArIdentityServiceHandler.java",
     "apps/ar/src/main/java/eu/example/ishare/apps/ar/ExternalArProxyTransportHandler.java"),
]

def stats(rel_path):
    path = ROOT / rel_path
    src = path.read_text(encoding="utf-8")
    info = ND_ANALYZER.analyze_source_code(str(path), src)
    fns = info.function_list
    return {
        "nloc": sum(f.nloc for f in fns),
        "funcs": len(fns),
        "wmc": sum(f.cyclomatic_complexity for f in fns),
        "max_ccn": max((f.cyclomatic_complexity for f in fns), default=0),
        "max_nd": max((getattr(f, "max_nesting_depth", 0) for f in fns), default=0),
        "imports": sum(1 for line in src.splitlines() if line.strip().startswith("import ")),
        "akka": sum(1 for line in src.splitlines() if line.strip().startswith("import akka")),
    }

def main():
    header = f"{'Component':26s} | side | NLOC | Funcs | WMC | MaxCCN | MaxND | Imp | Akka"
    print(header)
    print("-" * len(header))
    totals = {"SOP": dict.fromkeys(("nloc", "funcs", "wmc", "imports", "akka"), 0),
              "AOP": dict.fromkeys(("nloc", "funcs", "wmc", "imports", "akka"), 0)}
    for name, sop, aop in PAIRS:
        for side, rel in (("SOP", sop), ("AOP", aop)):
            s = stats(rel)
            print(f"{name:26s} | {side:4s} | {s['nloc']:4d} | {s['funcs']:5d} | {s['wmc']:3d} |"
                  f" {s['max_ccn']:6d} | {s['max_nd']:5d} | {s['imports']:3d} | {s['akka']}")
            if name != "External AR adapter":
                for k in totals[side]:
                    totals[side][k] += s[k]
    print("-" * len(header))
    for side in ("SOP", "AOP"):
        t = totals[side]
        print(f"{'VC total':26s} | {side:4s} | {t['nloc']:4d} | {t['funcs']:5d} | {t['wmc']:3d} |"
              f" {'':6s} | {'':5s} | {t['imports']:3d} | {t['akka']}")

if __name__ == "__main__":
    main()
