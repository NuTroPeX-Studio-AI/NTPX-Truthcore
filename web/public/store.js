const DB_NAME = "ntpx-truthcore";
const DB_VERSION = 1;

function openDb() {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, DB_VERSION);
    request.onupgradeneeded = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains("memory")) db.createObjectStore("memory", { keyPath: "id" });
      if (!db.objectStoreNames.contains("knowledge")) db.createObjectStore("knowledge", { keyPath: "id" });
      if (!db.objectStoreNames.contains("audit")) db.createObjectStore("audit", { keyPath: "seq", autoIncrement: true });
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

async function withStore(name, mode, fn) {
  const db = await openDb();
  try {
    return await new Promise((resolve, reject) => {
      const tx = db.transaction(name, mode);
      const store = tx.objectStore(name);
      let result;
      try { result = fn(store); } catch (error) { reject(error); return; }
      tx.oncomplete = () => resolve(result);
      tx.onerror = () => reject(tx.error);
      tx.onabort = () => reject(tx.error || new Error("IndexedDB transaction aborted"));
    });
  } finally {
    db.close();
  }
}

function requestResult(request) {
  return new Promise((resolve, reject) => {
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

export async function remember(content) {
  const record = {
    id: crypto.randomUUID(),
    content: String(content).trim(),
    trust: 0.85,
    importance: 0.5,
    createdAt: Date.now(),
  };
  if (!record.content) throw new Error("Memory content is required.");
  await withStore("memory", "readwrite", (store) => store.put(record));
  await appendAudit("memory.saved", record.id);
  return record;
}

export async function addKnowledge(label, content, sourceUri = null) {
  const record = {
    id: crypto.randomUUID(),
    label: String(label).trim(),
    content: String(content).trim(),
    sourceUri: sourceUri && /^https:\/\//i.test(sourceUri) ? sourceUri : null,
    trust: sourceUri && /^https:\/\//i.test(sourceUri) ? 0.85 : 0.75,
    createdAt: Date.now(),
  };
  if (!record.label || !record.content) throw new Error("Knowledge label and content are required.");
  await withStore("knowledge", "readwrite", (store) => store.put(record));
  await appendAudit("knowledge.saved", record.id);
  return record;
}

export async function searchMemory(query, limit = 8) {
  return searchStore("memory", query, limit, (item) => item.content);
}

export async function searchKnowledge(query, limit = 12) {
  return searchStore("knowledge", query, limit, (item) => `${item.label} ${item.content}`);
}

async function searchStore(name, query, limit, textOf) {
  const db = await openDb();
  try {
    const tx = db.transaction(name, "readonly");
    const all = await requestResult(tx.objectStore(name).getAll());
    const terms = tokenize(query);
    return all
      .map((item) => ({ item, score: score(terms, textOf(item)) }))
      .filter(({ score: value }) => value > 0 || terms.size === 0)
      .sort((a, b) => b.score - a.score || (b.item.createdAt || 0) - (a.item.createdAt || 0))
      .slice(0, limit)
      .map(({ item }) => item);
  } finally {
    db.close();
  }
}

export async function evidenceFor(query, limit = 16) {
  const [knowledge, memory] = await Promise.all([searchKnowledge(query, 12), searchMemory(query, 8)]);
  const rows = [
    ...knowledge.map((item) => ({
      id: `web-knowledge:${item.id}`,
      label: item.label,
      content: item.content,
      trust: Math.min(Number(item.trust) || 0, 0.9),
      sourceUri: item.sourceUri || `memory://web-knowledge/${item.id}`,
      independentKey: item.sourceUri || `web-knowledge:${item.id}`,
    })),
    ...memory.map((item) => ({
      id: `web-memory:${item.id}`,
      label: "Saved user memory",
      content: `Saved user memory: ${item.content}`,
      trust: Math.min(Number(item.trust) || 0, 0.9),
      sourceUri: `memory://web/${item.id}`,
      independentKey: `web-memory:${item.id}`,
    })),
  ];
  return rows.slice(0, limit);
}

let auditQueue = Promise.resolve();

/**
 * Appends one hash-chained audit entry.
 *
 * Appends are serialized across tabs with the Web Locks API (falling back to
 * an in-tab queue), and the tip is re-checked inside the write transaction as
 * a final guard, so concurrent writers cannot fork the chain.
 */
export function appendAudit(event, payload) {
  const run = () => appendAuditUnlocked(event, payload);
  if (globalThis.navigator?.locks?.request) {
    return navigator.locks.request("ntpx-truthcore-audit", run);
  }
  const next = auditQueue.then(run, run);
  auditQueue = next.catch(() => {});
  return next;
}

async function appendAuditUnlocked(event, payload) {
  const at = Date.now();
  const payloadHash = await sha256(String(payload));
  for (let attempt = 0; attempt < 8; attempt += 1) {
    const db = await openDb();
    try {
      // Read the current tip, compute the digest, then write only if the tip is unchanged.
      const tip = await readTip(db);
      const entryHash = await sha256(`${tip.hash}|${at}|${event}|${payloadHash}`);
      const written = await new Promise((resolve, reject) => {
        const tx = db.transaction("audit", "readwrite");
        const store = tx.objectStore("audit");
        let ok = false;
        const cursorRequest = store.openCursor(null, "prev");
        cursorRequest.onsuccess = () => {
          const cursor = cursorRequest.result;
          const currentTip = cursor ? cursor.value.entryHash : "GENESIS";
          if (currentTip !== tip.hash) return; // another writer won; retry with the new tip
          store.add({ at, event, payloadHash, previousHash: tip.hash, entryHash });
          ok = true;
        };
        tx.oncomplete = () => resolve(ok ? entryHash : null);
        tx.onerror = () => reject(tx.error);
        tx.onabort = () => reject(tx.error || new Error("Audit transaction aborted"));
      });
      if (written) return written;
    } finally {
      db.close();
    }
    await new Promise((resolve) => setTimeout(resolve, 5 + Math.random() * 20 * (attempt + 1)));
  }
  throw new Error("Audit chain is busy; try again.");
}

async function readTip(db) {
  return new Promise((resolve, reject) => {
    const tx = db.transaction("audit", "readonly");
    const request = tx.objectStore("audit").openCursor(null, "prev");
    request.onsuccess = () => resolve({ hash: request.result ? request.result.value.entryHash : "GENESIS" });
    request.onerror = () => reject(request.error);
  });
}

/** Deletes memories whose content contains every query word (or matches the id). Returns the count removed. */
export async function forgetMemory(query) {
  const terms = tokenize(query);
  const id = String(query).trim();
  const db = await openDb();
  let removed = [];
  try {
    const all = await requestResult(db.transaction("memory", "readonly").objectStore("memory").getAll());
    removed = all.filter((item) => item.id === id || (terms.size > 0 && score(terms, item.content) === terms.size)).map((item) => item.id);
  } finally {
    db.close();
  }
  if (!removed.length) return 0;
  await withStore("memory", "readwrite", (store) => removed.forEach((key) => store.delete(key)));
  await appendAudit("memory.deleted", removed.join(","));
  return removed.length;
}

export async function memoryCount() {
  const db = await openDb();
  try {
    return await requestResult(db.transaction("memory", "readonly").objectStore("memory").count());
  } finally {
    db.close();
  }
}

export async function verifyAuditChain() {
  const db = await openDb();
  try {
    const tx = db.transaction("audit", "readonly");
    const rows = await requestResult(tx.objectStore("audit").getAll());
    let previous = "GENESIS";
    for (const row of rows) {
      if (row.previousHash !== previous) return false;
      const expected = await sha256(`${previous}|${row.at}|${row.event}|${row.payloadHash}`);
      if (expected !== row.entryHash) return false;
      previous = row.entryHash;
    }
    return true;
  } finally {
    db.close();
  }
}

function tokenize(value) {
  return new Set(String(value).toLowerCase().split(/\W+/).filter((term) => term.length > 2));
}

function score(terms, text) {
  const lower = String(text).toLowerCase();
  let count = 0;
  for (const term of terms) if (lower.includes(term)) count += 1;
  return count;
}

async function sha256(value) {
  const data = new TextEncoder().encode(value);
  const digest = await crypto.subtle.digest("SHA-256", data);
  return [...new Uint8Array(digest)].map((byte) => byte.toString(16).padStart(2, "0")).join("");
}
