# Test Lab CLI Scripts

The plugin test workflow is matrix-driven and uses Node.js scripts located in `test-lab/scripts`.

Single source of truth:

- `test-lab/testing/test-matrix.json`

Primary runner:

- `test-lab/scripts/run-test-matrix.js`

Convenience wrappers:

- `test-lab/scripts/run-local-smoke-tests.js` (default profile: `quick`)
- `test-lab/scripts/run-release-gate.js` (profile: `release`)
- `test-lab/scripts/prepare-plugin-release.js` (release packaging)

Platform smoke runners:

- `test-lab/scripts/paper-smoke-test.js`
- `test-lab/scripts/fabric-smoke-test.js`
- `test-lab/scripts/forge-smoke-test.js`
- `test-lab/scripts/neoforge-smoke-test.js`

## Matrix runner

From repo root:

```bash
node test-lab/scripts/run-test-matrix.js
```

Useful examples:

```bash
# Full matrix
node test-lab/scripts/run-test-matrix.js --profile full

# Release subset
node test-lab/scripts/run-test-matrix.js --profile release

# Filter by platform
node test-lab/scripts/run-test-matrix.js --profile full --platforms forge,neoforge

# Run only specific test IDs
node test-lab/scripts/run-test-matrix.js --only paper-high,forge-1-21-11

# Skip artifact build
node test-lab/scripts/run-test-matrix.js --skip-build

# Docker executors + parallel
node test-lab/scripts/run-test-matrix.js --execution-mode docker --max-parallel 4
```

Docker mode defaults:

- image prefix: `serverspulse-test-executor` (override with `--docker-image-prefix`)
- Dockerfile: `test-lab/docker/test-executor.Dockerfile`
- persistent cache root: `test-lab/data/test-cache`

Run output defaults to:

- `test-lab/data/test-runs/<runId>/`

## Release packaging

From repo root:

```bash
node test-lab/scripts/prepare-plugin-release.js
```

This command:

- builds consolidated platform artifacts,
- validates the `release` profile,
- writes release files into `build/releases/<version>/`.
