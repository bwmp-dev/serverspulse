# ServersPulse Test Lab

Test Lab is a standalone local app for running the plugin version matrix and watching results live.

It uses:

- `test-lab/testing/test-matrix.json` as the single source of test versions.
- `test-lab/scripts/run-test-matrix.js` as the runner.

## What you get

- Pick a profile (`quick`, `release`, `full`) and platform filters.
- Select exact test IDs when needed.
- Choose local execution or Docker execution.
- Run Docker mode with multiple tests in parallel (`max parallel`).
- Keep per-version server files/worlds between runs (`Persist server data`).
- Launch a run and watch live logs/status in the browser.
- Keep run history and logs under `test-lab/data/runs`.

## Prerequisites

- Node.js 20+
- Java runtimes required by selected tests (8/17/21)
- Bash only if your installed server distribution requires `run.sh` launch scripts
- Docker Desktop (required for Docker execution mode)

## Run

From repo root:

```bash
npm --prefix ./test-lab install
npm --prefix ./test-lab run start
```

Open `http://localhost:4177`.

## Environment overrides

- `PORT` (default `4177`)
- `TEST_LAB_MATRIX_PATH` (default `test-lab/testing/test-matrix.json`)
- `TEST_LAB_RUNNER_PATH` (default `test-lab/scripts/run-test-matrix.js`)
- `TEST_LAB_RUN_ROOT` (default `test-lab/data/test-runs`)
- `TEST_LAB_NODE` (default: current Node executable)

## Version management

Edit `test-lab/testing/test-matrix.json` to add/remove versions, update profiles, or change mappings.

## Docker executors

Docker execution uses `test-lab/docker/test-executor.Dockerfile` and builds one image per Java major when needed:

- `serverspulse-test-executor-java8`
- `serverspulse-test-executor-java17`
- `serverspulse-test-executor-java21`

In the UI, set:

- `Execution` = `Docker`
- `Max parallel` > `1`
- `Persist server data` = enabled

for simultaneous cross-version runs.

Cached server data is stored under `test-lab/data/test-cache/<testId>` by default.

## CLI scripts

- `node test-lab/scripts/run-test-matrix.js`
- `node test-lab/scripts/run-local-smoke-tests.js`
- `node test-lab/scripts/run-release-gate.js`
- `node test-lab/scripts/prepare-plugin-release.js`

See `test-lab/scripts/README.md` for command options and examples.
