import { readdir, readFile } from "node:fs/promises";
import { join } from "node:path";

const banned = [
  "/api/v1/",
  "/actuator/health",
  "Invalid API key. Please try again.",
  "Device admin key",
  "Local demonstration access",
  "localhost:8080",
  "streamforge-admin-key",
  "SessionProvider",
];

const root = join(import.meta.dirname, "..", "dist");
const files = await walk(root);
let combined = "";
for (const file of files) {
  if (/\.(js|css|html|map)$/.test(file)) {
    combined += await readFile(file, "utf8");
  }
}
const hits = banned.filter((needle) => combined.includes(needle));
if (hits.length > 0) {
  console.error(`Public bundle contains live-backend markers: ${hits.join(", ")}`);
  process.exit(1);
}
if (!combined.includes("Browser-local simulation")) {
  console.error("Public bundle is missing the simulation disclosure.");
  process.exit(1);
}
console.log(`Public bundle isolation ok (${files.length} files).`);

async function walk(directory) {
  const entries = await readdir(directory, { withFileTypes: true });
  const found = [];
  for (const entry of entries) {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) {
      found.push(...(await walk(path)));
    } else {
      found.push(path);
    }
  }
  return found;
}
