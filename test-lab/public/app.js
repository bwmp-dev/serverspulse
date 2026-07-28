const state = {
  matrix: null,
  runs: [],
  selectedRunId: null,
  selectedTests: new Set(),
  platforms: new Set(["all"]),
  logByRunId: new Map(),
  activeRunId: null
};

const profileEl = document.getElementById("profile");
const platformsEl = document.getElementById("platforms");
const testsEl = document.getElementById("tests");
const startRunEl = document.getElementById("startRun");
const startMessageEl = document.getElementById("startMessage");
const selectionCountEl = document.getElementById("selectionCount");
const executionModeEl = document.getElementById("executionMode");
const maxParallelEl = document.getElementById("maxParallel");
const usePersistentCacheEl = document.getElementById("usePersistentCache");
const runsEl = document.getElementById("runs");
const runMetaEl = document.getElementById("runMeta");
const testTableEl = document.getElementById("testTable");
const logEl = document.getElementById("log");
const connectionEl = document.getElementById("connection");

executionModeEl.addEventListener("change", () => {
  syncExecutionControls();
});

document.getElementById("applyProfile").addEventListener("click", () => {
  applyProfileDefaults();
  renderTests();
});

document.getElementById("selectAll").addEventListener("click", () => {
  for (const test of visibleTests()) {
    state.selectedTests.add(test.id);
  }
  renderTests();
});

document.getElementById("clearAll").addEventListener("click", () => {
  for (const test of visibleTests()) {
    state.selectedTests.delete(test.id);
  }
  renderTests();
});

startRunEl.addEventListener("click", async () => {
  startMessageEl.textContent = "Starting...";
  const payload = {
    profile: profileEl.value,
    skipBuild: document.getElementById("skipBuild").checked,
    executionMode: executionModeEl.value,
    maxParallel: parsePositiveInt(maxParallelEl.value, executionModeEl.value === "docker" ? 4 : 1),
    usePersistentCache: usePersistentCacheEl.checked,
    rebuildDockerImages: document.getElementById("rebuildDockerImages").checked,
    platforms: Array.from(state.platforms),
    testIds: Array.from(state.selectedTests)
  };

  try {
    const response = await fetch("/api/runs", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload)
    });

    const json = await response.json();
    if (!response.ok) {
      throw new Error(json.error || "Failed to start run");
    }

    upsertRun(json.run);
    state.selectedRunId = json.run.id;
    startMessageEl.textContent = "Run started";
    renderRuns();
    renderSelectedRun();
  } catch (error) {
    startMessageEl.textContent = error.message;
  }
});

profileEl.addEventListener("change", () => {
  applyProfileDefaults();
  renderTests();
});

init().catch((error) => {
  connectionEl.textContent = error.message;
});

async function init() {
  const [matrixResponse, runsResponse] = await Promise.all([fetch("/api/matrix"), fetch("/api/runs")]);
  const matrixJson = await matrixResponse.json();
  const runsJson = await runsResponse.json();

  if (!matrixResponse.ok) {
    throw new Error(matrixJson.error || "Failed to load matrix");
  }

  state.matrix = matrixJson;
  state.runs = Array.isArray(runsJson.runs) ? runsJson.runs : [];
  state.activeRunId = findActiveRunId();

  renderProfileOptions();
  renderPlatformFilters();
  applyProfileDefaults();
  renderTests();
  renderRuns();
  renderSelectedRun();
  syncExecutionControls();
  connectSocket();
}

function renderProfileOptions() {
  const profiles = Object.keys(state.matrix.profiles || {});
  profileEl.innerHTML = "";

  for (const profile of profiles) {
    const option = document.createElement("option");
    option.value = profile;
    option.textContent = profile;
    profileEl.append(option);
  }

  if (profiles.includes("quick")) {
    profileEl.value = "quick";
  }
}

function renderPlatformFilters() {
  const platforms = ["all", "paper", "fabric", "forge", "neoforge"];
  platformsEl.innerHTML = "";

  for (const platform of platforms) {
    const label = document.createElement("label");
    const input = document.createElement("input");
    input.type = "checkbox";
    input.checked = state.platforms.has(platform);
    input.addEventListener("change", () => {
      if (platform === "all") {
        if (input.checked) {
          state.platforms = new Set(["all"]);
        } else {
          state.platforms.delete("all");
          if (state.platforms.size === 0) {
            state.platforms.add("paper");
          }
        }
      } else {
        state.platforms.delete("all");
        if (input.checked) {
          state.platforms.add(platform);
        } else {
          state.platforms.delete(platform);
        }

        if (state.platforms.size === 0) {
          state.platforms.add("all");
        }
      }

      renderPlatformFilters();
      renderTests();
    });

    label.append(input, document.createTextNode(platform));
    platformsEl.append(label);
  }
}

function applyProfileDefaults() {
  const profile = profileEl.value;
  const defaults = state.matrix.profiles?.[profile] || [];
  state.selectedTests = new Set(defaults);
  selectionCountEl.textContent = `${state.selectedTests.size} selected`;
}

function visibleTests() {
  const allTests = state.matrix.tests || [];
  if (state.platforms.has("all")) {
    return allTests;
  }

  return allTests.filter((test) => state.platforms.has(test.platform));
}

function renderTests() {
  const tests = visibleTests();
  testsEl.innerHTML = "";

  for (const test of tests) {
    const row = document.createElement("div");
    row.className = "test-row";

    const checkbox = document.createElement("input");
    checkbox.type = "checkbox";
    checkbox.checked = state.selectedTests.has(test.id);
    checkbox.addEventListener("change", () => {
      if (checkbox.checked) {
        state.selectedTests.add(test.id);
      } else {
        state.selectedTests.delete(test.id);
      }

      selectionCountEl.textContent = `${state.selectedTests.size} selected`;
    });

    const label = document.createElement("div");
    label.textContent = test.label;

    const version = document.createElement("div");
    version.className = "muted";
    version.textContent = `${test.platform} ${test.minecraftVersion}`;

    const java = document.createElement("div");
    java.className = "muted";
    java.textContent = `Java ${test.javaMajor}`;

    row.append(checkbox, label, version, java);
    testsEl.append(row);
  }

  selectionCountEl.textContent = `${state.selectedTests.size} selected`;
}

function renderRuns() {
  runsEl.innerHTML = "";

  for (const run of state.runs) {
    const item = document.createElement("div");
    item.className = `run-item${state.selectedRunId === run.id ? " active" : ""}`;
    item.addEventListener("click", () => {
      state.selectedRunId = run.id;
      renderRuns();
      loadRunDetails(run.id);
    });

    const badgeClass = statusToClass(run.status);
    item.innerHTML = `
      <div><strong>${run.id}</strong></div>
      <div class="muted">${run.profile} | ${run.executionMode || "local"} x${run.maxParallel || 1} | cache ${run.usePersistentCache === false ? "off" : "on"} | ${formatDate(run.startedAt)}</div>
      <div style="margin-top:4px"><span class="badge ${badgeClass}">${run.status}</span></div>
    `;

    runsEl.append(item);
  }

  state.activeRunId = findActiveRunId();
  startRunEl.disabled = Boolean(state.activeRunId);
}

async function loadRunDetails(runId) {
  try {
    const response = await fetch(`/api/runs/${runId}`);
    const json = await response.json();
    if (!response.ok) {
      throw new Error(json.error || "Failed to load run");
    }

    upsertRun(json.run);
    state.logByRunId.set(runId, json.logTail || []);
    renderSelectedRun();
  } catch (error) {
    runMetaEl.textContent = error.message;
  }
}

function renderSelectedRun() {
  const run = state.runs.find((entry) => entry.id === state.selectedRunId);
  if (!run) {
    runMetaEl.textContent = "Pick a run to view details.";
    testTableEl.innerHTML = "";
    logEl.textContent = "";
    return;
  }

  runMetaEl.innerHTML = `${run.profile} | ${run.executionMode || "local"} x${run.maxParallel || 1} | cache ${run.usePersistentCache === false ? "off" : "on"} | ${run.status} | started ${formatDate(run.startedAt)}${
    run.endedAt ? ` | ended ${formatDate(run.endedAt)}` : ""
  }`;

  const tests = Object.values(run.tests || {});
  testTableEl.innerHTML = `
    <thead>
      <tr>
        <th>Test</th>
        <th>Status</th>
        <th>Message</th>
      </tr>
    </thead>
    <tbody>
      ${tests
        .map(
          (test) => `<tr>
          <td>${escapeHtml(test.label || test.id || "")}</td>
          <td><span class="badge ${statusToClass(test.status)}">${escapeHtml(test.status || "pending")}</span></td>
          <td>${escapeHtml(test.message || "")}</td>
        </tr>`
        )
        .join("")}
    </tbody>
  `;

  const lines = state.logByRunId.get(run.id) || [];
  logEl.textContent = lines.join("\n");
  logEl.scrollTop = logEl.scrollHeight;
}

function connectSocket() {
  const protocol = window.location.protocol === "https:" ? "wss" : "ws";
  const socket = new WebSocket(`${protocol}://${window.location.host}/ws`);

  socket.addEventListener("open", () => {
    connectionEl.textContent = "Connected";
  });

  socket.addEventListener("close", () => {
    connectionEl.textContent = "Disconnected";
    setTimeout(connectSocket, 1000);
  });

  socket.addEventListener("message", (event) => {
    const message = JSON.parse(event.data);

    switch (message.event) {
      case "snapshot": {
        state.runs = Array.isArray(message.data?.runs) ? message.data.runs : [];
        state.activeRunId = message.data?.activeRunId || null;
        if (!state.selectedRunId && state.runs.length > 0) {
          state.selectedRunId = state.runs[0].id;
        }
        renderRuns();
        renderSelectedRun();
        break;
      }
      case "run.created":
      case "run.updated": {
        upsertRun(message.data);
        renderRuns();
        renderSelectedRun();
        break;
      }
      case "run.log": {
        const runId = message.data?.runId;
        const line = message.data?.line;
        if (!runId || typeof line !== "string") {
          break;
        }

        const lines = state.logByRunId.get(runId) || [];
        lines.push(line);
        if (lines.length > 1200) {
          lines.splice(0, lines.length - 1200);
        }
        state.logByRunId.set(runId, lines);

        if (state.selectedRunId === runId) {
          renderSelectedRun();
        }
        break;
      }
      default:
        break;
    }
  });
}

function findActiveRunId() {
  const active = state.runs.find((run) => run.status === "running" || run.status === "starting" || run.status === "queued");
  return active ? active.id : null;
}

function upsertRun(run) {
  const index = state.runs.findIndex((entry) => entry.id === run.id);
  if (index >= 0) {
    state.runs[index] = run;
  } else {
    state.runs.unshift(run);
  }

  state.runs.sort((a, b) => Date.parse(b.startedAt || "0") - Date.parse(a.startedAt || "0"));
}

function statusToClass(status) {
  const normalized = (status || "").toLowerCase();
  if (normalized.includes("pass")) {
    return "passed";
  }
  if (normalized.includes("fail")) {
    return "failed";
  }
  return "running";
}

function syncExecutionControls() {
  if (executionModeEl.value === "docker") {
    if (parsePositiveInt(maxParallelEl.value, 0) <= 1) {
      maxParallelEl.value = "4";
    }
    usePersistentCacheEl.disabled = false;
    usePersistentCacheEl.checked = true;
    document.getElementById("rebuildDockerImages").disabled = false;
    return;
  }

  maxParallelEl.value = "1";
  usePersistentCacheEl.checked = false;
  usePersistentCacheEl.disabled = true;
  document.getElementById("rebuildDockerImages").checked = false;
  document.getElementById("rebuildDockerImages").disabled = true;
}

function parsePositiveInt(value, fallback) {
  const parsed = Number(value);
  if (!Number.isFinite(parsed) || parsed <= 0) {
    return fallback;
  }
  return Math.floor(parsed);
}

function formatDate(value) {
  if (!value) {
    return "-";
  }

  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }

  return date.toLocaleString();
}

function escapeHtml(text) {
  return text
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}
