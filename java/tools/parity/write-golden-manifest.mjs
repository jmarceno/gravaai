#!/usr/bin/env node
/**
 * Writes java/parity/golden/manifest.json for the compositor parity tests (plan §6.2, P2.1).
 *
 *   { "cases": [ { "projectPath", "outputWidth", "outputHeight", "times", "referenceImages" } ] }
 *
 * Paths are relative to the manifest directory. Reference PNGs are decoded top-first (they are
 * plain ffmpeg PNGs of the Electron preview capture). A case with missing frames is an error: a
 * partial capture must never become a golden reference set.
 *
 * Comparison thresholds (agreed with the Lead, for media-native's P2.4 tests): dimension mismatch
 * is an error, mean absolute RGB difference <= 2.0, and at most 0.5 % of pixels may have any RGB
 * channel different by more than 2.
 *
 * Runs on its own after captures, or at the end of java/tools/parity/capture-fixtures.sh.
 */
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const repo = path.resolve(here, '../../..');
const goldenDir = path.join(repo, 'java/parity/golden');
const planPath = path.join(repo, 'java/parity/fixtures/capture-plan.json');
const manifestPath = path.join(goldenDir, 'manifest.json');

function pngSize(file) {
	const buffer = readFileSync(file);
	if (buffer.length < 24 || buffer.toString('ascii', 12, 16) !== 'IHDR') {
		throw new Error('not a PNG: ' + file);
	}
	return { width: buffer.readUInt32BE(16), height: buffer.readUInt32BE(20) };
}

const plan = JSON.parse(readFileSync(planPath, 'utf8'));
const cases = [];
for (const entry of plan.projects) {
	const directory = path.join(goldenDir, entry.name);
	if (!existsSync(directory)) {
		continue;
	}
	let outputWidth = 0;
	let outputHeight = 0;
	const referenceImages = [];
	for (const time of entry.timesSeconds) {
		const milliseconds = Math.round(time * 1000);
		const file = path.join(directory, milliseconds + '.png');
		if (!existsSync(file)) {
			throw new Error('case ' + entry.name + ' is missing ' + milliseconds + '.png; finish the capture first');
		}
		const size = pngSize(file);
		if (outputWidth === 0) {
			outputWidth = size.width;
			outputHeight = size.height;
		} else if (size.width !== outputWidth || size.height !== outputHeight) {
			throw new Error('case ' + entry.name + ' has mismatched frame sizes');
		}
		referenceImages.push(entry.name + '/' + milliseconds + '.png');
	}
	if (referenceImages.length === 0) {
		continue;
	}
	cases.push({
		projectPath: path.posix.join('..', 'fixtures', 'projects', entry.project),
		outputWidth,
		outputHeight,
		times: entry.timesSeconds,
		referenceImages,
	});
}

if (cases.length === 0) {
	throw new Error('no captured fixtures under ' + goldenDir);
}
writeFileSync(manifestPath, JSON.stringify({ cases }, null, 2) + '\n');
console.log('write-golden-manifest: ' + cases.length + ' cases -> ' + path.relative(repo, manifestPath));
