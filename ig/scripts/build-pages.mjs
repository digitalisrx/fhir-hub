#!/usr/bin/env node
//
// Builds ig/input/pagecontent/ — the narrative half of the published Implementation Guide.
//
// The contract prose is NOT written here. It is written once, in ../IMPLEMENTATION_GUIDE.md,
// and split into pages by this script, because a specification that exists twice is a
// specification that disagrees with itself. Only the pages that are *about the publication*
// rather than about the interface — the front door, the change policy, the changelog, the
// download instructions — are hand-written, and those live in ig/pages/.
//
// input/pagecontent/ is therefore a build output and is not committed. Run this before the
// publisher; `npm run build` does both.
//
// It fails rather than guesses. Three things have to agree — the guide's `##` sections, the
// SECTIONS table below, and the `pages:` block of sushi-config.yaml — and any drift between
// them is an error with the offending name in it. That is the point: a section added to the
// guide cannot silently go unpublished, and a page listed in sushi-config cannot silently be
// empty.

import { readFileSync, writeFileSync, mkdirSync, rmSync, readdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const IG = join(dirname(fileURLToPath(import.meta.url)), '..');
const GUIDE = join(IG, '..', 'IMPLEMENTATION_GUIDE.md');
const CONFIG = join(IG, 'sushi-config.yaml');
const HANDWRITTEN = join(IG, 'pages');
const OUT = join(IG, 'input', 'pagecontent');

// Where each `##` section of the guide is published. The key is the heading text verbatim,
// backticks and all, so a retitled section is a build failure rather than a lost page.
const SECTIONS = new Map([
	['Conventions', 'conventions.md'],
	['Authentication', 'authentication.md'],
	// One page per application, and then that application's operations. The order here is the
	// order of the guide, which is the order a reader is meant to meet them in.
	['Prescriptor', 'prescriptor.md'],
	['The Prescriptor flow', 'flow.md'],
	['`GET /fhir/evs/metadata`', 'metadata.md'],
	['`POST /fhir/evs/$formulary-session`', 'formulary-session.md'],
	['`POST /fhir/evs/$createrx-session`', 'createrx-session.md'],
	['`GET /fhir/evs/$session-result`', 'session-result.md'],
	['Surveillance', 'surveillance.md'],
	['`POST /fhir/surveillance/$check-medication-request`', 'check-medication-request.md'],
	['`POST /fhir/surveillance/$check-medication-statement`', 'check-medication-statement.md'],
	['Lab determinations', 'lab-determinations.md'],
	['Profiles', 'profiles.md'],
	['Code systems', 'code-systems.md'],
	['Extensions', 'extensions.md'],
	['Errors', 'errors.md'],
	['Behaviour to design around', 'design-notes.md'],
	['Moving from the JSON API', 'migration.md'],
	['Current limitations', 'limitations.md'],
]);

// The guide's own table of contents is dropped: the IG has a menu, and two navigations that
// have to be kept in step is one too many.
const DROP = new Set(['Table of contents']);

// The guide's preamble is the front door's opening, spliced into pages/index.md at this marker
// so the orientation text also exists only once.
const PREAMBLE_MARKER = '<!-- GUIDE-PREAMBLE -->';

const GENERATED_BANNER = (source) =>
	`<!-- GENERATED from ${source} by ig/scripts/build-pages.mjs. Do not edit: your changes will be overwritten on the next build. -->\n\n`;

const fail = (message) => {
	console.error(`build-pages: ${message}`);
	process.exit(1);
};

/** GitHub's and kramdown's heading slug, which is what the guide's own anchors assume. */
const slugify = (heading) =>
	heading
		.toLowerCase()
		.replace(/`/g, '')
		.replace(/[^\w\s-]/g, '')
		.trim()
		.replace(/\s+/g, '-');

// ---------------------------------------------------------------------------
// Read the guide and cut it into sections.
// ---------------------------------------------------------------------------

const guide = readFileSync(GUIDE, 'utf8');
const lines = guide.split('\n');

const preamble = [];
const sections = []; // { heading, body: string[] }
let inFence = false;

for (const line of lines) {
	if (line.startsWith('```')) {
		inFence = !inFence;
	}
	// A `##` inside a fenced block is sample content, not a heading.
	const heading = !inFence && /^## (?!#)/.test(line) ? line.slice(3).trim() : null;

	if (heading) {
		sections.push({ heading, body: [] });
	} else if (sections.length === 0) {
		preamble.push(line);
	} else {
		sections.at(-1).body.push(line);
	}
}

if (sections.length === 0) {
	fail(`no '## ' sections found in ${GUIDE} — has the guide changed shape?`);
}

// ---------------------------------------------------------------------------
// Check the three lists against each other before writing anything.
// ---------------------------------------------------------------------------

const published = sections.filter((s) => !DROP.has(s.heading));

for (const { heading } of published) {
	if (!SECTIONS.has(heading)) {
		fail(
			`the guide has a section this script does not publish: '${heading}'.\n` +
				`  Add it to SECTIONS in ig/scripts/build-pages.mjs and to 'pages:' in sushi-config.yaml,\n` +
				`  or add it to DROP if it is deliberately not part of the published spec.`
		);
	}
}

for (const heading of SECTIONS.keys()) {
	if (!published.some((s) => s.heading === heading)) {
		fail(`SECTIONS maps '${heading}', which the guide no longer has. Retitled, or removed?`);
	}
}

// sushi-config.yaml is the publisher's page list; it has to name every file produced here.
const configured = [
	...readFileSync(CONFIG, 'utf8')
		.replace(/[\s\S]*^pages:$/m, '')
		.matchAll(/^ {2}([\w.-]+\.md):$/gm),
].map((m) => m[1]);

if (configured.length === 0) {
	fail(`no 'pages:' entries found in ${CONFIG}`);
}

const handwritten = readdirSync(HANDWRITTEN).filter((f) => f.endsWith('.md'));
const produced = new Set([...SECTIONS.values(), ...handwritten]);

for (const page of configured) {
	if (!produced.has(page)) {
		fail(`sushi-config.yaml lists page '${page}', which nothing produces (no guide section maps to it and ig/pages/${page} does not exist)`);
	}
}
for (const page of produced) {
	if (!configured.includes(page)) {
		fail(`page '${page}' is produced but not listed under 'pages:' in sushi-config.yaml, so the publisher would not render it`);
	}
}

// ---------------------------------------------------------------------------
// Build the anchor map, so the guide's intra-document links keep working across pages.
// ---------------------------------------------------------------------------

const anchors = new Map(); // slug -> { page, fragment } | 'AMBIGUOUS'

const record = (slug, target) => {
	anchors.set(slug, anchors.has(slug) ? 'AMBIGUOUS' : target);
};

for (const { heading, body } of published) {
	const page = SECTIONS.get(heading);
	// A section's own heading becomes the page title, so a link to it is a link to the page.
	record(slugify(heading), { page, fragment: '' });

	let fenced = false;
	for (const line of body) {
		if (line.startsWith('```')) fenced = !fenced;
		if (fenced) continue;
		const sub = /^(#{3,6}) (.*)$/.exec(line);
		if (sub) {
			const slug = slugify(sub[2]);
			record(slug, { page, fragment: `#${slug}` });
		}
	}
}

/** Rewrite `](#slug)` into a link to the page the heading now lives on. */
const relink = (markdown, sourceName) =>
	markdown.replace(/\]\(#([\w-]+)\)/g, (whole, slug) => {
		const target = anchors.get(slug);
		if (!target) {
			fail(`${sourceName} links to '#${slug}', which is not a heading in the guide`);
		}
		if (target === 'AMBIGUOUS') {
			fail(
				`${sourceName} links to '#${slug}', but more than one section has a heading with that slug, ` +
					`so the page it lands on is not decidable. Make the heading unique or link to the page explicitly.`
			);
		}
		return `](${target.page.replace(/\.md$/, '.html')}${target.fragment})`;
	});

/**
 * The page title is rendered by the template, so a section's own heading is dropped and the
 * headings below it move up one level — otherwise the page would name itself twice and its
 * first heading would be an h3 under an h1.
 */
const promoteHeadings = (body) => {
	let fenced = false;
	return body.map((line) => {
		if (line.startsWith('```')) fenced = !fenced;
		if (fenced) return line;
		return line.replace(/^(#{3,6}) /, (whole, hashes) => `${hashes.slice(1)} `);
	});
};

// jsonc is not a language the publisher's highlighter knows; the payloads carry comments, so
// they are annotated JSON rather than JSON, and highlighting them as JSON is the closest thing
// that renders.
const highlightAsJson = (markdown) => markdown.replace(/^```jsonc$/gm, '```json');

const render = (body, sourceName) =>
	highlightAsJson(relink(promoteHeadings(body).join('\n').trim(), sourceName));

// ---------------------------------------------------------------------------
// Write.
// ---------------------------------------------------------------------------

rmSync(OUT, { recursive: true, force: true });
mkdirSync(OUT, { recursive: true });

for (const { heading, body } of published) {
	const page = SECTIONS.get(heading);
	writeFileSync(
		join(OUT, page),
		GENERATED_BANNER(`IMPLEMENTATION_GUIDE.md, section "${heading}"`) + render(body, `the guide's "${heading}"`) + '\n'
	);
}

// The guide's h1 is dropped: the template renders the page title, and the front door should
// not name itself twice.
const preambleText = relink(
	highlightAsJson(preamble.join('\n').replace(/^# .*\n/, '').trim()),
	"the guide's preamble"
);
let splicedPreamble = false;

for (const page of handwritten) {
	let content = readFileSync(join(HANDWRITTEN, page), 'utf8');
	if (content.includes(PREAMBLE_MARKER)) {
		content = content.replace(PREAMBLE_MARKER, preambleText);
		splicedPreamble = true;
	}
	writeFileSync(join(OUT, page), GENERATED_BANNER(`ig/pages/${page}`) + relink(content, `ig/pages/${page}`).trim() + '\n');
}

if (!splicedPreamble) {
	fail(`no hand-written page carries ${PREAMBLE_MARKER}, so the guide's preamble would not be published anywhere`);
}

console.log(
	`build-pages: ${published.length} pages from IMPLEMENTATION_GUIDE.md, ${handwritten.length} hand-written, into input/pagecontent/`
);
