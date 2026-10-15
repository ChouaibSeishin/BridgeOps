import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const excluded = new Set(['.git', 'node_modules', 'target', 'dist', '.tools', '.cache', '.tmp', 'coverage', 'playwright-report', 'test-results']);
function walk(dir) {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    if (excluded.has(entry.name)) return [];
    const location = path.join(dir, entry.name);
    return entry.isDirectory() ? walk(location) : [location];
  });
}
const files = walk(root);
const markdown = files.filter((file) => file.endsWith('.md'));
const failures = [];
let links = 0;
let diagrams = 0;
for (const file of markdown) {
  const source = fs.readFileSync(file, 'utf8');
  const relative = path.relative(root, file);
  const fences = source.match(/^\s*```/gm) ?? [];
  if (fences.length % 2) failures.push(`${relative}: unpaired code fence`);
  diagrams += (source.match(/^```mermaid\s*$/gm) ?? []).length;
  // Ignore code examples when checking actual Markdown link destinations.
  const prose = source.replace(/```[^\n]*\n[\s\S]*?```/g, '');
  for (const match of prose.matchAll(/\[[^\]\n]+\]\(([^)\n]+)\)/g)) {
    const target = match[1].replace(/^<|>$/g, '');
    if (/^[a-z][a-z\d+.-]*:/i.test(target) || target.startsWith('#')) continue;
    const local = decodeURIComponent(target.split('#')[0]);
    if (!local) continue;
    links++;
    if (!fs.existsSync(path.resolve(path.dirname(file), local))) {
      failures.push(`${relative}: missing link ${target}`);
    }
  }
}
const required = [
  'docs/CONFLICT_RESOLUTION.md', 'docs/SECURITY.md',
  'docs/development/PHASE_1_IMPLEMENTATION.md', 'docs/development/DEPENDENCY_LOCK.md',
  'docs/PHASE_0_REVIEW.md', 'docs/PROJECT_STATUS.md',
  'docs/product/REQUIREMENTS.md', 'docs/product/BACKLOG.md',
  'docs/product/MASTER_PROJECT_PROMPT.md', 'docs/architecture/SYSTEM.md',
  'docs/architecture/EXECUTION.md', 'docs/architecture/DATABASE.md',
  'docs/security/THREAT_MODEL.md', 'docs/api/CONTRACT_OUTLINE.md',
  'docs/development/TECHNOLOGY.md', 'docs/development/REPOSITORY.md',
  'docs/development/PHASE_1_VERIFICATION.md', 'docs/design/FOUNDATION.md',
  'docs/architecture/adr/README.md', 'frontend/src/styles/tokens.css',
];
for (const requiredFile of required) {
  if (!fs.existsSync(path.join(root, requiredFile))) failures.push(`Missing ${requiredFile}`);
}
const adrs = files.filter((file) => /[\\/]adr[\\/]\d{4}-.+\.md$/.test(file));
if (adrs.length < 14) failures.push('Expected fourteen reviewed architecture decision records');
if (diagrams < 4) failures.push('Expected component, sequence and relationship diagram sources');
if (failures.length) {
  console.error(failures.join('\n'));
  process.exitCode = 1;
} else {
  console.log(`PASS: ${markdown.length} Markdown files, ${links} local links, ${diagrams} Mermaid blocks, ${adrs.length} ADRs.`);
  console.log('Scope: file presence, local link targets and balanced fences only. Mermaid syntax/rendering, external URLs, application behavior and security are not verified.');
}
