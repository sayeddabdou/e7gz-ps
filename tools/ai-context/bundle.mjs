#!/usr/bin/env node
// Builds ONE text file with everything a browser AI chat needs for a Jira task.
// Usage (from the repo root):  node tools/ai-context/bundle.mjs E7-50
// Output: .ai-context/E7-50.md  -> upload it (or paste it) as the first message of a NEW chat.
import fs from 'node:fs';
import path from 'node:path';

const key = (process.argv[2] || '').toUpperCase();
const root = process.cwd();
const tasks = JSON.parse(fs.readFileSync(path.join(root, 'tools/ai-context/tasks.json'), 'utf8'));
const task = tasks[key];
if (!task) { console.error(`Unknown task "${key}". Example: node tools/ai-context/bundle.mjs E7-50`); process.exit(1); }

const MAX_FILE = 60_000;          // skip single files bigger than this
const TEXT = /\.(ts|tsx|js|jsx|json|css|md|html)$/;
const read = p => { try { return fs.readFileSync(path.join(root, p), 'utf8'); } catch { return null; } };
const walk = dir => {
  const abs = path.join(root, dir);
  if (!fs.existsSync(abs)) return [];
  if (fs.statSync(abs).isFile()) return [dir];
  return fs.readdirSync(abs, { withFileTypes: true }).flatMap(e =>
    e.name === 'node_modules' || e.name.startsWith('.') ? [] :
    e.isDirectory() ? walk(path.join(dir, e.name)) : TEXT.test(e.name) ? [path.join(dir, e.name)] : []);
};
const block = (title, body, lang = '') => `\n\n## ${title}\n\n\`\`\`${lang}\n${body.trim()}\n\`\`\`\n`;

let out = `# Context for ${key} — ${task.title}\n\n`;
out += `You are my coding assistant for the E7GZ PS project. Everything you need is below.\n`;
out += `Rules: follow AGENTS.md; give COMPLETE files with their full path; only touch the folders listed; use only fields that exist in the API contract; if something is unclear or missing, ask me instead of guessing.\n`;
out += `First reply: restate the task in 3 lines and list the files you will create or change. Wait for my OK before writing code.\n`;

out += block('AGENTS.md (project rules)', read('AGENTS.md') || '(missing)', 'markdown');
out += `\n\n## The task\n\n**${key} · ${task.title}** — ${task.what}\n\n**Steps**\n${task.steps.map((s, i) => `${i + 1}. ${s}`).join('\n')}\n\n**Finished when**\n${task.done.map(s => `- ${s}`).join('\n')}\n\n**Write only in**\n${task.folders.map(f => `- ${f}`).join('\n')}\n`;

// API contract: whole file if small, else only the paths that match the task's feature words
const api = read('packages/api-client/openapi.json');
if (api) {
  if (api.length < 120_000) out += block('API contract (packages/api-client/openapi.json)', api, 'json');
  else {
    const spec = JSON.parse(api);
    const words = task.title.toLowerCase().split(/[^a-z]+/).filter(w => w.length > 3);
    const paths = Object.fromEntries(Object.entries(spec.paths || {}).filter(([p]) => words.some(w => p.toLowerCase().includes(w))));
    out += block('API contract — matching paths only (ask me for more if needed)', JSON.stringify({ paths, components: spec.components }, null, 1).slice(0, 120_000), 'json');
  }
}

// design tokens + i18n + existing code in the task folders
const extra = ['packages/tokens/src', ...task.folders.map(f => f.replace(/ \(.*\)$/, ''))];
for (const dir of extra) for (const f of walk(dir)) {
  const c = read(f);
  if (c && c.length < MAX_FILE) out += block(`File: ${f}`, c, path.extname(f).slice(1));
}
for (const f of ['apps/customer/src/i18n/en.json', 'apps/venue-dashboard/src/i18n/en.json', 'apps/admin-web/src/i18n/en.json'])
  if (task.folders.some(x => x.startsWith(f.split('/src')[0]))) { const c = read(f); if (c) out += block(`File: ${f}`, c, 'json'); }

fs.mkdirSync(path.join(root, '.ai-context'), { recursive: true });
const file = path.join('.ai-context', `${key}.md`);
fs.writeFileSync(path.join(root, file), out);
console.log(`Wrote ${file} (${Math.round(out.length / 1024)} KB). Start a NEW chat and upload or paste it.`);
if (out.length > 400_000) console.log('Warning: large bundle — some free chats will cut it. Use Gemini CLI instead, or remove files you do not need.');
