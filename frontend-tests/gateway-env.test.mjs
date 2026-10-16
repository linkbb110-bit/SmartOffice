import test from "node:test";
import assert from "node:assert/strict";
import { loadEnv } from "vite";

for (const mode of ["development", "production"]) {
  test(`${mode} API uses the same-origin gateway proxy after the service split`, () => {
    assert.equal(loadEnv(mode, process.cwd(), "VITE_").VITE_API_BASE_URL || "/api", "/api");
  });
}
