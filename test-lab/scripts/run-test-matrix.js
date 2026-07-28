#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";
import { spawn, spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const __filename = fileURLToPath(import.meta.url);
const SCRIPT_DIR = path.dirname(__filename);
const TEST_LAB_ROOT = path.resolve(SCRIPT_DIR, "..");
const REPO_ROOT = path.resolve(TEST_LAB_ROOT, "..");
const PLUGIN_ROOT = REPO_ROOT;
const EVENT_PREFIX = "##TESTLAB##";

const RUNNER_SCRIPT_BY_PLATFORM = {
  paper: path.join(SCRIPT_DIR, "paper-smoke-test.js"),
  fabric: path.join(SCRIPT_DIR, "fabric-smoke-test.js"),
  forge: path.join(SCRIPT_DIR, "forge-smoke-test.js"),
  neoforge: path.join(SCRIPT_DIR, "neoforge-smoke-test.js")
};

function usage() {
  process.stdout.write(
    [
      "Usage:",
      "  node test-lab/scripts/run-test-matrix.js [options]",
      "",
      "Options:",
      "  --matrix-path <path>",
      "  --profile <quick|release|full>",
      "  --platforms <comma list>",
      "  --only <comma list>",
      "  --execution-mode <local|docker>",
      "  --max-parallel <n>",
      "  --use-persistent-cache <true|false>",
      "  --persistent-cache-root <path>",
      "  --docker-image-prefix <name>",
      "  --dockerfile-path <path>",
      "  --docker-startup-timeout-seconds <n>",
      "  --rebuild-docker-images",
      "  --skip-build",
      "  --emit-events",
      "  --run-id <id>",
      "  --run-root <path>",
      "  --help"
    ].join("\n") + "\n"
  );
}

function parseArgs(argv) {
  const out = {
    matrixPath: path.join(TEST_LAB_ROOT, "testing", "test-matrix.json"),
    profile: "quick",
    platforms: ["all"],
    only: [],
    executionMode: "local",
    maxParallel: 1,
    usePersistentCache: undefined,
    persistentCacheRoot: path.join(TEST_LAB_ROOT, "data", "test-cache"),
    dockerImagePrefix: "serverspulse-test-executor",
    dockerfilePath: path.join(TEST_LAB_ROOT, "docker", "test-executor.Dockerfile"),
    dockerStartupTimeoutSeconds: 120,
    rebuildDockerImages: false,
    skipBuild: false,
    emitEvents: false,
    runId: null,
    runRoot: path.join(TEST_LAB_ROOT, "data", "test-runs")
  };

  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === "--help" || arg === "-h") {
      out.help = true;
      continue;
    }

    const next = () => {
      const value = argv[i + 1];
      if (!value || value.startsWith("--")) {
        throw new Error(`Missing value for ${arg}`);
      }
      i += 1;
      return value;
    };

    switch (arg) {
      case "--matrix-path":
        out.matrixPath = resolveMaybeRelative(next());
        break;
      case "--profile":
        out.profile = next();
        break;
      case "--platforms":
        out.platforms = splitCsv(next());
        break;
      case "--only":
        out.only = splitCsv(next());
        break;
      case "--execution-mode":
        out.executionMode = next();
        break;
      case "--max-parallel":
        out.maxParallel = Number(next());
        break;
      case "--use-persistent-cache":
        out.usePersistentCache = parseBoolean(next());
        break;
      case "--persistent-cache-root":
        out.persistentCacheRoot = resolveMaybeRelative(next());
        break;
      case "--docker-image-prefix":
        out.dockerImagePrefix = next();
        break;
      case "--dockerfile-path":
        out.dockerfilePath = resolveMaybeRelative(next());
        break;
      case "--docker-startup-timeout-seconds":
        out.dockerStartupTimeoutSeconds = Number(next());
        break;
      case "--rebuild-docker-images":
        out.rebuildDockerImages = true;
        break;
      case "--skip-build":
        out.skipBuild = true;
        break;
      case "--emit-events":
        out.emitEvents = true;
        break;
      case "--run-id":
        out.runId = next();
        break;
      case "--run-root":
        out.runRoot = resolveMaybeRelative(next());
        break;
      default:
        throw new Error(`Unknown argument: ${arg}`);
    }
  }

  if (out.executionMode !== "local" && out.executionMode !== "docker") {
    throw new Error(`Invalid --execution-mode '${out.executionMode}'`);
  }

  if (!Number.isFinite(out.maxParallel) || out.maxParallel < 1) {
    out.maxParallel = 1;
  }

  if (!Number.isFinite(out.dockerStartupTimeoutSeconds) || out.dockerStartupTimeoutSeconds < 5) {
    out.dockerStartupTimeoutSeconds = 120;
  }

  if (out.executionMode === "docker" && out.usePersistentCache === undefined) {
    out.usePersistentCache = true;
  }

  if (out.usePersistentCache === undefined) {
    out.usePersistentCache = false;
  }

  if (!out.runId) {
    out.runId = makeRunId();
  }

  return out;
}

function makeRunId() {
  const d = new Date();
  const pad = (v) => String(v).padStart(2, "0");
  return `${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}-${pad(d.getHours())}${pad(d.getMinutes())}${pad(d.getSeconds())}`;
}

function splitCsv(value) {
  return value
    .split(",")
    .map((s) => s.trim())
    .filter(Boolean);
}

function parseBoolean(value) {
  const v = String(value).trim().toLowerCase();
  if (v === "true" || v === "1") return true;
  if (v === "false" || v === "0") return false;
  throw new Error(`Invalid boolean value '${value}'`);
}

function resolveMaybeRelative(p) {
  return path.isAbsolute(p) ? p : path.resolve(process.cwd(), p);
}

function emitEvent(enabled, runId, type, data = {}) {
  if (!enabled) return;
  const payload = {
    type,
    runId,
    timestamp: new Date().toISOString(),
    ...data
  };
  process.stdout.write(`${EVENT_PREFIX}${JSON.stringify(payload)}\n`);
}

function assertCommand(name) {
  const probe = process.platform === "win32"
    ? spawnSync("where.exe", [name], { stdio: "ignore", shell: false })
    : spawnSync("which", [name], { stdio: "ignore", shell: false });

  if (probe.status !== 0) {
    throw new Error(`Required command '${name}' was not found in PATH.`);
  }
}

function runCommandSync(command, args, options = {}) {
  return spawnSync(command, args, {
    cwd: options.cwd,
    env: options.env,
    shell: Boolean(options.shell),
    timeout: options.timeoutMs,
    stdio: options.stdio || "pipe",
    encoding: "utf8"
  });
}

async function assertDockerEngineAvailable(totalTimeoutSeconds) {
  const deadline = Date.now() + totalTimeoutSeconds * 1000;
  let lastError = "Docker engine unavailable.";

  while (Date.now() < deadline) {
    const result = runCommandSync("docker", ["version", "--format", "{{.Server.Version}}"], {
      shell: false,
      timeoutMs: 8000,
      stdio: "pipe"
    });

    if (result.status === 0) {
      return;
    }

    const stderr = (result.stderr || "").trim();
    const stdout = (result.stdout || "").trim();
    const timedOut = result.error && result.error.code === "ETIMEDOUT";
    lastError = timedOut
      ? "docker command timed out waiting for daemon response"
      : (stderr || stdout || "Docker engine unavailable.");

    await new Promise((resolve) => setTimeout(resolve, 3000));
  }

  throw new Error(`Docker daemon is not reachable after ${totalTimeoutSeconds}s. ${lastError}`);
}

function getJavaEnvForVersion(javaMajor) {
  const env = { ...process.env };
  const defaults = {
    8: "C:\\Program Files\\Eclipse Adoptium\\jdk-8.0.422.5-hotspot",
    17: "C:\\Program Files\\Eclipse Adoptium\\jdk-17.0.12.7-hotspot",
    21: "C:\\Program Files\\Eclipse Adoptium\\jdk-21.0.4.7-hotspot"
  };

  const candidates = {
    8: [env.SERVERPULSE_JAVA8_HOME, env.JAVA8_HOME, defaults[8]],
    17: [env.SERVERPULSE_JAVA17_HOME, env.JAVA17_HOME, defaults[17]],
    21: [env.SERVERPULSE_JAVA21_HOME, env.JAVA21_HOME, env.JAVA_HOME, defaults[21]]
  };

  const homes = candidates[javaMajor] || [];
  for (const javaHome of homes) {
    if (!javaHome) continue;
    const javaBin = path.join(javaHome, "bin");
    const javaExe = process.platform === "win32" ? path.join(javaBin, "java.exe") : path.join(javaBin, "java");
    if (!fs.existsSync(javaExe)) continue;

    env.JAVA_HOME = javaHome;
    env.PATH = `${javaBin}${path.delimiter}${process.env.PATH || ""}`;
    return env;
  }

  return env;
}

function toContainerPath(hostPath) {
  const abs = path.resolve(hostPath);
  const rel = path.relative(REPO_ROOT, abs);
  if (rel.startsWith("..") || path.isAbsolute(rel)) {
    throw new Error(`Path '${abs}' must be inside repository root '${REPO_ROOT}' for Docker mode.`);
  }
  if (!rel) return "/workspace";
  return `/workspace/${rel.replace(/\\/g, "/")}`;
}

function findLatestArtifact(patternRel) {
  const absPattern = path.resolve(PLUGIN_ROOT, patternRel);
  const dir = path.dirname(absPattern);
  const basePattern = path.basename(absPattern);

  if (!fs.existsSync(dir)) {
    throw new Error(`Artifact directory does not exist for pattern '${patternRel}'`);
  }

  const regex = wildcardToRegex(basePattern);
  const files = fs
    .readdirSync(dir)
    .filter((name) => regex.test(name))
    .map((name) => {
      const fullPath = path.join(dir, name);
      const stat = fs.statSync(fullPath);
      return { fullPath, mtime: stat.mtimeMs };
    })
    .sort((a, b) => b.mtime - a.mtime);

  if (files.length === 0) {
    throw new Error(`No artifact found for pattern '${patternRel}'`);
  }

  return files[0].fullPath;
}

function wildcardToRegex(pattern) {
  const escaped = pattern.replace(/[.+^${}()|[\]\\]/g, "\\$&");
  return new RegExp(`^${escaped.replace(/\*/g, ".*")}$`);
}

function getWorkDirForTest(options, runDir, testId) {
  if (options.usePersistentCache) {
    return path.join(options.persistentCacheRoot, testId);
  }
  return path.join(runDir, testId);
}

function ensureDir(dirPath) {
  fs.mkdirSync(dirPath, { recursive: true });
}

function buildSmokeInvocation(test, artifactPath, workDir, matrixTimeouts, executionMode) {
  const runnerScript = RUNNER_SCRIPT_BY_PLATFORM[test.platform];
  if (!runnerScript) {
    throw new Error(`Unsupported platform '${test.platform}' for test '${test.id}'`);
  }

  const toPath = executionMode === "docker"
    ? toContainerPath
    : (value) => path.resolve(value);

  const args = [
    "--minecraft-version",
    String(test.minecraftVersion),
    "--work-dir",
    toPath(workDir),
    "--boot-timeout",
    String(matrixTimeouts.bootSeconds),
    "--command-timeout",
    String(matrixTimeouts.commandSeconds)
  ];

  const artifactArg = toPath(artifactPath);
  if (test.platform === "paper") {
    args.push("--plugin-jar", artifactArg);
    if (test.paperBuild) args.push("--paper-build", String(test.paperBuild));
  } else if (test.platform === "fabric") {
    args.push("--mod-jar", artifactArg);
    if (test.loaderVersion) args.push("--loader-version", String(test.loaderVersion));
    if (test.installerVersion) args.push("--installer-version", String(test.installerVersion));
  } else if (test.platform === "forge") {
    if (!test.forgeVersion) throw new Error(`Test '${test.id}' missing forgeVersion`);
    args.push("--forge-version", String(test.forgeVersion), "--mod-jar", artifactArg);
  } else if (test.platform === "neoforge") {
    if (!test.neoforgeVersion) throw new Error(`Test '${test.id}' missing neoforgeVersion`);
    args.push("--neoforge-version", String(test.neoforgeVersion), "--mod-jar", artifactArg);
  }

  return { runnerScript, args };
}

function runProcessStreaming({ command, args, cwd, env, onLine }) {
  return new Promise((resolve) => {
    const child = spawn(command, args, { cwd, env, shell: false });

    let stdoutBuffer = "";
    let stderrBuffer = "";

    const flush = (buffer, isErr) => {
      const parts = buffer.split(/\r?\n/);
      const remain = parts.pop();
      for (const part of parts) {
        if (part.length > 0) onLine(part, isErr);
      }
      return remain;
    };

    child.stdout.on("data", (chunk) => {
      stdoutBuffer += chunk.toString("utf8");
      stdoutBuffer = flush(stdoutBuffer, false);
    });

    child.stderr.on("data", (chunk) => {
      stderrBuffer += chunk.toString("utf8");
      stderrBuffer = flush(stderrBuffer, true);
    });

    child.on("close", (code) => {
      if (stdoutBuffer.trim()) onLine(stdoutBuffer.trim(), false);
      if (stderrBuffer.trim()) onLine(stderrBuffer.trim(), true);
      resolve(code == null ? 1 : code);
    });
  });
}

function runGradleBuild(tasks) {
  const gradleExe = process.platform === "win32"
    ? path.join(PLUGIN_ROOT, "gradlew.bat")
    : path.join(PLUGIN_ROOT, "gradlew");

  if (!fs.existsSync(gradleExe)) {
    throw new Error(`Gradle wrapper not found: ${gradleExe}`);
  }

  const args = [...tasks, "--no-daemon"];
  const result = spawnSync(gradleExe, args, {
    cwd: PLUGIN_ROOT,
    stdio: "inherit",
    shell: process.platform === "win32"
  });

  if (result.status !== 0) {
    throw new Error("Gradle build failed.");
  }
}

function ensureDockerImages(tests, options, emit) {
  if (!fs.existsSync(options.dockerfilePath)) {
    throw new Error(`Dockerfile not found: ${options.dockerfilePath}`);
  }

  const javaMajors = Array.from(new Set(tests.map((t) => Number(t.javaMajor)))).sort((a, b) => a - b);
  for (const major of javaMajors) {
    const imageName = `${options.dockerImagePrefix}-java${major}`;
    let shouldBuild = options.rebuildDockerImages;

    if (!shouldBuild) {
      const inspect = runCommandSync("docker", ["image", "inspect", imageName], {
        stdio: "ignore",
        shell: false,
        timeoutMs: 15000
      });
      shouldBuild = inspect.status !== 0;

      if (!shouldBuild) {
        const nodeCheck = runCommandSync("docker", ["run", "--rm", imageName, "node", "--version"], {
          stdio: "ignore",
          shell: false,
          timeoutMs: 30000
        });
        shouldBuild = nodeCheck.status !== 0;
      }
    }

    if (!shouldBuild) continue;

    emit("docker.image.build.started", { image: imageName, javaMajor: major });
    const build = runCommandSync(
      "docker",
      ["build", "--build-arg", `JAVA_MAJOR=${major}`, "-f", options.dockerfilePath, "-t", imageName, REPO_ROOT],
      {
        stdio: "inherit",
        shell: false
      }
    );
    if (build.status !== 0) {
      throw new Error(`Failed to build Docker executor image '${imageName}'.`);
    }
    emit("docker.image.build.completed", { image: imageName, javaMajor: major });
  }
}

async function runSingleTestLocal({ test, options, matrixTimeouts, runDir, emit }) {
  const testId = String(test.id);
  const label = String(test.label);
  const outputDir = path.join(runDir, testId);
  const workDir = getWorkDirForTest(options, runDir, testId);
  const logPath = path.join(outputDir, "runner.log");
  ensureDir(outputDir);
  ensureDir(workDir);

  const start = Date.now();
  emit("test.started", { testId, label });
  emit("test.step", { testId, step: "work-dir", value: workDir });

  const runEnv = getJavaEnvForVersion(Number(test.javaMajor));
  let result = "PASS";
  let message = "";

  try {
    const artifactPath = findLatestArtifact(String(test.artifactPattern));
    emit("test.step", { testId, step: "artifact", value: artifactPath });
    const invocation = buildSmokeInvocation(test, artifactPath, workDir, matrixTimeouts, "local");

    if (fs.existsSync(logPath)) fs.rmSync(logPath, { force: true });
    const exitCode = await runProcessStreaming({
      command: process.execPath,
      args: [invocation.runnerScript, ...invocation.args],
      cwd: REPO_ROOT,
      env: runEnv,
      onLine: (line, isErr) => {
        const entry = isErr ? `[stderr] ${line}` : line;
        fs.appendFileSync(logPath, `${entry}\n`);
        process.stdout.write(`[${testId}] ${entry}\n`);
        emit("test.log", { testId, line: entry });
      }
    });

    if (exitCode !== 0) {
      throw new Error(`Smoke test script exited with code ${exitCode}`);
    }

    emit("test.passed", { testId });
  } catch (error) {
    result = "FAIL";
    message = error.message;
    process.stdout.write(`[${testId}] FAIL: ${message}\n`);
    emit("test.failed", { testId, message });
  }

  return {
    id: testId,
    label,
    platform: String(test.platform),
    minecraftVersion: String(test.minecraftVersion),
    javaMajor: Number(test.javaMajor),
    result,
    durationSeconds: Number(((Date.now() - start) / 1000).toFixed(2)),
    message,
    workDir,
    logPath
  };
}

async function runTestsDocker({ tests, options, matrixTimeouts, runDir, emit }) {
  const queue = [...tests];
  const active = new Map();
  const results = [];

  const spawnDockerTest = (test) => {
    const testId = String(test.id);
    const label = String(test.label);
    const outputDir = path.join(runDir, testId);
    const workDir = getWorkDirForTest(options, runDir, testId);
    const logPath = path.join(outputDir, "runner.log");
    ensureDir(outputDir);
    ensureDir(workDir);

    emit("test.started", { testId, label });
    emit("test.step", { testId, step: "work-dir", value: workDir });

    const artifactPath = findLatestArtifact(String(test.artifactPattern));
    emit("test.step", { testId, step: "artifact", value: artifactPath });

    const invocation = buildSmokeInvocation(test, artifactPath, workDir, matrixTimeouts, "docker");
    const imageName = `${options.dockerImagePrefix}-java${Number(test.javaMajor)}`;
    emit("test.step", { testId, step: "executor", value: imageName });

    const safeRun = options.runId.toLowerCase().replace(/[^a-z0-9_.-]/g, "-");
    const safeTest = testId.toLowerCase().replace(/[^a-z0-9_.-]/g, "-");
    const containerName = `sp-${safeRun}-${safeTest}`.slice(0, 63);

    const dockerArgs = [
      "run",
      "--rm",
      "--name",
      containerName,
      "-v",
      `${REPO_ROOT}:/workspace`,
      "-w",
      "/workspace",
      imageName,
      "node",
      toContainerPath(invocation.runnerScript),
      ...invocation.args
    ];

    const start = Date.now();
    const child = spawn("docker", dockerArgs, { cwd: REPO_ROOT, shell: false });
    let stdoutBuffer = "";
    let stderrBuffer = "";

    const appendLog = (line) => {
      fs.appendFileSync(logPath, `${line}\n`);
      process.stdout.write(`[${testId}] ${line}\n`);
      emit("test.log", { testId, line });
    };

    const flush = (buffer, isErr) => {
      const parts = buffer.split(/\r?\n/);
      const remain = parts.pop();
      for (const part of parts) {
        if (part.length > 0) appendLog(isErr ? `[stderr] ${part}` : part);
      }
      return remain;
    };

    child.stdout.on("data", (chunk) => {
      stdoutBuffer += chunk.toString("utf8");
      stdoutBuffer = flush(stdoutBuffer, false);
    });

    child.stderr.on("data", (chunk) => {
      stderrBuffer += chunk.toString("utf8");
      stderrBuffer = flush(stderrBuffer, true);
    });

    active.set(testId, { test, label, workDir, logPath, start, child });

    child.on("close", (code) => {
      const entry = active.get(testId);
      if (!entry) return;

      if (stdoutBuffer.trim()) appendLog(stdoutBuffer.trim());
      if (stderrBuffer.trim()) appendLog(`[stderr] ${stderrBuffer.trim()}`);

      const success = code === 0;
      const message = success ? "" : `Smoke test script exited with code ${code}`;
      if (success) {
        emit("test.passed", { testId });
      } else {
        emit("test.failed", { testId, message });
      }

      results.push({
        id: testId,
        label,
        platform: String(test.platform),
        minecraftVersion: String(test.minecraftVersion),
        javaMajor: Number(test.javaMajor),
        result: success ? "PASS" : "FAIL",
        durationSeconds: Number(((Date.now() - start) / 1000).toFixed(2)),
        message,
        workDir,
        logPath
      });

      active.delete(testId);
    });
  };

  while (queue.length > 0 || active.size > 0) {
    while (queue.length > 0 && active.size < options.maxParallel) {
      const test = queue.shift();
      try {
        spawnDockerTest(test);
      } catch (error) {
        const testId = String(test.id);
        const outputDir = path.join(runDir, testId);
        const workDir = getWorkDirForTest(options, runDir, testId);
        ensureDir(outputDir);
        const logPath = path.join(outputDir, "runner.log");
        const message = error.message;
        emit("test.failed", { testId, message });
        results.push({
          id: testId,
          label: String(test.label),
          platform: String(test.platform),
          minecraftVersion: String(test.minecraftVersion),
          javaMajor: Number(test.javaMajor),
          result: "FAIL",
          durationSeconds: 0,
          message,
          workDir,
          logPath
        });
      }
    }

    if (active.size > 0) {
      await new Promise((resolve) => setTimeout(resolve, 300));
    }
  }

  return results;
}

async function main() {
  const options = parseArgs(process.argv.slice(2));
  if (options.help) {
    usage();
    return;
  }

  const emit = (type, data) => emitEvent(options.emitEvents, options.runId, type, data);
  const startedAt = new Date().toISOString();

  try {
    if (!fs.existsSync(options.matrixPath)) {
      throw new Error(`Matrix file not found: ${options.matrixPath}`);
    }

    const matrix = JSON.parse(fs.readFileSync(options.matrixPath, "utf8"));
    const tests = Array.isArray(matrix.tests) ? matrix.tests : [];
    const profiles = matrix.profiles || {};

    if (!Array.isArray(profiles[options.profile]) || profiles[options.profile].length === 0) {
      throw new Error(`Profile '${options.profile}' not found or empty in matrix.`);
    }

    const testMap = new Map(tests.map((test) => [String(test.id), test]));
    const only = options.only.length > 0 ? options.only : profiles[options.profile];

    for (const id of only) {
      if (!testMap.has(id)) {
        throw new Error(`Unknown test ID: ${id}`);
      }
    }

    let selected = only.map((id) => testMap.get(id));
    if (!(options.platforms.length === 1 && options.platforms[0] === "all")) {
      selected = selected.filter((test) => options.platforms.includes(String(test.platform)));
    }

    if (selected.length === 0) {
      throw new Error("No tests selected. Check --profile, --platforms, and --only.");
    }

    if (options.executionMode === "local" && options.maxParallel > 1) {
      process.stdout.write("Local mode does not support parallel execution; using max parallel = 1.\n");
      options.maxParallel = 1;
    }

    if (options.usePersistentCache) {
      ensureDir(options.persistentCacheRoot);
    }

    const runDir = path.join(options.runRoot, options.runId);
    ensureDir(runDir);

    emit("run.started", {
      profile: options.profile,
      selectedTestIds: selected.map((test) => String(test.id)),
      runDir,
      executionMode: options.executionMode,
      maxParallel: options.maxParallel,
      usePersistentCache: options.usePersistentCache,
      persistentCacheRoot: options.persistentCacheRoot
    });

    if (options.executionMode === "docker") {
      assertCommand("docker");
      emit("docker.preflight.started", { timeoutSeconds: options.dockerStartupTimeoutSeconds });
      await assertDockerEngineAvailable(options.dockerStartupTimeoutSeconds);
      emit("docker.preflight.completed", {});
    }

    if (!options.skipBuild) {
      const tasks = Array.isArray(matrix.build && matrix.build.tasks) ? matrix.build.tasks : [];
      if (tasks.length === 0) {
        throw new Error("Matrix is missing build.tasks entries.");
      }
      emit("build.started", { tasks });
      runGradleBuild(tasks);
      emit("build.completed", {});
    }

    const matrixTimeouts = matrix.timeouts || { bootSeconds: 900, commandSeconds: 120 };
    let results;

    if (options.executionMode === "docker") {
      ensureDockerImages(selected, options, emit);
      results = await runTestsDocker({ tests: selected, options, matrixTimeouts, runDir, emit });
    } else {
      results = [];
      for (const test of selected) {
        // eslint-disable-next-line no-await-in-loop
        const result = await runSingleTestLocal({ test, options, matrixTimeouts, runDir, emit });
        results.push(result);
      }
    }

    const failed = results.filter((entry) => entry.result === "FAIL");
    const passed = results.filter((entry) => entry.result === "PASS");

    const summary = {
      runId: options.runId,
      profile: options.profile,
      executionMode: options.executionMode,
      maxParallel: options.maxParallel,
      usePersistentCache: options.usePersistentCache,
      persistentCacheRoot: options.persistentCacheRoot,
      matrixPath: options.matrixPath,
      startedAt,
      endedAt: new Date().toISOString(),
      tests: results
    };

    const summaryPath = path.join(runDir, "summary.json");
    fs.writeFileSync(summaryPath, JSON.stringify(summary, null, 2));

    emit("run.completed", {
      runDir,
      summaryPath,
      executionMode: options.executionMode,
      maxParallel: options.maxParallel,
      usePersistentCache: options.usePersistentCache,
      persistentCacheRoot: options.persistentCacheRoot,
      passedCount: passed.length,
      failedCount: failed.length,
      tests: results
    });

    process.stdout.write("\nRun summary\n");
    for (const entry of results) {
      process.stdout.write(`${entry.id}  ${entry.result}  ${entry.durationSeconds}s  ${entry.message || ""}\n`);
    }

    process.exit(failed.length > 0 ? 1 : 0);
  } catch (error) {
    process.stdout.write(`Run failed: ${error.message}\n`);
    emit("run.failed", { message: error.message });
    process.exit(1);
  }
}

main();
