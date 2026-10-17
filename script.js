
const API_URL = "http://localhost:8080/api/simulate";

const form = document.getElementById("simulationForm");
const runButton = document.getElementById("runButton");
const compareButton = document.getElementById("compareButton");
const statusMessage = document.getElementById("statusMessage");

function getSettings(mode) {
  return {
    mode,
    steps: Number(document.getElementById("steps").value),
    bufferSize: Number(document.getElementById("bufferSize").value),
    linkCapacity: Number(document.getElementById("linkCapacity").value),
    randomLoss: Number(document.getElementById("randomLoss").value)
  };
}

async function simulate(settings) {
  const response = await fetch(API_URL, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(settings)
  });

  const data = await response.json();

  if (!response.ok) {
    throw new Error(data.error || "Simulation failed.");
  }

  return data;
}

form.addEventListener("submit", async (event) => {
  event.preventDefault();
  await runSelectedMode();
});

async function runSelectedMode() {
  setBusy(true);

  try {
    const mode = document.getElementById("mode").value;
    const result = await simulate(getSettings(mode));

    displayMetrics(result);
    drawChart("cwndChart", result.cwndHistory, "#2563eb");
    drawChart("queueChart", result.queueHistory, "#0f9d83");

    statusMessage.textContent = "Simulation completed successfully.";
  } catch (error) {
    showError(error);
  } finally {
    setBusy(false);
  }
}

compareButton.addEventListener("click", async () => {
  setBusy(true);

  try {
    const settings = getSettings("tcp");

    const [controlled, uncontrolled] = await Promise.all([
      simulate(settings),
      simulate({ ...settings, mode: "none" })
    ]);

    fillComparison("tcp", controlled);
    fillComparison("none", uncontrolled);

    const selectedMode = document.getElementById("mode").value;
    const result = selectedMode === "none" ? uncontrolled : controlled;

    displayMetrics(result);
    drawChart("cwndChart", result.cwndHistory, "#2563eb");
    drawChart("queueChart", result.queueHistory, "#0f9d83");

    statusMessage.textContent = "Both modes compared successfully.";
  } catch (error) {
    showError(error);
  } finally {
    setBusy(false);
  }
});

function setBusy(busy) {
  runButton.disabled = busy;
  compareButton.disabled = busy;

  if (busy) {
    statusMessage.textContent = "Running simulation...";
  }
}

function showError(error) {
  if (error instanceof TypeError) {
    statusMessage.textContent =
      "Cannot connect to Java. Start CongestionServer.java first.";
  } else {
    statusMessage.textContent = error.message;
  }
}

function displayMetrics(result) {
  document.getElementById("totalSent").textContent = result.totalSent;
  document.getElementById("totalReceived").textContent = result.totalReceived;
  document.getElementById("totalDropped").textContent = result.totalDropped;
  document.getElementById("lossRate").textContent =
    result.lossRate.toFixed(2) + "%";
  document.getElementById("throughput").textContent =
    result.throughput.toFixed(2);
}

function fillComparison(prefix, result) {
  document.getElementById(prefix + "Sent").textContent = result.totalSent;
  document.getElementById(prefix + "Received").textContent =
    result.totalReceived;
  document.getElementById(prefix + "Dropped").textContent =
    result.totalDropped;
  document.getElementById(prefix + "Loss").textContent =
    result.lossRate.toFixed(2) + "%";
  document.getElementById(prefix + "Throughput").textContent =
    result.throughput.toFixed(2);
}

function drawChart(canvasId, values, color) {
  const canvas = document.getElementById(canvasId);
  const ctx = canvas.getContext("2d");
  const width = Math.max(300, canvas.clientWidth);
  const height = 240;
  const ratio = window.devicePixelRatio || 1;

  canvas.width = width * ratio;
  canvas.height = height * ratio;
  ctx.scale(ratio, ratio);

  const margin = { left: 42, right: 12, top: 20, bottom: 30 };
  const chartWidth = width - margin.left - margin.right;
  const chartHeight = height - margin.top - margin.bottom;
  const maxValue = Math.max(1, ...values);
  const ticks = 4;

  ctx.clearRect(0, 0, width, height);
  ctx.font = "11px Arial";
  ctx.textAlign = "right";
  ctx.textBaseline = "middle";

  for (let i = 0; i <= ticks; i++) {
    const y = margin.top + chartHeight - (i / ticks) * chartHeight;

    ctx.strokeStyle = "#e2e8f0";
    ctx.beginPath();
    ctx.moveTo(margin.left, y);
    ctx.lineTo(width - margin.right, y);
    ctx.stroke();

    ctx.fillStyle = "#64748b";
    ctx.fillText(
      Math.round((i / ticks) * maxValue),
      margin.left - 7,
      y
    );
  }

  const x = (i) =>
    margin.left + (i / Math.max(1, values.length - 1)) * chartWidth;

  const y = (value) =>
    margin.top + chartHeight - (value / maxValue) * chartHeight;

  ctx.strokeStyle = color;
  ctx.lineWidth = 2.5;
  ctx.beginPath();

  values.forEach((value, i) => {
    if (i === 0) {
      ctx.moveTo(x(i), y(value));
    } else {
      ctx.lineTo(x(i), y(value));
    }
  });

  ctx.stroke();

  ctx.fillStyle = "#64748b";
  ctx.textAlign = "center";
  ctx.fillText("Simulation step", width / 2, height - 10);
}
