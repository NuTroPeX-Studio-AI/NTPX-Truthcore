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
};

addMessage("TruthCore", "TruthCore Web is ready. Ask “status” or “help”, or connect a model provider in Model settings. Unsupported factual answers are withheld instead of guessed.", "LOCAL");

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
    const response = await fetch("/api/chat", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ message, provider: state.provider }),
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
