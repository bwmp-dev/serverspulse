import fs from "node:fs";
import path from "node:path";
import { spawn } from "node:child_process";

export function parseArgs(argv) {
  const args = {};
  for (let i = 0; i < argv.length; i += 1) {
    const key = argv[i];
    if (!key.startsWith("--")) {
      throw new Error(`Unknown argument: ${key}`);
    }

    if (key === "--help" || key === "-h") {
      args.help = true;
      continue;
    }

    const value = argv[i + 1];
    if (!value || value.startsWith("--")) {
      throw new Error(`Missing value for ${key}`);
    }

    args[key.slice(2)] = value;
    i += 1;
  }

  return args;
}

export function ensureRequired(args, keys, usageText) {
  for (const key of keys) {
    if (!args[key]) {
      throw new Error(`Missing required argument --${key}\n\n${usageText}`);
    }
  }
}

export function ensureDir(dirPath) {
  fs.mkdirSync(dirPath, { recursive: true });
}

export function ensureFileExists(filePath, label) {
  if (!fs.existsSync(filePath) || !fs.statSync(filePath).isFile()) {
    throw new Error(`${label} not found: ${filePath}`);
  }
}

export function writeServersPulseConfig(baseDir) {
  ensureDir(baseDir);
  fs.writeFileSync(
    path.join(baseDir, "config.yml"),
    [
      'api-key: "smoke-test"',
      'backend-url: "https://127.0.0.1.invalid"',
      "interval-seconds: 30",
      "debug: false",
      ""
    ].join("\n"),
    "utf8"
  );
}

export function writeEula(serverDir) {
  fs.writeFileSync(path.join(serverDir, "eula.txt"), "eula=true\n", "utf8");
}

export function randomServerPort() {
  return 20000 + Math.floor(Math.random() * 20000);
}

export function configureServerPort(serverDir, port) {
  const filePath = path.join(serverDir, "server.properties");
  let lines = [];

  if (fs.existsSync(filePath)) {
    lines = fs
      .readFileSync(filePath, "utf8")
      .split(/\r?\n/)
      .filter((line) => line.length > 0);
  }

  let replaced = false;
  lines = lines.map((line) => {
    if (line.startsWith("server-port=")) {
      replaced = true;
      return `server-port=${port}`;
    }
    return line;
  });

  if (!replaced) {
    lines.push(`server-port=${port}`);
  }

  fs.writeFileSync(filePath, `${lines.join("\n")}\n`, "utf8");
}

export async function fetchJson(url) {
  const response = await fetch(url);
  if (!response.ok) {
    throw new Error(`Request failed (${response.status}) for ${url}`);
  }
  return response.json();
}

export async function downloadFile(url, destinationPath) {
  const response = await fetch(url);
  if (!response.ok) {
    throw new Error(`Download failed (${response.status}) for ${url}`);
  }

  const arrayBuffer = await response.arrayBuffer();
  ensureDir(path.dirname(destinationPath));
  fs.writeFileSync(destinationPath, Buffer.from(arrayBuffer));
}

export async function runCommand(command, args, options = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, {
      cwd: options.cwd,
      env: options.env,
      stdio: options.stdio || "inherit",
      shell: false
    });

    child.on("error", reject);
    child.on("close", (code) => {
      if (code === 0) {
        resolve();
      } else {
        reject(new Error(`Command failed (${code}): ${command} ${args.join(" ")}`));
      }
    });
  });
}

export function resolveBashExecutable() {
  if (process.env.SERVERPULSE_BASH_EXE && fs.existsSync(process.env.SERVERPULSE_BASH_EXE)) {
    return process.env.SERVERPULSE_BASH_EXE;
  }

  const candidates = process.platform === "win32"
    ? [
        "C:\\Program Files\\Git\\bin\\bash.exe",
        "C:\\Program Files\\Git\\usr\\bin\\bash.exe",
        "C:\\Program Files (x86)\\Git\\bin\\bash.exe",
        "C:\\Program Files (x86)\\Git\\usr\\bin\\bash.exe"
      ]
    : ["/usr/bin/bash", "/bin/bash", "bash"];

  for (const candidate of candidates) {
    if (candidate === "bash") {
      return candidate;
    }
    if (fs.existsSync(candidate)) {
      return candidate;
    }
  }

  return "bash";
}

function tailLog(filePath, maxLines) {
  if (!fs.existsSync(filePath)) {
    return "";
  }

  const raw = fs.readFileSync(filePath, "utf8");
  const lines = raw.split(/\r?\n/).filter(Boolean);
  return lines.slice(Math.max(lines.length - maxLines, 0)).join("\n");
}

export class LoggedServer {
  constructor({ serverDir, logPath }) {
    this.serverDir = serverDir;
    this.logPath = logPath;
    this.child = null;
    this.logBuffer = "";
    this.logMaxBytes = 2 * 1024 * 1024;
    this.logFd = null;
  }

  async start(command, args, env = process.env) {
    ensureDir(path.dirname(this.logPath));
    this.logFd = fs.openSync(this.logPath, "w");

    this.child = spawn(command, args, {
      cwd: this.serverDir,
      env,
      shell: false,
      stdio: ["pipe", "pipe", "pipe"]
    });

    const onData = (chunk, isErr) => {
      const text = chunk.toString("utf8");
      fs.writeSync(this.logFd, isErr ? `[stderr] ${text}` : text);
      this.logBuffer += isErr ? `[stderr] ${text}` : text;
      if (this.logBuffer.length > this.logMaxBytes) {
        this.logBuffer = this.logBuffer.slice(this.logBuffer.length - this.logMaxBytes);
      }
    };

    this.child.stdout.on("data", (chunk) => onData(chunk, false));
    this.child.stderr.on("data", (chunk) => onData(chunk, true));

    this.child.on("close", () => {
      if (this.logFd != null) {
        fs.closeSync(this.logFd);
        this.logFd = null;
      }
    });
  }

  async waitForLogPattern(pattern, timeoutSeconds, timeoutMessage) {
    const deadline = Date.now() + timeoutSeconds * 1000;
    while (Date.now() < deadline) {
      if (this.logBuffer.includes(pattern)) {
        return;
      }

      if (!this.child || this.child.exitCode != null) {
        throw new Error(`Server exited before expected log output appeared.\n${this.getTail()}`);
      }

      await sleep(1000);
    }

    throw new Error(`${timeoutMessage}\n${this.getTail()}`);
  }

  send(command) {
    if (!this.child || !this.child.stdin) {
      throw new Error("Server process is not running.");
    }

    this.child.stdin.write(`${command}\n`);
  }

  async stop() {
    if (!this.child) {
      return;
    }

    if (this.child.exitCode == null) {
      this.send("stop");
    }

    await new Promise((resolve) => {
      const timer = setTimeout(() => {
        if (this.child && this.child.exitCode == null) {
          this.child.kill("SIGTERM");
        }
      }, 20000);

      this.child.on("close", () => {
        clearTimeout(timer);
        resolve();
      });
    });
  }

  async kill() {
    if (!this.child || this.child.exitCode != null) {
      return;
    }

    this.child.kill("SIGTERM");
    await sleep(1000);
    if (this.child.exitCode == null) {
      this.child.kill("SIGKILL");
    }
  }

  getTail() {
    return ["----- server.log (tail) -----", tailLog(this.logPath, 200), "-----------------------------"].join("\n");
  }
}

export function parseTimeout(value, fallback) {
  const parsed = Number(value);
  if (!Number.isFinite(parsed) || parsed < 1) {
    return fallback;
  }
  return Math.floor(parsed);
}

export function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
