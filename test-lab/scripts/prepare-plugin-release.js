#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const __filename = fileURLToPath(import.meta.url);
const scriptDir = path.dirname(__filename);
const testLabRoot = path.resolve(scriptDir, "..");
const repoRoot = path.resolve(testLabRoot, "..");
const pluginRoot = repoRoot;

function parseArgs(argv) {
  const options = {
    version: null,
    profile: "release",
    executionMode: "docker",
    maxParallel: 4,
    usePersistentCache: true,
    skipBuild: false,
    skipTests: false,
    outputRoot: path.join(pluginRoot, "build", "releases"),
    matrixPath: path.join(testLabRoot, "testing", "test-matrix.json"),
    runnerPath: path.join(testLabRoot, "scripts", "run-test-matrix.js")
  };

  const take = (index, flag) => {
    const value = argv[index + 1];
    if (!value || value.startsWith("--")) {
      throw new Error(`Missing value for ${flag}`);
    }
    return value;
  };

  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    switch (arg) {
      case "--version":
        options.version = take(i, arg);
        i += 1;
        break;
      case "--profile":
        options.profile = take(i, arg);
        i += 1;
        break;
      case "--execution-mode":
        options.executionMode = take(i, arg);
        i += 1;
        break;
      case "--max-parallel":
        options.maxParallel = Number(take(i, arg));
        i += 1;
        break;
      case "--use-persistent-cache":
        options.usePersistentCache = parseBoolean(take(i, arg));
        i += 1;
        break;
      case "--output-root":
        options.outputRoot = resolveMaybeRelative(take(i, arg));
        i += 1;
        break;
      case "--matrix-path":
        options.matrixPath = resolveMaybeRelative(take(i, arg));
        i += 1;
        break;
      case "--skip-build":
        options.skipBuild = true;
        break;
      case "--skip-tests":
        options.skipTests = true;
        break;
      case "--help":
      case "-h":
        printHelp();
        process.exit(0);
        break;
      default:
        throw new Error(`Unknown argument: ${arg}`);
    }
  }

  if (!Number.isFinite(options.maxParallel) || options.maxParallel < 1) {
    options.maxParallel = 1;
  }

  if (options.executionMode !== "docker" && options.executionMode !== "local") {
    throw new Error("--execution-mode must be 'docker' or 'local'");
  }

  return options;
}

function printHelp() {
  process.stdout.write(
    [
      "Usage:",
      "  node test-lab/scripts/prepare-plugin-release.js [options]",
      "",
      "Options:",
      "  --version <x.y.z>              Release version (defaults from gradle.properties)",
      "  --profile <name>               Matrix profile to validate (default: release)",
      "  --execution-mode <docker|local> Validation execution mode (default: docker)",
      "  --max-parallel <n>             Matrix parallelism (default: 4)",
      "  --use-persistent-cache <bool>  Keep per-version cached servers (default: true)",
      "  --skip-build                   Skip Gradle artifact build",
      "  --skip-tests                   Skip release profile validation",
      "  --output-root <path>           Output root (default: build/releases)",
      "  --matrix-path <path>           Matrix path override",
      "  --help                         Show this help"
    ].join("\n") + "\n"
  );
}

function parseBoolean(value) {
  const normalized = String(value).trim().toLowerCase();
  if (normalized === "true" || normalized === "1") return true;
  if (normalized === "false" || normalized === "0") return false;
  throw new Error(`Invalid boolean value '${value}'`);
}

function resolveMaybeRelative(inputPath) {
  return path.isAbsolute(inputPath) ? inputPath : path.resolve(process.cwd(), inputPath);
}

function runCommand(command, args, workdir, options = {}) {
  const useShell = options.shell === true;
  const executable = useShell && process.platform === "win32" && command.includes(" ") ? `"${command}"` : command;
  const result = spawnSync(executable, args, {
    cwd: workdir,
    stdio: "inherit",
    shell: useShell
  });

  if (result.status !== 0) {
    throw new Error(`Command failed: ${command} ${args.join(" ")}`);
  }
}

function getVersionFromGradleProperties() {
  const filePath = path.join(pluginRoot, "gradle.properties");
  const content = fs.readFileSync(filePath, "utf8");
  const line = content
    .split(/\r?\n/)
    .find((entry) => entry.trim().startsWith("version="));

  if (!line) {
    throw new Error("version entry was not found in gradle.properties");
  }

  return line.split("=").slice(1).join("=").trim();
}

function loadMatrix(matrixPath) {
  return JSON.parse(fs.readFileSync(matrixPath, "utf8"));
}

function sha256(filePath) {
  const hash = crypto.createHash("sha256");
  hash.update(fs.readFileSync(filePath));
  return hash.digest("hex");
}

function main() {
  const options = parseArgs(process.argv.slice(2));
  const resolvedVersion = options.version || getVersionFromGradleProperties();

  if (resolvedVersion.includes("SNAPSHOT")) {
    throw new Error(
      `Refusing release prep for snapshot version '${resolvedVersion}'. Update gradle.properties first.`
    );
  }

  const matrix = loadMatrix(options.matrixPath);
  const profileIds = matrix.profiles?.[options.profile];
  if (!Array.isArray(profileIds) || profileIds.length === 0) {
    throw new Error(`Matrix profile '${options.profile}' is missing or empty.`);
  }

  process.stdout.write(`Preparing plugin release ${resolvedVersion}\n`);

  if (!options.skipBuild) {
    process.stdout.write("Building consolidated platform artifacts...\n");
    const gradleExe = process.platform === "win32" ? ".\\gradlew.bat" : "./gradlew";
    runCommand(gradleExe, ["collectPlatformArtifacts", "--no-daemon"], pluginRoot, {
      shell: process.platform === "win32"
    });
  }

  if (!options.skipTests) {
    process.stdout.write(`Running '${options.profile}' validation profile...\n`);
    const runnerArgs = [
      options.runnerPath,
      "--matrix-path",
      options.matrixPath,
      "--profile",
      options.profile,
      "--execution-mode",
      options.executionMode,
      "--max-parallel",
      String(options.maxParallel),
      "--use-persistent-cache",
      String(options.usePersistentCache),
      "--skip-build"
    ];
    runCommand(process.execPath, runnerArgs, repoRoot);
  }

  const artifactsDir = path.join(pluginRoot, "build", "artifacts");
  if (!fs.existsSync(artifactsDir)) {
    throw new Error(`Expected artifacts directory does not exist: ${artifactsDir}`);
  }

  const artifactFiles = fs
    .readdirSync(artifactsDir)
    .filter((entry) => entry.endsWith(".jar"))
    .sort();

  if (artifactFiles.length === 0) {
    throw new Error(`No JAR artifacts were found in ${artifactsDir}`);
  }

  const mismatchedVersionArtifacts = artifactFiles.filter((fileName) => !fileName.includes(`-${resolvedVersion}.jar`));
  if (mismatchedVersionArtifacts.length > 0) {
    throw new Error(
      `Artifact version mismatch for ${resolvedVersion}: ${mismatchedVersionArtifacts.join(", ")}. Rebuild artifacts before packaging.`
    );
  }

  const releaseDir = path.join(options.outputRoot, resolvedVersion);
  fs.rmSync(releaseDir, { recursive: true, force: true });
  fs.mkdirSync(releaseDir, { recursive: true });

  const manifest = {
    version: resolvedVersion,
    generatedAt: new Date().toISOString(),
    profileValidated: options.skipTests ? null : options.profile,
    executionMode: options.skipTests ? null : options.executionMode,
    files: []
  };

  const sums = [];

  for (const fileName of artifactFiles) {
    const sourcePath = path.join(artifactsDir, fileName);
    const destinationPath = path.join(releaseDir, fileName);
    fs.copyFileSync(sourcePath, destinationPath);

    const stat = fs.statSync(destinationPath);
    const digest = sha256(destinationPath);

    manifest.files.push({
      fileName,
      sizeBytes: stat.size,
      sha256: digest
    });
    sums.push(`${digest}  ${fileName}`);
  }

  fs.writeFileSync(path.join(releaseDir, "manifest.json"), JSON.stringify(manifest, null, 2) + "\n", "utf8");
  fs.writeFileSync(path.join(releaseDir, "SHA256SUMS.txt"), sums.join("\n") + "\n", "utf8");

  const testedTargets = profileIds
    .map((id) => matrix.tests.find((test) => test.id === id))
    .filter(Boolean)
    .map((test) => `- ${test.label} (${test.platform} ${test.minecraftVersion})`)
    .join("\n");

  const notes = [
    `# ServersPulse Plugin Release ${resolvedVersion}`,
    "",
    "## Included Artifacts",
    ...manifest.files.map((file) => `- \`${file.fileName}\` (${file.sizeBytes} bytes)`),
    "",
    `## Validation Profile: ${options.skipTests ? "skipped" : options.profile}`,
    options.skipTests ? "- Validation was skipped for this preparation run." : testedTargets,
    "",
    "## Publish Targets",
    "- Spigot/Paper",
    "- Fabric",
    "- Forge",
    "- NeoForge",
    ""
  ].join("\n");
  fs.writeFileSync(path.join(releaseDir, "RELEASE_NOTES_TEMPLATE.md"), notes, "utf8");

  process.stdout.write(`Release artifacts prepared at ${releaseDir}\n`);
}

try {
  main();
} catch (error) {
  process.stderr.write(`${error.message}\n`);
  process.exit(1);
}
