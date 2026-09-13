import fs from 'node:fs';

const graphPath = 'docs/planning/github-issues/work-graph.json';
const treePath = 'docs/planning/github-issues/issue-tree.json';
const graph = JSON.parse(fs.readFileSync(graphPath, 'utf8'));
const tree = JSON.parse(fs.readFileSync(treePath, 'utf8'));
const byId = new Map(graph.issues.map((issue) => [issue.id, issue]));
const blocks = new Map(graph.issues.map((issue) => [issue.id, []]));
for (const issue of graph.issues) for (const dependency of issue.dependencies) blocks.get(dependency).push(issue.id);

const wave = new Map();
function getWave(id) {
  if (wave.has(id)) return wave.get(id);
  const dependencies = byId.get(id).dependencies;
  const value = dependencies.length === 0 ? 0 : Math.max(...dependencies.map(getWave)) + 1;
  wave.set(id, value);
  return value;
}
for (const issue of graph.issues) getWave(issue.id);

const waveGroups = new Map();
for (const issue of graph.issues) {
  const value = wave.get(issue.id);
  if (!waveGroups.has(value)) waveGroups.set(value, []);
  waveGroups.get(value).push(issue);
}

function localMermaid(issue) {
  const ids = [...issue.dependencies, issue.id, ...blocks.get(issue.id)];
  const lines = ['flowchart LR'];
  for (const id of [...new Set(ids)]) lines.push(`  ${id}["${id}: ${byId.get(id).title.replaceAll('"', "'")}"]`);
  for (const dependency of issue.dependencies) lines.push(`  ${dependency} --> ${issue.id}`);
  for (const blocked of blocks.get(issue.id)) lines.push(`  ${issue.id} --> ${blocked}`);
  return lines.join('\n');
}

function fullMermaid() {
  const lines = ['flowchart LR'];
  for (const [number, issues] of [...waveGroups.entries()].sort(([a], [b]) => a - b)) {
    lines.push(`  subgraph Wave${number}`);
    for (const issue of issues) lines.push(`    ${issue.id}["${issue.id}: ${issue.title.replaceAll('"', "'")}"]`);
    lines.push('  end');
  }
  for (const issue of graph.issues) for (const dependency of issue.dependencies) lines.push(`  ${dependency} --> ${issue.id}`);
  return lines.join('\n');
}

const list = [];
list.push('# Backend GitHub Issue 발행 목록', '', '이 문서는 GitHub 생성 직전 검토 목록이다. 실제 번호는 생성 후 ID 옆에 기록한다.', '');
list.push('## 규모와 우선순위', '', `- Mega Root 1개`, `- Track control issue ${tree.tracks.length}개`, `- 구현 Leaf ${graph.issues.length}개`, `- 총 GitHub Issue ${graph.issues.length + tree.tracks.length + 1}개`, '');
for (const priority of ['P0', 'P1', 'P2', 'P3']) list.push(`- ${priority}: ${graph.issues.filter((issue) => issue.priority === priority).length}개`);
list.push('', '## Track별 목록', '');
for (const track of tree.tracks) {
  list.push(`### ${track.title}`, '', '| ID | Priority | Wave | 제목 | blocked-by |', '|---|---:|---:|---|---|');
  for (const id of track.children) {
    const issue = byId.get(id);
    list.push(`| ${id} | ${issue.priority} | ${wave.get(id)} | ${issue.title} | ${issue.dependencies.join(', ') || '없음'} |`);
  }
  list.push('');
}
list.push('## Execution Waves', '');
for (const [number, issues] of [...waveGroups.entries()].sort(([a], [b]) => a - b)) list.push(`- Wave ${number}: ${issues.map((issue) => issue.id).join(', ')}`);
list.push('', '## Dependency Graph', '', '```mermaid', fullMermaid(), '```', '');
fs.writeFileSync('docs/planning/github-issues/README.md', `${list.join('\n').trimEnd()}\n`);

const drafts = [];
drafts.push('# Backend GitHub Issue 본문 초안', '', 'GitHub 번호 발급 전에는 안정 ID를 사용한다. 생성 후 ID를 실제 `#번호`로 치환한다.', '');
drafts.push('## Mega Root', '', '### Goal', '', graph.root.goal, '', '### Scope', '', graph.root.scope, '', '### Out of Scope', '', graph.root.out_of_scope, '', '### Success Criteria', '');
for (const criterion of graph.root.success_criteria) drafts.push(`- [ ] ${criterion}`);
drafts.push('', '### Child Tracks', '');
for (const track of tree.tracks) drafts.push(`- [ ] ${track.id}: ${track.title}`);
drafts.push('', '### Dependency Graph', '', '```mermaid', fullMermaid(), '```', '');

for (const track of tree.tracks) {
  drafts.push(`## ${track.id}. ${track.title}`, '', '### Objective', '', `이 Track의 ${track.children.length}개 Leaf 진행과 통합 상태를 추적한다. 직접 구현 코드를 포함하지 않는다.`, '', '### Child Issues', '');
  for (const id of track.children) drafts.push(`- [ ] ${id}: ${byId.get(id).title}`);
  drafts.push('', '### Definition of Done', '', '- [ ] 모든 필수 Leaf의 Acceptance Criteria와 merge 상태를 확인했다.', '- [ ] Track 내 통합 검증 결과를 연결했다.', '');
}

for (const track of tree.tracks) for (const id of track.children) {
  const issue = byId.get(id);
  drafts.push(`## ${id}. ${issue.title}`, '', `**Priority:** ${issue.priority}`, '', `**Wave:** ${wave.get(id)}`, '', `**Parent Track:** ${track.id}`, '', '### Objective', '', issue.objective, '', '### Context', '', issue.context, '', '### Scope', '', issue.scope, '', '### Out of Scope', '', issue.out_of_scope, '', '### Implementation Notes', '', issue.implementation_notes, '', '### Related Code / Modules', '', issue.related_code, '', '### Dependencies (blocked-by)', '');
  drafts.push(issue.dependencies.length ? issue.dependencies.map((dependency) => `- ${dependency}: ${byId.get(dependency).title}`).join('\n') : '- 없음 (즉시 Ready 후보)');
  drafts.push('', '### Blocks', '');
  drafts.push(blocks.get(id).length ? blocks.get(id).map((blocked) => `- ${blocked}: ${byId.get(blocked).title}`).join('\n') : '- 없음');
  drafts.push('', '### Position in Graph', '', '```mermaid', localMermaid(issue), '```', '', '### Expected Touch Points', '');
  for (const point of issue.expected_touch_points) drafts.push(`- \`${point}\``);
  drafts.push('', '### Parallel Safety / Conflict Notes', '', issue.parallel_notes || '같은 Wave의 다른 Issue와 touch point가 겹치지 않는다.', '', '### Acceptance Criteria', '');
  for (const criterion of issue.acceptance_criteria) drafts.push(`- [ ] ${criterion}`);
  drafts.push('', '### Verification Method', '', issue.verification_method, '', '### Agent Session State', '', '- blocked-by가 모두 Closed이면 `Ready`.', '- 담당자 또는 연결된 열린 PR이 있으면 `In progress`.', '- blocked-by가 하나라도 Open이면 `Waiting`이며 구현을 시작하지 않는다.', '- PR CI·review 중에는 `Review`; merge와 AC 확인 후에만 `Done`으로 닫는다.', '');
}
fs.writeFileSync('docs/planning/github-issues/issue-drafts.md', `${drafts.join('\n').trimEnd()}\n`);
