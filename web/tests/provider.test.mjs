import test from "node:test";
import assert from "node:assert/strict";
import { validateProviderConfig } from "../src/provider.mjs";

const publicResolver = async () => ["93.184.216.34"];

test("provider requires https", async () => {
  const result = await validateProviderConfig({ baseUrl: "http://example.com/v1", model: "demo" }, { allowedHosts: new Set(["example.com"]), resolveHost: publicResolver });
  assert.equal(result.ok, false);
});

test("provider host must be allowlisted", async () => {
  const result = await validateProviderConfig({ baseUrl: "https://example.com/v1", model: "demo" }, { allowedHosts: new Set(["other.example"]), resolveHost: publicResolver });
  assert.equal(result.ok, false);
  assert.match(result.error, /not in/i);
});

test("allowlisted public provider passes structural validation", async () => {
  const result = await validateProviderConfig({ baseUrl: "https://example.com/v1", model: "demo" }, { allowedHosts: new Set(["example.com"]), resolveHost: publicResolver });
  assert.equal(result.ok, true);
});
