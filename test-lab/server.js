import express from "express";
import { WebSocketServer } from "ws";
import { spawn } from "node:child_process";
import { createInterface } from "node:readline";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const repoRoot = path.resolve(__dirname, "..");

const matrixPath = process.env.TEST_LAB_MATRIX_PATH || path.join(__dirname, "testing", "test-matrix.json");
const runnerPath = process.env.TEST_LAB_RUNNER_PATH || path.join(__dirname, "scripts", "run-test-matrix.js");
const runOutputRoot = process.env.TEST_LAB_RUN_ROOT || path.join(__dirname, "data", "test-runs");
const nodeExe = process.env.TEST_LAB_NODE || process.execPath;
const port = Number(process.env.PORT || 4177);

const dataDir = path.join(__dirname, "data");
const runsDir = path.join(dataDir, "runs");

ensureDir(dataDir);
ensureDir(runsDir);
ensureDir(runOutputRoot);

const app = express();
app.use(express.json({ limit: "1mb" }));
app.use(express.static(path.join(__dirname, "public")));

const runs = new Map();
const sockets = new Set();
let activeRunId = null;

hydrateRuns();

app.get("/api/health", (_req, res) => {
  res.json({ ok: true, activeRunId });
});

app.get("/api/matrix", (_req, res) => {
  try {
    const matrix = loadMatrix();
    res.json(matrix);
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

app.get("/api/runs", (_req, res) => {
  res.json({ runs: listRuns() });
});

app.get("/api/runs/:runId", (req, res) => {
  const run = runs.get(req.params.runId);
  if (!run) {
    res.status(404).json({ error: "Run not found" });
    return;
  }

  const logTail = readLogTail(run.logFile, 400);
  res.json({ run: serializeRun(run), logTail });
});

app.post("/api/runs", (req, res) => {
  if (activeRunId) {
    res.status(409).json({ error: `Run ${activeRunId} is in progress` });
    return;
  }

  let matrix;
  try {
    matrix = loadMatrix();
  } catch (error) {
    res.status(500).json({ error: error.message });
    return;
  }

  const profile = typeof req.body?.profile === "string" ? req.body.profile : "quick";
  const skipBuild = Boolean(req.body?.skipBuild);
  const executionMode = req.body?.executionMode === "docker" ? "docker" : "local";
  const maxParallelRaw = Number(req.body?.maxParallel ?? 1);
  const normalizedParallel = Number.isFinite(maxParallelRaw) && maxParallelRaw > 0 ? Math.floor(maxParallelRaw) : 1;
  const maxParallel = Math.min(12, normalizedParallel);
  const usePersistentCache = req.body?.usePersistentCache !== false;
  const rebuildDockerImages = Boolean(req.body?.rebuildDockerImages);
  const requestedPlatforms = Array.isArray(req.body?.platforms) ? req.body.platforms : ["all"];
  const testIds = Array.isArray(req.body?.testIds) ? req.body.testIds : [];

  if (!matrix.profiles || !matrix.profiles[profile]) {
    res.status(400).json({ error: `Unknown profile '${profile}'` });
    return;
  }

  const validPlatforms = new Set(["all", "paper", "fabric", "forge", "neoforge"]);
  const invalidPlatforms = requestedPlatforms.filter((entry) => !validPlatforms.has(entry));
  if (invalidPlatforms.length > 0) {
    res.status(400).json({ error: `Invalid platforms: ${invalidPlatforms.join(", ")}` });
    return;
  }

  const matrixTestIds = new Set((matrix.tests || []).map((test) => test.id));
  const invalidTestIds = testIds.filter((testId) => !matrixTestIds.has(testId));
  if (invalidTestIds.length > 0) {
    res.status(400).json({ error: `Unknown test IDs: ${invalidTestIds.join(", ")}` });
    return;
  }

  const run = createRun({
    profile,
    platforms: requestedPlatforms,
    testIds,
    skipBuild,
    executionMode,
    maxParallel,
    usePersistentCache,
    rebuildDockerImages
  });
  runs.set(run.id, run);
  activeRunId = run.id;
  persistRun(run);
  broadcast("run.created", serializeRun(run));

  startRunner(run);
  res.status(202).json({ run: serializeRun(run) });
});

const server = app.listen(port, () => {
  console.log(`Test Lab listening on http://localhost:${port}`);
});

const wss = new WebSocketServer({ server, path: "/ws" });
wss.on("connection", (socket) => {
  sockets.add(socket);
  safeSend(socket, "snapshot", { runs: listRuns(), activeRunId });

  socket.on("close", () => {
    sockets.delete(socket);
  });
});

function loadMatrix() {
  if (!fs.existsSync(matrixPath)) {
    throw new Error(`Matrix file not found at ${matrixPath}`);
  }

  const raw = fs.readFileSync(matrixPath, "utf8");
  return JSON.parse(raw);
}

function startRunner(run) {
  const args = [
    runnerPath,
    "--matrix-path",
    matrixPath,
    "--profile",
    run.profile,
    "--run-id",
    run.id,
    "--run-root",
    runOutputRoot,
    "--emit-events"
  ];

  if (run.skipBuild) {
    args.push("--skip-build");
  }

  args.push("--execution-mode", run.executionMode);
  args.push("--max-parallel", String(run.maxParallel));
  args.push("--use-persistent-cache", run.usePersistentCache ? "true" : "false");

  if (run.rebuildDockerImages) {
    args.push("--rebuild-docker-images");
  }

  if (run.platforms.length > 0 && !run.platforms.includes("all")) {
    args.push("--platforms", run.platforms.join(","));
  }

  if (run.requestedTestIds.length > 0) {
    args.push("--only", run.requestedTestIds.join(","));
  }

  const child = spawn(nodeExe, args, {
    cwd: repoRoot,
    stdio: ["ignore", "pipe", "pipe"]
  });

  run.pid = child.pid;
  run.status = "starting";
  persistRun(run);
  broadcast("run.updated", serializeRun(run));

  const stdout = createInterface({ input: child.stdout });
  const stderr = createInterface({ input: child.stderr });

  stdout.on("line", (line) => {
    appendLog(run, line);
    handleRunnerLine(run, line);
  });

  stderr.on("line", (line) => {
    const normalized = `[stderr] ${line}`;
    appendLog(run, normalized);
    handleRunnerLine(run, normalized);
  });

  child.on("error", (error) => {
    run.status = "failed";
    run.error = error.message;
    run.endedAt = new Date().toISOString();
    activeRunId = null;
    persistRun(run);
    broadcast("run.updated", serializeRun(run));
  });

  child.on("close", (code) => {
    run.exitCode = code ?? -1;
    run.endedAt = new Date().toISOString();

    if (run.status === "starting" || run.status === "running") {
      run.status = run.exitCode === 0 ? "passed" : "failed";
    }

    activeRunId = null;
    persistRun(run);
    broadcast("run.updated", serializeRun(run));
  });
}

function handleRunnerLine(run, line) {
  const prefix = "##TESTLAB##";
  if (line.startsWith(prefix)) {
    const payloadText = line.slice(prefix.length);
    let event;
    try {
      event = JSON.parse(payloadText);
    } catch {
      return;
    }

    applyRunnerEvent(run, event);
    broadcast("run.event", { runId: run.id, event });
    persistRun(run);
    broadcast("run.updated", serializeRun(run));
    return;
  }

  broadcast("run.log", { runId: run.id, line });
}

function applyRunnerEvent(run, event) {
  switch (event.type) {
    case "run.started": {
      run.status = "running";
      run.runDir = event.runDir || run.runDir;
      run.executionMode = event.executionMode || run.executionMode;
      run.maxParallel = Number(event.maxParallel || run.maxParallel || 1);
      run.usePersistentCache = event.usePersistentCache !== undefined ? Boolean(event.usePersistentCache) : run.usePersistentCache;
      run.persistentCacheRoot = event.persistentCacheRoot || run.persistentCacheRoot;
      run.selectedTestIds = Array.isArray(event.selectedTestIds) ? event.selectedTestIds : run.selectedTestIds;
      for (const testId of run.selectedTestIds || []) {
        if (!run.tests[testId]) {
          run.tests[testId] = { id: testId, status: "pending", message: "" };
        }
      }
      break;
    }
    case "build.started": {
      run.phase = "building";
      break;
    }
    case "build.completed": {
      run.phase = "testing";
      break;
    }
    case "test.started": {
      const testId = event.testId;
      run.tests[testId] = {
        id: testId,
        label: event.label || testId,
        status: "running",
        message: ""
      };
      break;
    }
    case "test.step": {
      const testId = event.testId;
      const current = run.tests[testId] || { id: testId, status: "running" };
      current.lastStep = event.step || "";
      current.lastValue = event.value || "";
      run.tests[testId] = current;
      break;
    }
    case "test.passed": {
      const testId = event.testId;
      const current = run.tests[testId] || { id: testId };
      current.status = "passed";
      current.message = "";
      run.tests[testId] = current;
      break;
    }
    case "test.failed": {
      const testId = event.testId;
      const current = run.tests[testId] || { id: testId };
      current.status = "failed";
      current.message = event.message || "";
      run.tests[testId] = current;
      break;
    }
    case "run.completed": {
      run.phase = "complete";
      run.runDir = event.runDir || run.runDir;
      run.summaryPath = event.summaryPath || run.summaryPath;
      run.executionMode = event.executionMode || run.executionMode;
      run.maxParallel = Number(event.maxParallel || run.maxParallel || 1);
      run.usePersistentCache = event.usePersistentCache !== undefined ? Boolean(event.usePersistentCache) : run.usePersistentCache;
      run.persistentCacheRoot = event.persistentCacheRoot || run.persistentCacheRoot;
      run.passedCount = Number(event.passedCount || 0);
      run.failedCount = Number(event.failedCount || 0);
      run.status = run.failedCount > 0 ? "failed" : "passed";

      if (Array.isArray(event.tests)) {
        for (const testResult of event.tests) {
          run.tests[testResult.id] = {
            id: testResult.id,
            label: testResult.label,
            status: (testResult.result || "").toLowerCase(),
            message: testResult.message || "",
            durationSeconds: testResult.durationSeconds,
            workDir: testResult.workDir,
            logPath: testResult.logPath
          };
        }
      }
      break;
    }
    case "run.failed": {
      run.phase = "complete";
      run.status = "failed";
      run.error = event.message || "Unknown runner error";
      break;
    }
    default:
      break;
  }
}

function createRun({ profile, platforms, testIds, skipBuild, executionMode, maxParallel, usePersistentCache, rebuildDockerImages }) {
  const now = new Date();
  const stamp = now.toISOString().replace(/[.:]/g, "-");
  const suffix = Math.random().toString(16).slice(2, 8);
  const runId = `${stamp}-${suffix}`;
  return {
    id: runId,
    profile,
    platforms,
    requestedTestIds: testIds,
    selectedTestIds: [],
    skipBuild,
    executionMode,
    maxParallel,
    usePersistentCache,
    persistentCacheRoot: null,
    rebuildDockerImages,
    status: "queued",
    phase: "queued",
    startedAt: now.toISOString(),
    endedAt: null,
    pid: null,
    exitCode: null,
    passedCount: 0,
    failedCount: 0,
    runDir: null,
    summaryPath: null,
    logFile: path.join(runsDir, `${runId}.log`),
    tests: {},
    error: ""
  };
}

function appendLog(run, line) {
  fs.appendFileSync(run.logFile, `${line}\n`);
}

function serializeRun(run) {
  return {
    id: run.id,
    profile: run.profile,
    platforms: run.platforms,
    requestedTestIds: run.requestedTestIds,
    selectedTestIds: run.selectedTestIds,
    skipBuild: run.skipBuild,
    executionMode: run.executionMode,
    maxParallel: run.maxParallel,
    usePersistentCache: run.usePersistentCache,
    persistentCacheRoot: run.persistentCacheRoot,
    rebuildDockerImages: run.rebuildDockerImages,
    status: run.status,
    phase: run.phase,
    startedAt: run.startedAt,
    endedAt: run.endedAt,
    pid: run.pid,
    exitCode: run.exitCode,
    passedCount: run.passedCount,
    failedCount: run.failedCount,
    runDir: run.runDir,
    summaryPath: run.summaryPath,
    logFile: run.logFile,
    tests: run.tests,
    error: run.error || ""
  };
}

function persistRun(run) {
  const outPath = path.join(runsDir, `${run.id}.json`);
  fs.writeFileSync(outPath, JSON.stringify(serializeRun(run), null, 2));
}

function hydrateRuns() {
  if (!fs.existsSync(runsDir)) {
    return;
  }

  const files = fs.readdirSync(runsDir).filter((entry) => entry.endsWith(".json"));
  for (const fileName of files) {
    const filePath = path.join(runsDir, fileName);
    try {
      const raw = fs.readFileSync(filePath, "utf8");
      const run = JSON.parse(raw);
      runs.set(run.id, run);
    } catch {
      continue;
    }
  }
}

function listRuns() {
  return Array.from(runs.values())
    .map((run) => serializeRun(run))
    .sort((a, b) => {
      const left = Date.parse(b.startedAt || "0");
      const right = Date.parse(a.startedAt || "0");
      return left - right;
    });
}

function readLogTail(logFile, maxLines) {
  if (!logFile || !fs.existsSync(logFile)) {
    return [];
  }

  const raw = fs.readFileSync(logFile, "utf8");
  const lines = raw.split(/\r?\n/).filter((line) => line.length > 0);
  if (lines.length <= maxLines) {
    return lines;
  }

  return lines.slice(lines.length - maxLines);
}

function broadcast(event, data) {
  for (const socket of sockets) {
    safeSend(socket, event, data);
  }
}

function safeSend(socket, event, data) {
  if (socket.readyState !== 1) {
    return;
  }

  socket.send(JSON.stringify({ event, data }));
}

function ensureDir(dirPath) {
  if (!fs.existsSync(dirPath)) {
    fs.mkdirSync(dirPath, { recursive: true });
  }
}
