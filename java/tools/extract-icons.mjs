#!/usr/bin/env node
/**
 * One-time icon extraction (plan §5.12): reads every icon imported in src/ and renders it with
 * react-dom/server into an SVG file for the JavaFX Icon loader. Never hand-draw icons.
 *
 * Run once from the repository root:
 *   node java/tools/extract-icons.mjs
 *
 * Output (relative to the repository root):
 *   java/gravaai-ui/src/main/resources/kit/icons/<lib>/<weight>/<Name>.svg
 *
 * Solar: src/components/ui/icons.tsx maps the "regular" weight to the linear set and
 * "fill"/"bold" to the bold set, so both variants are extracted.
 * Phosphor: every weight the library offers is extracted.
 * Composites: icons.tsx composes a slash over a library glyph for MicrophoneSlash; the script
 * reproduces that composition from the extracted glyphs (no drawing by hand).
 */
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { readdirSync, readFileSync, mkdirSync, writeFileSync, rmSync } from "node:fs";
import { dirname, join, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const srcDir = join(repoRoot, "src");
const outRoot = join(repoRoot, "java/gravaai-ui/src/main/resources/kit/icons");

const PHOSPHOR_WEIGHTS = ["regular", "thin", "light", "bold", "fill", "duotone"];

function walk(dir) {
  const out = [];
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) out.push(...walk(path));
    else if (/\.(tsx?|mts)$/.test(entry.name)) out.push(path);
  }
  return out;
}

const files = walk(srcDir);
const read = (file) => readFileSync(file, "utf8");

/** @type {Map<string, {specifier: string, weight: string, name: string}>} */
const solar = new Map();
/** @type {Map<string, {specifier: string, name: string}>} */
const phosphor = new Map();
/** @type {Map<string, {specifier: string, name: string}>} */
const reactIcons = new Map();

const solarRe = /from\s+["\']@solar-icons\/react\/(linear|bold)\/([a-z0-9-]+)["\']/g;
const phosphorRe = /(?:import|export)\s*\{([^}]*)\}\s*from\s*["\']@phosphor-icons\/react["\']/g;
const reactIconsRe = /import\s*\{([^}]*)\}\s*from\s*["\'](react-icons\/[a-z0-9]+)["\']/g;

for (const file of files) {
  const text = read(file);
  for (const match of text.matchAll(solarRe)) {
    const [, weight, name] = match;
    solar.set(weight + "/" + name, {
      specifier: "@solar-icons/react/" + weight + "/" + name,
      weight,
      name,
    });
  }
  for (const match of text.matchAll(phosphorRe)) {
    for (const raw of match[1].split(",")) {
      const name = raw.trim().split(/\s+as\s+/)[0].trim();
      if (!name) continue;
      phosphor.set(name, { specifier: "@phosphor-icons/react", name });
    }
  }
  for (const match of text.matchAll(reactIconsRe)) {
    const specifier = match[2];
    for (const raw of match[1].split(",")) {
      const name = raw.trim().split(/\s+as\s+/)[0].trim();
      if (!name) continue;
      reactIcons.set(specifier + "/" + name, { specifier, name });
    }
  }
}

function pickComponent(module, name) {
  const candidates = Object.keys(module).filter(
    (key) => typeof module[key] === "function" || (module[key] && module[key].$$typeof),
  );
  if (candidates.includes(name)) return module[name];
  const icon = candidates.find((key) => key.endsWith("Icon"));
  if (icon) return module[icon];
  if (candidates.length === 1) return module[candidates[0]];
  throw new Error("Cannot find a component export for " + name + " (exports: " + candidates.join(", ") + ")");
}

function svgOf(markup) {
  const start = markup.indexOf("<svg");
  const end = markup.lastIndexOf("</svg>");
  if (start < 0 || end < 0) throw new Error("renderToStaticMarkup did not return an <svg>");
  return markup.slice(start, end + 6);
}

function writeIcon(relativePath, markup) {
  const file = join(outRoot, relativePath);
  mkdirSync(dirname(file), { recursive: true });
  writeFileSync(file, svgOf(markup) + "\n", "utf8");
}

let written = 0;
const manifest = [];

rmSync(outRoot, { recursive: true, force: true });

// Solar: linear and bold variants.
for (const [, entry] of solar) {
  const module = await import(entry.specifier);
  const component = pickComponent(module, entry.name);
  const markup = renderToStaticMarkup(createElement(component, { size: 24 }));
  writeIcon(join("solar", entry.weight, entry.name + ".svg"), markup);
  manifest.push("solar/" + entry.weight + "/" + entry.name);
  written++;
}

// Phosphor: every weight.
for (const [, entry] of phosphor) {
  const module = await import(entry.specifier);
  const component = pickComponent(module, entry.name);
  for (const weight of PHOSPHOR_WEIGHTS) {
    const markup = renderToStaticMarkup(createElement(component, { size: 24, weight }));
    writeIcon(join("phosphor", weight, entry.name + ".svg"), markup);
    manifest.push("phosphor/" + weight + "/" + entry.name);
    written++;
  }
}

// react-icons: single-weight glyphs.
for (const [, entry] of reactIcons) {
  const module = await import(entry.specifier);
  const component = pickComponent(module, entry.name);
  const markup = renderToStaticMarkup(createElement(component, { size: 24 }));
  writeIcon(join("react-icons", entry.name + ".svg"), markup);
  manifest.push("react-icons/" + entry.name);
  written++;
}

// Composites from src/components/ui/icons.tsx: the slash branch renders the glyph inside a
// 24x24 svg and adds the slash path; reproduce that mechanically from the extracted glyphs.
function innerOf(relativePath) {
  const svg = readFileSync(join(outRoot, relativePath), "utf8");
  return svg.replace(/^[\s\S]*?<svg[^>]*>/, "").replace(/<\/svg>[\s\S]*$/, "");
}
const slashComposites = [
  { name: "MicrophoneSlash", linear: "solar/linear/microphone.svg", bold: "solar/bold/microphone.svg" },
];
const OPEN = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"24\" height=\"24\" viewBox=\"0 0 24 24\" fill=\"none\">";
const SLASH = "<path d=\"M4 4L20 20\" stroke=\"currentColor\" stroke-width=\"1.5\" stroke-linecap=\"round\"/></svg>";
for (const composite of slashComposites) {
  for (const [weight, source] of [
    ["linear", composite.linear],
    ["bold", composite.bold],
  ]) {
    const markup = OPEN + innerOf(source) + SLASH;
    writeIcon(join("solar", weight, composite.name + ".svg"), markup);
    manifest.push("solar/" + weight + "/" + composite.name);
    written++;
  }
}

manifest.sort();
writeFileSync(join(outRoot, "manifest.txt"), manifest.join("\n") + "\n", "utf8");

console.log("extract-icons: " + written + " svg files written to " + relative(repoRoot, outRoot));
