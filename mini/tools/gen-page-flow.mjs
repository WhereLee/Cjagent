#!/usr/bin/env node
/**
 * 页面流转图生成器：解析 mini/src/pages.json 与代码里的页面跳转调用，
 * 生成 docs/页面流转.md（Mermaid）。
 *
 * 为什么要用脚本而不是手画一张图：手画的图一旦与代码分叉就再也没人信它。
 * 这里顺手做两类**代码级检查**（--check 时任一不过就退出 1，可进 CI 当门禁）：
 *   1. 跳转目标没在 pages.json 注册 → 点了会白屏（写错一个字母的常见后果）；
 *   2. 页面注册了却没有任何入口能到 → 要么是死页面，要么是漏接线。
 *
 * 用法：
 *   node mini/tools/gen-page-flow.mjs          # 生成文档
 *   node mini/tools/gen-page-flow.mjs --check  # 生成并校验，问题以非零码退出
 *
 * 已知边界：只识别 **字面量** url。`uni.navigateTo({ url })` 这种变量写法看不见，
 * 所以约定：跳转目标一律写完整字面量路径（跳转点本来就少，牺牲一点抽象换来可静态校验值得）。
 */
import fs from 'node:fs'
import path from 'node:path'
import process from 'node:process'

const PROJECT_ROOT = path.resolve(import.meta.dirname, '..', '..')
const SRC_DIR = path.join(PROJECT_ROOT, 'mini', 'src')
const PAGES_JSON = path.join(SRC_DIR, 'pages.json')
const OUTPUT = path.join(PROJECT_ROOT, 'docs', '页面流转.md')

/** 跳转 API 与它的语义差异：reLaunch 会清空页面栈，navigateTo 保留（可返回）。 */
const NAV_PATTERNS = [
  { api: 'navigateTo', label: '进入（可返回）' },
  { api: 'redirectTo', label: '替换当前页' },
  { api: 'reLaunch', label: '重启到（清空栈）' },
  { api: 'switchTab', label: '切换标签' },
]

const checkMode = process.argv.includes('--check')

/** 页面 path → 标题（来自 pages.json 的 navigationBarTitleText） */
function loadPages() {
  const raw = JSON.parse(fs.readFileSync(PAGES_JSON, 'utf8'))
  const pages = raw.pages.map((item) => ({
    path: item.path,
    title: item.style?.navigationBarTitleText ?? item.path,
  }))
  return { pages, entry: pages[0] }
}

function collectSourceFiles(dir) {
  const out = []
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name)
    if (entry.isDirectory()) {
      out.push(...collectSourceFiles(full))
    } else if (/\.(vue|ts)$/.test(entry.name)) {
      out.push(full)
    }
  }
  return out
}

/**
 * 扫出所有跳转调用。
 *
 * 正则不追求解析语法（那要引 AST 依赖），但要求**跨行也能匹配**：
 * `uni.navigateTo({\n  url: '/pages/x' })` 是常见写法，只匹配单行会静默漏掉边，
 * 于是图看起来"没问题"而实际缺线——这比报错更糟。
 */
function collectEdges(files) {
  const edges = []
  for (const file of files) {
    const text = fs.readFileSync(file, 'utf8')
    const from = relativePage(file)
    for (const { api, label } of NAV_PATTERNS) {
      const re = new RegExp(`uni\\.${api}\\s*\\(\\s*\\{[\\s\\S]{0,200}?url:\\s*['"\`]([^'"\`?#]+)`, 'g')
      for (const match of text.matchAll(re)) {
        edges.push({ from, to: normalizeTarget(match[1]), api, label, line: lineOf(text, match.index) })
      }
    }
  }
  return edges
}

function relativePage(file) {
  const rel = path.relative(SRC_DIR, file).replace(/\\/g, '/')
  return rel.startsWith('pages/') ? rel.replace(/\.vue$/, '') : rel
}

function normalizeTarget(url) {
  return url.replace(/^\//, '').replace(/\.vue$/, '')
}

function lineOf(text, index) {
  return text.slice(0, index).split('\n').length
}

function build({ pages, entry }, edges) {
  const titles = new Map(pages.map((p) => [p.path, p.title]))
  const nodes = pages.map((p) => `    ${nodeId(p.path)}["${p.title}<br/>${p.path}"]`)

  const lines = edges.map(
    (e) => `    ${nodeId(e.from)} -->|${e.api}| ${nodeId(e.to)}`,
  )

  const body = [
    '# 骑手端页面流转',
    '',
    '> 本文件由 `node mini/tools/gen-page-flow.mjs` 从 `pages.json` 与页面源码生成，**不要手工编辑**。',
    '> 手画的流转图一旦和代码分叉就再没人信它，所以宁可脚本生成、偶尔过期，也不手写。',
    '',
    '## 图',
    '',
    '```mermaid',
    'graph LR',
    ...nodes,
    ...lines,
    '```',
    '',
    '## 页面清单',
    '',
    '| 页面 | 标题 | 入口 |',
    '|---|---|---|',
    ...pages.map((p) => `| \`${p.path}\` | ${p.title} | ${p.path === entry.path ? '**启动页**' : ''} |`),
    '',
    '## 跳转边',
    '',
    '| 从 | 到 | API | 语义 | 位置 |',
    '|---|---|---|---|---|',
    ...edges.map(
      (e) =>
        `| \`${e.from}\` | \`${e.to}\` | ${e.api} | ${e.label} | ${e.from}:${e.line} |`,
    ),
    '',
    '## 说明',
    '',
    `- 未注册的跳转目标会被 --check 拦下；标题来源：\`pages.json\` 的 \`navigationBarTitleText\`（共 ${titles.size} 页）。`,
    '- 小程序端没有 vue-router，鉴权拦在各页面 `onShow` 的 `ensureAuthenticated()` 与后端每个接口的 `hasRole(\'RIDER\')` 两处；',
    '  凭证失效由请求层 `clearTokensAndRelaunch()` 直接 `reLaunch` 到登录页（用 reLaunch 是为了清掉整条页面栈，',
    '  否则用户按返回会再撞一次 401）。',
    '',
  ].join('\n')

  return body
}

function nodeId(pagePath) {
  return 'P_' + pagePath.replace(/[^a-zA-Z0-9]/g, '_')
}

function verify({ pages, entry }, edges) {
  const problems = []
  const registered = new Set(pages.map((p) => p.path))

  for (const e of edges) {
    if (!registered.has(e.to)) {
      problems.push(`跳转目标未注册：${e.from}:${e.line} → /${e.to}（uni.${e.api} 会失败并白屏）`)
    }
  }

  const reachable = new Set(edges.map((e) => e.to))
  for (const p of pages) {
    if (p.path === entry.path || p.path === 'pages/login/index') continue
    if (!reachable.has(p.path)) {
      problems.push(`页面注册了却没有任何入口可达：${p.path}（死页面或漏接线）`)
    }
  }

  return problems
}

const meta = loadPages()
const files = collectSourceFiles(SRC_DIR)
const edges = collectEdges(files)

fs.mkdirSync(path.dirname(OUTPUT), { recursive: true })
fs.writeFileSync(OUTPUT, build(meta, edges), 'utf8')

console.log(`已生成 ${path.relative(PROJECT_ROOT, OUTPUT)}：${meta.pages.length} 个页面、${edges.length} 条跳转`)

if (checkMode) {
  const problems = verify(meta, edges)
  if (problems.length > 0) {
    console.error('页面流转检查未通过：')
    for (const p of problems) console.error('  - ' + p)
    process.exit(1)
  }
  console.log('页面流转检查通过')
}
