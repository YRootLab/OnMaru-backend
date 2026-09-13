import fs from 'node:fs';
import { execFileSync } from 'node:child_process';
import { parsePublishMode, planRelationshipChanges } from './lib/issue-graph-publish.mjs';

const repo = 'YRootLab/OnMaru-backend';
const mode = parsePublishMode(process.argv.slice(2));
const graphPath = 'docs/planning/github-issues/work-graph.json';
const treePath = 'docs/planning/github-issues/issue-tree.json';
const statePath = 'docs/planning/github-issues/publication.json';
const graph = JSON.parse(fs.readFileSync(graphPath, 'utf8'));
const tree = JSON.parse(fs.readFileSync(treePath, 'utf8'));
const state = fs.existsSync(statePath)
  ? JSON.parse(fs.readFileSync(statePath, 'utf8'))
  : { repo, root: null, tracks: {}, leaves: {}, publishedAt: null };
const byId = new Map(graph.issues.map((issue) => [issue.id, issue]));
const trackByLeaf = new Map(tree.tracks.flatMap((track) => track.children.map((id) => [id, track])));
const blocks = new Map(graph.issues.map((issue) => [issue.id, []]));
for (const issue of graph.issues) for (const dependency of issue.dependencies) blocks.get(dependency).push(issue.id);

const wave = new Map();
function getWave(id) {
  if (wave.has(id)) return wave.get(id);
  const dependencies = byId.get(id).dependencies;
  const result = dependencies.length === 0 ? 0 : Math.max(...dependencies.map(getWave)) + 1;
  wave.set(id, result);
  return result;
}
for (const issue of graph.issues) getWave(issue.id);

function gh(args) {
  return execFileSync('gh', [...args, '--repo', repo], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
}

function viewIssue(number) {
  return JSON.parse(gh(['issue', 'view', String(number), '--json', 'number,body,parent,blockedBy']));
}

function editIssue(number, args) {
  if (args.length) gh(['issue', 'edit', String(number), ...args]);
}

function save() {
  fs.writeFileSync(statePath, `${JSON.stringify(state, null, 2)}\n`);
}

function create(title, body, labels, parent, blockedBy = []) {
  const args = ['issue', 'create', '--title', title, '--body', body];
  for (const label of labels) args.push('--label', label);
  if (parent) args.push('--parent', String(parent));
  if (blockedBy.length) args.push('--blocked-by', blockedBy.join(','));
  const url = gh(args);
  const number = Number(url.split('/').at(-1));
  return { number, url };
}

function fullMermaid() {
  const groups = new Map();
  for (const issue of graph.issues) {
    const number = getWave(issue.id);
    if (!groups.has(number)) groups.set(number, []);
    groups.get(number).push(issue);
  }
  const lines = ['flowchart LR'];
  for (const [number, issues] of [...groups].sort(([a], [b]) => a - b)) {
    lines.push(`  subgraph Wave${number}`);
    for (const issue of issues) lines.push(`    ${issue.id}["${issue.id}: ${issue.title.replaceAll('"', "'")}"]`);
    lines.push('  end');
  }
  for (const issue of graph.issues) for (const dependency of issue.dependencies) lines.push(`  ${dependency} --> ${issue.id}`);
  return lines.join('\n');
}

function rootBody() {
  const lines = ['## Goal', '', graph.root.goal, '', '## Background / Motivation', '', graph.root.background, '', '## Scope', '', graph.root.scope, '', '## Out of Scope', '', graph.root.out_of_scope, '', '## Success Criteria', ''];
  for (const criterion of graph.root.success_criteria) lines.push(`- [ ] ${criterion}`);
  lines.push('', '## Architecture / Approach 요약', '', graph.root.architecture_summary, '', '## Child Tracks', '');
  for (const track of tree.tracks) {
    const published = state.tracks[track.id];
    lines.push(`- [ ] ${published ? `#${published.number}` : track.id} ${track.title}`);
  }
  lines.push('', '## Dependency Graph', '', '```mermaid', fullMermaid(), '```', '', '## Execution Waves', '');
  const maxWave = Math.max(...wave.values());
  for (let number = 0; number <= maxWave; number++) lines.push(`- Wave ${number}: ${graph.issues.filter((issue) => wave.get(issue.id) === number).map((issue) => issue.id).join(', ')}`);
  lines.push('', '## Integration Gates', '', '- 각 Leaf는 모든 blocked-by가 Closed일 때만 Ready다.', '- 같은 Wave라도 Expected Touch Points가 겹치면 병렬 실행하지 않는다.', '- Track은 모든 필수 Leaf의 AC와 merge를 확인한 뒤 닫는다.', '- P3 RAG는 평가 gate 미통과 시 비활성 상태를 정상 결과로 기록한다.', '', '## Risks', '');
  for (const risk of graph.root.risks) lines.push(`- ${risk}`);
  lines.push('', '## Definition of Done', '');
  for (const criterion of graph.root.definition_of_done) lines.push(`- [ ] ${criterion}`);
  lines.push('', '## Agent Scheduling', '', '- `Ready`: Open + 모든 blocked-by Closed + 담당자/열린 PR 없음', '- `Waiting`: blocked-by 중 하나 이상 Open', '- `In progress`: 담당자 또는 연결된 열린 PR 존재', '- `Review`: PR CI/review 진행 중', '- `Done`: AC 검증 + PR merge + Issue Closed');
  return `${lines.join('\n')}\n`;
}

function trackBody(track) {
  const lines = ['## Objective', '', `이 Track의 ${track.children.length}개 구현 Leaf와 통합 상태를 추적한다. 이 Issue 자체는 구현 코드를 소유하지 않는다.`, '', '## Child Issues', ''];
  for (const id of track.children) {
    const published = state.leaves[id];
    lines.push(`- [ ] ${published ? `#${published.number}` : id} ${byId.get(id).title}`);
  }
  lines.push('', '## Execution Rule', '', '- Leaf의 native blocked-by가 모두 Closed일 때만 Ready다.', '- 같은 Wave라도 touch point가 겹치면 한쪽을 Waiting으로 둔다.', '- 담당자 또는 열린 PR이 있는 Leaf를 다른 Agent가 중복 구현하지 않는다.', '', '## Definition of Done', '', '- [ ] 모든 P0/P1/P2 Leaf의 Acceptance Criteria와 merge 상태를 확인했다.', '- [ ] P3 Leaf는 gate 결과에 따라 완료 또는 비활성 근거를 기록했다.', '- [ ] Track 통합 검증 결과를 연결했다.');
  return `${lines.join('\n')}\n`;
}

function ref(id) {
  const published = state.leaves[id];
  return published ? `#${published.number}` : id;
}

function leafBody(issue) {
  const track = trackByLeaf.get(issue.id);
  const lines = [`**Stable ID:** ${issue.id}  `, `**Priority:** ${issue.priority}  `, `**Wave:** ${wave.get(issue.id)}  `, `**Parent Track:** #${state.tracks[track.id].number}`, '', '## Objective', '', issue.objective, '', '## Context', '', issue.context, '', '## Scope', '', issue.scope, '', '## Out of Scope', '', issue.out_of_scope, '', '## Implementation Notes', '', issue.implementation_notes, '', '## Related Code / Modules', '', issue.related_code, '', '## Dependencies (blocked-by)', ''];
  lines.push(issue.dependencies.length ? issue.dependencies.map((id) => `- ${ref(id)} ${byId.get(id).title}`).join('\n') : '- 없음 (Wave 0, 즉시 Ready 후보)');
  lines.push('', '## Blocks', '');
  lines.push(blocks.get(issue.id).length ? blocks.get(issue.id).map((id) => `- ${ref(id)} ${byId.get(id).title}`).join('\n') : '- 없음');
  lines.push('', '## Position in Graph', '', '```mermaid', 'flowchart LR');
  for (const id of [...new Set([...issue.dependencies, issue.id, ...blocks.get(issue.id)])]) lines.push(`  ${id}["${id}: ${byId.get(id).title.replaceAll('"', "'")}"]`);
  for (const id of issue.dependencies) lines.push(`  ${id} --> ${issue.id}`);
  for (const id of blocks.get(issue.id)) lines.push(`  ${issue.id} --> ${id}`);
  lines.push('```', '', '## Expected Touch Points', '');
  for (const point of issue.expected_touch_points) lines.push(`- \`${point}\``);
  lines.push('', '## Parallel Safety / Conflict Notes', '', issue.parallel_notes || '같은 Wave의 다른 Leaf와 touch point가 겹치지 않는다.', '', '## Acceptance Criteria', '');
  for (const criterion of issue.acceptance_criteria) lines.push(`- [ ] ${criterion}`);
  lines.push('', '## Verification Method', '', issue.verification_method, '', '## Agent Session State', '', '- 모든 blocked-by가 Closed이면 `Ready`.', '- blocked-by가 하나라도 Open이면 `Waiting`; 구현을 시작하지 않는다.', '- 담당자 또는 연결된 열린 PR이 있으면 `In progress`; 중복 구현하지 않는다.', '- PR CI·review 중에는 `Review`; merge와 AC 확인 후에만 `Done`으로 닫는다.');
  return `${lines.join('\n')}\n`;
}

const ordered = [...graph.issues].sort((a, b) => getWave(a.id) - getWave(b.id) || a.id.localeCompare(b.id));

function inspectExistingState() {
  const result = {
    createRoot: state.root ? 0 : 1,
    createTracks: tree.tracks.filter((track) => !state.tracks[track.id]).length,
    createLeaves: graph.issues.filter((issue) => !state.leaves[issue.id]).map((issue) => issue.id),
    bodyUpdates: 0,
    parentUpdates: [],
    blockedByAdds: [],
    blockedByRemoves: [],
  };

  const inspectBody = (published, expectedBody) => {
    if (!published) return null;
    const remote = viewIssue(published.number);
    if (remote.body !== expectedBody.trimEnd()) result.bodyUpdates += 1;
    return remote;
  };

  inspectBody(state.root, state.root ? rootBody() : '');
  for (const track of tree.tracks) {
    const published = state.tracks[track.id];
    const remote = inspectBody(published, published ? trackBody(track) : '');
    if (remote && remote.parent?.number !== state.root.number) {
      result.parentUpdates.push({ id: track.id, number: published.number, parent: state.root.number });
    }
  }

  for (const issue of graph.issues) {
    const published = state.leaves[issue.id];
    const remote = inspectBody(published, published ? leafBody(issue) : '');
    if (!remote) continue;
    const desiredParent = state.tracks[trackByLeaf.get(issue.id).id].number;
    if (remote.parent?.number !== desiredParent) {
      result.parentUpdates.push({ id: issue.id, number: published.number, parent: desiredParent });
    }
    const current = remote.blockedBy.nodes.map((node) => node.number);
    const desired = issue.dependencies.map((id) => state.leaves[id]?.number).filter(Boolean);
    const relationshipChanges = planRelationshipChanges(current, desired);
    if (relationshipChanges.add.length) result.blockedByAdds.push({ id: issue.id, number: published.number, numbers: relationshipChanges.add });
    if (relationshipChanges.remove.length) result.blockedByRemoves.push({ id: issue.id, number: published.number, numbers: relationshipChanges.remove });
  }
  return result;
}

function printPlan(plan) {
  console.log(JSON.stringify({
    mode,
    issuesAfterApply: 1 + tree.tracks.length + graph.issues.length,
    create: {
      root: plan.createRoot,
      tracks: plan.createTracks,
      leaves: plan.createLeaves,
    },
    update: {
      bodies: plan.bodyUpdates,
      parents: plan.parentUpdates.length,
      blockedByAdds: plan.blockedByAdds.reduce((sum, item) => sum + item.numbers.length, 0),
      blockedByRemoves: plan.blockedByRemoves.reduce((sum, item) => sum + item.numbers.length, 0),
    },
  }, null, 2));
}

if (mode === 'dry-run') {
  printPlan(inspectExistingState());
  process.exit(0);
}

for (const [name, color, description] of [
  ['priority:P0', 'B60205', 'Blocks foundational backend work'],
  ['priority:P1', 'D93F0B', 'Required R1/R2 product work'],
  ['priority:P2', 'FBCA04', 'AI journey and operational completion'],
  ['priority:P3', '0E8A16', 'Conditional work enabled only after evaluation']
]) execFileSync('gh', ['label', 'create', name, '--repo', repo, '--color', color, '--description', description, '--force'], { stdio: 'ignore' });

if (!state.root) {
  state.root = create('[Root] OnMaru Backend 전체 구현', rootBody(), ['BE', 'Feature', 'Planning'], null);
  save();
  console.log(`created ROOT #${state.root.number}`);
}

for (const track of tree.tracks) if (!state.tracks[track.id]) {
  state.tracks[track.id] = create(`[${track.id}] ${track.title}`, trackBody(track), ['BE', 'Feature', 'Planning'], state.root.number);
  save();
  console.log(`created ${track.id} #${state.tracks[track.id].number}`);
}

for (const issue of ordered) if (!state.leaves[issue.id]) {
  const dependencyNumbers = issue.dependencies.map((id) => state.leaves[id].number);
  const track = trackByLeaf.get(issue.id);
  state.leaves[issue.id] = create(`[${issue.id}] ${issue.title}`, leafBody(issue), [...issue.labels, `priority:${issue.priority}`], state.tracks[track.id].number, dependencyNumbers);
  save();
  console.log(`created ${issue.id} #${state.leaves[issue.id].number}`);
}

const plan = inspectExistingState();
if (plan.bodyUpdates) {
  editIssue(state.root.number, ['--body', rootBody()]);
  for (const track of tree.tracks) editIssue(state.tracks[track.id].number, ['--body', trackBody(track)]);
  for (const issue of graph.issues) editIssue(state.leaves[issue.id].number, ['--body', leafBody(issue)]);
}
for (const update of plan.parentUpdates) editIssue(update.number, ['--parent', String(update.parent)]);
for (const update of plan.blockedByAdds) editIssue(update.number, ['--add-blocked-by', update.numbers.join(',')]);
for (const update of plan.blockedByRemoves) editIssue(update.number, ['--remove-blocked-by', update.numbers.join(',')]);

state.publishedAt = new Date().toISOString();
save();
printPlan(plan);
console.log(`published ${1 + tree.tracks.length + graph.issues.length} issues`);
