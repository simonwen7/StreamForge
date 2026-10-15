import { readdir, readFile } from "node:fs/promises";
import { join } from "node:path";

const root = join(import.meta.dirname, "..", "dist");
const files = await walk(root);
let combined = "";
for (const file of files) {
  if (/\.(js|html)$/.test(file)) {
    combined += await readFile(file, "utf8");
  }
}
if (!combined.includes("/api/v1/devices") || !combined.includes("Device admin key")) {
  console.error("Live bundle is missing the operations console API client or sign-in.");
  process.exit(1);
}
if (!combined.includes("/devices")) {
  console.error("Live bundle is missing the /devices route.");
  process.exit(1);
}
console.log("Live bundle contains the operations console.");

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
