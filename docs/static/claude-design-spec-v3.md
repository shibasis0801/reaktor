# Reaktor Desktop — Claude Design Specification v3

## What This Is

Reaktor Desktop is a **developer control panel** for full-stack application development. Think: **Unreal Engine Blueprint Editor + FlutterFlow WYSIWYG + Chrome DevTools + Cloudflare Dashboard + Grafana** — unified by a typed directed graph that models the entire application from UI to database.

This is an **interactive high-fidelity prototype** built with React 19 + Vite. It should look and feel like a real professional developer tool — not a mockup. Every button should do something. Every panel should show real-looking data. The design language is dark, dense, and information-rich — like VS Code, DataGrip, or Grafana, not like Figma or Notion.

The current prototype (v2.1) has 5 screens across ~6,100 lines. This specification defines **v3** — a complete redesign that adds 3 new screens (Agent, Database, Auth), upgrades all existing screens, and makes every interaction fully functional.

---

## Design System

### Color Tokens
```
Background:  --bg: #06080d   --bg-1: #0a0d14   --bg-2: #0f131c   --bg-3: #161b27
Text:        --t-1: #e6eaf2 (primary)  --t-2: #a1abc0 (secondary)  --t-3: #6b7588 (tertiary)  --t-4: #4a536b (muted)
Accent:      --accent: #4b7bff   --accent-2: #6d8bff

Entity Types:
  Route:     #5891ff (blue)        Screen:    #3fbf78 (green)
  UI:        #3fbf78 (green)       Action:    #a075f0 (purple)
  Container: #a075f0 (purple)      Interactor:#e0a04a (amber)
  Service:   #e0a04a (amber)       Edge:      #36c4d4 (cyan)
  DB:        #36c4d4 (cyan)        Topic:     #36c4d4 (cyan)
  Test:      #3fbf78 (green)       Release:   #6d8bff (blue)

Status:  --ok: #3fbf78   --warn: #f0a93a   --error: #ef5b5b
```

### Typography
- **UI text:** Inter, -apple-system, 13-14px
- **Mono/code:** JetBrains Mono, 11-12px
- **Headers:** Inter 600-700 weight
- **Badges/tags:** 9-10px uppercase, letter-spacing 0.08-0.12em

### Component Patterns
- **Cards:** bg-2 background, 1px #1a2031 border, 8px radius, 12-16px padding
- **Badges:** Colored background + border matching entity type, 4px radius, uppercase
- **Buttons:** `.btn` base, `.btn.xs` for inline actions, `.btn.primary` for CTAs
- **Pill rows:** Horizontal button group for mode selection (phone/tablet/desktop, local/staging/prod)
- **Sparklines:** Inline SVG, 60-80px wide, 16-20px tall, area fill with stroke
- **Dot indicators:** 6px circles — green (ok), amber (warn), red (error), gray (muted)
- **Resize handles:** 4px wide strips between panels, cursor: col-resize / row-resize
- **Collapsed panels:** 44px wide strips with vertical icon buttons

---

## Shell Layout

The shell is a 4-region layout:

```
┌─────────────────────────────────────────────────────┐
│ TopBar                                               │
├─────────────────────────────────────────────────────┤
│ ModeToolbar (8 tabs)                                 │
├────────┬──────────────────────────────┬─────────────┤
│ Left   │ Center (screen content)      │ Right       │
│ Sidebar│                              │ Inspector   │
│ 280px  │ flex                         │ 420px       │
│        │                              │             │
├────────┴──────────────────────────────┴─────────────┤
│ Drawer (collapsible, resizable height, 260px)        │
├─────────────────────────────────────────────────────┤
│ StatusStrip                                          │
└─────────────────────────────────────────────────────┘
```

### TopBar
Left: **Reaktor** DESKTOP logo mark + **BestBuds** project name dropdown + environment badges (Local / Staging / Prod with colored dots) + branch name `feature/onboarding-auth` + commit `a4f12c8`
Right: **App Running** indicator (green dot + "App Running") + **Pixel 7 Pro** device dropdown + **Hot Reload** toggle + **Connect** button (IDE connection) + **Deploy** primary CTA button + user avatar

### ModeToolbar (8 modes)
Horizontal tab bar below the top bar. Each tab has an icon + label. Active tab has accent underline + brighter text.

Tabs (left to right):
1. **Graph** — blueprint node editor (icon: connected nodes)
2. **Run App** — WYSIWYG live preview (icon: play triangle)
3. **DevTools** — causal trace + profiling (icon: terminal/wrench)
4. **Deploy** — infrastructure topology + CI (icon: rocket)
5. **Insights** — analytics + telemetry (icon: chart)
6. **Agent** — AI assistant + MCP (icon: sparkle/AI) ← NEW
7. **Database** — multi-DB browser + query (icon: cylinder) ← NEW
8. **Auth** — RBAC + identity + sessions (icon: shield/lock) ← NEW

Below the mode tabs, a **secondary control bar** specific to the active mode. For example:
- Graph: "38 nodes · 39 edges · 0 warnings" + focus controls + overlay toggles
- Run App: device selector pills + theme toggle + network mode
- Deploy: env selector pills + deploy action buttons
- etc.

### Left Sidebar (280px, collapsible to 44px)
Content changes based on active mode:
- **Graph:** Entity tree grouped by type (Routes, Screens, Containers, Services, Edge, Data), each group collapsible, with colored dot + title. Search input at top.
- **Run App:** Component tree (hierarchical: App → OnboardingScreen → VStack → AppleButton → Icon + Label). Shows composable hierarchy.
- **DevTools:** Trace list (recent traces with timing, status, entity name). Filter by status.
- **Deploy:** Resource tree (App Bundles, Cloudflare, Data, JVM Servers, Mobile Runtime)
- **Insights:** Metric selector tree (Performance, Errors, Costs, Traces, Logs, Tests)
- **Agent:** Conversation history list (recent chats with titles and timestamps)
- **Database:** Database tree (Supabase Postgres → tables, Memgraph → graphs, D1 → tables, Durable Objects → namespaces)
- **Auth:** Identity tree (Providers → Apple/Google/Email, Roles → admin/user/moderator, Policies list)

### Right Inspector (420px, collapsible to 44px)
Type-aware inspector that changes based on the selected entity. Always has:
- **Header:** Type badge + entity name + source file link + "Open in IDE" button
- **Section tabs:** Horizontal scrollable tab bar (Overview, Metrics, Connections, Source, Actions) — active tab updates on scroll
- **Sections:** Each section has a header with expand/collapse
- **Footer actions:** Row of xs buttons (Open in Graph, Follow chain, Open in IDE, View trace, Ask Agent)

### Bottom Drawer (260px default, resizable, collapsible to 38px)
Tab bar with 9 tabs:
1. **Command Queue** (shows count badge) — pending GraphCommand objects with status indicators
2. **Code Agent** (shows count badge) — chat messages from the AI agent
3. **Logs** (shows count badge) — filterable log stream from app + workers + build
4. **Traces** (shows count badge) — recent PortInvocationEvent traces
5. **Network** — request/response log (like Chrome DevTools Network tab)
6. **Tests** (shows count badge) — test runner output with pass/fail indicators
7. **Build** — Gradle/Vite build output with step timers
8. **CodeDiff** — side-by-side diff viewer for pending code changes
9. **Diagnostics** — graph health, port wiring status, autoWire resolution

Right side of drawer tab bar: "Revert All" + "Apply All" + "Send to Code Agent" + "Run tests" + "Hot Reload" + "Deploy Staging" primary button

### StatusStrip (bottom bar, 28px)
Left: mode icon + mode label + entity count ("38 nodes · 39 edges") + "graph is valid" indicator
Center: app running indicator + hot reload count + "6/7 compiled"
Right: git branch + commit + "49k lines" + "crash-free 99.7%" + clock

### Search Palette (Cmd+K modal)
Centered modal (600px wide, max 480px tall) with:
- Large text input with autofocus, placeholder "Search graph, files, commands..."
- Results grouped by type: Entities, Commands, Files, Recent
- Each result: type badge + title + source file + module name
- Keyboard: arrow keys navigate, Enter selects, Escape closes
- ">" prefix switches to command mode: "> deploy staging", "> add service node", "> run tests"

---

## Screen 1: Graph (Blueprint Editor)

The primary editing surface. Shows all 38+ entities as cards on a 2D canvas with typed edges.

### Canvas
- Dark background with subtle dot grid (every 20px, #111724 dots)
- Pan: click-drag on empty canvas
- Zoom: scroll wheel (centered on cursor), current zoom shown as "62%"
- Fit: button in zoom controls auto-fits all nodes

### Graph Nodes
Each node is a card with:
- **Header bar:** Colored left border (type color), type badge (e.g., "ROUTE", "SCREEN", "SVC"), title, module tag
- **Body:** Port pins on left (consumers, green dots) and right (providers, amber dots) with labels
- **Footer:** Source file in mono, metrics row (p95, err%, calls)
- **States:**
  - Default: bg-2 background
  - Hovered: slight brightening + tooltip preview
  - Selected: accent border glow + corner handles (blue squares at corners)
  - Active path: blue glow animation (the traced execution path)
  - Warning: amber border pulse (high error rate or p95)
  - Container: dashed border, transparent background, larger width

### Edges
Bezier curves connecting nodes. Colored by type (route=blue, consumes=green, invokes=amber, writes=cyan, emits=purple, deploys=indigo). Active path edges have thicker stroke + glow.

### Overlay Controls (secondary bar)
- "Focus selected" — center view on selected node
- "Trace path" — highlight the active causal path
- "Blast radius" — dim everything except N-hop neighbors
- "Affected tests" — highlight test-connected entities
- "Overlays" dropdown — checkboxes for each edge type with color swatches
- "Minimap" toggle — small map in bottom-right corner showing full graph with viewport rect

### Minimap
Bottom-right corner, 180x120px. Shows all nodes as tiny colored rectangles. Viewport as a semi-transparent blue rectangle. Click to pan. Drag to reposition viewport.

---

## Screen 2: Run App (WYSIWYG Preview)

Live preview of the running application with device framing, click-to-inspect, and design overlay tools.

### Layout
**Split-view option:** Two device frames side by side. Default: phone + tablet, or phone + desktop. User picks from a preset dropdown:
- "Phone only" (centered)
- "Phone + Tablet" (side by side)  
- "Phone + Desktop" (side by side, desktop is wider)
- "Desktop only" (centered, wider)
- "All three" (phone + tablet + desktop, scaled down)

### Device Frames
Each frame is a realistic device chrome:
- **Phone (iPhone 16 Pro):** 312×624dp, radius 36, dynamic island notch, status bar (9:41, battery, signal), home indicator
- **Tablet (iPad):** 500×700dp, radius 24, thinner bezels, status bar
- **Desktop (browser):** 680×440dp, radius 10, browser chrome (traffic lights, URL bar showing "bestbuds.app/onboarding", tabs)

Device content is the **live rendered app** (OnboardingScreen showing BestBuds logo, "Discover and connect with amazing people around you", Continue with Apple button, Continue with Google button, avatar stack, terms text).

### Secondary Control Bar
- Device preset dropdown: "Phone + Tablet", "Phone only", etc.
- Specific device dropdown: "iPhone 16 Pro", "Pixel 9", "iPhone SE", "iPad Pro 12.9", "MacBook Pro 14"
- Theme toggle: light/dark pill buttons (switches the preview theme)
- Network mode: "Online" / "Slow 3G" / "Offline" pills (shows overlay on device: "OFFLINE" red bar or "SLOW 3G" amber bar)
- Accessibility toggle: increases font sizes, shows contrast overlays
- Orientation toggle: portrait/landscape (rotates device frame 90°)
- Scale slider: 50%-150% zoom on the device frames

### Click-to-Inspect
When the user clicks any element in the device preview:
1. **Selection overlay** appears on the element: blue outline, corner handles (blue squares at 4 corners), dimension label ("360 × 56dp"), padding guides ("20dp" on each side)
2. **Floating Graph Link card** appears next to the device (connected by a dashed line):
   - Type badge + entity name (e.g., "UI · BUTTON  AppleButton")
   - Key: `apple_button`
   - Source file: `ui/onboarding/OnboardingScreen.kt:88`
   - **Action chain strip:** horizontal chain of entity badges showing the causal path from this UI element through the graph: `AppleButton → loginWithApple → AuthInteractor → AuthService → cf.workers/auth → d1.sessions`
   - **Latest trace:** "trace abc4f1e2 · 142ms · provider=apple · 12s ago"
   - **Action buttons:** "Open in Graph" | "Follow action chain" | "Open in IntelliJ" | "Open in VS Code"
   - **More buttons:** "View last trace" | "Ask Code Agent"

### Component Tree (Left Sidebar in Run mode)
Hierarchical tree matching the Compose component tree:
```
▾ BestBudsApp
  ▾ OnboardingScreen
    ▾ Column
      Logo (Image)
      TitleText ("BestBuds")
      SubtitleText
      ▸ AppleButton ← selected, highlighted blue
        ShieldIcon
        "Continue with Apple" (Text)
      ▾ GoogleButton
        GoogleIcon
        "Continue with Google" (Text)
      AvatarStack
      TermsText
```
Each tree node shows: indent lines, expand arrow, component name, type icon (composable/text/image/button). Selected node is highlighted blue. Clicking a tree node selects the corresponding element in the preview AND the graph entity.

---

## Screen 3: DevTools (Causal Trace Inspector)

Chrome DevTools-style waterfall for tracing request flow through the entire graph — from button tap to database write.

### Layout
- **Left panel (320px):** Selected entity summary card (mini preview of the tapped button + entity info) with blast radius and test impact cards below
- **Center:** Causal trace waterfall (vertical rail)
- **Right:** Inspector (shows the selected trace step's entity details)

### Waterfall Rail
The **Active Path** is rendered as a vertical sequence of cards, each representing one hop in the execution chain:

```
 ROUTE  /onboarding                    0 ms       ████░░░░░░░░░░░░ p95 142ms
 ──────────────────────────────────────────────────────────────────
 SCREEN OnboardingScreen               +2 ms      █████░░░░░░░░░░░ 
   ui/onboarding/OnboardingScreen.kt:42
   [RIS · SetProperty] [RO3 · SetProperty]
   ⟶ Graph
 ──────────────────────────────────────────────────────────────────
 UI     AppleButton                    +2 ms      █░░░░░░░░░░░░░░░
   ui/onboarding/OnboardingScreen.kt:88
   [RIS · SetProperty] [RO3 · SetProperty] [RO4 · UpdateAction]
   ⟶ Graph
 ──────────────────────────────────────────────────────────────────
 ACTION loginWithApple                 +6 ms      ██░░░░░░░░░░░░░░
   ... continues down through AuthInteractor, AuthService,
   cf.workers/auth, d1.sessions, analytics.signup
```

Each card in the rail:
- **Left edge:** Colored border matching entity type
- **Header:** Type badge + entity name + key identifier
- **Source file:** mono text, clickable → opens in IDE
- **Command pills:** Any GraphCommands that touch this entity, shown as small pills with status color
- **Duration bar:** Horizontal bar showing proportional timing (offset from trace start, width = duration)
- **Timing text:** "+Xms" offset and "Yms" duration
- **Metrics row:** p95, error rate, calls count, each with sparkline
- **Action buttons on hover:** "Open in Graph" | "Open in IDE"

### Left Panel Cards
**Selected entity card:** Shows a mini version of the device preview (just the clicked element, 120px tall) with the element highlighted.

**Blast Radius card:**
- Title: "BLAST RADIUS"  
- Summary: "14 across 5 modules"  
- Expandable list of affected entities grouped by module
- Visual: small node-link diagram showing 1-hop and 2-hop neighbors

**Tests Impacted card:**
- Title: "TESTS IMPACTED"
- Summary: "7 commands" 
- List: test names with framework badge (Maestro, JUnit, Integration)

### Secondary Control Bar
- "Upstream ←" / "→ Downstream" navigation buttons
- Trace selector dropdown (shows recent traces with timing)
- Filter: entity type checkboxes
- "Compare traces" toggle (shows before/after side by side)
- Duration threshold slider (dim spans under Xms)

---

## Screen 4: Deploy (Infrastructure Topology + CI)

Visual map of all deployment targets with live CI pipeline status.

### Layout
- **Top section (60% height):** Topology map
- **Bottom section (40% height):** CI pipeline timeline

### Topology Map
Organized in labeled groups (like a cloud architecture diagram):

**APP BUNDLES** (left column):
- Android → APK with version badge (e.g., "v4.2.1(812)")
- iOS → IPA with version badge
- Web → bundle with size indicator ("1.2 MB")
- Desktop → .dmg with size indicator
- Hermes → hot-update bundle (shows "23 style changes (M1,M3,M5) → no compile, hot-reload required")

**CLOUDFLARE** (center column):
- cf.workers/auth — card with status dot (green), "replica_eu (1)" badge, deployment info, endpoint URL
- cf.workers/chat — card with status, endpoint
- cf.workers/feed — card with status
- cf.queues — card with queue depth indicator
- Realtime (PartyServer) — connection count indicator
- Email (Cloudflare Email) — send/receive indicator

**DATA** (center-bottom):
- d1.sessions — card with row count, last migration
- d1.users — card with row count
- d1.chat — card with row count
- r2.media — card with storage size ("4.2 GB"), object count

**REAKTOR CLOUD** (right column):
- graph.registry — synced indicator
- command.queue — pending count
- telemetry.ingest — events/sec indicator
- route-binding — "21 style changes (M1,M3,M5) → no compile, hot-reload required" indicator

**MOBILE RUNTIME · HOT RELOAD** (right column):
- hot-reload-bridge — connection status
- state-watch — watched state count

**JVM SERVERS (GCP k3s)** (bottom-left):
- graph-host — status with uptime
- codeagent-bridge — status with connection count
- Spring Boot servers with Keploy/Grafana indicators

**GRAPH DATABASE** (bottom):
- Memgraph on k3s — node count, edge count, query latency

**SUPABASE** (bottom-center):
- Postgres — table count, connection pool, pg_stat indicator
- Auth (email/magic-link only) — provider badges

**FIREBASE** (bottom-right):
- Analytics — events/day
- Crashlytics — crash-free rate with sparkline
- Notifications — sent/day
- Distribution — active testers count

**GCP** (bottom):
- Compute Engine VMs — instance count, region
- PubSub — topic count, messages/sec
- (k3s cluster indicator)

**BILLING** (small corner):
- Razorpay — active subscriptions
- Zoho — invoice count

Each card in the topology is clickable → selects the entity → inspector shows deploy-specific details (endpoint, version, environment, cert status, last deploy time).

Arrows between groups show data flow (e.g., cf.workers/auth → d1.sessions).

### CI Pipeline (bottom section)
A horizontal timeline of CI steps for the current bundle:

```
 GraphKMP     Graph         Compile   Unit Tests  Android    iOS build    Maestro     Browserstack  Smoke test  Monitor     Deploy
 integrity    build         KMP       1,264       build      $210         flows       $210          prod        error spike staging
 ⬤ 12s       ⬤ 14s       ⬤ 34s    ⬤ 80s      ⬤ 82s    ⬤ 120s      ⬤ 130s     ⬤ 142s       ⬤ 60s      ⬤ 30s      ⬤ queued
```

Each step is a card with: name, duration, status indicator (green check / amber spinner / gray circle / red X), pass/fail count. Steps connected by lines.

### Secondary Control Bar
- **Environment pills:** "Local" / "Staging" / "Production" — switches the view
  - Local: all steps green, "dev" label, no deploy needed
  - Staging: mixed statuses, "rc-812" label, "Push to Staging" button
  - Production: all gated, "v811" label, "Promote to Production" button
- **Rollback button** (only in Production)
- **Deploy history** dropdown (last 10 deploys with status)
- **Cost indicator:** "$124 / month estimated" for current infrastructure

### Header info
"graph · 6/7P · 2 ports · 1 schema field · 4 nodes · 1 deprecated handler" — summary of what the current bundle changes. Plus "Bundle #1042 · 7 commands"

---

## Screen 5: Insights (Analytics + Telemetry)

Dashboard for application metrics, performance data, and business analytics.

### Layout
- **Top section:** Tab bar for sub-views: Performance | Errors | Product Analytics | Costs | Traces | Logs | Tests
- **Main area:** Dashboard content based on selected tab

### Performance Tab (default)
**Timeline chart (full width, 200px tall):**
- X axis: time (adjusts based on range: 1h/24h/7d/30d)
- Y axis: requests per second (left) + p95 latency (right, different color)
- Line chart with area fill
- Deploy markers: vertical dashed lines at each deployment, labeled "rc-811 deployed" and "rc-812 deployed"
- Anomaly highlighting: if a metric spikes after a deploy, shade the region amber with a tooltip "p95 spike +40% after rc-812"
- Interactive: hover shows crosshair with exact values at that timestamp

**Metric cards row (6 cards):**
- Active Users · 24h: **42,118** with sparkline and trend arrow
- Route Visits: **218k** with sparkline
- Crash-Free Sessions: **99.7%** with sparkline
- P95 Latency · /chats: **180ms** with sparkline
- Worker CPU: **62%** with sparkline
- Cloud Token Cost: **$124** with sparkline

**Heatmap (full width):**
- Title: "Graph heatmap · p95 latency"
- Columns: routes (/onboarding, /chats/{id}, /events/public, /campaigns/current, /friends/{id})
- Rows grouped by type: ROUTES (with p95 values), SCREENS (with p95), SERVICES (with p95), EDGE (with p95)
- Each cell colored: green (<100ms) → yellow (100-200ms) → red (>200ms)
- Click a cell → select that entity → inspector shows performance details
- Time range selector at top-right: 1h | 6h | 24h | 7d | 30d buttons

### Errors Tab
- Error timeline (volume over time)
- Error list: grouped by entity, with count, last occurrence, stack trace preview
- Click error → navigate to the entity in Graph mode

### Product Analytics Tab  
- Funnel visualization: Onboarding → Sign Up → First Chat → Retained (Day 7)
- Conversion rates between steps
- Cohort chart
- User flow Sankey diagram through routes

### Costs Tab
- Infrastructure cost breakdown by provider (Cloudflare, GCP, Supabase, Firebase)
- Per-entity cost attribution (which service costs the most)
- Trend chart: cost over time with projection
- Budget alert thresholds

---

## Screen 6: Agent (AI Assistant + MCP) ← NEW

The AI-powered development assistant. Modeled after Claude Code's terminal interface but with full graph context and visual tool invocations.

### Layout
- **Left panel (320px):** Conversation list + MCP connection status panel
- **Center:** Chat interface (full-height scrollable)
- **Right:** Inspector (shows entity context for the current conversation)

### Chat Interface
Messages are rendered in a terminal-like chat:

**User message:** Dark card with user avatar, message text, timestamp.

**Agent message:** Slightly different background, AI avatar (sparkle icon), message text with:
- **Tool invocations** rendered inline as collapsible cards:
  - `read_graph()` — shows "Reading graph... 38 entities, 39 edges" with expand to see JSON
  - `select_entity("service:AuthService")` — shows entity card inline with type badge
  - `blast_radius("service:AuthService", 3)` — shows mini graph visualization of affected entities
  - `push_command(AddNode(...))` — shows the command card with preview
  - `generate_code([...])` — shows CodeDiff preview inline
  - `query_traces("edge:cf.workers/auth", 5)` — shows trace list inline
  - `open_in_ide("auth/AuthService.kt", 42)` — shows "Opened in IntelliJ" confirmation

- **Code blocks** with syntax highlighting (Kotlin, TypeScript, SQL)
- **Entity references** as clickable badges (click → select in graph)
- **Command previews** as cards showing what will change

**Example conversation:**
```
User: Add Apple Pay as a payment method for checkout

Agent: I'll analyze the current payment flow in the graph.
  [Tool: read_graph() — 38 entities, 39 edges]
  [Tool: select_entity("service:AuthService") — viewing AuthService]
  
  I can see the current auth flow: OnboardingScreen → AppleButton → loginWithApple → 
  AuthInteractor → AuthService → cf.workers/auth → d1.sessions.
  
  To add Apple Pay, I'll need to:
  1. Add a new `ApplePayService` node  
  2. Connect it to a new `cf.workers/payment` edge worker
  3. Add a `d1.transactions` database
  4. Wire the UI button to the new flow
  
  [Tool: push_command(AddNode(ServiceNode, "ApplePayService", module="payment"))]
  [Tool: push_command(AddNode(EdgeNode, "cf.workers/payment", runtime="cloudflare-workers"))]
  [Tool: push_command(AddNode(DbNode, "d1.transactions", runtime="cloudflare-d1"))]
  [Tool: push_command(ConnectPorts(ApplePayService.output, cf.workers/payment.input))]
  
  4 commands added to queue. Review them in the Command Queue tab, then click Apply 
  to generate the code.
  
  [View in Graph →]  [Open Queue →]
```

### Input area
- Multi-line text input with placeholder "Ask about the graph, generate commands, or get help..."
- Send button + keyboard shortcut indicator (Cmd+Enter)
- Attachment button (attach screenshots, error logs)
- "Context" indicator showing what the agent can see: "Graph (38 entities) · Selection (AuthService) · Trace (abc4f1e2)"

### MCP Connection Panel (bottom of left sidebar)
Shows connected MCP clients and their status:

```
MCP CONNECTIONS
  ⬤ Claude Code     connected · 12 queries today
  ⬤ Cursor          connected · 3 queries today
  ◯ Codex           not connected
  ◯ IntelliJ Plugin not connected
  
  [Configure MCP Server →]
```

Each connection shows: status dot, tool name, connection status, query count. Click to see recent queries from that tool.

### Agent Memory Panel (collapsible in left sidebar)
Shows what the agent remembers across sessions:
```
AGENT MEMORY
  "User prefers single bundled PRs for refactors"
  "AuthService was recently refactored for Apple login"
  "Deploy freeze on Thursdays for mobile release"
```

### Agent Management Panel (main content area, toggled via "Manage Agents" tab in center toolbar)

Users configure their own agents — assigning different AI models to specialized roles with custom instructions, styles, and permissions. This is the core differentiator: **orchestration of multiple AI agents with domain-specific roles.**

**Layout:** Two-column — agent card grid (left 60%) + agent editor (right 40%, opens on select)

**Agent Card Grid:**
Each agent is a card showing: model icon (Claude/Codex/Gemini/custom) + agent name + role badge + short description + status (active/paused) + last run timestamp + stats (tasks completed, avg quality score).

**Built-in agent presets (user can customize or create from scratch):**

```
┌─────────────────────────────────────────────────────────────────┐
│ MY AGENTS                                          [+ New Agent] │
├─────────────────────────────────────────────────────────────────┤
│                                                                   │
│  ┌─────────────────────┐  ┌─────────────────────┐               │
│  │ 🔷 Codex            │  │ 🟣 Claude           │               │
│  │ ─────────────────── │  │ ─────────────────── │               │
│  │ Architect+Engineer  │  │ Critic+Guardian     │               │
│  │                     │  │                     │               │
│  │ Plans architecture, │  │ Reviews arch        │               │
│  │ implements features,│  │ decisions, writes   │               │
│  │ builds end-to-end   │  │ tests/guardrails/   │               │
│  │ with full context   │  │ harnesses, ensures  │               │
│  │                     │  │ resilience          │               │
│  │ ⬤ Active · 47 tasks │  │ ⬤ Active · 31 tasks │               │
│  └─────────────────────┘  └─────────────────────┘               │
│                                                                   │
│  ┌─────────────────────┐  ┌─────────────────────┐               │
│  │ 🔴 Gemini           │  │ ⚙️ Custom            │               │
│  │ ─────────────────── │  │ ─────────────────── │               │
│  │ Cross-Codebase Ops  │  │ Deploy Shepherd     │               │
│  │                     │  │                     │               │
│  │ Aspect-oriented:    │  │ Monitors CI/CD,     │               │
│  │ simplify without    │  │ manages rollouts,   │               │
│  │ changing meaning,   │  │ auto-rollback on    │               │
│  │ find common code,   │  │ error spike         │               │
│  │ perf/version        │  │                     │               │
│  │ upgrades            │  │                     │               │
│  │ ⬤ Active · 18 tasks │  │ ◯ Paused            │               │
│  └─────────────────────┘  └─────────────────────┘               │
│                                                                   │
└─────────────────────────────────────────────────────────────────┘
```

**Agent Editor (right panel, opens when a card is selected):**

```
┌─────────────────────────────────────────────────┐
│ Edit Agent: Claude — Critic+Guardian             │
├─────────────────────────────────────────────────┤
│                                                   │
│ MODEL         [Claude ▾] (dropdown: Claude,       │
│               Codex, Gemini, Ollama, Custom API)  │
│                                                   │
│ ROLE          [Critic ▾] + [Guardian ▾]           │
│               (multi-select from: Architect,      │
│               Engineer, Critic, Guardian,         │
│               Optimizer, Analyst, Deployer)       │
│                                                   │
│ STYLE                                             │
│ ┌─────────────────────────────────────────┐      │
│ │ Thorough, adversarial. Question every   │      │
│ │ assumption. Prefer failing fast over    │      │
│ │ silent degradation. Flag complexity.    │      │
│ └─────────────────────────────────────────┘      │
│                                                   │
│ INSTRUCTIONS (system prompt)                      │
│ ┌─────────────────────────────────────────┐      │
│ │ You are a code critic for the reaktor   │      │
│ │ framework. Your job:                    │      │
│ │ 1. Review architecture decisions from   │      │
│ │    the Architect agent — challenge      │      │
│ │    assumptions, find edge cases         │      │
│ │ 2. Write comprehensive test harnesses   │      │
│ │    for every new service/edge/route     │      │
│ │ 3. Create guardrails: runtime checks,   │      │
│ │    circuit breakers, fallback paths     │      │
│ │ 4. Ensure resilience: chaos scenarios,  │      │
│ │    failure injection, recovery paths    │      │
│ │                                         │      │
│ │ Tech context: Kotlin-first, CMP, CF    │      │
│ │ Workers, Supabase, GCP k3s             │      │
│ └─────────────────────────────────────────┘      │
│                                                   │
│ GRAPH PERMISSIONS                                 │
│ ☑ Read graph        ☑ Read traces                │
│ ☑ Push commands     ☐ Apply commands (review only)│
│ ☑ Generate code     ☑ Run tests                  │
│ ☐ Deploy            ☐ Modify RBAC                │
│                                                   │
│ TRIGGERS (when does this agent auto-run?)         │
│ ┌─────────────────────────────────────────┐      │
│ │ • After Architect pushes >3 commands    │      │
│ │ • On PR creation (auto-review)          │      │
│ │ • On new service/edge node added        │      │
│ │ • Nightly: audit unused code paths      │      │
│ └─────────────────────────────────────────┘      │
│ [+ Add Trigger]                                   │
│                                                   │
│ SCOPE (what parts of the graph can it touch?)     │
│ [All modules ▾]  or specific:                    │
│ ☑ auth  ☑ graph  ☑ core  ☑ io                   │
│ ☐ ui    ☐ media  ☐ tactile                      │
│                                                   │
│ COLLABORATION (how agents interact)               │
│ • Receives from: [Codex/Architect ▾]             │
│ • Sends to: [Command Queue ▾]                    │
│ • Blocks deploy: [Yes ▾] (must approve first)    │
│                                                   │
│ ──────────────────────────────────────────        │
│ RECENT ACTIVITY                                   │
│  2h ago  Reviewed AddNode(PaymentService)         │
│          → Flagged: missing circuit breaker       │
│  5h ago  Generated 12 test cases for AuthFlow     │
│          → All passing ✓                          │
│  1d ago  Nightly audit: found 3 dead code paths   │
│          → Created cleanup commands               │
│                                                   │
│ [Save]  [Duplicate]  [Pause Agent]  [Delete]      │
└─────────────────────────────────────────────────┘
```

**Agent Pipeline Visualization (bottom strip, always visible):**
Shows how agents chain together as a pipeline/DAG:

```
Codex (Architect) ──→ Claude (Critic) ──→ Command Queue ──→ Apply
       │                    │
       └── Gemini (Ops) ───┘──→ Tests ──→ Deploy Shepherd ──→ CD
```

Each node in the pipeline is clickable → opens that agent's card. Edges show data flow (commands, reviews, approvals). Animated dots flow along edges when agents are actively communicating.

**Key interactions:**
- Drag agent cards to reorder priority
- Drag between agents in pipeline viz to create collaboration links
- Click "Run Now" on any agent card → starts a task with current graph context
- "Compare Agents" button → side-by-side view of two agents' outputs on the same task
- Agent activity feed shows real-time streaming of what each agent is doing
- "Fork Agent" → duplicate with modifications (e.g., fork Critic into "Security Critic" + "Performance Critic")

---

## Screen 7: Database (Multi-DB Browser) ← NEW

A DataGrip-style database explorer that shows all databases in the stack.

### Layout
- **Left panel (280px):** Database tree
- **Center top (60%):** Query editor + results
- **Center bottom (40%):** Schema viewer / table data / relationship graph
- **Right:** Inspector (shows table/column details, query stats)

### Database Tree (Left Sidebar)
```
▾ Supabase Postgres (T2 · primary)
  ▾ public
    ▸ users (142,318 rows)
    ▸ profiles (141,892 rows)
    ▸ friendships (892,441 rows)
    ▸ messages (12,441,892 rows)
    ▸ events (34,112 rows)
    ▸ campaigns (1,204 rows)
  ▾ auth (Supabase managed)
    ▸ users
    ▸ sessions
    ▸ mfa_factors

▾ Memgraph (T3 · graph DB · k3s)
  ▸ :User (142,318 nodes)
  ▸ :FRIENDS_WITH (892,441 edges)
  ▸ :ATTENDED (34,112 edges)
  ▸ :MESSAGED (12,441,892 edges)

▾ Cloudflare D1 (T1 · edge)
  ▸ sessions (d1.sessions · 8,442 rows)
  ▸ users_cache (d1.users · 142,318 rows)
  ▸ chat_cache (d1.chat · 24,112 rows)

▾ Durable Objects
  ▸ ChatRoom (namespace, 1,204 active)
  ▸ UserPresence (namespace, 42,118 active)
  ▸ RateLimiter (namespace)

▾ Cloudflare R2 (T4 · blob)
  ▸ media (4.2 GB · 892,441 objects)
  ▸ avatars (1.1 GB · 142,318 objects)

▾ Local SQLite (T0 · device)
  ▸ offline_queue
  ▸ user_preferences
  ▸ cached_feed
```

Each database entry shows: tier badge (T0-T4), row/node/object count, status dot. Color-coded by tier.

### Query Editor (center top)
- Tab bar for multiple query tabs: "Query 1", "Query 2", "+ New"
- Syntax-highlighted editor with autocomplete for table/column names
- Run button + Cmd+Enter shortcut
- Database selector dropdown (which DB to run against)
- Results table below: sortable columns, pagination, row count indicator
- Query plan viewer: toggle to see EXPLAIN output with cost indicators
- Query history dropdown

### Schema Viewer (center bottom, tab 1)
For selected table:
- Column list: name, type, nullable, default, constraints (PK, FK, UNIQUE, INDEX)
- Visual ER diagram showing relationships to other tables
- Migration history: list of migrations that touched this table
- **Graph link:** "This table is used by: `edge:cf.workers/auth`, `service:AuthService`" — clickable badges that navigate to Graph

### Table Data Viewer (center bottom, tab 2)
- Scrollable data grid with column headers
- Inline editing (click cell to edit, for non-production environments)
- Filter bar: WHERE clause builder
- Sort by clicking column headers
- Export to CSV/JSON

### Relationship Graph (center bottom, tab 3)
- For Memgraph: Cypher query visualization — nodes and edges rendered as a mini graph (reuse graph rendering)
- For Postgres: ER diagram with FK relationships
- Interactive: click a node → shows related records

### Secondary Control Bar
- Database selector dropdown
- Tier filter pills: T0 | T1 | T2 | T3 | T4
- "Sync schema" button (refresh from live databases)
- "Migrations" button (show pending/recent migrations)
- Connection pool indicator: "12/50 connections active"
- Read-only mode toggle (prevents accidental writes)

---

## Screen 8: Auth (RBAC + Identity) ← NEW

Visualize and manage the application's authentication, authorization, and identity system.

### Layout
- **Left panel (280px):** Identity tree (providers, roles, policies)
- **Center:** Main view (switches between sub-views)
- **Right:** Inspector (user details, role permissions, session info)

### Sub-views (tabs in secondary control bar)
**Roles & Permissions** (default):
- Visual role hierarchy diagram: `superadmin → admin → moderator → user → guest`
- Each role is a card showing: name, user count, permission count, color badge
- **Permission matrix** below: rows = roles, columns = resources (users, messages, events, campaigns, payments, admin), cells = CRUD checkboxes (colored green=allowed, red=denied, gray=inherited)
- Click a role → inspector shows all permissions, users with that role, audit log

**Providers:**
- Cards for each auth provider:
  - **Supabase Email/Magic Link** — status, user count, config details (SMTP settings preview)
  - **Apple Sign-In** — status, user count, team ID, service ID, redirect URI
  - **Google Sign-In** — status, user count, client ID, redirect URI
- Each card shows: status dot, provider logo, user count using this provider, last authentication timestamp
- **Auth flow diagram:** Visual flow showing: App → Provider → Callback → Token → Session → Graph
  - Rendered as a horizontal flowchart with icons at each step
  - Highlights the current flow when a user logs in

**Sessions:**
- Active session list: user, device, IP, location, provider, created, last activity, token expiry
- Session map: world map with dots showing active session locations
- "Revoke All" button, individual "Revoke" buttons
- Session duration histogram
- Token inspector: decode JWT, show claims, expiry countdown

**Audit Log:**
- Filterable event stream: login, logout, role_change, permission_change, session_revoke, password_reset
- Each entry: timestamp, user, action, target, IP, success/failure
- Filter by: action type, user, date range, success/failure

### Secondary Control Bar
- Sub-view tabs: Roles & Permissions | Providers | Sessions | Audit Log
- User count: "142,318 users"
- Active sessions: "42,118 active"
- "Add Role" | "Add Policy" buttons
- Environment indicator (same auth config across envs, or env-specific)

---

## Inspector Bodies (Right Panel)

The right inspector panel renders different content based on the selected entity type. Each inspector body follows the same structure: Header → Section Tabs → Sections → Actions.

### RouteInspector (for route entities)
- **Overview:** Route pattern, attached screen(s), owner, module
- **Metrics:** p95 sparkline (w=full width), error rate sparkline, call volume sparkline, conversion rate
- **Navigation:** List of navigation targets (where this route can go), backstack behavior
- **Traces:** Last 5 traces for this route with timing and status
- **Actions:** "Open in Graph" | "Follow to screen" | "View traces" | "Open in IDE"

### ScreenInspector (for screen entities)
- **Overview:** Screen name, route binding, owner, module
- **Preview:** Mini live preview of the screen (120px tall, actual rendered content)
- **Metrics:** Recompose count sparkline, crash-free rate, session count, load time
- **Components:** List of child UI elements with counts
- **Actions:** "Open in Run App" | "Open in Graph" | "View traces" | "Open in IDE"

### ServiceInspector (for service/interactor/repository entities)
- **Overview:** Service name, transport mode, owner, module
- **Endpoints:** List of port names with type signatures
- **Metrics:** p95 sparkline, error rate sparkline, call volume sparkline, interceptor chain
- **Dependencies:** Upstream (who calls this) and downstream (what this calls) entity badges
- **Actions:** "Open in Graph" | "Follow chain" | "View traces" | "Open in IDE" | "Deploy worker"

### EdgeInspector (for edge/worker entities)
- **Overview:** Worker name, runtime (cloudflare-workers), region, endpoint URL
- **Deploy:** Current version, last deploy time, environment status (local/staging/prod)
- **Metrics:** p95 latency histogram (not just sparkline — a proper histogram with percentile bars), error rate, request volume
- **Configuration:** Environment variables (redacted), CPU limit, memory limit, routes
- **Cert:** SSL cert status, expiry date, auto-renew indicator
- **Actions:** "Open in Graph" | "Deploy" | "View logs" | "Rollback" | "Open in IDE"

### DbInspector (for database entities)
- **Overview:** Database name, runtime, tier (T0-T4), module
- **Schema:** Column list with types (compact table view)
- **Metrics:** Query p95, row count with trend, storage size
- **Migrations:** Recent migrations with status (applied/pending/failed)
- **Actions:** "Open in Database" | "View schema" | "View queries" | "Open in IDE"

### UiInspector (for UI element entities)
- **Overview:** Component name, key, parent screen, accessibility label
- **Props:** Editable property list (backgroundColor, borderRadius, fontSize, etc.) — each with a color swatch or value editor
- **Design:** Dimensions ("360 × 56dp"), padding, margin, design token references
- **Action Chain:** Visual strip showing the causal chain from this element through the graph
- **Actions:** "Open in Run App" | "Open in Graph" | "Edit props" | "Open in IDE"

---

## Drawer Tab Details

### Command Queue Tab
Shows all pending GraphCommand objects in order:

Each command card:
- **Status indicator:** colored dot (queued=gray, agent=amber, codediff=blue, compiled=green, hot-reloaded=green+zap, failed=red)
- **Scope badge:** UI (green) | Graph (purple) | Backend (amber) | Tests (green) | Deploy (blue)
- **Command description:** e.g., "SetProperty · AppleButton.backgroundColor" with before→after diff
- **Affected entities:** small badges showing which entities are affected
- **File path:** mono text
- **Expand:** shows CodeDiff preview with syntax highlighting
  - Green lines = added
  - Red lines = removed
  - Gray lines = context
  - Line numbers on both sides

Right-side actions per command: "Apply" | "Revert" | "Edit" | "Delete"
Top actions: "Apply All" | "Revert All" | "Send to Code Agent"

### CodeDiff Tab
Full-screen (well, full-drawer) side-by-side diff viewer:
- Left: "Before" with red highlighted deletions
- Right: "After" with green highlighted additions
- File selector dropdown (when multiple files changed)
- "Accept All" | "Accept Hunk" | "Reject Hunk" buttons
- Syntax highlighting matching the file type (Kotlin, TypeScript, SQL, YAML)

### Traces Tab
List of recent PortInvocationEvent traces:
Each trace:
- Timestamp + trace ID
- Entity name + type badge
- Duration with colored indicator (green <100ms, amber 100-300ms, red >300ms)
- Status (ok/error)
- Provider/context info
- Click → navigates to DevTools with this trace selected

### Logs Tab
Filterable log stream:
- Source filter: App | Workers | Build | Tests
- Level filter: Debug | Info | Warn | Error
- Search input
- Each log line: timestamp (mono), source badge, level badge (colored), message text
- Auto-scroll with "pause" button
- Click entity references in log messages → select in graph

---

## Data Model

Keep the existing entity types but add new entities for the new screens:

### New entities to add to scenario:
```javascript
// Database entities (for Database screen)
'db:supabase.users':       { type:'db', title:'supabase.users',       module:'data', tag:'DB',   runtime:'supabase-postgres', tier:'T2', rows:142318, metrics:{qps:1200, p95:12} },
'db:supabase.messages':    { type:'db', title:'supabase.messages',    module:'data', tag:'DB',   runtime:'supabase-postgres', tier:'T2', rows:12441892, metrics:{qps:8400, p95:24} },
'db:supabase.friendships': { type:'db', title:'supabase.friendships', module:'data', tag:'DB',   runtime:'supabase-postgres', tier:'T2', rows:892441 },
'db:memgraph.social':      { type:'db', title:'memgraph.social',      module:'data', tag:'GRAPH_DB', runtime:'memgraph-k3s', tier:'T3', nodes:142318, edges:892441 },
'db:sqlite.offline':       { type:'db', title:'sqlite.offline',       module:'data', tag:'DB',   runtime:'sqlite-local', tier:'T0' },
'db:durable.chatroom':     { type:'db', title:'durable.chatroom',     module:'data', tag:'DO',   runtime:'cloudflare-do', active:1204 },
'db:durable.presence':     { type:'db', title:'durable.presence',     module:'data', tag:'DO',   runtime:'cloudflare-do', active:42118 },

// Auth entities (for Auth screen)
'auth:provider.apple':     { type:'auth', title:'Apple Sign-In',      module:'auth', tag:'PROVIDER', users:89421, status:'active' },
'auth:provider.google':    { type:'auth', title:'Google Sign-In',     module:'auth', tag:'PROVIDER', users:41892, status:'active' },
'auth:provider.email':     { type:'auth', title:'Email / Magic Link', module:'auth', tag:'PROVIDER', users:11005, status:'active', runtime:'supabase-auth' },
'auth:role.admin':         { type:'auth', title:'admin',              module:'auth', tag:'ROLE', users:3, permissions:42 },
'auth:role.moderator':     { type:'auth', title:'moderator',          module:'auth', tag:'ROLE', users:12, permissions:28 },
'auth:role.user':          { type:'auth', title:'user',               module:'auth', tag:'ROLE', users:142303, permissions:14 },

// Infrastructure entities (for Deploy screen)
'infra:gcp.k3s':           { type:'infra', title:'GCP k3s cluster',   module:'infra', tag:'K3S', region:'asia-south1', vms:3 },
'infra:gcp.pubsub':        { type:'infra', title:'GCP PubSub',        module:'infra', tag:'PUBSUB', topics:4, msgPerSec:1200 },
'infra:firebase.analytics': { type:'infra', title:'Firebase Analytics', module:'infra', tag:'ANALYTICS', eventsPerDay:'1.2M' },
'infra:firebase.crashlytics':{ type:'infra', title:'Firebase Crashlytics', module:'infra', tag:'CRASH', crashFree:'99.7%' },
'infra:razorpay':          { type:'infra', title:'Razorpay',          module:'billing', tag:'BILLING', activeSubs:1204 },
```

Add edges connecting these new entities to existing services.

### New color tokens for new types:
```
auth:     #f472b6 (pink)     — for auth providers, roles, policies
infra:    #94a3b8 (slate)    — for infrastructure entities
```

---

## Interactions That Must Work

Every single one of these must be functional in the prototype:

1. **Mode switching:** Click any of the 8 mode tabs → screen changes, sidebar changes, secondary bar changes
2. **Entity selection:** Click any entity anywhere (graph node, sidebar item, trace step, deploy card, heatmap cell, DB table) → inspector updates, entity highlighted across all views
3. **Cross-mode navigation:** "Open in Graph" button → switches to Graph mode, selects entity, centers view
4. **Search (Cmd+K):** Opens palette, type to filter, arrow keys to navigate, Enter to select, Escape to close
5. **Drawer tabs:** Click any of 9 tabs → drawer content changes
6. **Drawer resize:** Drag the top edge of the drawer to resize height
7. **Panel resize:** Drag resize handles between panels
8. **Panel collapse:** Click collapse button on sidebar/inspector → collapses to 44px strip
9. **Zoom controls:** +/- buttons in graph mode change zoom percentage
10. **Device mode toggle:** Phone/Tablet/Desktop pills in Run mode → device frame changes size
11. **Theme toggle:** Light/Dark in Run mode → preview content inverts colors
12. **Network mode:** Online/Slow/Offline → network badge appears on device preview
13. **Deploy env switch:** Local/Staging/Production → CI pipeline and deploy cards change
14. **Time range switch:** 1h/24h/7d/30d in Insights → chart and heatmap data adjusts
15. **Command status:** Each command in the queue shows its pipeline status with appropriate color
16. **CodeDiff expand:** Click a command → expands to show the code diff with syntax highlighting
17. **Edge filter:** Overlay toggles in Graph mode → edges of unchecked types disappear
18. **Tree expand/collapse:** All tree nodes in sidebar are expandable/collapsible
19. **Sidebar search:** Search input in sidebar filters the entity tree
20. **Keyboard shortcuts:** Cmd+K (search), Cmd+1-8 (modes), Escape (deselect/close), \ (toggle drawer), Arrow keys (walk active path)
21. **Agent chat:** Messages appear in the chat, tool invocations render as collapsible cards
22. **DB query:** Query editor with syntax highlighting, run button shows results table
23. **Auth role matrix:** Permission checkboxes are toggleable, role cards are clickable
24. **Responsive:** Window resize below 1200px collapses right panel, below 900px collapses both

---

## Quality Bar

This prototype must feel like a **real shipping developer tool**, not a design mockup:

- **Dense information:** Every pixel should carry data. No empty space. Think Grafana, DataGrip, IntelliJ — not Notion.
- **Consistent micro-interactions:** Hover states on every clickable element. Smooth transitions (0.15s) on panel resizes and mode switches.
- **Realistic data:** All numbers, metrics, file paths, timestamps should be realistic. Entity names match a real social app (BestBuds). Trace timings are realistic (2ms for UI, 60ms for service, 80ms for edge worker, 56ms for database).
- **No placeholder text:** No "Lorem ipsum". Every label, every tooltip, every status message should be real.
- **Code quality:** Syntax-highlighted code diffs should be real Kotlin/TypeScript. File paths should follow KMP conventions.
- **Professional icons:** Use consistent 10-14px icons throughout. Minimal, geometric, single-color.
- **Keyboard-first:** Every action has a keyboard shortcut. Tab navigation works.

---

## Tech Stack Context (for realistic data)

The app being inspected (BestBuds) uses:
- **Kotlin Multiplatform** for almost everything (mobile, desktop, server, even CF workers via Kotlin/JS)
- **TypeScript** only where Kotlin can't go (some CF Worker glue, FFI bindings)
- **C++** for security-critical modules and FFI
- **Compose Multiplatform** for UI (Android, iOS, Desktop, Web)
- **Cloudflare** for edge: Workers, D1, R2, Durable Objects, Queues, Email, PartyServer (Realtime)
- **Supabase** for primary database (Postgres) and email/magic-link auth only
- **Firebase** for analytics, crashlytics, notifications, distribution (NO databases, NO cloud functions)
- **GCP** for Compute Engine VMs running k3s clusters (Spring Boot servers, Memgraph, Keploy, Grafana)
- **GCP PubSub** for the queue/pubsub messaging layer
- **Memgraph** on k3s for graph database (social graph, recommendation engine)
- **Razorpay** for payment processing
- **Zoho** for billing/invoicing
- **reaktor-graph** for the typed directed graph runtime (8 node types, typed ports, visitor/selector/traverser)
- **reaktor-flow** for the graph canvas (Compose port of React Flow)
- **reaktor-io** for network (6 transport modes: HTTP, LOCAL, PEER, PUBSUB, QUEUE, WORKFLOW)
- **reaktor-db** for SQLite (5-tier data model: T0 local, T1 durable objects, T2 supabase postgres, T3 memgraph, T4 R2)
- **reaktor-auth** for authentication

All file paths in the prototype should follow this structure:
- Kotlin: `ui/onboarding/OnboardingScreen.kt`, `auth/AuthService.kt`, `backend/auth/Schemas.kt`
- Workers: `workers/auth/index.ts`
- Migrations: `db/migrations/0042_sessions.sql`
- Tests: `flows/auth-login.yaml` (Maestro), `test/auth/AuthServiceTest.kt` (JUnit)
- Config: `workers/auth/wrangler.toml`, `k3s/deployment.yaml`
