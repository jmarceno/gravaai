#!/usr/bin/env node
/**
 * Builds the phase UI parity gallery (plan §6.3, §7.4):
 * docs/java-rewrite/phase-<N>-ui.html, one row per screen with the React reference on the
 * left and the JavaFX capture on the right. Plain static HTML with relative image paths.
 *
 *   node java/tools/parity/gallery.mjs [phase]
 */
import { existsSync, mkdirSync, readdirSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const repo = path.resolve(here, '../../..');
const reactDir = path.join(repo, 'java/parity/ui');
const javaDir = path.join(repo, 'java/parity/ui-java');
const outDir = path.join(repo, 'docs/java-rewrite');
const phase = process.argv[2] ?? '1';

const names = new Set();
for (const dir of [reactDir, javaDir]) {
	if (!existsSync(dir)) continue;
	for (const entry of readdirSync(dir)) {
		if (entry.endsWith('.png')) names.add(entry.slice(0, -4));
	}
}
const sorted = [...names].sort();

function cell(name, src, label) {
	if (!src) return '        <figure class="missing"><figcaption>' + label + ': not captured yet</figcaption></figure>';
	return '        <figure><img src="' + src + '" alt="' + name + ' ' + label + '"><figcaption>' + label + '</figcaption></figure>';
}

const rows = sorted.map((name) => {
	const react = existsSync(path.join(reactDir, name + '.png')) ? '../java/parity/ui/' + name + '.png' : null;
	const java = existsSync(path.join(javaDir, name + '.png')) ? '../java/parity/ui-java/' + name + '.png' : null;
	return '      <tr>\n'
		+ '        <th scope="row">' + name + '</th>\n'
		+ '        <td>\n' + cell(name, react, 'React (reference)') + '\n        </td>\n'
		+ '        <td>\n' + cell(name, java, 'JavaFX') + '\n        </td>\n'
		+ '      </tr>';
}).join('\n');

const html = [
	'<!doctype html>',
	'<html lang="en">',
	'<head>',
	'<meta charset="utf-8">',
	'<title>Gravaai - Phase ' + phase + ' UI parity</title>',
	'<style>',
	'	:root { color-scheme: dark; }',
	'	body { margin: 0; padding: 24px; background: #121418; color: #e6e8eb; font: 14px "DM Sans", system-ui, sans-serif; }',
	'	table { border-collapse: collapse; width: 100%; }',
	'	th, td { border: 1px solid #2a2e37; padding: 8px; vertical-align: top; }',
	'	th[scope="row"] { text-align: left; white-space: nowrap; font-weight: 600; }',
	'	img { max-width: 100%; display: block; }',
	'	figcaption { color: #8b929a; margin-bottom: 6px; font-size: 12px; }',
	'	.missing figcaption { color: #e5656e; }',
	'</style>',
	'</head>',
	'<body>',
	'<h1>Gravaai - Phase ' + phase + ' UI parity</h1>',
	'<p>React reference screenshots (browser harness) next to JavaFX captures. Pixel equality is not',
	'expected; compare layout, spacing, colours, typography, icons and wording (plan §6.3).</p>',
	'<table>',
	'    <thead><tr><th>Screen</th><th>Reference</th><th>Java</th></tr></thead>',
	'    <tbody>',
	rows,
	'    </tbody>',
	'</table>',
	'</body>',
	'</html>',
	'',
].join('\n');

mkdirSync(outDir, { recursive: true });
const outFile = path.join(outDir, 'phase-' + phase + '-ui.html');
writeFileSync(outFile, html, 'utf8');
console.log('gallery.mjs: wrote ' + path.relative(repo, outFile) + ' (' + sorted.length + ' screens)');
