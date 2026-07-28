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
  downloadFile,
  runCommand,
  resolveBashExecutable,
  parseTimeout
} from "./smoke-common.js";

const usageText = [
  "Usage:",
  "  node test-lab/scripts/forge-smoke-test.js --minecraft-version <mc> --forge-version <forge> --mod-jar <path> [options]",
  "",
  "Options:",
  "  --work-dir <path>          Reuse a working directory (default: temporary dir)",
  "  --boot-timeout <seconds>   Max seconds to wait for server startup (default: 600)",
  "  --command-timeout <sec>    Max seconds to wait for command output (default: 120)",
  ""
].join("\n");

async function main() {
  const args = parseArgs(process.argv.slice(2));
  if (args.help) {
    process.stdout.write(usageText);
    return;
  }

  ensureRequired(args, ["minecraft-version", "forge-version", "mod-jar"], usageText);

  const minecraftVersion = args["minecraft-version"];
  const forgeVersion = args["forge-version"];
  const modJar = path.resolve(args["mod-jar"]);
  ensureFileExists(modJar, "Mod jar");

  const bootTimeout = parseTimeout(args["boot-timeout"], 600);
  const commandTimeout = parseTimeout(args["command-timeout"], 120);

  const hasWorkDir = Boolean(args["work-dir"]);
  const workDir = hasWorkDir
    ? path.resolve(args["work-dir"])
    : fs.mkdtempSync(path.join(os.tmpdir(), "serverspulse-forge-"));
  if (hasWorkDir) {
    ensureDir(workDir);
  }

  const serverDir = path.join(workDir, `server-${minecraftVersion}-${forgeVersion}`);
  const logPath = path.join(serverDir, "server.log");

  let server;
  try {
    process.stdout.write(`Preparing Forge smoke test for MC ${minecraftVersion} / Forge ${forgeVersion}\n`);

    ensureDir(path.join(serverDir, "mods"));
    ensureDir(path.join(serverDir, "config", "serverspulse"));
    fs.copyFileSync(modJar, path.join(serverDir, "mods", path.basename(modJar)));
    writeServersPulseConfig(path.join(serverDir, "config", "serverspulse"));
    writeEula(serverDir);
    const serverPort = randomServerPort();
    configureServerPort(serverDir, serverPort);

    const installerUrl = `https://maven.minecraftforge.net/net/minecraftforge/forge/${minecraftVersion}-${forgeVersion}/forge-${minecraftVersion}-${forgeVersion}-installer.jar`;
    const installerJar = path.join(serverDir, "forge-installer.jar");
    const markerPath = path.join(serverDir, ".forge-installed");
    const installKey = `${minecraftVersion}|${forgeVersion}`;

    const forgeJar = path.join(serverDir, `forge-${minecraftVersion}-${forgeVersion}.jar`);
    const forgeUniversalJar = path.join(serverDir, `forge-${minecraftVersion}-${forgeVersion}-universal.jar`);
    const unixArgsPath = path.join(
      serverDir,
      "libraries",
      "net",
      "minecraftforge",
      "forge",
      `${minecraftVersion}-${forgeVersion}`,
      "unix_args.txt"
    );
    const runScript = path.join(serverDir, "run.sh");

    let shouldInstall = true;
    if (fs.existsSync(markerPath)) {
      const existingKey = fs.readFileSync(markerPath, "utf8").trim();
      if (
        existingKey === installKey &&
        (fs.existsSync(runScript) || fs.existsSync(forgeJar) || fs.existsSync(forgeUniversalJar) || fs.existsSync(unixArgsPath))
      ) {
        shouldInstall = false;
      }
    }

    if (shouldInstall) {
      process.stdout.write(`Downloading installer: ${installerUrl}\n`);
      await downloadFile(installerUrl, installerJar);

      process.stdout.write("Installing Forge server\n");
      await runCommand("java", ["-jar", installerJar, "--installServer"], { cwd: serverDir });
      fs.writeFileSync(markerPath, `${installKey}\n`, "utf8");
    } else {
      process.stdout.write(`Reusing cached Forge server install for MC ${minecraftVersion} / Forge ${forgeVersion}\n`);
    }

    let command = "java";
    let commandArgs;
    if (fs.existsSync(forgeJar)) {
      commandArgs = ["-Djline.terminal=dumb", "-Dcom.mojang.eula.agree=true", "-jar", forgeJar, "nogui"];
    } else if (fs.existsSync(forgeUniversalJar)) {
      commandArgs = ["-Djline.terminal=dumb", "-Dcom.mojang.eula.agree=true", "-jar", forgeUniversalJar, "nogui"];
    } else if (fs.existsSync(unixArgsPath)) {
      const relativeUnixArgs = path
        .relative(serverDir, unixArgsPath)
        .split(path.sep)
        .join("/");
      commandArgs = ["-Djline.terminal=dumb", "-Dcom.mojang.eula.agree=true", `@${relativeUnixArgs}`, "nogui"];
    } else if (fs.existsSync(runScript)) {
      command = resolveBashExecutable();
      commandArgs = [runScript, "nogui"];
    } else {
      throw new Error(`Could not determine Forge launch command in ${serverDir}`);
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

    process.stdout.write(`Forge smoke test passed for MC ${minecraftVersion} / Forge ${forgeVersion}\n`);
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
