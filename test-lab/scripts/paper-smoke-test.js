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
  parseTimeout
} from "./smoke-common.js";

const usageText = [
  "Usage:",
  "  node test-lab/scripts/paper-smoke-test.js --minecraft-version <mc> --plugin-jar <path> [options]",
  "",
  "Options:",
  "  --paper-build <build>       Specific Paper build (default: latest for version)",
  "  --work-dir <path>           Reuse a working directory (default: temporary dir)",
  "  --boot-timeout <seconds>    Max seconds to wait for server startup (default: 600)",
  "  --command-timeout <seconds> Max seconds to wait for command output (default: 120)",
  ""
].join("\n");

async function resolveLatestPaperBuild(mcVersion) {
  const url = `https://api.papermc.io/v2/projects/paper/versions/${mcVersion}/builds`;
  const payload = await fetchJson(url);
  const builds = Array.isArray(payload.builds) ? payload.builds : [];
  const defaults = builds.filter((entry) => entry.channel === "default").map((entry) => Number(entry.build));
  const candidates = defaults.length > 0 ? defaults : builds.map((entry) => Number(entry.build));
  const valid = candidates.filter((value) => Number.isFinite(value));
  if (valid.length === 0) {
    throw new Error(`No Paper builds found for Minecraft ${mcVersion}`);
  }
  return String(Math.max(...valid));
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  if (args.help) {
    process.stdout.write(usageText);
    return;
  }

  ensureRequired(args, ["minecraft-version", "plugin-jar"], usageText);
  const minecraftVersion = args["minecraft-version"];
  const pluginJar = path.resolve(args["plugin-jar"]);
  ensureFileExists(pluginJar, "Plugin jar");

  const bootTimeout = parseTimeout(args["boot-timeout"], 600);
  const commandTimeout = parseTimeout(args["command-timeout"], 120);

  const hasWorkDir = Boolean(args["work-dir"]);
  const workDir = hasWorkDir
    ? path.resolve(args["work-dir"])
    : fs.mkdtempSync(path.join(os.tmpdir(), "serverspulse-paper-"));
  if (hasWorkDir) {
    ensureDir(workDir);
  }

  const serverDir = path.join(workDir, `server-paper-${minecraftVersion}`);
  const logPath = path.join(serverDir, "server.log");

  let server;
  try {
    let paperBuild = args["paper-build"];
    if (!paperBuild) {
      process.stdout.write(`Resolving latest Paper build for ${minecraftVersion}\n`);
      paperBuild = await resolveLatestPaperBuild(minecraftVersion);
    }

    process.stdout.write(`Preparing Paper smoke test for MC ${minecraftVersion} / build ${paperBuild}\n`);

    ensureDir(path.join(serverDir, "plugins", "serverspulse"));
    fs.copyFileSync(pluginJar, path.join(serverDir, "plugins", "serverspulse.jar"));
    writeServersPulseConfig(path.join(serverDir, "plugins", "serverspulse"));
    writeEula(serverDir);
    const serverPort = randomServerPort();
    configureServerPort(serverDir, serverPort);

    const paperJar = path.join(serverDir, `paper-${minecraftVersion}-${paperBuild}.jar`);
    const paperUrl = `https://api.papermc.io/v2/projects/paper/versions/${minecraftVersion}/builds/${paperBuild}/downloads/paper-${minecraftVersion}-${paperBuild}.jar`;

    if (fs.existsSync(paperJar)) {
      process.stdout.write(`Reusing cached Paper server jar: ${paperJar}\n`);
    } else {
      process.stdout.write(`Downloading Paper server: ${paperUrl}\n`);
      await downloadFile(paperUrl, paperJar);
    }

    process.stdout.write(`Starting server on port ${serverPort}\n`);
    server = new LoggedServer({ serverDir, logPath });
    await server.start("java", ["-Djline.terminal=dumb", "-Dcom.mojang.eula.agree=true", "-jar", paperJar, "nogui"]);

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

    process.stdout.write(`Paper smoke test passed for MC ${minecraftVersion} / build ${paperBuild}\n`);
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
