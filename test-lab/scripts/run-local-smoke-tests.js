#!/usr/bin/env node

import path from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const __filename = fileURLToPath(import.meta.url);
const scriptDir = path.dirname(__filename);
const runner = path.join(scriptDir, "run-test-matrix.js");

const passthrough = process.argv.slice(2);
const hasProfile = passthrough.includes("--profile");
const args = hasProfile ? [...passthrough] : ["--profile", "quick", ...passthrough];

const result = spawnSync(process.execPath, [runner, ...args], {
  stdio: "inherit",
  cwd: path.resolve(scriptDir, "..", "..")
});

process.exit(result.status == null ? 1 : result.status);
