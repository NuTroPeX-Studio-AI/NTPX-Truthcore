import dns from "node:dns";
import https from "node:https";
import net from "node:net";

/**
 * Network guard for outbound provider traffic.
 *
 * Every hostname is resolved through a lookup hook that rejects private,
 * loopback, link-local, metadata, multicast and other reserved addresses at
 * connect time. Because the check runs inside the socket's own DNS lookup, a
 * provider hostname cannot pass validation and then re-resolve to an internal
 * address (DNS rebinding / TOCTOU).
 */

const BLOCKED_HOSTNAMES = new Set(["localhost", "metadata.google.internal", "metadata", "instance-data"]);

export function isBlockedHostname(host) {
  const value = String(host || "").toLowerCase().replace(/\.$/, "");
  return BLOCKED_HOSTNAMES.has(value)
    || value.endsWith(".localhost")
    || value.endsWith(".local")
    || value.endsWith(".internal")
    || value.endsWith(".home.arpa");
}

export function isPrivateAddress(address) {
  const value = String(address || "").trim().toLowerCase().replace(/^\[|\]$/g, "").split("%")[0];
  const family = net.isIP(value);
  if (family === 4) return isPrivateV4(value);
  if (family === 6) return isPrivateV6(value);
  return true;
}

function isPrivateV4(address) {
  const parts = address.split(".").map(Number);
  if (parts.length !== 4 || parts.some((part) => !Number.isInteger(part) || part < 0 || part > 255)) return true;
  const [a, b, c] = parts;
  return a === 0
    || a === 10
    || a === 127
    || (a === 100 && b >= 64 && b <= 127) // carrier-grade NAT
    || (a === 169 && b === 254) // link-local + cloud metadata
    || (a === 172 && b >= 16 && b <= 31)
    || (a === 192 && b === 0 && (c === 0 || c === 2)) // IETF protocol assignments, TEST-NET-1
    || (a === 192 && b === 88 && c === 99) // 6to4 relay anycast
    || (a === 192 && b === 168)
    || (a === 198 && (b === 18 || b === 19)) // benchmarking
    || (a === 198 && b === 51 && c === 100) // TEST-NET-2
    || (a === 203 && b === 0 && c === 113) // TEST-NET-3
    || a >= 224; // multicast + reserved + broadcast
}

function expandV6(address) {
  let value = address;
  const v4Tail = value.match(/(\d+\.\d+\.\d+\.\d+)$/);
  if (v4Tail) {
    const octets = v4Tail[1].split(".").map(Number);
    const hex = `${((octets[0] << 8) | octets[1]).toString(16)}:${((octets[2] << 8) | octets[3]).toString(16)}`;
    value = value.slice(0, -v4Tail[1].length) + hex;
  }
  const [head, tail] = value.split("::");
  const headParts = head ? head.split(":") : [];
  const tailParts = tail !== undefined && tail ? tail.split(":") : [];
  const missing = value.includes("::") ? 8 - headParts.length - tailParts.length : 0;
  const groups = [...headParts, ...Array(Math.max(0, missing)).fill("0"), ...tailParts];
  return groups.map((group) => parseInt(group || "0", 16));
}

function isPrivateV6(address) {
  const groups = expandV6(address);
  if (groups.length !== 8 || groups.some((group) => !Number.isFinite(group))) return true;
  const allZeroPrefix = (count) => groups.slice(0, count).every((group) => group === 0);
  if (groups.every((group) => group === 0)) return true; // ::
  if (allZeroPrefix(7) && groups[7] === 1) return true; // ::1
  // IPv4-mapped (::ffff:a.b.c.d) and IPv4-compatible (::a.b.c.d) addresses.
  if (allZeroPrefix(5) && (groups[5] === 0xffff || groups[5] === 0)) {
    const v4 = `${groups[6] >> 8}.${groups[6] & 255}.${groups[7] >> 8}.${groups[7] & 255}`;
    return isPrivateV4(v4);
  }
  // NAT64 well-known prefix 64:ff9b::/96 embeds an IPv4 address.
  if (groups[0] === 0x64 && groups[1] === 0xff9b && groups.slice(2, 6).every((group) => group === 0)) {
    const v4 = `${groups[6] >> 8}.${groups[6] & 255}.${groups[7] >> 8}.${groups[7] & 255}`;
    return isPrivateV4(v4);
  }
  const first = groups[0];
  if (first === 0x2002) return true; // 6to4 can tunnel to arbitrary IPv4
  if (first === 0x2001 && groups[1] === 0x0db8) return true; // documentation
  if (first === 0x2001 && groups[1] === 0x0000) return true; // Teredo
  if ((first & 0xfe00) === 0xfc00) return true; // unique local fc00::/7
  if ((first & 0xffc0) === 0xfe80) return true; // link-local fe80::/10
  if ((first & 0xffc0) === 0xfec0) return true; // deprecated site-local
  if ((first & 0xff00) === 0xff00) return true; // multicast
  return false;
}

/** dns.lookup-compatible hook that refuses any reserved destination. */
export function guardedLookup(hostname, options, callback) {
  const cb = typeof options === "function" ? options : callback;
  const opts = typeof options === "object" && options ? options : {};
  if (isBlockedHostname(hostname)) {
    const error = new Error("Destination hostname is blocked");
    error.code = "ETRUTHCORE_BLOCKED";
    return cb(error);
  }
  dns.lookup(hostname, { ...opts, all: true, verbatim: true }, (error, addresses) => {
    if (error) return cb(error);
    const rows = Array.isArray(addresses) ? addresses : [{ address: addresses, family: net.isIP(addresses) }];
    if (!rows.length || rows.some((row) => isPrivateAddress(row.address))) {
      const blocked = new Error("Destination resolves to a private or reserved network address");
      blocked.code = "ETRUTHCORE_BLOCKED";
      return cb(blocked);
    }
    if (opts.all) return cb(null, rows);
    return cb(null, rows[0].address, rows[0].family);
  });
}

/**
 * Minimal fetch-like POST over node:https using guardedLookup. Redirects are
 * never followed and the response body is capped.
 */
export function guardedPost(url, { headers = {}, body = "", timeoutMs = 30000, maxBytes = 2 * 1024 * 1024, signal } = {}) {
  return new Promise((resolve, reject) => {
    const target = new URL(url);
    if (target.protocol !== "https:") {
      reject(new Error("Only HTTPS destinations are allowed"));
      return;
    }
    const hostname = target.hostname.replace(/^\[|\]$/g, "");
    // Literal IPs skip the lookup hook, so reject reserved ones before any socket exists.
    if (net.isIP(hostname) && isPrivateAddress(hostname)) {
      reject(new Error("Destination is a private or reserved network address"));
      return;
    }
    if (!net.isIP(hostname) && isBlockedHostname(hostname)) {
      reject(new Error("Destination hostname is blocked"));
      return;
    }
    const payload = Buffer.from(String(body), "utf8");
    let settled = false;
    const fail = (error) => {
      if (settled) return;
      settled = true;
      reject(error);
    };
    const request = https.request({
      protocol: "https:",
      hostname,
      port: target.port || 443,
      path: `${target.pathname}${target.search}`,
      method: "POST",
      lookup: guardedLookup,
      headers: { ...headers, "content-length": payload.length },
      timeout: timeoutMs,
      signal,
    }, (response) => {
      const chunks = [];
      let total = 0;
      response.on("data", (chunk) => {
        total += chunk.length;
        if (total > maxBytes) {
          request.destroy(new Error("Response body exceeds the allowed size"));
          return;
        }
        chunks.push(chunk);
      });
      response.on("end", () => {
        if (settled) return;
        settled = true;
        const text = Buffer.concat(chunks).toString("utf8");
        resolve({
          status: response.statusCode || 0,
          ok: (response.statusCode || 0) >= 200 && (response.statusCode || 0) < 300,
          text: async () => text,
        });
      });
      response.on("error", fail);
    });
    request.on("error", fail);
    request.on("timeout", () => request.destroy(Object.assign(new Error("Request timed out"), { name: "AbortError" })));
    request.end(payload);
  });
}
