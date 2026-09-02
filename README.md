# ishare-edc

An iSHARE-compatible Eclipse Dataspace Components (EDC) dataspace, built twice: once with
HTTP between its internal components (SOP) and once with Akka actor messages (AOP). The two
share all business logic, so the transport is the only variable.

Java 17, Gradle 8.5.

## Structure

```
apps/                Standalone processes
  common/            Transport-agnostic domain logic shared by every app
  ar/                Authorization Registry
  consumer/          Consumer application, drives the flow, exposes /ping /catalog /run
  provider/          Provider application
  issuer/            Verifiable Credential issuer
  wallet/            Verifiable Credential wallet
common/              Akka cluster bootstrap and the typed messages shared by all actors
extensions/
  connector-consumer/  Consumer EDC runtime
  connector-provider/  Provider EDC runtime
  ishare-identity/     iSHARE identity service (AR delegation, PR participant checks)
  actor-extension/     Akka cluster + management bridge for the connectors
init/                CLI that seeds the provider data plane and the AR, and runs a demo flow
perf/                Closed-loop load harness
scripts/             Scenario launchers, performance and metrics entry points
resources/certs/     Certificates and keys (not committed)
results/             metrics/ and sweep/ output
```

Each component has one interface with an HTTP implementation and an Akka implementation
behind it. The transport is chosen at startup via a system property, never by a code branch.

## Prerequisites

- JDK 17
- Python 3 with `lizard` (`pip3 install lizard`) for the metrics report
- A Participant Registry. This project uses the FIWARE iSHARE Satellite:
  https://github.com/FIWARE/ishare-satellite
  Point the AR at it with `-Dar.satellite-url=<url>` (default `http://192.168.77.128:8081`).

## Certificates

Nothing under `resources/certs/` is committed. You supply your own.

**PEM key pairs**, used by the apps to sign and verify iSHARE JWTs:

| File | Used by |
|---|---|
| `resources/certs/ar_private_key.pem` | AR, VC issuer |
| `resources/certs/ar_cert.pem` | AR, VC issuer |
| `resources/certs/consumer_private_key.pem` | Consumer wallet |
| `resources/certs/consumer_cert.pem` | Consumer wallet |
| `resources/certs/provider_private_key.pem` | Provider wallet |
| `resources/certs/provider_cert.pem` | Provider wallet |

Private keys must be **PKCS#8 RSA** (`-----BEGIN PRIVATE KEY-----`); `BEGIN RSA PRIVATE KEY`
is also accepted. Certificate files hold the **X.509 chain**, leaf first — the full chain
matters, because it is what goes into the `x5c` header of every signed token.

**PKCS#12 keystores**, used by the EDC connectors:

| File | Password |
|---|---|
| `resources/certs/consumer.p12` | `changeit` |
| `resources/certs/provider.p12` | `changeit` |

The connectors also read `*-vault.properties` next to their configuration, holding
`consumer-private-key` / `consumer-certificate` (and the provider equivalents). These are
gitignored too.

For an interoperable run the certificates must be iSHARE-issued and the EORI in each
certificate must match the configured party ID (`EU.EORI.NLTESTAR`, `EU.EORI.NLTESTCONSUMER`,
`EU.EORI.NLTESTPROVIDER`). Self-signed certificates work for local runs against a test
satellite, but real participant checks will reject them.

## Running

Build every jar first:

```
./gradlew build
```

Then launch a scenario. Each script starts both connectors, the AR (or the issuer and
wallets), both apps, and seeds them via `:init`:

```
scripts/scenario-1-full-sop.sh     # AR, consumer, provider all HTTP
scripts/scenario-2-full-aop.sh     # all Akka
scripts/scenario-3-ar-actor.sh     # AR on Akka, apps on HTTP
scripts/scenario-4-apps-actor.sh   # apps on Akka, AR on HTTP
scripts/scenario-5-vc-sop.sh       # Verifiable Credentials, HTTP
scripts/scenario-6-vc-aop.sh       # Verifiable Credentials, Akka
```

`.ps1` equivalents exist for Windows. Logs and PIDs go to `logs/scenario-<n>/`; stop
everything with `kill $(cat logs/scenario-<n>/*.pid)`.

Ports: consumer app 7002, provider app 7001, AR 7000, issuer 7005, wallets 7006/7007.
Consumer EDC management 29193, protocol 29194, benchmark 29199. Akka cluster seeds on 25251.

## Performance tests

The harness is closed-loop: a fixed number of workers each send their next request only
after the previous one returns, and concurrency is swept upward. It drives the consumer app
at one of three depths:

| Mode | Endpoint | Exercises |
|---|---|---|
| `ping` | `/ping` | transport only |
| `catalog` | `/catalog` | transport plus AR and PR identity checks |
| `run` | `/run` | the complete catalog → negotiate → transfer flow |

With a scenario running:

```
scripts/run-perf.sh <scenario 1-4> [modes] [concurrency] [duration-s] [repeats]
scripts/run-perf.sh 1 ping,catalog,run 1,4,16 30 3
```

Raw samples and `summary.csv` land in `results/perf/scenario-<n>/`. `scripts/run-all-perf.ps1`
sweeps every scenario in one go. Aggregate across scenarios with:

```
./gradlew :perf:perfCompare        # -> results/perf/comparison.md
```

Override anything with `-Dperf.base-url`, `-Dperf.warmup-seconds`, `-Dperf.out-dir`.

## Code metrics

Static analysis compares the classes that have to be written once per transport — the
transport handlers and gateways — and excludes shared domain logic, message definitions and
cluster bootstrap, which are identical on both sides.

```
scripts/run-metrics.sh             # -> docs/metrics-report.md, results/metrics/*.csv
```

It installs `lizard` if missing, then runs `scripts/lizard_report.py`, which reports NLOC,
cyclomatic complexity, WMC, nesting depth, Halstead effort and import fan-out per class,
paired SOP against AOP.

`scripts/adaptability_report.py` runs the same analysis over the Verifiable Credential
issuer and wallet components, measuring what adding a new capability costs under each
transport.
