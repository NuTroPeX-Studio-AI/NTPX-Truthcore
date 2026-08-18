import {
  addKnowledge,
  evidenceFor,
  remember,
  searchKnowledge,
  searchMemory,
  verifyAuditChain,
} from "/store.js";

const elements = {
  messages: document.querySelector("#messages"),
  input: document.querySelector("#messageInput"),
  send: document.querySelector("#sendButton"),
  mic: document.querySelector("#micButton"),
  stop: document.querySelector("#stopButton"),
  speak: document.querySelector("#speakToggle"),
  providerPill: document.querySelector("#providerPill"),
  voicePill: document.querySelector("#voicePill"),
  settingsButton: document.querySelector("#settingsButton"),
  settingsDialog: document.querySelector("#settingsDialog"),
  baseUrl: document.querySelector("#baseUrlInput"),
  model: document.querySelector("#modelInput"),
  apiKey: document.querySelector("#apiKeyInput"),
  test: document.querySelector("#testButton"),
  disconnect: document.querySelector("#disconnectButton"),
  settingsStatus: document.querySelector("#settingsStatus"),
  install: document.querySelector("#installButton"),
};

const state = {
  provider: null,
  busy: false,
  recognition: null,
  deferredInstall: null,
  pending: new Map(),
};

addMessage("TruthCore", "TruthCore Web v1 runtime is ready. ClaimLock, persistent local memory/knowledge, bounded browser tools, multi-agent review, and the local audit chain are active. Connect a model for open-ended reasoning.", "LOCAL");

if ("serviceWorker" in navigator) navigator.serviceWorker.register("/sw.js").catch(() => {});

window.addEventListener("beforeinstallprompt", (event) => {
  event.preventDefault();
  state.deferredInstall = event;
  elements.install.classList.remove("hidden");
});

elements.install.addEventListener("click", async () => {
  if (!state.deferredInstall) return;
  await state.deferredInstall.prompt();
  state.deferredInstall = null;
  elements.install.classList.add("hidden");
});

elements.settingsButton.addEventListener("click", () => elements.settingsDialog.showModal());
elements.disconnect.addEventListener("click", () => {
  state.provider = null;
  elements.apiKey.value = "";
  elements.providerPill.textContent = "Provider: local only";
  elements.settingsStatus.textContent = "Disconnected. Credentials cleared from this tab's runtime state.";
});

elements.test.addEventListener("click", async () => {
  const candidate = readProviderForm();
  elements.settingsStatus.textContent = "Testing connection…";
  elements.test.disabled = true;
  try {
    const response = await fetch("/api/provider/test", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ provider: candidate }),
    });
    const result = await response.json();
    if (!response.ok || !result.ok) throw new Error(result.error || "Connection test failed");
    state.provider = candidate;
    elements.apiKey.value = "";
    elements.providerPill.textContent = `Provider: ${candidate.model}`;
    elements.settingsStatus.textContent = "Connected for this tab. The API key is held in runtime memory only.";
  } catch (error) {
    state.provider = null;
    elements.providerPill.textContent = "Provider: local only";
    elements.settingsStatus.textContent = error.message || "Connection test failed";
  } finally {
    elements.test.disabled = false;
  }
});

elements.send.addEventListener("click", submit);
elements.input.addEventListener("keydown", (event) => {
  if (event.key === "Enter" && !event.shiftKey) {
    event.preventDefault();
    submit();
  }
});

elements.mic.addEventListener("click", startVoice);
elements.stop.addEventListener("click", stopVoiceAndSpeech);

async function submit() {
  const message = elements.input.value.trim();
  if (!message || state.busy) return;
  elements.input.value = "";
  addMessage("You", message, null, true);
  setBusy(true);
  try {
    const local = await handleLocalCommand(message);
    if (local) {
      addMessage("TruthCore", local.text, local.status);
      if (elements.speak.checked) speak(local.text);
      return;
    }

    if (/^(team:|\/team\s+|review team:\s*)/i.test(message)) {
      const reply = await runTeam(message.replace(/^(team:|\/team\s+|review team:\s*)/i, "").trim());
      addMessage("TruthCore", reply.text, reply.status);
      if (elements.speak.checked) speak(reply.text);
      return;
    }

    if (/^(agent:|\/agent\s+|do:\s+)/i.test(message)) {
      const reply = await runAgent(message.replace(/^(agent:|\/agent\s+|do:\s+)/i, "").trim());
      addMessage("TruthCore", reply.text, reply.status);
      if (elements.speak.checked) speak(reply.text);
      return;
    }

    const clientEvidence = await evidenceFor(message);
    const response = await fetch("/api/chat", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ message, provider: state.provider, clientEvidence }),
    });
    const reply = await response.json();
    if (!response.ok) throw new Error(reply.error || "TruthCore request failed");
    addMessage("TruthCore", reply.text, reply.status || "LOCAL");
    if (elements.speak.checked) speak(reply.text);
  } catch (error) {
    addMessage("TruthCore", error.message || "The request failed.", "PROVIDER_ERROR");
  } finally {
    setBusy(false);
  }
}

async function handleLocalCommand(message) {
  const lower = message.toLowerCase();
  if (lower.startsWith("remember that ")) {
    return proposeWrite({ tool: "memory.remember", args: { content: message.slice(14).trim() } });
  }
  if (lower.startsWith("add knowledge: ")) {
    const body = message.slice(message.indexOf(":") + 1).trim();
    const label = body.split("|")[0]?.trim() || "";
    const content = body.includes("|") ? body.slice(body.indexOf("|") + 1).trim() : "";
    return proposeWrite({ tool: "knowledge.add", args: { label, content } });
  }
  if (lower.startsWith("approve ")) {
    return executeApproval(message.slice(8).trim());
  }
  if (lower.startsWith("what do you remember about ") || lower.startsWith("search memory for ")) {
    const query = lower.startsWith("what do you remember about ") ? message.slice(27).trim() : message.slice(18).trim();
    const rows = await searchMemory(query);
    return { text: rows.length ? rows.map((row) => `• ${row.content}`).join("\n") : "No matching memory.", status: "LOCAL" };
  }
  if (lower.startsWith("search knowledge for ")) {
    const rows = await searchKnowledge(message.slice(21).trim());
    return { text: rows.length ? rows.map((row) => `• ${row.label}: ${row.content}`).join("\n") : "No matching knowledge.", status: "LOCAL" };
  }
  if (lower === "audit status" || lower === "verify audit") {
    const valid = await verifyAuditChain();
    return { text: valid ? "The browser-local TruthCore audit hash chain is internally consistent." : "The browser-local TruthCore audit hash chain failed verification.", status: valid ? "LOCAL" : "ALERT" };
  }
  if (lower === "list tools" || lower === "tools") {
    return { text: "clock.now [READ_ONLY]\nmemory.search [READ_ONLY]\nknowledge.search [READ_ONLY]\nmemory.remember [WRITE_LOCAL]\nknowledge.add [WRITE_LOCAL]", status: "LOCAL" };
  }
  return null;
}

function proposeWrite(call) {
  if (!Object.values(call.args).some((value) => String(value).trim())) {
    return { text: "The requested write is empty.", status: "ACTION_FAILED" };
  }
  const token = crypto.randomUUID();
  state.pending.set(token, call);
  return { text: `Approval required for ${call.tool}. Type or say: approve ${token}`, status: "APPROVAL_REQUIRED" };
}

async function executeApproval(token) {
  const call = state.pending.get(token);
  if (!call) return { text: "No pending action for that approval token.", status: "ACTION_DENIED" };
  state.pending.delete(token);
  if (call.tool === "memory.remember") {
    const record = await remember(call.args.content);
    return { text: `Saved memory ${record.id}.`, status: "ACTION_EXECUTED" };
  }
  if (call.tool === "knowledge.add") {
    const record = await addKnowledge(call.args.label, call.args.content);
    return { text: `Saved knowledge ${record.id}.`, status: "ACTION_EXECUTED" };
  }
  return { text: "The pending tool is no longer allowed.", status: "ACTION_DENIED" };
}

async function runTeam(goal) {
  if (!state.provider) return { text: "A model provider is required for the multi-agent review team.", status: "ABSTAINED" };
  const response = await fetch("/api/team", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ goal, provider: state.provider }),
  });
  const body = await response.json();
  if (!response.ok || !body.ok) return { text: body.error || "Team review failed.", status: body.status || "PROVIDER_ERROR" };
  return { text: body.text, status: body.status || "TEAM_GENERATED" };
}

async function runAgent(task) {
  if (!state.provider) return { text: "A model provider is required to plan agent tasks.", status: "ABSTAINED" };
  const response = await fetch("/api/agent/plan", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ task, provider: state.provider }),
  });
  const body = await response.json();
  if (!response.ok || !body.ok) return { text: body.error || "Agent planning failed.", status: "PROVIDER_ERROR" };
  const calls = parseToolCalls(body.plan).slice(0, 4);
  if (!calls.length) return { text: "The planner did not produce an executable registered-tool plan.", status: "ABSTAINED" };

  const results = [];
  for (const call of calls) {
    if (["memory.remember", "knowledge.add"].includes(call.tool)) return proposeWrite(call);
    const result = await executeReadOnlyTool(call);
    if (!result.ok) return { text: result.output, status: "ACTION_FAILED" };
    results.push(`${call.tool}: ${result.output}`);
  }

  const finalResponse = await fetch("/api/agent/finalize", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ task, provider: state.provider, results }),
  });
  const finalBody = await finalResponse.json();
  return finalResponse.ok && finalBody.ok
    ? { text: finalBody.text, status: "ACTION_EXECUTED" }
    : { text: results.join("\n"), status: "ACTION_EXECUTED" };
}

function parseToolCalls(plan) {
  return String(plan).split(/\r?\n/).map((raw) => {
    const line = raw.trim();
    if (!/^TOOL\s+/i.test(line)) return null;
    const body = line.replace(/^TOOL\s+/i, "");
    const firstSpace = body.indexOf(" ");
    const tool = (firstSpace < 0 ? body : body.slice(0, firstSpace)).trim();
    if (!/^[a-z0-9_.-]+$/.test(tool)) return null;
    const argsText = firstSpace < 0 ? "" : body.slice(firstSpace + 1);
    const args = Object.fromEntries(argsText.split(";").map((part) => {
      const at = part.indexOf("=");
      if (at < 1) return null;
      const key = part.slice(0, at).trim();
      const value = part.slice(at + 1).trim();
      return /^[a-zA-Z0-9_.-]+$/.test(key) && value ? [key, value] : null;
    }).filter(Boolean));
    return { tool, args };
  }).filter(Boolean);
}

async function executeReadOnlyTool(call) {
  if (call.tool === "clock.now") return { ok: true, output: new Date().toISOString() };
  if (call.tool === "memory.search") {
    const rows = await searchMemory(call.args.query || "");
    return { ok: true, output: rows.map((row) => row.content).join("\n") || "No matching memory." };
  }
  if (call.tool === "knowledge.search") {
    const rows = await searchKnowledge(call.args.query || "");
    return { ok: true, output: rows.map((row) => `${row.label}: ${row.content}`).join("\n") || "No matching knowledge." };
  }
  return { ok: false, output: `Unknown or disallowed browser tool: ${call.tool}` };
}

function readProviderForm() {
  return {
    baseUrl: elements.baseUrl.value.trim(),
    model: elements.model.value.trim(),
    apiKey: elements.apiKey.value,
  };
}

function addMessage(speaker, text, status, user = false) {
  const article = document.createElement("article");
  article.className = `message ${user ? "user" : "truthcore"}`;
  const meta = document.createElement("div");
  meta.className = "message-meta";
  const who = document.createElement("span");
  who.textContent = speaker;
  meta.append(who);
  if (status) {
    const badge = document.createElement("span");
    badge.className = `message-status ${status}`;
    badge.textContent = status;
    meta.append(badge);
  }
  const body = document.createElement("div");
  body.className = "message-text";
  body.textContent = text;
  article.append(meta, body);
  elements.messages.append(article);
  elements.messages.scrollTop = elements.messages.scrollHeight;
}

function setBusy(value) {
  state.busy = value;
  elements.send.disabled = value;
  elements.send.textContent = value ? "Working…" : "Send";
}

function speak(text) {
  if (!("speechSynthesis" in window)) return;
  window.speechSynthesis.cancel();
  const utterance = new SpeechSynthesisUtterance(text);
  window.speechSynthesis.speak(utterance);
}

function startVoice() {
  const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;
  if (!SpeechRecognition) {
    elements.voicePill.textContent = "Voice: unavailable";
    addMessage("TruthCore", "Browser speech recognition is unavailable here. Text chat still works.", "LOCAL");
    return;
  }
  stopVoiceAndSpeech();
  const recognition = new SpeechRecognition();
  state.recognition = recognition;
  recognition.lang = "en-US";
  recognition.interimResults = false;
  recognition.maxAlternatives = 1;
  recognition.onstart = () => { elements.voicePill.textContent = "Voice: listening"; };
  recognition.onresult = (event) => {
    elements.input.value = event.results?.[0]?.[0]?.transcript || "";
    elements.voicePill.textContent = "Voice: captured";
  };
  recognition.onerror = (event) => { elements.voicePill.textContent = `Voice: ${friendlyVoiceError(event.error)}`; };
  recognition.onend = () => {
    if (elements.voicePill.textContent === "Voice: listening") elements.voicePill.textContent = "Voice: idle";
    state.recognition = null;
  };
  recognition.start();
}

function stopVoiceAndSpeech() {
  try { state.recognition?.abort(); } catch {}
  state.recognition = null;
  if ("speechSynthesis" in window) window.speechSynthesis.cancel();
  elements.voicePill.textContent = "Voice: idle";
}

function friendlyVoiceError(code) {
  return ({
    "not-allowed": "permission blocked",
    "service-not-allowed": "service blocked",
    "no-speech": "no speech heard",
    "audio-capture": "microphone unavailable",
    network: "network error",
    aborted: "stopped",
  })[code] || "error";
}
