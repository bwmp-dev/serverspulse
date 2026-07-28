#!/usr/bin/env node

import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import {
  LoggedServer,
  parseArgs,
  ensureRequired,
  ensureFileExists,
  ensureDir,
  writeServersPulseConfig,
  writeEula,
  configureServerPort,
  randomServerPort,
  fetchJson,
  downloadFile,
  runCommand,
  resolveBashExecutable,
  parseTimeout
} from "./smoke-common.js";

const usageText = [
  "Usage:",
  "  node test-lab/scripts/fabric-smoke-test.js --minecraft-version <mc> --mod-jar <path> [options]",
  "",
  "Options:",
  "  --loader-version <version>   Fabric Loader version (default: latest stable for MC)",
  "  --installer-version <ver>    Fabric Installer version (default: latest stable)",
  "  --work-dir <path>            Reuse a working directory (default: temporary dir)",
  "  --boot-timeout <seconds>     Max seconds to wait for server startup (default: 600)",
  "  --command-timeout <seconds>  Max seconds to wait for command output (default: 120)",
  ""
].join("\n");

async function resolveLatestLoader(mcVersion) {
  const payload = await fetchJson(`https://meta.fabricmc.net/v2/versions/loader/${mcVersion}`);
  if (!Array.isArray(payload) || payload.length === 0) {
    throw new Error(`No Fabric loader versions found for Minecraft ${mcVersion}`);
  }

  const stable = payload.find((entry) => entry?.loader?.stable);
  const chosen = stable || payload[0];
  if (!chosen?.loader?.version) {
    throw new Error(`Could not resolve Fabric loader for Minecraft ${mcVersion}`);
  }

  return String(chosen.loader.version);
}

async function resolveLatestInstaller() {
  const payload = await fetchJson("https://meta.fabricmc.net/v2/versions/installer");
  if (!Array.isArray(payload) || payload.length === 0) {
    throw new Error("No Fabric installer versions found.");
  }

  const stable = payload.find((entry) => entry?.stable);
  const chosen = stable || payload[0];
  if (!chosen?.version) {
    throw new Error("Could not resolve Fabric installer version.");
  }

  return String(chosen.version);
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  if (args.help) {
    process.stdout.write(usageText);
    return;
  }

  ensureRequired(args, ["minecraft-version", "mod-jar"], usageText);

  const minecraftVersion = args["minecraft-version"];
  const modJar = path.resolve(args["mod-jar"]);
  ensureFileExists(modJar, "Mod jar");

  const bootTimeout = parseTimeout(args["boot-timeout"], 600);
  const commandTimeout = parseTimeout(args["command-timeout"], 120);

  const hasWorkDir = Boolean(args["work-dir"]);
  const workDir = hasWorkDir
    ? path.resolve(args["work-dir"])
    : fs.mkdtempSync(path.join(os.tmpdir(), "serverspulse-fabric-"));
  if (hasWorkDir) {
    ensureDir(workDir);
  }

  const serverDir = path.join(workDir, `server-fabric-${minecraftVersion}`);
  const logPath = path.join(serverDir, "server.log");

  let server;
  try {
    let loaderVersion = args["loader-version"];
    if (!loaderVersion) {
      process.stdout.write(`Resolving latest Fabric loader for MC ${minecraftVersion}\n`);
      loaderVersion = await resolveLatestLoader(minecraftVersion);
    }

    let installerVersion = args["installer-version"];
    if (!installerVersion) {
      process.stdout.write("Resolving latest Fabric installer version\n");
      installerVersion = await resolveLatestInstaller();
    }

    process.stdout.write(`Preparing Fabric smoke test for MC ${minecraftVersion} / loader ${loaderVersion}\n`);

    ensureDir(path.join(serverDir, "mods"));
    ensureDir(path.join(serverDir, "config", "serverspulse"));
    fs.copyFileSync(modJar, path.join(serverDir, "mods", path.basename(modJar)));
    writeServersPulseConfig(path.join(serverDir, "config", "serverspulse"));
    writeEula(serverDir);
    const serverPort = randomServerPort();
    configureServerPort(serverDir, serverPort);

    const installerJar = path.join(serverDir, "fabric-installer.jar");
    const installerUrl = `https://maven.fabricmc.net/net/fabricmc/fabric-installer/${installerVersion}/fabric-installer-${installerVersion}.jar`;
    const markerPath = path.join(serverDir, ".fabric-installed");
    const installKey = `${minecraftVersion}|${loaderVersion}|${installerVersion}`;
    const launchJar = path.join(serverDir, "fabric-server-launch.jar");
    const runScript = path.join(serverDir, "run.sh");

    let shouldInstall = true;
    if (fs.existsSync(markerPath)) {
      const existingKey = fs.readFileSync(markerPath, "utf8").trim();
      if (existingKey === installKey && (fs.existsSync(runScript) || fs.existsSync(launchJar))) {
        shouldInstall = false;
      }
    }

    if (shouldInstall) {
      process.stdout.write(`Downloading Fabric installer: ${installerUrl}\n`);
      await downloadFile(installerUrl, installerJar);

      process.stdout.write("Installing Fabric server\n");
      await runCommand("java", [
        "-jar",
        installerJar,
        "server",
        "-mcversion",
        minecraftVersion,
        "-loader",
        loaderVersion,
        "-downloadMinecraft"
      ], { cwd: serverDir });

      fs.writeFileSync(markerPath, `${installKey}\n`, "utf8");
    } else {
      process.stdout.write(`Reusing cached Fabric server install for MC ${minecraftVersion} / loader ${loaderVersion}\n`);
    }

    let command = "java";
    let commandArgs;

    if (fs.existsSync(launchJar)) {
      commandArgs = ["-Djline.terminal=dumb", "-Dcom.mojang.eula.agree=true", "-jar", launchJar, "nogui"];
    } else if (fs.existsSync(runScript)) {
      command = resolveBashExecutable();
      commandArgs = [runScript, "nogui"];
    } else {
      throw new Error(`Could not determine Fabric launch command in ${serverDir}`);
    }

    process.stdout.write(`Starting server on port ${serverPort}\n`);
    server = new LoggedServer({ serverDir, logPath });
    await server.start(command, commandArgs);

    await server.waitForLogPattern("Done (", bootTimeout, "Timed out waiting for server startup.");

    process.stdout.write("Running command: serverspulse status\n");
    server.send("serverspulse status");
    await server.waitForLogPattern(
      "[ServersPulse] Agent:",
      commandTimeout,
      "Timed out waiting for /serverspulse status output."
    );

    process.stdout.write("Running command: serverspulse reload\n");
    server.send("serverspulse reload");
    await server.waitForLogPattern(
      "[ServersPulse] Configuration reloaded successfully.",
      commandTimeout,
      "Timed out waiting for /serverspulse reload output."
    );

    process.stdout.write("Command checks passed, stopping server\n");
    await server.stop();

    if (server.child && server.child.exitCode !== 0) {
      throw new Error(`Server exited with non-zero status (${server.child.exitCode}).\n${server.getTail()}`);
    }

    process.stdout.write(`Fabric smoke test passed for MC ${minecraftVersion} / loader ${loaderVersion}\n`);
  } catch (error) {
    process.stderr.write(`${error.message}\n`);
    process.exitCode = 1;
  } finally {
    if (server) {
      await server.kill();
    }

    if (!hasWorkDir) {
      fs.rmSync(workDir, { recursive: true, force: true });
    }
  }
}

main();
