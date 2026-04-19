const fs = require("fs");
const {
  Document, Packer, Paragraph, TextRun, Table, TableRow, TableCell,
  Header, Footer, AlignmentType, LevelFormat, ExternalHyperlink,
  HeadingLevel, BorderStyle, WidthType, ShadingType,
  PageNumber, PageBreak, TabStopType, TabStopPosition,
} = require("docx");

// ─── Constants & Helpers ─────────────────────────────────────────────────────
const PAGE_WIDTH = 12240, PAGE_HEIGHT = 15840, MARGIN = 1000;
const CW = PAGE_WIDTH - 2 * MARGIN;
const C = {
  pri: "1B4F72", sec: "2E86C1", acc: "E74C3C", dk: "1C1C1C",
  med: "555555", lt: "888888", bgC: "F5F5F5", bdr: "B0BEC5",
  thd: "1B4F72", thx: "FFFFFF", alt: "EBF5FB", lnk: "2471A3", cbdr: "CCCCCC",
};
const tb = { style: BorderStyle.SINGLE, size: 1, color: C.bdr };
const bds = { top: tb, bottom: tb, left: tb, right: tb };

function h1(t){return new Paragraph({heading:HeadingLevel.HEADING_1,children:[new TextRun({text:t,font:"Arial",size:36,bold:true,color:C.pri})]})}
function h2(t){return new Paragraph({heading:HeadingLevel.HEADING_2,spacing:{before:360,after:200},children:[new TextRun({text:t,font:"Arial",size:30,bold:true,color:C.sec})]})}
function h3(t){return new Paragraph({heading:HeadingLevel.HEADING_3,spacing:{before:280,after:160},children:[new TextRun({text:t,font:"Arial",size:26,bold:true,color:C.dk})]})}
function h4(t){return new Paragraph({spacing:{before:200,after:120},children:[new TextRun({text:t,font:"Arial",size:24,bold:true,color:C.med})]})}
function p(t,o={}){return new Paragraph({spacing:{after:160,line:312},...o.p,children:[new TextRun({text:t,font:"Arial",size:22,color:C.dk,...o})]})}
function B(t){return new TextRun({text:t,font:"Arial",size:22,bold:true,color:C.dk})}
function N(t){return new TextRun({text:t,font:"Arial",size:22,color:C.dk})}
function I(t){return new TextRun({text:t,font:"Arial",size:22,italics:true,color:C.med})}
function L(t,u){return new ExternalHyperlink({children:[new TextRun({text:t,font:"Arial",size:22,color:C.lnk,underline:{type:"single"}})],link:u})}
function rp(ch,o={}){return new Paragraph({spacing:{after:160,line:312},...o,children:ch})}
function sp(n=120){return new Paragraph({spacing:{before:n,after:0},children:[]})}
function code(lines){return lines.map((l,i)=>new Paragraph({spacing:{before:i===0?80:0,after:i===lines.length-1?80:0,line:240},shading:{fill:C.bgC,type:ShadingType.CLEAR},indent:{left:360},children:[new TextRun({text:l||" ",font:"Consolas",size:18,color:C.dk})]}))}
function tbl(hds,rows,cw){
  const cm={top:60,bottom:60,left:100,right:100};
  const sum=cw.reduce((a,b)=>a+b,0);
  const scale=CW/sum;
  const widths=cw.map((w,i)=>i===cw.length-1?CW-Math.floor(cw.slice(0,-1).reduce((a,b)=>a+(b*scale),0)):Math.floor(w*scale));
  const hr=new TableRow({tableHeader:true,children:hds.map((h,i)=>new TableCell({borders:bds,width:{size:widths[i],type:WidthType.DXA},shading:{fill:C.thd,type:ShadingType.CLEAR},margins:cm,children:[new Paragraph({children:[new TextRun({text:h,font:"Arial",size:20,bold:true,color:C.thx})]})]}))});
  const dr=rows.map((r,ri)=>new TableRow({children:r.map((c,ci)=>new TableCell({borders:bds,width:{size:widths[ci],type:WidthType.DXA},shading:ri%2===1?{fill:C.alt,type:ShadingType.CLEAR}:undefined,margins:cm,children:[new Paragraph({spacing:{line:276},children:typeof c==="string"?[new TextRun({text:c,font:"Arial",size:20,color:C.dk})]:c})]}))}));
  return new Table({width:{size:CW,type:WidthType.DXA},columnWidths:widths,rows:[hr,...dr]});
}
function bul(t,lv=0){return new Paragraph({numbering:{reference:"bullets",level:lv},spacing:{after:80,line:312},children:typeof t==="string"?[N(t)]:t})}
function num(t,lv=0,r="numbers"){return new Paragraph({numbering:{reference:r,level:lv},spacing:{after:80,line:312},children:typeof t==="string"?[N(t)]:t})}
function box(title,body,color=C.sec,bg="EBF5FB"){
  return new Table({width:{size:CW,type:WidthType.DXA},columnWidths:[CW],rows:[new TableRow({children:[new TableCell({borders:{top:{style:BorderStyle.SINGLE,size:1,color},bottom:{style:BorderStyle.SINGLE,size:1,color},left:{style:BorderStyle.SINGLE,size:6,color},right:{style:BorderStyle.SINGLE,size:1,color}},width:{size:CW,type:WidthType.DXA},shading:{fill:bg,type:ShadingType.CLEAR},margins:{top:120,bottom:120,left:200,right:200},children:[new Paragraph({spacing:{after:80},children:[new TextRun({text:title,font:"Arial",size:22,bold:true,color:C.pri})]}),new Paragraph({spacing:{after:0,line:300},children:[new TextRun({text:body,font:"Arial",size:20,color:C.dk})]})]})]})]});
}
function warnBox(title,body){return box(title,body,C.acc,"FDEDEC")}

const nc={config:[
  {reference:"bullets",levels:[
    {level:0,format:LevelFormat.BULLET,text:"\u2022",alignment:AlignmentType.LEFT,style:{paragraph:{indent:{left:720,hanging:360}}}},
    {level:1,format:LevelFormat.BULLET,text:"\u25E6",alignment:AlignmentType.LEFT,style:{paragraph:{indent:{left:1440,hanging:360}}}},
    {level:2,format:LevelFormat.BULLET,text:"\u2013",alignment:AlignmentType.LEFT,style:{paragraph:{indent:{left:2160,hanging:360}}}},
  ]},
  {reference:"numbers",levels:[
    {level:0,format:LevelFormat.DECIMAL,text:"%1.",alignment:AlignmentType.LEFT,style:{paragraph:{indent:{left:720,hanging:360}}}},
    {level:1,format:LevelFormat.LOWER_LETTER,text:"%2)",alignment:AlignmentType.LEFT,style:{paragraph:{indent:{left:1440,hanging:360}}}},
  ]},
]};

const ch = [];
const rawPush = ch.push.bind(ch);
ch.push = function(...items) {
  for (const item of items) {
    rawPush(item);
    if (item instanceof Table) rawPush(sp(80));
  }
  return ch.length;
};
const pb = () => ch.push(new Paragraph({children:[new PageBreak()]}));

// ═══════════════════════════════════════════════════════════════════════════════
// TITLE PAGE
// ═══════════════════════════════════════════════════════════════════════════════
ch.push(sp(2400));
ch.push(new Paragraph({alignment:AlignmentType.CENTER,spacing:{after:200},children:[new TextRun({text:"REAKTOR",font:"Arial",size:56,bold:true,color:C.pri,characterSpacing:400})]}));
ch.push(new Paragraph({alignment:AlignmentType.CENTER,spacing:{after:120},children:[new TextRun({text:"Tactile Design System",font:"Arial",size:48,bold:true,color:C.dk})]}));
ch.push(new Paragraph({alignment:AlignmentType.CENTER,spacing:{after:80},children:[new TextRun({text:"Complete Architecture & Implementation Guide",font:"Arial",size:28,color:C.med})]}));
ch.push(sp(400));
ch.push(new Paragraph({alignment:AlignmentType.CENTER,border:{top:{style:BorderStyle.SINGLE,size:2,color:C.sec,space:8}},children:[]}));
ch.push(sp(200));
ch.push(new Paragraph({alignment:AlignmentType.CENTER,spacing:{after:80},children:[new TextRun({text:"Version 4.0 \u2014 April 2026",font:"Arial",size:24,color:C.lt})]}));
ch.push(new Paragraph({alignment:AlignmentType.CENTER,spacing:{after:80},children:[I("A living UI runtime for Compose Multiplatform & React Strict DOM")]}));
ch.push(sp(600));
ch.push(new Paragraph({alignment:AlignmentType.CENTER,spacing:{after:80},children:[N("Authored by Shibasis Patnaik")]}));
ch.push(new Paragraph({alignment:AlignmentType.CENTER,children:[new TextRun({text:"with architectural analysis by Claude (Anthropic) and Codex (OpenAI)",font:"Arial",size:20,color:C.lt})]}));
pb();

// ═══════════════════════════════════════════════════════════════════════════════
// TOC
// ═══════════════════════════════════════════════════════════════════════════════
ch.push(h1("Table of Contents"));
ch.push(sp(80));
const toc=[
  "1|Executive Summary & Vision",
  "2|Architectural Overview & Layer Model",
  "3|System Design: Invariants, Data Flow & State Ownership",
  "4|Interaction State Machines",
  "5|Design Language System",
  "6|Atomic Design: The Tactile Component Library",
  "7|Adaptive Layouts & Multi-Pane Navigation",
  "8|Physics Engine & Sensory Feedback",
  "9|Cross-Platform Rendering: Reconciler & RSD",
  "10|Reaktor Graph Integration",
  "11|AI & Server-Driven UI",
  "12|Implementation Roadmap",
  "13|Annotated References & Further Reading",
  "14|Implementation Checklists, Glossary & Decision Records",
  "15|Reaktor Capability, Environment & Strategy Runtime",
  "16|Reaktor + BestBuds Current State Analysis",
  "17|Next-Generation UI Runtime Contracts",
  "18|Applications and Experiences This Runtime Enables",
  "19|Motivation, Architecture Rationale & Problems Solved",
];
toc.forEach(e=>{const[n,t]=e.split("|");ch.push(new Paragraph({spacing:{after:100},children:[N(`${n}.  ${t}`)]}))});
pb();

// ═══════════════════════════════════════════════════════════════════════════════
// 1. EXECUTIVE SUMMARY
// ═══════════════════════════════════════════════════════════════════════════════
ch.push(h1("1. Executive Summary & Vision"));
ch.push(sp(80));

ch.push(h2("1.1 What is the Tactile Design System?"));
ch.push(p("The Tactile Design System is a living UI runtime in which interface elements react physically to user interaction. When a user presses a button, the background surface ripples like a stone thrown in water. When a card is flicked, neighboring elements sway in response. When a toggle is switched, the device vibrates with a force proportional to the toggle\u2019s virtual mass."));
ch.push(p("The system is built on three foundational pillars:"));
ch.push(bul([B("Interaction state machines: "), N("Every element tracks concurrent interaction channels (focus, hover, press, drag) as a snapshot, not a single sealed state.")]));
ch.push(bul([B("Pluggable design languages: "), N("The same component can render as NeoBrutalism, Glass, Material, NeoMorphism, or any custom skin\u2014switched at runtime without changing component code.")]));
ch.push(bul([B("Atomic Compose design: "), N("A full Atomic Design hierarchy (atoms \u2192 molecules \u2192 organisms \u2192 templates \u2192 pages) where every component combines interaction snapshots with design language visuals through Compose Multiplatform.")]));
ch.push(p("These three pillars form the foundation. On top of them, adaptive layouts provide responsive multi-pane navigation using compose-adaptive patterns and reaktor-graph. And beyond the foundation, cross-platform rendering via a React reconciler (for native paths) and React Strict DOM (for web) extends reach\u2014but the design system stands complete without them."));

ch.push(h2("1.2 The Core Insight"));
ch.push(p("Every major design system today is static: components change color on hover, maybe animate a shadow. But physical objects don\u2019t behave that way. A rubber ball bounces differently from a steel plate. A wooden block transmits vibration differently from a glass surface. The Tactile Design System assigns physical material properties\u2014mass, stiffness, damping, friction\u2014to every UI element, creating interactions that feel tangible and real."));
ch.push(p("This insight draws from Apple\u2019s WWDC 2018 \u201CDesigning Fluid Interfaces\u201D talk, where they demonstrated that spring-based animations feel more natural than duration-based ones because they model real physics. The Tactile system extends this from individual animations to an entire connected surface of interactive elements."));

ch.push(h2("1.3 Architectural Principles"));
ch.push(bul([B("Foundation first: "), N("The design system is fully usable in pure Compose without React, without FFI, without server-driven UI. Each upper layer adds capability, not dependency.")]));
ch.push(bul([B("Extend, don\u2019t replace: "), N("ComposeContainer and ComposeContent from reaktor-graph are extended as needed. There is no TactileContainer\u2014the graph already has recursive rendering built in.")]));
ch.push(bul([B("Skin as data: "), N("A DesignLanguage produces pure data (colors, radii, shadows as primitives). Renderers consume data. This makes skins testable, serializable, and portable.")]));
ch.push(bul([B("Physics, not timelines: "), N("All interactive animation is spring-based with material properties. No duration-based easing curves.")]));
ch.push(bul([B("Separation of concerns: "), N("Interaction tracking is separated from visual resolution, which is separated from rendering. Three independent systems composed at the component level.")]));
ch.push(bul([B("Incremental adoption: "), N("Existing R-prefixed components coexist with T-prefixed components. Teams migrate one component at a time, one screen at a time.")]));

ch.push(h2("1.4 How It Differs from Existing Systems"));
ch.push(p("Understanding where Tactile sits relative to existing design systems clarifies its value proposition:"));
ch.push(tbl(["System","Approach","Tactile Difference"],[
  ["Material Design 3","Static tokens + component specs. Animation via MotionSpec duration curves.","Tactile adds physics (spring/mass/damping), pluggable skins, cross-element reactivity via TactileField. Material becomes one of many skins."],
  ["Radix UI","Headless unstyled primitives + slot API. Bring your own styles.","Tactile components are opinionated but skinnable. Interaction + physics + visuals are integrated, not separate concerns the consumer assembles."],
  ["Shopify Polaris","Token-driven, single visual language, web-only.","Tactile is multi-platform (CMP + RSD), supports multiple design languages, adds physical interaction model."],
  ["React Native Paper","Material Design for React Native. Static theming.","Tactile is Kotlin-first with React as optional composition layer. Physics-driven rather than animation-spec-driven."],
  ["Apple HIG / SwiftUI","Platform-specific, spring animations, haptics.","Tactile brings Apple-quality spring physics to ALL platforms. Design language abstraction enables non-Apple visual styles."],
],[2000,3200,4160]));

ch.push(h2("1.5 Target Applications"));
ch.push(tbl(["Application","Platform","Role","Current State"],[
  ["BestBuds","Android, iOS, Web, Desktop","First consumer\u2014social app proving ground","22 screens, graph navigation, custom theme"],
  ["Nexergy","Android, iOS, Web","Energy management dashboard","Early development"],
  ["Manna","Android, iOS, Web","Learning platform","Early development"],
],[2200,2400,2800,1960]));

ch.push(h2("1.6 Priority Map for This Document"));
ch.push(p("The rest of this document is organized around two priority tiers. Critical items are required for the first useful version of the runtime. Important items are not optional long-term, but they should be layered on top of the critical system so the core does not become dependent on the React, FFI, shader, or AI stack."));
ch.push(tbl(["Priority","Area","Why It Is Prioritized","Primary Sections"],[
  ["C1","Interaction state machines","They define what UI components can do. If this layer is shallow, every component becomes a one-off gesture implementation.","4, 6"],
  ["C2","DesignLanguage interface","It separates behavior from appearance. Without this boundary, Material, NeoBrutalism, LiquidGlass, and product-specific styles become copy-pasted components.","5"],
  ["C3","Atomic Compose implementation","This is the first concrete product surface: finished atoms, molecules, organisms, templates, and pages usable by Kotlin app teams today.","6"],
  ["C4","Adaptive layouts","Atoms and molecules are not enough. Real apps need panes, responsive grids, navigation chrome, focus policy, and back behavior.","7"],
  ["C5","reaktor-graph integration","The app runtime already owns navigation, DI, lifecycle, routes, ports, and services. Tactile must fit into that runtime instead of creating a parallel UI shell.","7, 10"],
  ["I1","Physical tactile engine","It creates the tactile feel: springs, haptics, gestures, field impulses, shaders. It is important, but the state machine and skin contracts must exist first.","8"],
  ["I2","React Strict DOM implementation","It brings the same component model to web and React Native through html.* and css.create.","9"],
  ["I3","Adaptive layouts for RSD","It mirrors the adaptive policies of Compose for the React path, but does not replace the Compose implementation.","7, 9"],
  ["I4","Physical engine in RSD and skins","The web path should use the same material vocabulary even when platform capabilities differ.","8, 9"],
  ["I5","React reconciler","It enables React authoring on Hermes while targeting the Compose design system, and becomes the bridge for server/AI-generated UI.","9"],
  ["I6","Generative React integration","AI and servers generate constrained React components, not a static JSON UI DSL.","11"],
],[1100,2200,3860,2200]));
ch.push(box("Reading Guidance","If you are a less experienced programmer, read Sections 3, 4.1-4.6, 5.1-5.4, 6.1-6.4, and 7.1-7.5 first. If you are an expert implementing the runtime, treat Sections 3.1, 4.5-4.9, 5.8-5.12, 7.7-7.13, 9.2-9.8, and 10.4-10.8 as the contract surface."));

ch.push(h2("1.7 Target Priority for Runtime Validation"));
ch.push(p("The runtime should be proven through real products, not abstract demos. The first validation loop is BestBuds plus Reaktor Desktop. Nexergy, Manna, and new apps are important later, but they should not drive the first version of the UI runtime."));
ch.push(tbl(["Priority","Target","What It Must Prove","UI Runtime Work It Exercises"],[
  ["1","BestBuds mobile/tablet/desktop","A real social app can migrate from current Compose + product-specific components into graph-aware adaptive Tactile UI without losing chat, auth, cache, services, or navigation behavior.","Atomic components, BestBudsSkin, adaptive navigation, list-detail chat, network-aware graceful degradation, forms, loading/error states."],
  ["1","Reaktor Desktop / Workbench","The engine can explain itself: graph, panes, route state, tokens, skins, host tree, capabilities, telemetry, and generated UI are inspectable live.","Workbench panes, inspectors, component catalogue, capability dashboard, host-tree debugger, visual regression matrix."],
  ["2","Nexergy","Transactional and operational app patterns after core runtime stabilizes.","Dashboards, tables, forms, upload/media, offline queue, payment-safe action contracts."],
  ["2","Manna","Learning/productivity app patterns after core runtime stabilizes.","Roadmaps, knowledge cards, assessment forms, AI-generated panels, progress visualization."],
  ["2","New apps","External proof once BestBuds and Reaktor Desktop are good enough to teach the system.","CLI templates, docs, generated registries, sample apps."],
],[1000,1900,3400,3060]));

ch.push(h2("1.8 The Consistent Story"));
ch.push(p("This runtime has one story: Reaktor turns an app into a live, typed graph; reaktor-capability tells the graph what kind of environment it is running in; Tactile turns graph-backed intent into adaptive, tactile, semantic UI; renderers put that UI on Compose, RSD, or a React host tree; the workbench explains every decision. BestBuds proves this as a real product. Reaktor Desktop proves it as an engine and editor."));
ch.push(...code([
  "Environment + capabilities",
  "        -> runtime policy",
  "        -> interaction machines",
  "        -> semantic component contracts",
  "        -> DesignLanguage visual specs",
  "        -> atomic/adaptive UI",
  "        -> reaktor-graph actions/routes/services",
  "        -> Compose/RSD/host-tree rendering",
  "        -> workbench inspection and telemetry",
]));

ch.push(h2("1.9 Why This Runtime Needs to Exist"));
ch.push(p("The motivation is not to make another design system with better buttons. The motivation is that modern applications are becoming runtime systems: they adapt to screen size, device class, network quality, permissions, live services, AI-generated content, offline state, user role, and platform constraints. Ordinary component libraries are not structured to own that much behavior."));
ch.push(p("Reaktor already has the hard parts that most UI systems bolt on late: graph navigation, ports, DI, services, repositories, auth, telemetry, FFI, and a capability model. Tactile should be the UI runtime that sits on top of that foundation and makes those systems visible, usable, inspectable, and composable."));
ch.push(tbl(["Pressure","What Usually Happens","Tactile Runtime Answer"],[
  ["Adaptive apps","Phone, tablet, desktop, and web layouts become separate implementations with separate navigation behavior.","Graph-backed adaptive containers render the same route state into one pane, two panes, three panes, drawer, rail, grid, or supporting pane layouts."],
  ["Design system scale","Teams copy Material components, then fork them for product styles, then fork them again for states and edge cases.","Interaction machines define behavior once. DesignLanguage implementations define appearance separately. Atomic components compose both."],
  ["Physical interaction","Springs, haptics, gestures, shader ripples, and press effects are implemented per component or ignored.","A shared tactile runtime owns interaction snapshots, material physics, haptics, field impulses, shader inputs, and motion policy."],
  ["Capability variance","Apps scatter checks for low battery, slow network, device memory, offline state, and reduced motion.","EnvironmentFeature normalizes device, execution, and network profiles; policies choose tiers and graceful degradation consistently."],
  ["Generated UI","Static SDUI invents a partial programming language with conditionals, loops, bindings, effects, and templates.","React or Kotlin remains the authoring model. Host trees and registries become validated internal contracts, not product-facing DSLs."],
  ["Debugging","UI bugs require chasing screenshots, logs, route state, service state, and device state separately.","Reaktor Desktop workbench inspects graph, panes, capabilities, semantic nodes, visual specs, host tree commits, actions, and telemetry together."],
],[2200,3450,3710]));

ch.push(h2("1.10 What Problems This Solves"));
ch.push(p("The architecture solves several classes of problems at once. They should not be treated as separate feature lists; they are connected symptoms of the same missing runtime boundary."));
ch.push(bul([B("Behavioral reuse: "), N("A button, card, menu, sheet, list item, slider, or form field should not reimplement focus, hover, press, drag, disabled, loading, validation, semantics, and action dispatch. The state machine owns this behavior once.")]));
ch.push(bul([B("Visual language reuse: "), N("Material, NeoBrutalism, LiquidGlass, CellShading, product skins, and experimental styles should not create parallel component libraries. A skin maps behavior + tokens + policy into visual specs.")]));
ch.push(bul([B("Layout reuse: "), N("Compact, medium, expanded, desktop, foldable, and web layouts should not fork app navigation. Adaptive containers decide how many graph routes can be visible and how focus/back behavior collapses.")]));
ch.push(bul([B("Runtime adaptation: "), N("The same UI should automatically lower visual cost, animation intensity, media quality, network concurrency, and generated-bundle behavior when environment or accessibility policy requires it.")]));
ch.push(bul([B("Safe dynamism: "), N("AI and server-driven surfaces should compose approved components and actions, not ship arbitrary unreviewed UI protocols or brittle JSON mini-languages.")]));
ch.push(bul([B("Operational clarity: "), N("When the runtime chooses a degraded mode, rejects a generated component, collapses a pane, or disables a shader, developers should be able to see exactly why.")]));

ch.push(h2("1.11 Motivation by Audience"));
ch.push(tbl(["Audience","Current Pain","What This Gives Them"],[
  ["Product engineers","Every screen manually combines navigation, loading, errors, forms, gestures, responsive layout, and product styling.","A graph-aware component/runtime library where the default path already handles state, accessibility, adaptive layout, actions, and policies."],
  ["Designers","A visual direction becomes expensive because each style needs new components or special-case overrides.","DesignLanguage lets one behavior system render as multiple visual languages while keeping token and component semantics stable."],
  ["Platform engineers","Cross-platform UI becomes a pile of target-specific exceptions and hidden performance costs.","RendererCapabilities, EnvironmentFeature, and policy tiers make platform differences explicit and testable."],
  ["Backend/server engineers","Dynamic UI often means shipping JSON schemas that slowly turn into a weak programming language.","Server UI ships signed React bundles or constrained component definitions that validate against stable registries and action contracts."],
  ["AI agents","Generated UI is risky because the model can invent components, call unsafe actions, or break accessibility.","The component/action/capability manifests become the allowed vocabulary for generation, validation, and rollback."],
  ["Operations/devtools","Runtime behavior is hard to explain across graph state, service state, UI state, and device conditions.","Reaktor Desktop becomes a workbench that correlates graph, panes, host tree, policies, telemetry, and visual decisions."],
],[1900,3700,3760]));

ch.push(h2("1.12 Success Criteria"));
ch.push(p("A successful version of the runtime is not measured by whether a demo looks impressive. It is measured by whether a real app can move faster and become easier to reason about."));
ch.push(num("A BestBuds chat surface can move from phone push navigation to tablet/desktop list-detail-supporting-pane layout without duplicating screen logic."));
ch.push(num("A component can switch from Material to NeoBrutalism or LiquidGlass by changing the DesignLanguage, not by replacing the component."));
ch.push(num("A low-end, hot, offline, metered, or reduced-motion environment gets a degraded but coherent UI without product screens hand-writing all branches."));
ch.push(num("A generated React surface can be validated, rendered, inspected, rolled back, and constrained to approved actions."));
ch.push(num("Reaktor Desktop can explain why a route, pane, component, capability tier, visual spec, or host tree commit exists."));
ch.push(num("A less experienced programmer can build screens using the finished Atomic Design components, while an expert can drop down into machines, policies, registries, or renderer implementations without fighting the architecture."));
pb();

// ═══════════════════════════════════════════════════════════════════════════════
// 2. ARCHITECTURAL OVERVIEW
// ═══════════════════════════════════════════════════════════════════════════════
ch.push(h1("2. Architectural Overview & Layer Model"));
ch.push(sp(80));

ch.push(h2("2.1 Layer Model"));
ch.push(p("The system is organized into layers with strict dependency direction: upper layers depend on lower layers, never the reverse. Each layer is independently adoptable\u2014you can use interaction state machines without the component library, or use components without adaptive layouts:"));
ch.push(tbl(["Layer","Name","Responsibility","Depends On","Can Use Alone?"],[
  ["0","Foundation","Reaktor core, capability, graph, graph-port, io, db, telemetry, FFI substrate","External (Koin, Ktor, Compose)","Yes (existing + new capability)"],
  ["1","Environment + Policy","Normalized device/execution/network profiles, runtime policies, strategy tiers","Layer 0","Yes + Layer 0"],
  ["2","Interaction","State machines, physics, haptics, field, shaders, tokens","Layers 0\u20131","Yes + Layers 0\u20131"],
  ["3","Design Languages","Pluggable visual skins per component per interaction state and environment policy","Layers 1\u20132","Yes + Layers 1\u20132"],
  ["4","Atomic Components","Full component library: atoms, molecules, organisms, templates","Layers 0\u20133","Yes + Layers 0\u20133"],
  ["5","Adaptive Layouts","Multi-pane containers, responsive navigation, renderer/layout capability negotiation","Layers 0\u20134","Yes + Layers 0\u20134"],
  ["6","Cross-Platform","React reconciler, React Strict DOM, FFI bridge","Layers 0\u20135","Requires Layers 0\u20135"],
  ["7","AI & SDUI","AI-generated React, server-shipped bundles, validation, action contracts","Layer 6","Requires Layer 6"],
],[900,1300,2800,2400,1960]));

ch.push(h2("2.2 Why This Layering Matters"));
ch.push(p("Consider three adoption scenarios that demonstrate layer independence:"));
ch.push(h4("Scenario A: Existing Compose app wants physics-based animations"));
ch.push(p("The team adopts only Layer 1 (InteractionController + SpringSimulation + MaterialPhysics). They use rememberInteractionController() and Modifier.tactileInteraction() inside their existing Material composables. No skin system, no new component library. Just physics."));
ch.push(h4("Scenario B: Team wants skinnable components without adaptive layouts"));
ch.push(p("The team adopts Layers 1\u20133. They get TButton, TCard, TInput\u2014all skin-aware, all physics-driven. They use their existing navigation (maybe even Jetpack Compose Navigation). No adaptive containers, no React, no SDUI."));
ch.push(h4("Scenario C: Full system with React authoring for web parity"));
ch.push(p("The team uses all layers. Product engineers write React components that render via reconciler on native and via RSD on web. This is the full vision, but it\u2019s not required to get enormous value from the system."));

ch.push(h2("2.3 Module Dependency Graph"));
ch.push(p("The Kotlin module structure mirrors the layer model. Arrows indicate compile-time dependencies:"));
ch.push(...code([
  "reaktor-core           (logging, capabilities, dispatchers)",
  "    \u2502",
  "reaktor-capability     (NEW: FeatureContract, EnvironmentFeature,",
  "    \u2502                     CapabilityAvailability, tiers, strategy policy)",
  "    \u2502",
  "reaktor-graph-port     (port-based wiring primitives)",
  "    \u2502",
  "reaktor-graph          (Graph, Node, navigation, DI, ComposeContainer/Content)",
  "    \u2502",
  "reaktor-ui             (DesignTokens, ColorScheme, Typography, Spacing, Shapes,",
  "    \u2502                     ComponentSpec, R-prefixed atoms/molecules, Responsive)",
  "    \u2502",
  "reaktor-tactile        (NEW: interaction, physics, field, sensory,",
  "    \u2502                     DesignLanguage, skins, T-prefixed components,",
  "    \u2502                     adaptive containers)",
  "    \u2502",
  "reaktor-ffi            (Hermes JSI bridge, FlexBuffer encoding)",
  "    \u2502",
  "reaktor-tactile-react  (FUTURE: reconciler host config, RSD implementations)",
]));
ch.push(p("Key observation: reaktor-tactile depends on reaktor-ui (for base tokens), reaktor-capability (for normalized environment and runtime policy), and reaktor-graph (for navigation/DI/ComposeContainer). It does NOT depend on reaktor-ffi for the pure Compose path. The FFI bridge is only needed when the React rendering path is active."));

ch.push(h2("2.4 Relationship to Existing Modules"));
ch.push(tbl(["Existing Module","What Tactile Uses From It","What Tactile Adds"],[
  ["reaktor-ui","DesignTokens (9 token categories, 100+ values), ComponentSpec enums (Variant, Size, State), Responsive utilities, TokenFactory color derivation","TactileTokens wrapping DesignTokens, physics tokens, DesignLanguage-driven visual resolution replacing static token lookup"],
  ["reaktor-graph","Graph, ComposeContainer, ComposeContent, ContainerNode, GraphContent, ObservableStack, WindowSize, BackHandlerContainer, Koin DI, Port-based wiring","AdaptiveNavigationContainer (extends ContainerNode), AdaptivePaneContainer, GraphRouteContent, TactileGraphApplication"],
  ["reaktor-core","Capabilities, ConcurrencyCapability, Feature slots, Dispatchers, Logging","Extends capability model for HapticEngine, SoundEngine"],
  ["bestbuds/design","BestBudsTheme, BestBudsTokens, BBColors, gradients","Migration target: BestBudsSkin implements DesignLanguage, T-components replace custom components one by one"],
],[2000,3800,3560]));

ch.push(h2("2.5 The Three Concerns of Every Component"));
ch.push(p("The most important architectural idea in the Tactile Design System is that every component is the composition of three independent concerns:"));
ch.push(sp(80));
ch.push(...code([
  "\u250C\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2510",
  "\u2502  TButton (Tactile Component)                                       \u2502",
  "\u2502                                                                    \u2502",
  "\u2502  1. INTERACTION       2. VISUAL RESOLUTION     3. RENDERING        \u2502",
  "\u2502  (what is happening)  (what should it look     (put pixels on      \u2502",
  "\u2502                        like given #1?)          screen per #2)     \u2502",
  "\u2502                                                                    \u2502",
  "\u2502  InteractionSnapshot  DesignLanguage.button    Compose Box +       \u2502",
  "\u2502   .pressed = true       Visuals(variant,       graphicsLayer +     \u2502",
  "\u2502   .hovered = false       size, state, snap)    background +        \u2502",
  "\u2502   .focused = true       \u2192 ButtonVisuals        border + shadow +  \u2502",
  "\u2502   .pressOrigin = (x,y)   { bgArgb, scale,      clip + content     \u2502",
  "\u2502                            shadow, radius... }                     \u2502",
  "\u2502                                                                    \u2502",
  "\u2502  InteractionController  DesignLanguage impl    Spring-animated     \u2502",
  "\u2502  Modifier.tactile()     (NeoBrutalism, Glass)  values from #2     \u2502",
  "\u2514\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2518",
]));
ch.push(p("This separation is NOT just code organization. It is the fundamental design constraint. Each concern is independently replaceable: swap the skin without touching interaction. Swap the renderer (Compose vs RSD) without touching the skin. Add new interaction channels without touching rendering."));
ch.push(p("This is how one component code path serves five visual languages, two renderers, and multiple platform targets."));

ch.push(h2("2.6 The Runtime Story From Product Feature to Pixels"));
ch.push(p("Every later section is one part of the same pipeline. When the document feels large, return to this story: a product feature becomes a graph-backed UI capability, then a semantic component tree, then policy-aware visuals, then renderer-specific pixels, and finally a workbench-inspectable runtime trace."));
ch.push(tbl(["Stage","Question","Runtime Owner"],[
  ["Product intent","What is the user trying to do? Open chat, send message, create event, inspect graph node, switch skin.","ActionRegistry + reaktor-graph route/action model"],
  ["Environment and policy","What can this device/network/runtime safely do right now? Full tactile effects, basic mode, queued upload, no remote bundle?","reaktor-capability EnvironmentFeature + TactileRuntimePolicy"],
  ["Interaction semantics","What is happening at the component boundary? Pressed, focused, dragged, selected, invalid, busy?","Interaction machines + InteractionSnapshot"],
  ["Component contract","What is this component allowed to be, contain, expose, and do?","ComponentCapabilityRegistry + SemanticNode + AccessibilityContract"],
  ["Visual resolution","How should this semantic component look under this skin and policy?","DesignLanguage -> VisualSpec"],
  ["Layout and navigation","Where does it live: compact route, list pane, detail pane, supporting pane, drawer, grid, form?","Adaptive containers + GraphRouteContent"],
  ["Rendering","Which renderer puts it on screen? Compose native, Compose host tree, RSD web/native?","Compose/RSD/HostTree renderer"],
  ["Inspection","Why did the runtime choose this behavior?","Reaktor Desktop workbench + telemetry"],
],[2300,4300,2760]));
ch.push(p("This pipeline is the consistency rule for the entire system. If a new feature bypasses one stage, that shortcut should be deliberate and documented. For example, a product-only BestBuds card may skip host-tree support at first, but it should not skip semantics, accessibility, environment policy, or graph action boundaries."));

ch.push(h2("2.7 The Architecture as Runtime Planes"));
ch.push(p("The layer diagram explains dependencies. Runtime planes explain ownership. A plane is an area of responsibility that cuts across modules and targets. Keeping these planes separate prevents the system from becoming a single large UI framework where every class knows too much."));
ch.push(tbl(["Plane","Primary Owners","Owns"],[
  ["Capability and environment plane","reaktor-capability, reaktor-core Feature slots","Device, execution, network, permission, renderer, media, haptic, storage, and runtime availability profiles."],
  ["Graph and action plane","reaktor-graph, graph-port, service/repository nodes","Routes, panes, payloads, DI scope, lifecycle, ports, service calls, repositories, and allowed app actions."],
  ["Semantic component plane","Tactile component registry, semantic nodes, accessibility contracts","What each component means, what children it may contain, what state it exposes, what actions it can emit, and how accessibility is validated."],
  ["Interaction and tactile plane","Interaction machines, physical engine, field, sensory adapters","Pointer/focus/hover/press/drag snapshots, springs, haptics, gestures, field impulses, shader uniforms, and motion budgets."],
  ["Visual language plane","DesignLanguage, tokens, visual specs","How semantic components look under a skin, variant, component state, interaction snapshot, and runtime policy."],
  ["Rendering and tooling plane","Compose renderer, RSD renderer, host-tree renderer, Reaktor Desktop","How contracts become pixels and how the runtime is inspected, debugged, profiled, and validated."],
],[2300,3000,4060]));
ch.push(p("A healthy contribution should know which plane it belongs to. For example, a new button shape belongs to the visual language plane. A new press-and-hold behavior belongs to the interaction plane. A new list-detail collapse rule belongs to the graph/adaptive plane. A new validation rule for AI-generated UI belongs to the semantic component plane."));

ch.push(h2("2.8 Runtime Control Loops"));
ch.push(p("A next-generation UI runtime is a set of control loops, not only a render tree. Each loop observes inputs, normalizes state, applies policy, and updates the UI or runtime. These loops must be explicit because they operate at different speeds."));
ch.push(tbl(["Loop","Runs When","Result"],[
  ["Interaction loop","Every pointer/focus/keyboard/drag event; often frame-adjacent.","Updates InteractionSnapshot, springs, haptics, field impulses, local visuals. Must not require JS/FFI per frame."],
  ["Environment loop","Network, battery, thermal, app state, device profile, renderer capability, or telemetry changes.","Updates EnvironmentSnapshot and TactileRuntimePolicy; components and strategies degrade or upgrade tiers."],
  ["Graph/action loop","User action, service response, route transition, repository update, or generated action ref dispatch.","Updates graph state, navigation stack, route payloads, repositories, and visible pane content."],
  ["Adaptive layout loop","Window metrics, posture, orientation, input mode, or pane availability changes.","Recomputes visible panes, chrome, focus targets, collapse behavior, grids, and supporting pane placement."],
  ["Generation loop","AI/server bundle or component source is received, validated, signed, loaded, committed, or rolled back.","Produces a validated host tree or rejects the generated UI with explainable errors."],
  ["Tooling loop","Runtime emits traces, validation results, policy changes, or performance samples.","Workbench correlates graph, UI, policy, host tree, semantic nodes, and telemetry for inspection."],
],[2500,3400,3460]));

ch.push(h2("2.9 Ownership Boundaries"));
ch.push(p("The most common failure mode in UI runtimes is boundary collapse. A renderer starts owning navigation. A skin starts knowing about services. A component starts checking raw device state. A generated UI layer starts dispatching arbitrary app mutations. The Tactile architecture avoids that by making ownership explicit."));
ch.push(bul([B("Components own local semantics, not app orchestration: "), N("A TButton knows it can emit onPress. It does not decide which graph route opens. That belongs to an ActionRegistry or route binding.")]));
ch.push(bul([B("Skins own visuals, not behavior: "), N("A LiquidGlass skin can change blur, translucency, radius, depth, and press response values. It cannot change whether a component is focusable, selectable, dismissible, or destructive.")]));
ch.push(bul([B("Adaptive containers own pane presentation, not business logic: "), N("They choose list/detail/supporting pane visibility and focus policy. They do not fetch chat messages or decide which friend profile is valid.")]));
ch.push(bul([B("Capability policies own degradation, not scattered screens: "), N("A screen asks for capabilities through policy tiers. It should not branch directly on raw model strings, user-agent text, or arbitrary device trivia.")]));
ch.push(bul([B("React authoring owns composition, not native animation: "), N("React can produce the host tree and handle semantic state changes. Compose/Kotlin owns tactile frame loops, haptics, shaders, and local gestures.")]));
ch.push(bul([B("Workbench owns explanation, not runtime decisions: "), N("Tooling observes, simulates, and explains. Runtime modules remain usable without the workbench present.")]));

ch.push(h2("2.10 Why These Layers Are Ordered This Way"));
ch.push(p("The order is designed to let the first useful system exist before the ambitious parts arrive. The Compose Atomic Design path can ship first. Physics deepens it. Adaptive graph integration makes it app-scale. React/RSD and Hermes extend authoring and rendering. AI/server-driven UI becomes possible only after the contracts are stable."));
ch.push(tbl(["Layer Choice","Reason"],[
  ["Interaction machines before skins","A skin can only be correct if it receives complete component state. Otherwise each skin invents its own idea of pressed, dragged, loading, invalid, focused, selected, or disabled."],
  ["DesignLanguage before finished components","Components should not hard-code Material. They should ask the active design language for renderer-neutral visual specs."],
  ["Compose components before React reconciler","A real native component library validates semantics, interaction, accessibility, and graph integration before FFI complexity is introduced."],
  ["Adaptive containers before generated UI","Generated UI is only useful when the runtime already knows where generated surfaces may live and how panes collapse."],
  ["Capability/environment before physical effects","Tactility must respect device performance, low power, thermal pressure, reduced motion, and renderer support from day one."],
  ["Workbench before broad external adoption","A runtime this powerful needs first-class inspection. Without tooling, every advanced behavior becomes hard to trust."],
],[3000,6360]));
pb();

// ═══════════════════════════════════════════════════════════════════════════════
// 3. SYSTEM DESIGN: INVARIANTS, DATA FLOW, STATE OWNERSHIP
// ═══════════════════════════════════════════════════════════════════════════════
ch.push(h1("3. System Design: Invariants, Data Flow & State Ownership"));
ch.push(sp(80));
ch.push(p("This section describes the architectural rules that hold across the entire system. Understanding these invariants is essential for anyone extending, debugging, or reasoning about the system."));

ch.push(h2("3.1 System Invariants"));
ch.push(p("These are the guarantees the system makes. They are not guidelines\u2014they are hard constraints enforced by the architecture:"));
ch.push(sp(80));

ch.push(h3("Invariant 1: Skins are pure functions"));
ch.push(p("A DesignLanguage implementation receives (tokens, variant, state, snapshot) and returns a visual spec data class. It has no side effects, no mutable state, no lifecycle, no platform imports. Given the same inputs, it always produces the same output. This makes skins:"));
ch.push(bul("Testable with unit tests (no Compose test harness needed)"));
ch.push(bul("Serializable (visual specs are data classes with primitive fields)"));
ch.push(bul("Portable (the same skin works for Compose rendering and CSS generation)"));
ch.push(bul("Previewable (generate specs for all states without running the app)"));

ch.push(h3("Invariant 2: InteractionSnapshot is immutable"));
ch.push(p("InteractionController emits new snapshot instances via StateFlow. Snapshots are never mutated in place. This means:"));
ch.push(bul("Compose reads snapshot via collectAsState() and recomposes on change"));
ch.push(bul("Multiple observers (skin resolver, haptic engine, field emitter) see consistent state"));
ch.push(bul("Time-travel debugging is possible (record snapshots, replay)"));
ch.push(bul("No race conditions between interaction channels"));

ch.push(h3("Invariant 3: Animation never crosses the FFI bridge"));
ch.push(p("When the React reconciler path is active, React owns the component tree (what is visible) and Kotlin owns the frame loop (how things animate). Springs, haptics, shader impulses, and drag physics run natively at 60/120fps. React commits semantic state changes\u2014text content, visibility, navigation\u2014over the bridge. This is the same architecture as React Native."));

ch.push(h3("Invariant 4: Visual specs use only primitives"));
ch.push(p("ButtonVisuals, CardVisuals, InputVisuals\u2014all visual spec data classes use Long (ARGB color), Float (dp dimensions), Int (font weight), and Boolean. No Compose Color, no Dp, no ImageVector, no platform types. This is enforced because:"));
ch.push(bul("Visual specs must be consumable by both Compose and CSS renderers"));
ch.push(bul("Visual specs must be serializable for debugging, logging, snapshot testing"));
ch.push(bul("Visual specs must be producible by pure Kotlin functions (skins) without Compose imports"));

ch.push(h3("Invariant 5: ComposeContainer/ComposeContent are extended, never replaced"));
ch.push(p("Tactile adaptive containers implement ComposeContainer. Tactile screens implement ComposeContent. GraphContent\u2019s recursive rendering continues to work unchanged. Any ComposeContainer can contain any other ComposeContainer. This preserves the graph\u2019s existing composition model and avoids a parallel container hierarchy."));

ch.push(h3("Invariant 6: Tokens flow downward, never sideways"));
ch.push(p("DesignTokens and TactileTokens flow through CompositionLocals from parent to child. A child component never reaches up to its grandparent or sideways to a sibling for token values. The active skin resolves tokens into visual specs at the component level, reading from locals."));

ch.push(h2("3.2 Data Flow: Press Event to Pixels"));
ch.push(p("Understanding the complete data flow for a single user interaction reveals how all system parts connect. Here is what happens when a user presses a TButton:"));
ch.push(sp(80));
ch.push(...code([
  "STEP 1: Pointer Input (Platform Layer)",
  "\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500",
  "Compose PointerInputScope detects ACTION_DOWN at (120, 340)",
  "Modifier.tactileInteraction() receives the event",
  "                    \u2502",
  "                    \u25BC",
  "STEP 2: State Machine (Interaction Layer)",
  "\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500",
  "InteractionController.onPointerDown(Offset(120, 340))",
  "  \u2192 Emits new InteractionSnapshot(",
  "      pressed=true, pressOrigin=(120,340), pressCount=1, ...)",
  "  \u2192 Starts pressDurationJob coroutine (updates every 16ms)",
  "  \u2192 Emits Impulse into TactileField (if connected)",
  "                    \u2502",
  "                    \u25BC",
  "STEP 3: Visual Resolution (Skin Layer)",
  "\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500",
  "Compose recomposes because snapshot StateFlow changed",
  "TButton reads LocalDesignLanguage.current (e.g. NeoBrutalismSkin)",
  "skin.buttonVisuals(Filled, Medium, Enabled, snapshot{pressed=true})",
  "  \u2192 Returns ButtonVisuals(",
  "      scaleX=1.0, translationY=3f, shadowOffsetX=1f, shadowOffsetY=1f, ...)",
  "                    \u2502",
  "                    \u25BC",
  "STEP 4: Spring Animation (Physics Layer)",
  "\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500",
  "animateFloatAsState targets change from idle values to pressed values",
  "Compose Animatable uses SpringSpec(dampingRatio=0.47, stiffness=500)",
  "  \u2192 Over ~6 frames: translationY interpolates 0 \u2192 3dp with overshoot",
  "  \u2192 Shadow offsets interpolate 4 \u2192 1dp",
  "                    \u2502",
  "                    \u25BC",
  "STEP 5: Rendering (Compose Layer)",
  "\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500",
  "graphicsLayer { scaleX = animScale; translationY = animTranslateY }",
  "Modifier.shadow(animShadowY.dp, cornerRadius.dp)",
  "Modifier.background(Color(visuals.backgroundArgb))",
  "  \u2192 Skia render tree updated, GPU draws the frame",
  "                    \u2502",
  "                    \u25BC",
  "STEP 6: Cross-Element Effects (Field Layer, concurrent)",
  "\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500",
  "TactileField.step() propagates impulse wave",
  "Nearby components query field.displacementAt(theirPosition)",
  "  \u2192 Neighboring cards sway based on distance + their material",
  "Background shader reads field.shaderUniforms()",
  "  \u2192 FBM noise field distorts around press origin",
  "                    \u2502",
  "                    \u25BC",
  "STEP 7: Sensory Feedback (Haptic Layer, concurrent)",
  "\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500",
  "HapticEngine.play(PhysicsImpact(mass=2.0, velocity=press_velocity))",
  "  \u2192 Android: VibrationEffect amplitude = 160",
  "  \u2192 iOS: CHHapticEvent intensity=0.8, sharpness=0.5",
]));
ch.push(p("Total latency from finger down to first visual frame: <16ms (one frame). Springs begin animating on the next frame. Haptics fire on the same frame as the press. The field impulse propagates over subsequent frames."));

ch.push(h2("3.3 State Ownership Model"));
ch.push(p("Clear state ownership prevents the confusion that plagues most design systems (who owns the color? who decides the animation? who tracks hover?):"));
ch.push(tbl(["State","Owner","Storage","Consumers"],[
  ["Interaction state (pressed, hovered, focused, etc.)","InteractionController (per component)","MutableStateFlow<InteractionSnapshot>","Skin resolver, haptic engine, field emitter, component renderer"],
  ["Active design language (current skin)","TactileTheme composable","CompositionLocal<DesignLanguage>","All T-components via LocalDesignLanguage.current"],
  ["Base design tokens (colors, typography, spacing)","ReaktorTheme composable","CompositionLocal<DesignTokens>","DesignLanguage.tokens(), component renderers for non-skin values"],
  ["Tactile tokens (physics + base tokens combined)","TactileTheme composable","CompositionLocal<TactileTokens>","Components reading physics defaults, reducedMotion flag"],
  ["TactileField impulses","TactileField singleton (per screen/container)","MutableStateFlow<List<Impulse>>","All components observing field, background shader"],
  ["Material physics for a component","Component parameter OR LocalMaterial","CompositionLocal<MaterialPhysics>","InteractionController, spring animation specs"],
  ["Visual specs (ButtonVisuals, etc.)","Derived (skin function of tokens + snapshot)","Local val, not stored","Component renderer only (consumed immediately)"],
  ["Animation state (current spring position)","Compose Animatable (per animated value)","Compose internal","Compose rendering (graphicsLayer, shadow, etc.)"],
  ["Navigation state (back stack)","Graph.backStack (ObservableStack)","MutableStateFlow<List<BackStackEntry>>","GraphContent, adaptive containers"],
  ["Window size class","WindowSize.state","MutableStateFlow<WindowSize>","Adaptive containers, responsive utilities"],
],[2600,2200,2000,2560]));

ch.push(h2("3.4 The Token Resolution Pipeline"));
ch.push(p("When a component needs a color, it goes through a four-stage resolution pipeline:"));
ch.push(sp(80));
ch.push(...code([
  "Stage 1: Primitive Tokens (reaktor-ui)",
  "\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500",
  "ColorPalette.neutral0   = 0xFF_FFFFFF   // \"white\"",
  "ColorPalette.primary500 = 0xFF_1B4F72   // \"dark blue\"",
  "  No semantic meaning. Just swatches.",
  "",
  "Stage 2: Semantic Tokens (reaktor-ui)",
  "\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500",
  "ColorSchemeTokens.primary         = palette.primary500",
  "ColorSchemeTokens.primaryContainer = lighten(primary, 0.8)",
  "ColorSchemeTokens.onPrimary       = autoContentColor(primary)",
  "  Theme-switchable (light/dark). TokenFactory derives 40+ from 3 inputs.",
  "",
  "Stage 3: Tactile Tokens (reaktor-tactile)",
  "\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500",
  "TactileTokens.base          = semanticTokens       // all of reaktor-ui",
  "TactileTokens.defaultMaterial = Materials.Rubber     // physical feel",
  "TactileTokens.pressScale     = 0.95f                // skin-specific",
  "TactileTokens.focusRingArgb  = base.colors.primary  // skin-specific",
  "  A skin\u2019s tokens() method customizes these from base semantic tokens.",
  "",
  "Stage 4: Component Visuals (DesignLanguage)",
  "\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500",
  "skin.buttonVisuals(Filled, Medium, Enabled, snapshot{pressed=true})",
  "  \u2192 ButtonVisuals(",
  "      backgroundArgb = tokens.primaryContainer,  // from Stage 2",
  "      scaleX = tokens.pressScale,               // from Stage 3",
  "      shadowOffsetY = 1f,                        // skin-specific for pressed",
  "  )",
  "  This is the final visual truth consumed by the renderer.",
]));
ch.push(p("Each stage adds specificity. Stage 1 is universal (\"here are 32 colors\"). Stage 4 is maximally specific (\"this exact button, in this exact interaction state, should be this exact shade with this exact shadow offset\"). The pipeline never skips stages\u2014a skin can only reference semantic tokens, not raw palette values."));

ch.push(h2("3.5 Why Snapshot Over Sealed State"));
ch.push(p("This is the most critical design decision in the interaction layer. Here is the full reasoning:"));
ch.push(h4("The sealed-state problem"));
ch.push(p("A traditional model uses sealed class InteractionState { Idle, Hovered, Pressed, Focused, Disabled }. This forces a priority ranking: if the user hovers AND focuses, which state wins? Material Design answers: Focused > Hovered > Idle. But this loses information. A skin that wants to show BOTH a hover glow AND a focus ring cannot\u2014the state says only \u201CFocused.\u201D"));
ch.push(h4("The snapshot solution"));
ch.push(p("InteractionSnapshot reports all channels simultaneously: { focused=true, hovered=true, pressed=false }. The skin decides what to do with concurrent states. NeoBrutalism might ignore hover when focused. Glass might composite both effects. The interaction layer reports truth; the skin layer applies policy."));
ch.push(h4("Consequences"));
ch.push(bul("Skins have strictly more information to work with\u2014they can always ignore channels they don\u2019t care about."));
ch.push(bul("Accessibility is automatic: screen reader focus produces focused=true, which skins handle explicitly."));
ch.push(bul("New channels (e.g., force touch, stylus tilt) can be added to the snapshot without changing any existing skin\u2014they just get new optional fields with defaults."));
ch.push(bul("Testing is easier: construct a snapshot with exact desired state, pass to skin, assert visual output."));

ch.push(h2("3.6 Why Skin-as-Data Over Skin-as-Composable"));
ch.push(p("An alternative architecture would have skins return composables: skin.Button(onClick, label). This is what Radix + Tailwind does (headless + style layer). Tactile rejects this approach:"));
ch.push(tbl(["Aspect","Skin-as-Composable","Skin-as-Data (Tactile)"],[
  ["Testing","Requires Compose test harness to verify visual output","Pure unit tests: assertEquals(expected, skin.buttonVisuals(...))"],
  ["Cross-renderer","Skin is tied to Compose. Need separate CSS skin.","Same skin produces data consumed by Compose OR CSS renderer"],
  ["Serialization","Composables aren\u2019t serializable","Visual specs are data classes\u2014JSON, FlexBuffer, logging trivial"],
  ["Preview","Need @Preview per skin per state","Generate visual spec matrix without running Compose"],
  ["AI integration","LLM can\u2019t reason about composable output","LLM can read/write visual spec JSON\u2014enables generative theming"],
  ["Composition","Skin controls layout, limiting component flexibility","Component controls layout; skin controls only appearance"],
],[2000,3700,3660]));
ch.push(p("The tradeoff: skins cannot fundamentally change layout (e.g., a skin can\u2019t make a button render as a chip). This is intentional\u2014layout changes are structural, not cosmetic. If you need different structure, that\u2019s a different component."));

ch.push(h2("3.7 Why Extend ComposeContainer, Not Create TactileContainer"));
ch.push(p("The reaktor-graph rendering pipeline already solves recursive graph rendering:"));
ch.push(...code([
  "GraphContent(graph) \u2192",
  "  observes backStack.entries",
  "  gets top entry\u2019s attached node",
  "  if ComposeContainer: node.Content { childGraph, focused -> GraphContent(...) }",
  "  if ComposeContent:   node.Content()",
]));
ch.push(p("This recursion handles any depth of nested containers: BottomNavigationContainer contains Graphs, which contain ComposeContent screens, which might contain nested Graph fragments. A hypothetical TactileContainer would need to replicate or intercept this entire pipeline."));
ch.push(p("Instead, AdaptiveNavigationContainer simply implements ComposeContainer and provides its own Content() that delegates to the same renderer lambda. From GraphContent\u2019s perspective, it\u2019s just another container. This means:"));
ch.push(bul("Existing BestBuds screens work inside adaptive containers without modification"));
ch.push(bul("Mixed container types are fine: AdaptiveNavigationContainer can contain a TabbedContainer"));
ch.push(bul("Testing uses the same graph wiring patterns"));
ch.push(bul("The rendering pipeline is one code path, not two"));

ch.push(h2("3.8 Concurrency Model"));
ch.push(p("Understanding which operations happen on which threads:"));
ch.push(tbl(["Operation","Thread/Dispatcher","Why"],[
  ["InteractionController.onPointerDown()","Main (Compose UI thread)","Pointer events arrive on main thread; StateFlow update is instant"],
  ["pressDurationJob (16ms tick)","Dispatchers.Default via coroutineScope","Avoids blocking main thread with timer; StateFlow update triggers recomposition on main"],
  ["DesignLanguage.buttonVisuals()","Main (during composition)","Pure function called during Compose recomposition; must be fast (<1ms)"],
  ["SpringSimulation.step()","Compose animation thread","Compose Animatable runs on animation dispatcher; never blocks main"],
  ["TactileField.step()","LaunchedEffect coroutine (Main)","Updates impulse list; consumers observe via StateFlow"],
  ["HapticEngine.play()","Main","Android Vibrator / iOS CHHapticEngine require main thread"],
  ["Shader uniform upload","Render thread (Skia)","graphicsLayer modifier marshals uniforms to GPU pipeline"],
  ["React reconciler (future)","reaktor-ffi JS thread","Hermes runs on a dedicated thread; commits to Kotlin via JSI"],
],[3000,2800,3560]));

ch.push(h2("3.9 Error Boundaries & Graceful Degradation"));
ch.push(p("The system degrades gracefully when parts are unavailable:"));
ch.push(tbl(["Missing Part","Behavior"],[
  ["No TactileField provided","Components render normally; no cross-element effects. InteractionController skips field.emit()."],
  ["No HapticEngine available","Controller checks isAvailable(). If false, haptics silently skipped. Visual feedback unaffected."],
  ["reducedMotion = true","Springs replaced with instant transitions (spring stiffness = Spring.StiffnessHigh). Field propagation disabled."],
  ["No skin provided (LocalDesignLanguage missing)","Falls back to MaterialSkin (staticCompositionLocalOf default)."],
  ["Shader compilation fails","Compose fallback: solid color background instead of FBM shader. No crash."],
  ["React FFI bridge disconnected","Only affects reconciler path. Pure Compose components continue working."],
],[3000,6360]));
pb();

// ═══════════════════════════════════════════════════════════════════════════════
// 4. INTERACTION STATE MACHINES
// ═══════════════════════════════════════════════════════════════════════════════
ch.push(h1("4. Interaction State Machines"));
ch.push(sp(80));
ch.push(p("The interaction layer captures what the user is doing to an element\u2014not as a single sealed state, but as a concurrent snapshot of all active channels. This section covers the complete implementation."));

ch.push(h2("4.1 InteractionSnapshot"));
ch.push(...code([
  "data class InteractionSnapshot(",
  "    val focused: Boolean = false,       // keyboard/accessibility focus",
  "    val hovered: Boolean = false,       // pointer hover (desktop/web)",
  "    val pressed: Boolean = false,       // finger/mouse/stylus down",
  "    val dragging: Boolean = false,      // pointer moved while pressed",
  "    val enabled: Boolean = true,        // can receive interaction",
  "    val pointerPosition: Offset? = null,",
  "    val pressOrigin: Offset? = null,",
  "    val velocity: Velocity? = null,",
  "    val pressDurationMs: Long = 0,",
  "    val pressCount: Int = 0,",
  ") {",
  "    val isIdle get() = !focused && !hovered && !pressed && !dragging",
  "    val pressProgress: Float",
  "        get() = (pressDurationMs / LONG_PRESS_THRESHOLD_MS.toFloat()).coerceIn(0f, 1f)",
  "    val isLongPress get() = pressDurationMs >= LONG_PRESS_THRESHOLD_MS",
  "    val isDoubleTap get() = pressCount >= 2",
  "",
  "    companion object {",
  "        const val LONG_PRESS_THRESHOLD_MS = 500L",
  "        const val DOUBLE_TAP_WINDOW_MS = 300L",
  "        const val DRAG_THRESHOLD_DP = 8f",
  "    }",
  "}",
]));

ch.push(h2("4.2 InteractionController"));
ch.push(p("The controller manages the state machine. It runs entirely in Kotlin\u2014no JS involvement:"));
ch.push(...code([
  "class InteractionController(",
  "    private val field: TactileField? = null,",
  "    private val material: MaterialPhysics = Materials.Rubber,",
  "    private val coroutineScope: CoroutineScope",
  ") {",
  "    private val _snapshot = MutableStateFlow(InteractionSnapshot())",
  "    val snapshot: StateFlow<InteractionSnapshot> = _snapshot.asStateFlow()",
  "    private var pressStartTime = 0L",
  "    private var pressDurationJob: Job? = null",
  "    private var lastTapTime = 0L",
  "    private var tapCount = 0",
  "",
  "    fun onPointerDown(position: Offset) {",
  "        val now = currentTimeMs()",
  "        tapCount = if (now - lastTapTime < InteractionSnapshot.DOUBLE_TAP_WINDOW_MS)",
  "            tapCount + 1 else 1",
  "        lastTapTime = now; pressStartTime = now",
  "        _snapshot.update { it.copy(",
  "            pressed = true, pressOrigin = position, pointerPosition = position,",
  "            pressCount = tapCount, pressDurationMs = 0",
  "        )}",
  "        field?.emit(Impulse(position, material.mass * DEFAULT_PRESS_VELOCITY,",
  "            material, now))",
  "        pressDurationJob = coroutineScope.launch {",
  "            while (isActive) {",
  "                delay(16)",
  "                _snapshot.update { it.copy(pressDurationMs = currentTimeMs() - pressStartTime) }",
  "            }",
  "        }",
  "    }",
  "",
  "    fun onPointerUp() {",
  "        pressDurationJob?.cancel()",
  "        _snapshot.update { it.copy(pressed = false, dragging = false,",
  "            velocity = computeVelocity(), pressDurationMs = currentTimeMs() - pressStartTime) }",
  "    }",
  "",
  "    fun onPointerMove(position: Offset) {",
  "        val origin = _snapshot.value.pressOrigin ?: return",
  "        _snapshot.update { it.copy(pointerPosition = position,",
  "            dragging = it.pressed && (position - origin).getDistance() > DRAG_THRESHOLD_DP) }",
  "    }",
  "",
  "    fun onHoverEnter() { _snapshot.update { it.copy(hovered = true) } }",
  "    fun onHoverExit()  { _snapshot.update { it.copy(hovered = false) } }",
  "    fun onFocusGain()  { _snapshot.update { it.copy(focused = true) } }",
  "    fun onFocusLose()  { _snapshot.update { it.copy(focused = false) } }",
  "    fun setEnabled(e: Boolean) { _snapshot.update { it.copy(enabled = e) } }",
  "    fun reset() { pressDurationJob?.cancel(); _snapshot.value = InteractionSnapshot() }",
  "}",
]));

ch.push(h2("4.3 Modifier.tactileInteraction"));
ch.push(...code([
  "fun Modifier.tactileInteraction(",
  "    controller: InteractionController,",
  "    onClick: (() -> Unit)? = null,",
  "    onLongPress: (() -> Unit)? = null,",
  "): Modifier = this",
  "    .pointerInput(controller) {",
  "        detectTapGestures(onPress = { offset ->",
  "            controller.onPointerDown(offset)",
  "            val released = tryAwaitRelease()",
  "            controller.onPointerUp()",
  "            if (released && !controller.snapshot.value.dragging) {",
  "                if (controller.snapshot.value.isLongPress) onLongPress?.invoke()",
  "                else onClick?.invoke()",
  "            }",
  "        })",
  "    }",
  "    .pointerInput(controller) {",
  "        detectDragGestures { change, _ -> controller.onPointerMove(change.position) }",
  "    }",
  "    .onPointerEvent(PointerEventType.Enter) { controller.onHoverEnter() }",
  "    .onPointerEvent(PointerEventType.Exit) { controller.onHoverExit() }",
  "    .onFocusChanged { if (it.isFocused) controller.onFocusGain() else controller.onFocusLose() }",
  "    .focusable()",
]));

ch.push(h2("4.4 rememberInteractionController"));
ch.push(...code([
  "@Composable",
  "fun rememberInteractionController(",
  "    field: TactileField? = LocalTactileField.current,",
  "    material: MaterialPhysics = LocalMaterial.current,",
  "): InteractionController {",
  "    val scope = rememberCoroutineScope()",
  "    return remember(field, material) { InteractionController(field, material, scope) }",
  "}",
]));

ch.push(h2("4.5 Event Model: What Enters the State Machine"));
ch.push(p("The state machine should never receive raw platform events directly from the component body. Raw pointer, keyboard, focus, accessibility, and lifecycle events are normalized into a small renderer-neutral event vocabulary. This makes the same component behavior testable in common code and reusable from Compose, RSD, and the future host-tree renderer."));
ch.push(tbl(["Event","Produced By","Updates","Notes"],[
  ["Create(componentId, bounds)","Component first composition or host node creation","createdAt, bounds, enabled default","Used for diagnostics, field placement, and testing; not a visible state."],
  ["BoundsChanged(bounds)","onGloballyPositioned / layout callback","bounds, center, size","Required for TactileField displacement and pane-aware interactions."],
  ["PointerEnter(pointerType, position)","Mouse/stylus hover enter","hovered=true, pointerPosition","Ignored on touch-only platforms unless stylus hover is available."],
  ["PointerExit(pointerType)","Mouse/stylus hover exit","hovered=false, pointerPosition=null","Does not clear focus."],
  ["PointerDown(pointerId, position, pressure)","Touch/mouse/stylus down","pressed=true, pressOrigin, pressCount, pressure","Emits an optional field impulse."],
  ["PointerMove(pointerId, position, pressure)","Pointer movement","pointerPosition, dragging, velocity","Drag starts only after slop threshold is crossed."],
  ["PointerUp(pointerId, position)","Pointer release","pressed=false, dragging=false, velocity","May emit Click, LongPress, Fling, or Cancel outcome."],
  ["PointerCancel(pointerId, reason)","Gesture cancellation, scroll parent capture, route transition","pressed=false, dragging=false","Never dispatches onClick."],
  ["FocusGain(source)","Keyboard, accessibility, programmatic focus","focused=true, focusSource","Focus source lets skins distinguish keyboard ring from touch focus if desired."],
  ["FocusLose(source)","Blur, route transition","focused=false","Does not clear hover if mouse remains inside bounds."],
  ["KeyDown(key, modifiers)","Keyboard input","pressed or activation outcome","Space/Enter activates button-like controls; arrow keys move sliders/tabs."],
  ["KeyUp(key, modifiers)","Keyboard release","pressed=false where applicable","Completes keyboard activation if not cancelled."],
  ["EnableChanged(enabled)","Component props or host tree update","enabled flag; if disabled, clears active channels","Disabled components do not emit haptics or field impulses."],
  ["Dispose","Composition leaves tree or host node removed","terminal cleanup","Cancels jobs and unregisters field listeners."],
],[2300,2600,2500,1960]));

ch.push(h2("4.6 InteractionSnapshot v1 Contract"));
ch.push(p("The first stable InteractionSnapshot should be deliberately larger than the minimal button example. It is the single vocabulary used by skins, haptics, field propagation, and testing. Fields may be ignored by simple components, but they should exist so advanced components do not need side channels."));
ch.push(...code([
  "data class InteractionSnapshot(",
  "    val lifecycle: Lifecycle = Lifecycle.Created,",
  "    val enabled: Boolean = true,",
  "    val focused: Boolean = false,",
  "    val focusSource: FocusSource? = null,",
  "    val hovered: Boolean = false,",
  "    val pressed: Boolean = false,",
  "    val dragging: Boolean = false,",
  "    val selected: Boolean = false,",
  "    val checked: Boolean = false,",
  "    val expanded: Boolean = false,",
  "    val valid: Boolean = true,",
  "    val busy: Boolean = false,",
  "    val pointerType: PointerType? = null,",
  "    val pointerPosition: Offset? = null,",
  "    val pressOrigin: Offset? = null,",
  "    val bounds: Rect? = null,",
  "    val pressure: Float = 1f,",
  "    val velocity: Velocity = Velocity.Zero,",
  "    val dragDelta: Offset = Offset.Zero,",
  "    val pressDurationMs: Long = 0,",
  "    val pressCount: Int = 0,",
  "    val interactionGeneration: Long = 0,",
  ")",
  "",
  "enum class Lifecycle { Created, Mounted, Active, Disposed }",
  "enum class PointerType { Touch, Mouse, Stylus, Keyboard, Accessibility }",
  "enum class FocusSource { Keyboard, Pointer, Accessibility, Programmatic }",
]));
ch.push(p("interactionGeneration increments whenever a visible interaction channel changes. It is useful for components that need to restart a spring or haptic sequence only on meaningful interaction changes, not on every pointer coordinate update."));

ch.push(h2("4.7 Component-Specific Machines"));
ch.push(p("A shared snapshot vocabulary does not mean every component has identical behavior. Each primitive has a policy layer that interprets events and emits semantic outcomes. The generic controller tracks channels; the component machine decides whether a channel is meaningful."));
ch.push(tbl(["Component","Extra State","Accepted Outcomes","Special Rules"],[
  ["Button / IconButton","pressed, focused, hovered, busy","Click, LongPress, Cancel","Enter/Space activate. If busy=true, keep focus but ignore click unless explicitly configured."],
  ["Toggle / Checkbox / Radio","checked, pressed, focused","CheckedChange, Cancel","Toggle changes on release, not down. Radio cannot uncheck itself unless group policy allows nullable selection."],
  ["TextField / SearchField","focused, valid, dirty, selection, composing","ValueChange, Submit, Clear, FocusChange","Pointer down focuses but does not emit field impulse by default; IME composition is separate from final text."],
  ["Slider / RangeSlider","dragging, value, activeThumb, velocity","ValueChange, ValueCommit, Cancel","Arrow keys step value; drag uses material friction for thumb resistance."],
  ["Menu / Select","expanded, focusedIndex, selectedIndex","Open, Close, Select, Typeahead","Escape closes. Outside click cancels. Typeahead is a state machine, not ad hoc string matching."],
  ["Tabs","selectedIndex, focusedIndex","SelectTab, MoveFocus","Arrow keys move focus; Enter/Space commits selection. On compact layouts tabs may collapse into segmented controls."],
  ["Dialog / Sheet","expanded, modalFocus, dismissReason","Open, Dismiss, Confirm, Cancel","Focus trap is part of the interaction machine, not a visual detail."],
  ["Card / ListItem","pressed, hovered, selected","Click, LongPress, SecondaryAction","Cards can be passive surfaces or interactive containers; interactivity must be explicit."],
  ["DragHandle / ReorderableItem","dragging, dragDelta, dropTarget","DragStart, DragMove, Drop, Cancel","Uses slop threshold and velocity; emits field impulses on drop/impact."],
],[1800,2200,2100,3260]));

ch.push(h2("4.8 Accessibility Is Part of the Machine"));
ch.push(p("Accessibility cannot be bolted on at the end. Keyboard focus, screen reader activation, semantic roles, state descriptions, and reduced-motion preferences all affect interaction snapshots and component outcomes."));
ch.push(bul([B("Roles are explicit: "), N("TButton sets Role.Button, TSwitch sets Role.Switch, TTab sets Role.Tab, and custom host nodes must declare their role in the registry.")]));
ch.push(bul([B("Keyboard activation matches platform norms: "), N("Buttons activate on Enter/Space, tabs move with arrows, sliders step with arrows/PageUp/PageDown, menus close with Escape.")]));
ch.push(bul([B("Reduced motion changes behavior, not just visuals: "), N("field propagation, shader distortion, and high-amplitude springs are disabled or clamped when reducedMotion=true.")]));
ch.push(bul([B("Focus is not hover: "), N("A desktop user can be hovered and keyboard-focused at the same time. Skins receive both facts and choose the visual composition.")]));
ch.push(bul([B("State descriptions come from semantic state: "), N("checked, selected, expanded, busy, invalid, and disabled must be readable by assistive technology in both Compose semantics and RSD ARIA.")]));

ch.push(h2("4.9 Testing Interaction Machines"));
ch.push(p("Every primitive state machine should ship with table-driven tests. These tests do not render Compose. They feed normalized events into the machine and assert snapshots plus outcomes."));
ch.push(...code([
  "class ButtonMachineTest {",
  "    @Test fun `click is emitted only after released inside bounds`() {",
  "        val machine = ButtonInteractionMachine(bounds = Rect(0f, 0f, 100f, 48f))",
  "        machine.accept(PointerDown(id = 1, position = Offset(10f, 10f)))",
  "        machine.accept(PointerMove(id = 1, position = Offset(20f, 10f)))",
  "        val outcome = machine.accept(PointerUp(id = 1, position = Offset(20f, 10f)))",
  "        assertEquals(ButtonOutcome.Click, outcome)",
  "        assertFalse(machine.snapshot.value.pressed)",
  "    }",
  "}",
]));
ch.push(box("Implementation Rule","Do not implement button, slider, text field, menu, and sheet behavior as unrelated pointerInput blocks. Build reusable component machines, then adapt Compose/RSD/native events into the shared event vocabulary. That is the difference between a real UI runtime and a collection of animated widgets."));
pb();

// ═══════════════════════════════════════════════════════════════════════════════
// 5. DESIGN LANGUAGE SYSTEM
// ═══════════════════════════════════════════════════════════════════════════════
ch.push(h1("5. Design Language System"));
ch.push(sp(80));
ch.push(p("A design language (skin) is a complete visual personality. It receives semantic tokens and interaction snapshots, and returns pure data\u2014no rendering logic, no platform types. This section covers the interface, the visual specs, and concrete skin implementations."));

ch.push(h2("5.1 The DesignLanguage Interface"));
ch.push(p("Every skin implements this interface. Note: the interface uses reaktor-ui\u2019s existing enums (ComponentVariant, ComponentSize, ComponentState) from ComponentSpec.kt\u2014no new enum types needed:"));
ch.push(...code([
  "interface DesignLanguage {",
  "    val name: String",
  "    val description: String",
  "    val defaultMaterial: MaterialPhysics",
  "",
  "    fun tokens(base: DesignTokens): TactileTokens",
  "",
  "    // Atom visuals",
  "    fun buttonVisuals(variant: ComponentVariant, size: ComponentSize,",
  "        state: ComponentState, snap: InteractionSnapshot): ButtonVisuals",
  "    fun cardVisuals(variant: ComponentVariant,",
  "        state: ComponentState, snap: InteractionSnapshot): CardVisuals",
  "    fun surfaceVisuals(elevation: Float, snap: InteractionSnapshot): SurfaceVisuals",
  "    fun inputVisuals(variant: ComponentVariant,",
  "        state: ComponentState, snap: InteractionSnapshot): InputVisuals",
  "    fun textVisuals(role: TextRole, size: ComponentSize): TextVisuals",
  "    fun toggleVisuals(checked: Boolean,",
  "        state: ComponentState, snap: InteractionSnapshot): ToggleVisuals",
  "    fun chipVisuals(variant: ChipVariant, selected: Boolean,",
  "        state: ComponentState, snap: InteractionSnapshot): ChipVisuals",
  "    fun iconVisuals(size: ComponentSize): IconVisuals",
  "    fun progressVisuals(type: ProgressType, state: ComponentState): ProgressVisuals",
  "    fun dividerVisuals(): DividerVisuals",
  "    fun avatarVisuals(size: ComponentSize): AvatarVisuals",
  "    fun badgeVisuals(color: BadgeColor): BadgeVisuals",
  "",
  "    // Molecule visuals",
  "    fun listItemVisuals(state: ComponentState, snap: InteractionSnapshot): ListItemVisuals",
  "    fun appBarVisuals(variant: AppBarVariant): AppBarVisuals",
  "    fun searchBarVisuals(state: ComponentState, snap: InteractionSnapshot): SearchBarVisuals",
  "",
  "    // Layout chrome visuals",
  "    fun navigationBarVisuals(): NavigationBarVisuals",
  "    fun navigationRailVisuals(): NavigationRailVisuals",
  "    fun navigationDrawerVisuals(): NavigationDrawerVisuals",
  "    fun sheetVisuals(variant: SheetVariant): SheetVisuals",
  "    fun dialogVisuals(): DialogVisuals",
  "}",
]));

ch.push(h2("5.2 Visual Spec Data Classes"));
ch.push(p("Key visual specs. All use primitives only (Long/Float/Int/Boolean)\u2014see Invariant 4:"));

ch.push(h3("5.2.1 ButtonVisuals"));
ch.push(...code([
  "data class ButtonVisuals(",
  "    val backgroundArgb: Long, val contentArgb: Long,",
  "    val borderWidth: Float, val borderArgb: Long, val cornerRadius: Float,",
  "    val elevation: Float, val shadowArgb: Long,",
  "    val shadowOffsetX: Float, val shadowOffsetY: Float,",
  "    val scaleX: Float, val scaleY: Float, val translationY: Float,",
  "    val fontSizeSp: Float, val fontWeight: Int,",
  "    val focusRingWidth: Float, val focusRingArgb: Long, val focusRingOffset: Float,",
  "    val opacity: Float,",
  ")",
]));
ch.push(h3("5.2.2 CardVisuals"));
ch.push(...code([
  "data class CardVisuals(",
  "    val backgroundArgb: Long, val contentArgb: Long,",
  "    val borderWidth: Float, val borderArgb: Long, val cornerRadius: Float,",
  "    val elevation: Float, val shadowArgb: Long,",
  "    val shadowOffsetX: Float, val shadowOffsetY: Float,",
  "    val translationY: Float, val scaleX: Float, val scaleY: Float,",
  "    val blurRadius: Float, val backgroundOpacity: Float,",
  ")",
]));
ch.push(h3("5.2.3 Other Visual Specs (Summary)"));
ch.push(tbl(["Spec","Key Properties","Used By"],[
  ["SurfaceVisuals","backgroundArgb, elevation, blurRadius, tintArgb","TSurface, backgrounds"],
  ["InputVisuals","backgroundArgb, borderArgb, focusBorderArgb, errorBorderArgb, cornerRadius, labelArgb, textArgb, cursorArgb","TInput"],
  ["TextVisuals","colorArgb, fontSizeSp, fontWeight, letterSpacingSp, lineHeightSp","TText"],
  ["ToggleVisuals","trackArgb, thumbArgb, thumbScale, borderArgb","TSwitch, TCheckbox, TRadio"],
  ["ChipVisuals","backgroundArgb, borderArgb, borderWidth, cornerRadius, contentArgb, selectedBackgroundArgb","TChip variants"],
  ["ListItemVisuals","backgroundArgb, pressedBackgroundArgb, dividerArgb","TListItem"],
  ["NavigationBarVisuals","backgroundArgb, selectedArgb, unselectedArgb, indicatorArgb, elevation","Adaptive nav bar"],
  ["NavigationRailVisuals","backgroundArgb, selectedArgb, unselectedArgb, width","Adaptive nav rail"],
  ["NavigationDrawerVisuals","backgroundArgb, selectedArgb, unselectedArgb, width, elevation","Adaptive nav drawer"],
  ["DialogVisuals","backgroundArgb, scrimArgb, cornerRadius, elevation","TDialog"],
  ["SheetVisuals","backgroundArgb, handleArgb, cornerRadius, elevation, scrimArgb","TBottomSheet"],
],[2000,4000,3360]));

ch.push(h2("5.3 Concrete Skin: NeoBrutalism"));
ch.push(p("Thick black borders, bold primary colors, sharp corners (0 radius), hard-offset shadows that collapse on press:"));
ch.push(...code([
  "object NeoBrutalismSkin : DesignLanguage {",
  "    override val name = \"NeoBrutalism\"",
  "    override val description = \"Bold colors, thick borders, hard shadows, sharp corners\"",
  "    override val defaultMaterial = Materials.Wood",
  "",
  "    override fun buttonVisuals(variant, size, state, snap): ButtonVisuals {",
  "        val bg = when (variant) {",
  "            Filled -> 0xFF_FFD700L; Tonal -> 0xFF_FFE082L",
  "            Outlined -> 0xFF_FFFFFFL; Text -> 0x00_000000L; Elevated -> 0xFF_FFFFFFL",
  "        }",
  "        val disabled = state == Disabled; val pressed = snap.pressed && !disabled",
  "        val shadowOffset = when { pressed -> 1f; snap.hovered -> 6f; else -> 4f }",
  "        return ButtonVisuals(",
  "            backgroundArgb = bg, contentArgb = 0xFF_000000L,",
  "            borderWidth = if (variant == Text) 0f else 3f, borderArgb = 0xFF_000000L,",
  "            cornerRadius = 0f, elevation = 0f, shadowArgb = 0xFF_000000L,",
  "            shadowOffsetX = shadowOffset, shadowOffsetY = shadowOffset,",
  "            scaleX = 1f, scaleY = 1f, translationY = if (pressed) 3f else 0f,",
  "            fontSizeSp = when(size){Small->14f; Medium->16f; Large->18f},",
  "            fontWeight = 700,",
  "            focusRingWidth = if (snap.focused) 3f else 0f, focusRingArgb = 0xFF_000000L,",
  "            focusRingOffset = 2f, opacity = if (disabled) 0.5f else 1f,",
  "        )",
  "    }",
  "    // ... card, input, toggle visuals follow same pattern",
  "}",
]));

ch.push(h2("5.4 Concrete Skin: Glass"));
ch.push(p("Translucent surfaces with backdrop blur, subtle borders, rounded corners. Press increases blur:"));
ch.push(...code([
  "object GlassSkin : DesignLanguage {",
  "    override val name = \"Glass\"",
  "    override val defaultMaterial = Materials.Rubber",
  "    override fun buttonVisuals(variant, size, state, snap) = ButtonVisuals(",
  "        backgroundArgb = 0x33_FFFFFFL, contentArgb = 0xFF_FFFFFFL,",
  "        borderWidth = 0.5f, borderArgb = 0x66_FFFFFFL, cornerRadius = 16f,",
  "        elevation = 0f, shadowArgb = 0x00_000000L, shadowOffsetX = 0f, shadowOffsetY = 0f,",
  "        scaleX = if (snap.pressed) 0.97f else 1f, scaleY = if (snap.pressed) 0.97f else 1f,",
  "        translationY = 0f, fontSizeSp = 16f, fontWeight = 500,",
  "        focusRingWidth = if (snap.focused) 2f else 0f, focusRingArgb = 0x88_FFFFFFL,",
  "        focusRingOffset = 2f, opacity = if (state == Disabled) 0.3f else 1f,",
  "    )",
  "}",
]));

ch.push(h2("5.5 Concrete Skin: Material 3 (Default)"));
ch.push(p("Maps directly to existing reaktor-ui color scheme for backward compatibility:"));
ch.push(...code([
  "object MaterialSkin : DesignLanguage {",
  "    override val name = \"Material\"",
  "    override val defaultMaterial = Materials.Rubber",
  "    override fun tokens(base) = TactileTokens(",
  "        base = base, defaultMaterial = Materials.Rubber,",
  "        pressScale = 0.95f, hoverElevationBoost = 2f,",
  "        focusRingWidth = 2f, focusRingArgb = base.colors.primary,",
  "        impactPropagation = true, impactRadius = 160f,",
  "    )",
  "    override fun buttonVisuals(variant, size, state, snap) = ButtonVisuals(",
  "        // Uses base.colors.primary/secondary/surface per variant",
  "        cornerRadius = 12f, // Material 3 standard",
  "        scaleX = if (snap.pressed) 0.95f else 1f,",
  "        // ... elevation-based shadows, 38% opacity for disabled",
  "    )",
  "}",
]));

ch.push(h2("5.6 Skin Comparison Matrix"));
ch.push(tbl(["Property","Material","NeoBrutalism","Glass","NeoMorphism","NeoRetro"],[
  ["Border","0\u20131dp themed","2\u20133dp black","0.5dp white","None","1\u20132dp vintage"],
  ["Corners","12dp","0dp (sharp)","16\u201320dp","20dp (soft)","8dp"],
  ["Shadow","Elevation-based","Hard offset 4\u20135dp","None (blur)","Dual soft (light+dark)","Glow/neon"],
  ["Press effect","Scale 0.95","Translate into shadow","Scale 0.97 + blur\u2191","Shadow inversion","Color pulse"],
  ["Default material","Rubber","Wood","Rubber","Feather","Steel"],
  ["Disabled state","38% opacity","50% opacity","30% opacity","40% opacity","Desaturate"],
],[1600,1300,1600,1500,1600,1760]));

ch.push(h2("5.7 Runtime Skin Switching"));
ch.push(...code([
  "val LocalDesignLanguage = staticCompositionLocalOf<DesignLanguage> { MaterialSkin }",
  "",
  "@Composable",
  "fun TactileTheme(",
  "    skin: DesignLanguage = MaterialSkin,",
  "    baseTokens: DesignTokens = Tokens.current,",
  "    field: TactileField = remember { TactileField() },",
  "    content: @Composable () -> Unit",
  ") {",
  "    val tactileTokens = remember(skin, baseTokens) { skin.tokens(baseTokens) }",
  "    CompositionLocalProvider(",
  "        LocalDesignLanguage provides skin,",
  "        LocalTactileTokens provides tactileTokens,",
  "        LocalTactileField provides field,",
  "        LocalMaterial provides skin.defaultMaterial,",
  "    ) { ReaktorTheme(tokens = tactileTokens.base) { content() } }",
  "}",
]));

ch.push(h2("5.8 DesignLanguage Implementation Contract"));
ch.push(p("A skin is allowed to decide appearance, but it must not decide structure, behavior, navigation, data loading, or business logic. This boundary must stay strict or the design system will become impossible to reason about."));
ch.push(tbl(["Allowed In DesignLanguage","Forbidden In DesignLanguage","Reason"],[
  ["Colors, typography, spacing multipliers, radii, borders, shadows, blur, opacity","Network calls, database reads, service calls","A skin must be pure and deterministic."],
  ["State-to-visual mapping, e.g. pressed -> translateY=3","Calling onClick, dispatching graph actions","Interaction machines own outcomes; graph/actions own effects."],
  ["Material defaults: mass, stiffness, damping, friction","Launching coroutines or timers","Physics data is fine; running physics belongs in runtime/controller."],
  ["Component visual specs for all component states","Rendering Composables or RSD html.* nodes","Renderers consume specs; skins do not render."],
  ["Accessibility visual hints such as focus ring thickness/color","Changing semantics roles or labels","Semantics are component contracts, not styling decisions."],
  ["Reduced-motion fallback values","Reading platform settings directly","The runtime reads platform settings and passes them through tokens/snapshot."],
],[3000,3000,3360]));
ch.push(p("A good test for this boundary: a DesignLanguage should be usable in a JVM unit test with no Compose runtime, no Android SDK, no browser, no Hermes, and no app graph."));

ch.push(h2("5.9 LiquidGlass Skin"));
ch.push(p("LiquidGlass is treated as a first-class design language, not as an afterthought of Glass. Its visual identity is elastic translucency, specular highlights, refractive background distortion, and fluid surface response. It needs richer visual specs than plain Glass, but still uses primitives."));
ch.push(...code([
  "data class LiquidSurfaceVisuals(",
  "    val backgroundArgb: Long,",
  "    val contentArgb: Long,",
  "    val borderArgb: Long,",
  "    val borderWidth: Float,",
  "    val cornerRadius: Float,",
  "    val blurRadius: Float,",
  "    val refractionStrength: Float,",
  "    val highlightArgb: Long,",
  "    val highlightOpacity: Float,",
  "    val specularStrength: Float,",
  "    val scaleX: Float,",
  "    val scaleY: Float,",
  "    val displacementX: Float,",
  "    val displacementY: Float,",
  ")",
  "",
  "object LiquidGlassSkin : DesignLanguage {",
  "    override val name = \"LiquidGlass\"",
  "    override val defaultMaterial = Materials.Rubber.copy(",
  "        mass = 0.8f, stiffness = 220f, damping = 14f, friction = 0.08f",
  "    )",
  "    // ButtonVisuals remains compatible. Components that support advanced glass",
  "    // can additionally request LiquidSurfaceVisuals through optional extension APIs.",
  "}",
]));
ch.push(p("Important: LiquidGlass should degrade in three tiers. Tier 1 uses native shader/blur support and TactileField uniforms. Tier 2 uses Compose blur plus translucent overlays. Tier 3 uses static translucent colors and borders. The component API stays identical across tiers."));

ch.push(h2("5.10 Custom Skin Authoring Guide"));
ch.push(p("A product team should be able to create its own design language without learning the internals of every component. The skin authoring flow should be documented and enforced by compile-time defaults."));
ch.push(num("Start with an existing skin: MaterialSkin is the compatibility baseline; NeoBrutalismSkin is the easiest non-Material reference."));
ch.push(num("Implement tokens(base): derive a TactileTokens object from the app's DesignTokens. Do not use raw hard-coded colors unless the skin is intentionally brand-fixed."));
ch.push(num("Implement atom visuals first: button, text, surface, card, input, icon, toggle. These unblock the rest of the library."));
ch.push(num("Implement molecule visuals next: list item, search bar, app bar, nav item. These should mostly compose atom visual decisions."));
ch.push(num("Implement layout chrome visuals last: navigation bar, rail, drawer, sheet, dialog."));
ch.push(num("Run the visual matrix tests: every component x variant x state x interaction snapshot must produce a valid spec."));
ch.push(num("Preview the skin in the workbench: idle, hover, focus, press, disabled, error, selected, busy, reduced motion."));

ch.push(h2("5.11 Visual Spec Versioning"));
ch.push(p("Visual specs are part of the runtime ABI because the Compose renderer, RSD renderer, host-tree renderer, preview tools, and tests all consume them. Evolve them like public data contracts."));
ch.push(tbl(["Change Type","Rule","Example"],[
  ["Add optional field with default","Allowed in same version","ButtonVisuals adds focusRingOffset: Float = 0f"],
  ["Rename field","Breaking; create v2 spec or migration adapter","shadowY -> shadowOffsetY"],
  ["Change units","Breaking unless field name changes","cornerRadius from dp to px is not allowed silently"],
  ["Change semantic meaning","Breaking unless documented by version","elevation used as z-order instead of visual elevation"],
  ["Add new component visual method","Allowed if default implementation exists","fun segmentedControlVisuals(...) = defaultSegmentedControlVisuals(...)"],
],[1800,3600,3960]));

ch.push(h2("5.12 Skin Test Matrix"));
ch.push(p("Every official skin must pass a common test matrix. This prevents a skin from looking good only for the happy path and failing under keyboard focus, disabled state, error state, or reduced motion."));
ch.push(tbl(["Test Category","Assertions"],[
  ["Contrast","Text/content colors meet the configured contrast threshold against backgrounds for enabled states."],
  ["Disabled state","Opacity or color changes are visible, but semantics remain readable."],
  ["Focus state","Keyboard focus produces a visible ring/indicator independent of hover and press."],
  ["Pressed state","Pressed visuals differ from idle for interactive components unless reducedMotion disables motion."],
  ["Reduced motion","Scale, translation, field displacement, shader distortion, and haptic intensity are clamped."],
  ["Platform parity","Compose and RSD visual specs map to equivalent user-visible behavior within platform capability limits."],
  ["Snapshot safety","All visual methods return finite numbers, valid colors, non-negative sizes, and no invalid numeric values."],
],[2400,6960]));
pb();

// ═══════════════════════════════════════════════════════════════════════════════
// 6. ATOMIC DESIGN COMPONENT LIBRARY
// ═══════════════════════════════════════════════════════════════════════════════
ch.push(h1("6. Atomic Design: The Tactile Component Library"));
ch.push(sp(80));
ch.push(p("Every component follows the same six-step pattern (see Section 2.5): create controller \u2192 read skin \u2192 compute visuals \u2192 animate \u2192 render \u2192 wire gestures."));

ch.push(h2("6.1 TButton (Canonical Example)"));
ch.push(...code([
  "@Composable",
  "fun TButton(",
  "    onClick: () -> Unit, modifier: Modifier = Modifier,",
  "    label: String? = null, icon: ImageVector? = null,",
  "    variant: ComponentVariant = ComponentVariant.Filled,",
  "    size: ComponentSize = ComponentSize.Medium,",
  "    state: ComponentState = ComponentState.Enabled,",
  "    material: MaterialPhysics = LocalMaterial.current,",
  ") {",
  "    val controller = rememberInteractionController(material = material)",
  "    val snap by controller.snapshot.collectAsState()",
  "    val skin = LocalDesignLanguage.current",
  "    val visuals = skin.buttonVisuals(variant, size, state, snap)",
  "",
  "    val animScale by animateFloatAsState(visuals.scaleX,",
  "        SpringSimulation(material).toComposeSpringSpec())",
  "    val animTranslateY by animateFloatAsState(visuals.translationY,",
  "        SpringSimulation(material).toComposeSpringSpec())",
  "",
  "    Box(",
  "        modifier = modifier",
  "            .tactileInteraction(controller, onClick)",
  "            .graphicsLayer { scaleX = animScale; scaleY = animScale",
  "                translationY = animTranslateY.dp.toPx() }",
  "            .shadow(visuals.shadowOffsetY.dp, visuals.cornerRadius.dp.let { RoundedCornerShape(it) })",
  "            .clip(RoundedCornerShape(visuals.cornerRadius.dp))",
  "            .background(Color(visuals.backgroundArgb))",
  "            .alpha(visuals.opacity)",
  "            .height(when(size) { Small->32.dp; Medium->40.dp; Large->48.dp }),",
  "        contentAlignment = Alignment.Center",
  "    ) {",
  "        Row(verticalAlignment = Alignment.CenterVertically) {",
  "            icon?.let { Icon(it, null, tint = Color(visuals.contentArgb)) }",
  "            label?.let { Text(it, color = Color(visuals.contentArgb),",
  "                fontSize = visuals.fontSizeSp.sp, fontWeight = FontWeight(visuals.fontWeight)) }",
  "        }",
  "    }",
  "}",
]));

ch.push(h2("6.2 Full Atom Inventory"));
ch.push(tbl(["Atom","Composable","Skin Method","Key Interaction"],[
  ["Button","TButton + TButtonPrimary/Secondary/Outlined/Text","buttonVisuals()","Press scale/translate, haptic on tap"],
  ["Card","TCard + TElevatedCard/TOutlinedCard/TFilledCard","cardVisuals()","Press scale, hover lift, optional click"],
  ["Surface","TSurface","surfaceVisuals()","Hover elevation, field displacement"],
  ["Text","TText + TDisplayText/THeadline/TTitle/TBody/TLabel","textVisuals()","None (static visual)"],
  ["Input","TInput + TSearchInput/TPasswordInput/TEmailInput","inputVisuals()","Focus border animation, error state"],
  ["Icon","TIcon","iconVisuals()","None (tint from skin)"],
  ["Avatar","TAvatar + image/initials/icon variants","avatarVisuals()","None (size/color from skin)"],
  ["Badge","TBadge + TStatusDot","badgeVisuals()","None (color from skin)"],
  ["Chip","TAssistChip/TFilterChip/TInputChip/TSuggestionChip","chipVisuals()","Press + selected state"],
  ["Toggle","TSwitch/TCheckbox/TRadio","toggleVisuals()","Spring-animated thumb/check, haptic"],
  ["Progress","TCircularProgress/TLinearProgress","progressVisuals()","None (track/indicator from skin)"],
  ["Divider","TDivider","dividerVisuals()","None"],
],[1200,2200,1800,4160]));

ch.push(h2("6.3 Molecule & Organism Components"));
ch.push(tbl(["Level","Component","Composed Of","Interaction"],[
  ["Molecule","TListItem","TText + TIcon + TAvatar + TToggle","Pressed background, haptic tap"],
  ["Molecule","TSearchBar","TInput + TIcon","Focus animation, clear button"],
  ["Molecule","TAppBar","TText + TIconButton + TButton","None (container)"],
  ["Molecule","TSnackbar","TSurface + TText + TButton","Swipe dismiss, auto-dismiss timer"],
  ["Molecule","TTabRow","TText + animated indicator","Tab selection with spring indicator"],
  ["Molecule","TNavItem","TIcon + TText + TBadge","Selected state + indicator animation"],
  ["Organism","TForm","Column of TInputs + TButton","Validation state propagation"],
  ["Organism","TNavDrawerContent","Header + list of TNavItems","Selected item highlight"],
  ["Organism","TChatMessage","TAvatar + text bubble + timestamp","Long-press for reactions"],
  ["Organism","TEventCard","TCard + TAvatar + TText + TButton","Card press + action button"],
],[1000,1600,2800,3960]));

ch.push(h2("6.4 Templates & Pages"));
ch.push(p("Templates define layout structure. Pages are templates with content. Both use adaptive containers (Section 7):"));
ch.push(bul([B("ListDetailTemplate: "), N("Compact = list only with push navigation. Medium/Expanded = list + detail side-by-side via AdaptivePaneContainer.")]));
ch.push(bul([B("FeedTemplate: "), N("TAppBar + scrollable content + FAB. Responsive grid via responsiveColumns().")]));
ch.push(bul([B("AuthTemplate: "), N("Centered TCard with logo + TForm. Max-width constrained.")]));
ch.push(bul([B("DashboardTemplate: "), N("AdaptiveNavigationContainer + grid of TCards with responsive columns.")]));

ch.push(h2("6.5 Migration from reaktor-ui"));
ch.push(p("R-prefixed and T-prefixed components coexist. Migration is per-component, per-screen:"));
ch.push(tbl(["reaktor-ui","Tactile","What Changes"],[
  ["RButton(onClick, label)","TButton(onClick, label)","+ skin visuals, + spring physics, + interaction snapshot"],
  ["RCard(variant, content)","TCard(variant, content)","+ press/hover physics, + skin shadows/borders"],
  ["RInput(value, onChange)","TInput(value, onChange)","+ animated focus border, + skin colors"],
  ["Theme.ButtonPrimary()","TButton(variant = Filled)","Extension function pattern \u2192 composable with skin"],
],[2000,2000,5360]));

ch.push(h2("6.6 Atomic Design Package Structure"));
ch.push(p("The package structure should make Atomic Design visible in the codebase. A programmer should be able to navigate from primitive atoms to full page templates without guessing where components live."));
ch.push(...code([
  "reaktor-tactile/src/commonMain/kotlin/dev/shibasis/reaktor/tactile/",
  "  atoms/",
  "    Button.kt, Text.kt, Icon.kt, Card.kt, Surface.kt, Input.kt, Toggle.kt, Chip.kt",
  "  molecules/",
  "    ListItem.kt, SearchBar.kt, AppBar.kt, Snackbar.kt, TabRow.kt, NavItem.kt",
  "  organisms/",
  "    Form.kt, NavigationDrawerContent.kt, ChatMessage.kt, EventCard.kt, DataTable.kt",
  "  templates/",
  "    ListDetailTemplate.kt, FeedTemplate.kt, AuthTemplate.kt, DashboardTemplate.kt",
  "  pages/",
  "    PageScaffold.kt, RoutePage.kt",
  "  interaction/",
  "    machines/ButtonMachine.kt, SliderMachine.kt, TextFieldMachine.kt, MenuMachine.kt",
  "  skin/",
  "    DesignLanguage.kt, visuals/*.kt, material/, neobrutalism/, liquidglass/",
  "  adaptive/",
  "    AdaptiveNavigationContainer.kt, AdaptivePaneContainer.kt, GraphRouteContent.kt",
]));
ch.push(p("Keep machine code out of atoms where possible. Atoms use machines; machines should be unit-testable without Compose."));

ch.push(h2("6.7 Unstyled Primitives vs Finished Components"));
ch.push(p("Tactile should expose both unstyled primitives and finished components. This gives expert users escape hatches without forcing normal app developers to assemble every piece manually."));
ch.push(tbl(["Layer","Public API","Who Uses It","Responsibility"],[
  ["Unstyled primitive","TactileButtonPrimitive(controller, semantics, content)","Design-system authors and advanced app teams","Owns semantics and interaction wiring, but does not apply a skin."],
  ["Finished atom","TButton(...)","Most app developers","Combines primitive + DesignLanguage + spring animation + default content layout."],
  ["Product wrapper","BestBudsPrimaryButton(...)","Product teams","Applies product defaults such as variant, size, icon policy, analytics tags."],
  ["Host node renderer","ComposeButton(node)","React reconciler path","Maps host-tree props/handlers into the finished atom or primitive."],
],[2100,2300,2460,2500]));
ch.push(...code([
  "@Composable",
  "fun TactileButtonPrimitive(",
  "    controller: InteractionController,",
  "    enabled: Boolean,",
  "    onClick: () -> Unit,",
  "    modifier: Modifier = Modifier,",
  "    content: @Composable (InteractionSnapshot) -> Unit",
  ") {",
  "    val snap by controller.snapshot.collectAsState()",
  "    Box(",
  "        modifier = modifier",
  "            .semantics { role = Role.Button; disabled(!enabled) }",
  "            .tactileInteraction(controller, onClick = if (enabled) onClick else null)",
  "    ) { content(snap) }",
  "}",
  "",
  "@Composable",
  "fun TButton(...){",
  "    val controller = rememberInteractionController()",
  "    TactileButtonPrimitive(controller, enabled, onClick, modifier) { snap ->",
  "        val visuals = LocalDesignLanguage.current.buttonVisuals(variant, size, state, snap)",
  "        // Apply visuals and render content.",
  "    }",
  "}",
]));

ch.push(h2("6.8 Component Authoring Checklist"));
ch.push(p("Use this checklist whenever a new component is added. It is intentionally strict because components become public API across Compose, RSD, host-tree rendering, server-driven React, and AI tooling."));
ch.push(num("Define the user-facing API: props, defaults, variants, size options, slot structure, and event callbacks."));
ch.push(num("Define semantics: role, state description, disabled behavior, focusability, keyboard behavior, screen-reader labels."));
ch.push(num("Select or create the interaction machine: button, toggle, text field, slider, menu, drag, or a new reusable machine."));
ch.push(num("Add or reuse visual specs in DesignLanguage. Do not encode skin decisions inside the component."));
ch.push(num("Implement the finished Compose component with TactileTheme, InteractionController, DesignLanguage visuals, and spring specs."));
ch.push(num("Add snapshot/unit tests for the interaction machine."));
ch.push(num("Add skin matrix tests for all official skins and all supported states."));
ch.push(num("Add Compose previews/workbench examples for idle, hover, focus, press, disabled, error, selected, busy."));
ch.push(num("Add host component registry entry if the component should be renderable from React/Hermes."));
ch.push(num("Add RSD implementation if the component is part of the shared cross-platform API."));

ch.push(h2("6.9 Atomic Design in BestBuds Terms"));
ch.push(p("Mapping existing BestBuds UI to Atomic Design clarifies migration boundaries. Existing product components are valuable references, but the reusable core should move into Tactile only when the component is generic."));
ch.push(tbl(["BestBuds Existing Surface","Atomic Level","Tactile Equivalent","Migration Note"],[
  ["GradientButton / JoinButton / ActionButton","Atom/Product wrapper","TButton + BestBudsButton defaults","Core TButton should not know BestBuds gradients; BestBudsSkin or product wrappers provide them."],
  ["BBText / BBTextField","Atom","TText / TInput","Typography and input visuals move to BestBudsSkin."],
  ["StatusChip / EventTypeToggle / SegmentedSwitch","Atom/Molecule","TChip / TSegmentedControl","Selection state should use shared toggle/segmented machines."],
  ["SearchBar","Molecule","TSearchBar","Focus, clear button, placeholder, and submit behavior should be generic."],
  ["EventRow / EventCard","Organism","TEventCard as product organism or BestBuds-specific component","Keep domain-specific layout in BestBuds unless it becomes reusable across apps."],
  ["TabLayout","Molecule/Organism","TTabRow or Adaptive navigation chrome","Keyboard and selected-state behavior should come from shared tab machine."],
],[2400,1500,2300,3160]));
pb();

// ═══════════════════════════════════════════════════════════════════════════════
// 7. ADAPTIVE LAYOUTS
// ═══════════════════════════════════════════════════════════════════════════════
ch.push(h1("7. Adaptive Layouts & Multi-Pane Navigation"));
ch.push(sp(80));

ch.push(h2("7.1 Design Philosophy"));
ch.push(p("Adaptive layouts automatically adjust UI structure to screen size. The key insight: navigation chrome and pane count are functions of window width class, not separate configurations. One container declaration produces correct layout on phone, tablet, and desktop."));
ch.push(box("Architectural Decision","There is no TactileContainer. AdaptiveNavigationContainer and AdaptivePaneContainer extend ContainerNode and implement ComposeContainer\u2014the same interfaces as BottomNavigationContainer and TabbedContainer. The graph\u2019s recursive rendering (GraphContent \u2192 ComposeContainer.Content \u2192 renderer lambda \u2192 GraphContent) works unchanged."));

ch.push(h2("7.2 Window Size Classes"));
ch.push(p("Uses reaktor-graph\u2019s existing WindowSize observable:"));
ch.push(tbl(["Width Class","Breakpoint","Panes","Navigation Chrome"],[
  ["COMPACT","< 600dp","1","Bottom navigation bar"],
  ["MEDIUM","600\u2013839dp","2","Navigation rail"],
  ["EXPANDED","840\u20131199dp","2\u20133","Navigation rail"],
  ["LARGE","1200\u20131599dp","2\u20133","Permanent navigation drawer"],
  ["EXTRA_LARGE","\u2265 1600dp","3","Full navigation drawer"],
],[1600,1200,800,5760]));

ch.push(h2("7.3 AdaptiveNavigationContainer"));
ch.push(p("Replaces BottomNavigationContainer. Same constructor API, but Content() switches navigation chrome based on WindowSize:"));
ch.push(...code([
  "class AdaptiveNavigationContainer(",
  "    graph: Graph, pattern: String,",
  "    val children: Map<String, ChildGraph>, initialSelection: String,",
  "    val bottomNavKeys: Set<String> = children.keys",
  "): ContainerNode(graph, pattern, ArrayList(children.values.map { it.graph })),",
  "   ComposeContainer {",
  "",
  "    val selected = MutableStateFlow(initialSelection)",
  "    val controller by provides<Controller>(...)",
  "",
  "    @Composable override fun Content(renderer: @Composable (Graph, Boolean) -> Unit) {",
  "        val windowSize by WindowSize.state.collectAsState()",
  "        val selectedKey by controller.impl.selected.collectAsState()",
  "        val skin = LocalDesignLanguage.current",
  "",
  "        when (windowSize.width) {",
  "            COMPACT  -> CompactLayout(...)  // skin.navigationBarVisuals()",
  "            MEDIUM   -> MediumLayout(...)   // skin.navigationRailVisuals()",
  "            else     -> ExpandedLayout(...) // skin.navigationDrawerVisuals()",
  "        }",
  "    }",
  "}",
]));
ch.push(p("The navigation chrome (bar/rail/drawer) is itself skin-driven. NeoBrutalism gets a thick-bordered bar; Glass gets a blurred translucent rail; Material gets standard elevation."));

ch.push(h2("7.4 AdaptivePaneContainer"));
ch.push(p("For list-detail layouts. Manages 1\u20133 panes based on window width:"));
ch.push(...code([
  "class AdaptivePaneContainer(",
  "    graph: Graph, pattern: String,",
  "    val paneGraphs: List<Graph>,  // primary, secondary, [tertiary]",
  "): ContainerNode(graph, pattern, ArrayList(paneGraphs)), ComposeContainer {",
  "",
  "    @Composable override fun Content(renderer: @Composable (Graph, Boolean) -> Unit) {",
  "        val windowSize by WindowSize.state.collectAsState()",
  "        when (windowSize.width) {",
  "            COMPACT -> SinglePane(paneGraphs, renderer)  // shows active only",
  "            MEDIUM  -> DualPane(paneGraphs, renderer)    // primary 40%, secondary 60%",
  "            else    -> TriPane(paneGraphs, renderer)     // 25/45/30 split",
  "        }",
  "    }",
  "}",
]));

ch.push(h2("7.5 GraphRouteContent: Rendering Specific Routes"));
ch.push(p("Current GraphContent renders the back stack top. Multi-pane layouts need to render a specific route:"));
ch.push(...code([
  "@Composable",
  "fun GraphRouteContent(graph: Graph, routePattern: String, isFocused: Boolean = true) {",
  "    val entries by graph.backStack.entries.collectAsState()",
  "    val entry = entries.find { it.edge.end.pattern.original == routePattern } ?: return",
  "    val node = entry.edge.end.attachedNode() ?: return",
  "    when (node) {",
  "        is ComposeContainer -> node.Content { g, f -> GraphContent(g, f && isFocused) }",
  "        is ComposeContent -> node.Content()",
  "        else -> {}",
  "    }",
  "}",
]));

ch.push(h2("7.6 BestBuds Migration"));
ch.push(...code([
  "// BEFORE: BottomNavigationContainer (phone-only)",
  "BottomNavigationContainer(this, pattern, children, \"chat\",",
  "    bottomNavKeys = setOf(\"chat\", \"campaign\", \"discover\", \"events\"))",
  "",
  "// AFTER: AdaptiveNavigationContainer (same API, adaptive behavior)",
  "AdaptiveNavigationContainer(this, pattern, children, \"chat\",",
  "    bottomNavKeys = setOf(\"chat\", \"campaign\", \"discover\", \"events\"))",
  "// Phone: bottom bar. Tablet: rail. Desktop: drawer. Zero screen changes.",
]));

ch.push(h2("7.7 Compose Adaptive APIs to Use"));
ch.push(p("Tactile adaptive layout should be a thin policy layer over official Compose adaptive primitives where those primitives fit. reaktor-graph owns route state and navigation. Compose adaptive owns pane measurement, directives, and responsive presentation."));
ch.push(tbl(["Need","Compose API Family","Tactile Wrapper","Notes"],[
  ["Canonical list-detail layouts","material3-adaptive ListDetailPaneScaffold","TListDetailPane / AdaptivePaneContainer","Use for chat list + chat detail, settings list + detail, inbox + item view."],
  ["Supporting pane layouts","material3-adaptive SupportingPaneScaffold","TSupportingPane","Use for main content + contextual inspector/profile/details pane."],
  ["Window adaptive information","currentWindowAdaptiveInfo, WindowSizeClass, posture info","rememberTactileAdaptiveInfo()","Translate official adaptive info into graph pane policy."],
  ["Flexible wrapping rows/columns","foundation layout FlowRow / FlowColumn","TFlexRow / TFlexColumn","Use for chips, filters, action groups, toolbar wrapping."],
  ["Responsive grids","LazyVerticalGrid / LazyHorizontalGrid with GridCells.Adaptive","TAdaptiveGrid","Use for cards, media galleries, dashboards."],
  ["Custom pane splits","PaneScaffoldDirective / custom measured layout","TPaneScaffold","Use when official canonical layouts do not fit a product-specific pane arrangement."],
],[2700,2800,2200,1660]));

ch.push(h2("7.8 Pane Model"));
ch.push(p("A pane is not just a Composable slot. In Reaktor it is a graph-backed route surface with focus, route state, collapse policy, and back behavior."));
ch.push(...code([
  "enum class PaneRole { Primary, List, Detail, Supporting, Extra, Inspector }",
  "",
  "data class GraphPaneSpec<P : Payload>(",
  "    val role: PaneRole,",
  "    val graph: Graph,",
  "    val route: RouteNode<P, *>,",
  "    val payload: P? = null,",
  "    val minWidthDp: Int = 280,",
  "    val preferredWidthDp: Int = 420,",
  "    val collapsePolicy: PaneCollapsePolicy = PaneCollapsePolicy.Auto,",
  "    val focusPolicy: PaneFocusPolicy = PaneFocusPolicy.FocusWhenSelected,",
  ")",
  "",
  "sealed interface PaneCollapsePolicy {",
  "    data object Auto : PaneCollapsePolicy",
  "    data object Overlay : PaneCollapsePolicy",
  "    data object HideFirst : PaneCollapsePolicy",
  "    data object KeepVisible : PaneCollapsePolicy",
  "}",
]));
ch.push(p("The pane spec must remain graph-aware because a list/detail layout is not simply two children. Each pane may have its own back stack, data providers, route payload, and lifecycle. Adaptive containers coordinate these without replacing graph navigation."));

ch.push(h2("7.9 Adaptive Back and Focus Policy"));
ch.push(p("Multi-pane apps fail when back behavior is vague. The container must define deterministic behavior for compact, medium, expanded, and large widths."));
ch.push(tbl(["Situation","Compact","Medium/Expanded","Large/Desktop"],[
  ["List item selected","Push detail route; list no longer visible","Keep list visible, focus detail pane","Keep list visible, focus detail pane; optional inspector opens supporting pane"],
  ["Back from detail","Pop detail and return to list","Move focus to list if detail is focused; pop detail only if focused graph can pop","Close inspector/supporting pane first, then focus detail, then list"],
  ["Deep link directly to detail","Show detail route with back affordance to list","Open list + detail; list may select matching item if known","Open list + detail + optional supporting context"],
  ["Keyboard focus crosses panes","Single pane focus order","Pane boundary participates in traversal; arrow/Tab policy configurable","Desktop-style focus rings and pane headers required"],
  ["Route graph can pop internally","Pop internal route first","Pop focused pane's graph first","Pop focused pane's graph first, then container-level pane collapse"],
],[1800,2500,2500,2560]));
ch.push(box("Back Rule","Back should first resolve transient UI state (open menu/sheet/dialog), then focused pane local back stack, then pane collapse/focus, then parent graph navigation. This order must be shared across Compose and RSD paths."));

ch.push(h2("7.10 GraphRouteContent Contract"));
ch.push(p("GraphRouteContent is the missing primitive that makes multi-pane Reaktor navigation possible. It must render a route that may not be the top entry of the graph's global back stack, while still respecting lifecycle and route binding."));
ch.push(tbl(["Requirement","Behavior"],[
  ["Route binding","If the requested route has a payload, bind that payload exactly as if the route were top-of-stack."],
  ["Lifecycle","Attach route content when pane becomes visible; detach or pause according to collapse policy when hidden."],
  ["Focus","Pass isFocused into nested GraphContent so focused graph receives keyboard/back priority."],
  ["Containers","If the route node is ComposeContainer, call node.Content(renderer) recursively."],
  ["TactileContent future path","If the route node is TactileContent, render TactileComposeRenderer for its host tree."],
  ["Missing route","Render an EmptyPane/placeholder or route-not-found diagnostic in debug builds; never crash production."],
],[2300,7060]));

ch.push(h2("7.11 Flexbox and Grid Usage Rules"));
ch.push(p("Flexbox and grid are layout tools, not replacements for adaptive pane policy."));
ch.push(bul([B("Use FlowRow/FlowColumn for wrapping small items: "), N("chips, reaction buttons, filters, toolbar actions, avatar groups, segmented options.")]));
ch.push(bul([B("Use LazyVerticalGrid with adaptive cells for repeated cards: "), N("events, campaigns, dashboards, media tiles, discovery items.")]));
ch.push(bul([B("Do not use grid to fake panes: "), N("list-detail, supporting pane, inspector, and navigation chrome need pane semantics, focus, and back policy.")]));
ch.push(bul([B("Grid cell sizing must be tokenized: "), N("minimum card width, gap, and max content width come from TactileTokens/adaptive tokens.")]));
ch.push(bul([B("Flex wrapping must be stable: "), N("controls should not reflow unpredictably on hover/press; component dimensions should be stable across interaction states.")]));

ch.push(h2("7.12 RSD Adaptive Equivalence"));
ch.push(p("The RSD path should follow the same adaptive decisions even though it uses CSS and React eventing. The shared policy is: width/posture -> pane configuration -> navigation chrome -> focus/back behavior."));
ch.push(tbl(["Compose Path","RSD Path","Shared Contract"],[
  ["ListDetailPaneScaffold","CSS grid/flex layout with list/detail regions","Pane roles, focus policy, collapse policy"],
  ["SupportingPaneScaffold","CSS grid with main/supporting/extra areas","Supporting pane opens/closes according to graph action state"],
  ["FlowRow / FlowColumn","CSS flex-wrap","Tokenized gap, min item width, stable child dimensions"],
  ["LazyVerticalGrid GridCells.Adaptive","CSS grid-template-columns: repeat(auto-fit/minmax(...))","Minimum cell size and item semantics"],
  ["BackHandler","Browser history + React Native BackHandler adapter","Same back resolution order"],
],[2500,3100,3760]));

ch.push(h2("7.13 BestBuds and Desktop Transition Surface"));
ch.push(p("The current BestBuds app already has good candidates for adaptive layouts. The migration should preserve current graph route names and screen ownership, then change the containers and component library around them."));
ch.push(tbl(["Current Surface","Adaptive Target","Why"],[
  ["ChatListScreen + ChatScreen","ListDetailPane: List + Detail","Classic split view. Phone behavior remains push navigation; tablet/desktop show both panes."],
  ["FriendProfile / GroupProfile from chat","Supporting pane or Extra pane","Profile context should sit beside chat on large screens instead of replacing it."],
  ["CampaignScreen / DiscoverScreen / Event screens","Adaptive grid + supporting filters pane","Cards and filters naturally fit grid/flex; detail can open as supporting pane."],
  ["Profile/Friends/Dev overflow routes","Adaptive navigation drawer destinations","On desktop/large widths, overflow should become visible navigation sections."],
  ["Reaktor desktop workbench: graph/tree/preview/inspector","Three-pane desktop template","Workbench already behaves like an inspector layout; Tactile can standardize panes and skin it."],
],[2600,2600,4160]));
pb();

// ═══════════════════════════════════════════════════════════════════════════════
// 8. PHYSICS & SENSORY
// ═══════════════════════════════════════════════════════════════════════════════
ch.push(h1("8. Physics Engine & Sensory Feedback"));
ch.push(sp(80));

ch.push(h2("8.1 MaterialPhysics & Predefined Materials"));
ch.push(...code([
  "data class MaterialPhysics(",
  "    val mass: Float,       // inertia, haptic intensity",
  "    val stiffness: Float,  // spring return speed",
  "    val damping: Float,    // oscillation decay rate",
  "    val friction: Float    // surface drag coefficient",
  ")",
]));
ch.push(tbl(["Material","Mass","Stiffness","Damping","Damping Ratio","Feel"],[
  ["Feather","0.1","80","8","~1.41 (overdamped)","Floaty, slow, no bounce"],
  ["Rubber","1.0","300","20","~0.58 (underdamped)","Bouncy, responsive (default)"],
  ["Wood","2.0","500","30","~0.47 (underdamped)","Solid, moderate bounce"],
  ["Steel","5.0","800","40","~0.63 (underdamped)","Heavy, minimal deformation"],
  ["Stone","8.0","1200","60","~0.61 (underdamped)","Massive, barely moves"],
],[1000,700,900,900,1800,4060]));

ch.push(h2("8.2 Spring Solver"));
ch.push(p("Damped harmonic oscillator (F = -kx - cv, a = F/m) with semi-implicit Euler integration. Converts to Compose SpringSpec via dampingRatio = c / (2 * sqrt(k * m))."));

ch.push(h2("8.3 TactileField"));
ch.push(p("Spatial impulse bus for cross-element reactivity. Press one element; neighbors sway. Shaders distort. The field decouples producers (interactive elements) from consumers (other elements, shaders)."));
ch.push(p("Displacement follows inverse-square falloff * exponential decay * material resistance. Heavy elements (Steel) barely respond; light elements (Feather) sway dramatically."));

ch.push(h2("8.4 Haptic Engine"));
ch.push(p("expect/actual per platform. PhysicsImpact(mass, velocity) maps to: Android VibrationEffect amplitude = (mass*velocity*40).coerceIn(1,255); iOS CHHapticEvent intensity = (mass*velocity).coerceIn(0,1)."));

ch.push(h2("8.5 Skia Shaders"));
ch.push(p("AGSL/SkSL shaders read TactileField.shaderUniforms(). Background FBM noise distorts around impulse origins. CMP-only; RSD uses CSS animated-gradient fallbacks."));

ch.push(h2("8.6 Physical Engine Boundaries"));
ch.push(p("The physical engine is important, but it should not leak into business logic or routing. It owns physical response; interaction machines own semantic outcomes; graph owns effects."));
ch.push(tbl(["Concept","Owned By","Example"],[
  ["MaterialPhysics","DesignLanguage tokens or component parameter","NeoBrutalism buttons default to Wood; LiquidGlass surfaces default to Rubber."],
  ["SpringSimulation","Tactile runtime / Compose animation adapter","Press scale, card lift, slider thumb movement."],
  ["Gesture velocity","InteractionController","Flinging a sheet or slider thumb computes velocity from pointer samples."],
  ["Impact impulse","InteractionController + TactileField","Dropping a card emits an impact at its center."],
  ["Haptic waveform","HapticEngine actual implementation","Android amplitude and iOS CHHapticEvent mapping."],
  ["Shader uniforms","TactileField","Current impulse origins, forces, decay values, and time."],
  ["Navigation after click","Graph action registry","Button click calls an action; physics does not navigate."],
],[2400,2600,4360]));

ch.push(h2("8.7 Gesture Taxonomy"));
ch.push(p("A tactile runtime needs more than tap and drag. The interaction layer should normalize gesture outcomes so components can share behavior and skins can react consistently."));
ch.push(tbl(["Gesture","Produced By","Common Components","Physical Response"],[
  ["Tap","Pointer down/up within slop and time threshold","Button, card, list item, chip","Short impulse, light haptic, press spring."],
  ["Long press","Pointer held beyond threshold","Message bubble, card, list item","Ramping press progress, heavier haptic at threshold."],
  ["Drag","Movement beyond slop while pressed","Slider, sheet, reorder item","Continuous velocity update, friction resistance."],
  ["Fling","Drag release with velocity","Sheet, carousel, scroll-adjacent controls","Momentum simulation and impact on settle."],
  ["Hover","Pointer enter/exit","Desktop/web buttons, cards, menus","Elevation/lift and pointer-follow effects."],
  ["Focus","Keyboard/accessibility focus","All interactive controls","Focus ring, no haptic, no field impulse."],
  ["Force/pressure","Stylus/pressure-capable devices","Canvas tools, tactile buttons","Impulse force scales by pressure where available."],
],[1500,2200,2500,3160]));

ch.push(h2("8.8 Runtime Capability Degradation"));
ch.push(p("Different targets will not support the same haptics, shaders, blur, pointer hover, or pressure. Capability detection must be explicit and visible to the runtime."));
ch.push(...code([
  "data class TactileCapabilities(",
  "    val supportsHover: Boolean,",
  "    val supportsPressure: Boolean,",
  "    val supportsHaptics: Boolean,",
  "    val supportsAdvancedHaptics: Boolean,",
  "    val supportsRuntimeShaders: Boolean,",
  "    val supportsBackdropBlur: Boolean,",
  "    val prefersReducedMotion: Boolean,",
  ")",
]));
ch.push(p("Components read capabilities through CompositionLocal or graph DI. Skins may produce fallback visuals, but capability detection itself must not live inside the skin."));
pb();

// ═══════════════════════════════════════════════════════════════════════════════
// 9. CROSS-PLATFORM RENDERING
// ═══════════════════════════════════════════════════════════════════════════════
ch.push(h1("9. Cross-Platform Rendering: Reconciler & RSD"));
ch.push(sp(80));
ch.push(p("The foundation (Sections 3\u20138) works standalone in Compose Multiplatform. This section describes additional rendering paths."));

ch.push(h2("9.1 The Two Paths"));
ch.push(tbl(["Path","Target","Mechanism","Foundation Required?"],[
  ["Compose (native)","Android, iOS, Desktop, WebWasm","T-components render directly","Layers 0\u20134 only"],
  ["Compose via Reconciler","Android, iOS, Desktop","React/Hermes \u2192 reconciler \u2192 host tree \u2192 Compose","+ Layer 5 (FFI)"],
  ["React Strict DOM","Web, React Native","React \u2192 html.*/css.create \u2192 DOM","+ Layer 5 (RSD)"],
],[2200,2200,3200,1740]));

ch.push(h2("9.2 Reconciler Architecture"));
ch.push(p("React on Hermes produces TactileHostTree (typed nodes with versioned IDs). This crosses the FFI bridge as JSON (later FlexBuffer). Compose observes via StateFlow and maps node types to T-components. Springs, haptics, shaders run natively\u2014NEVER per-frame across the bridge."));

ch.push(h2("9.3 RSD Architecture"));
ch.push(p("Same React API backed by React Strict DOM (html.* + css.create). DesignLanguage skins mirrored in TypeScript. No reconciler, no FFI\u2014standard React rendering to DOM or React Native."));

ch.push(h2("9.4 When to Use Which"));
ch.push(bul([B("Pure Compose (default): "), N("Kotlin teams building native apps. Full design system, no React dependency.")]));
ch.push(bul([B("Reconciler: "), N("Mixed teams wanting React authoring with native rendering quality.")]));
ch.push(bul([B("RSD: "), N("Web-first teams, or when you need the same React code to run in browser and app.")]));

ch.push(h2("9.5 Host Tree Contract"));
ch.push(p("The reconciler path does not expose a product-facing JSON schema. It produces an internal host tree, similar in purpose to React Native's shadow tree. Product engineers author React; the host tree is the renderer boundary."));
ch.push(...code([
  "data class TactileHostNode(",
  "    val id: Int,",
  "    val type: String,              // e.g. reaktor.tactile.Button@1",
  "    val props: Map<String, TactileValue>,",
  "    val handlers: Map<String, HandlerRef>,",
  "    val children: List<TactileHostNode>,",
  "    val key: String? = null,",
  ")",
  "",
  "sealed class TactileValue {",
  "    data class Str(val value: String) : TactileValue()",
  "    data class Num(val value: Double) : TactileValue()",
  "    data class Bool(val value: Boolean) : TactileValue()",
  "    data class Obj(val value: Map<String, TactileValue>) : TactileValue()",
  "    data class Lst(val value: List<TactileValue>) : TactileValue()",
  "    data object Null : TactileValue()",
  "}",
  "",
  "data class HandlerRef(val id: String)",
]));
ch.push(tbl(["Rule","Why"],[
  ["Host type IDs are versioned: reaktor.tactile.Button@1","A v2 button can add props without breaking old bundles."],
  ["Props are serializable values only","Functions, classes, platform objects, and closures cannot cross the host-tree boundary."],
  ["Handlers are references, not serialized lambdas","Kotlin dispatches HandlerRef back to Hermes; JS owns the callback."],
  ["Children are ordered","React reconciliation depends on stable ordering and keys."],
  ["Unknown host nodes degrade gracefully","In production, render children or a debug placeholder instead of crashing the app."],
],[3200,6160]));

ch.push(h2("9.6 Event Bridge Rules"));
ch.push(p("The bridge should carry semantic events, not per-frame motion. A native press animation starts and completes locally. The bridge is used only when application state must change."));
ch.push(tbl(["Native Event","Stays Native","Crosses Bridge"],[
  ["Pointer down","pressed snapshot, spring target, haptic, field impulse","Nothing"],
  ["Pointer move during drag","drag snapshot, velocity, local transform","Only throttled semantic drag callback if component API requires it"],
  ["Pointer up completing click","release spring, haptic settle","onPress HandlerRef event"],
  ["Slider drag","thumb physics, haptic ticks","onValueChange at configured rate; onValueCommit on release"],
  ["Text input","IME composition, focus visuals","onChangeText / onSubmitEditing semantic payloads"],
  ["Route action","Nothing visual by itself","ActionRef dispatch to graph action registry"],
],[2200,3500,3660]));

ch.push(h2("9.7 React Strict DOM Implementation Rules"));
ch.push(p("The RSD implementation should not be a loose rewrite of the Compose components. It must mirror the same public component props, interaction snapshot vocabulary, design-language visual specs, and adaptive policies."));
ch.push(num("Use react-strict-dom html.* elements, not arbitrary div/span elements from React DOM."));
ch.push(num("Use css.create for styles so web extraction and native compatibility stay aligned."));
ch.push(num("Mirror DesignLanguage in TypeScript, using the same visual-spec field names and units."));
ch.push(num("Implement useInteraction() as the JS equivalent of InteractionController, with the same event vocabulary and snapshot fields where platform capabilities exist."));
ch.push(num("Map semantics to ARIA: role, aria-disabled, aria-checked, aria-selected, aria-expanded, aria-busy, aria-invalid."));
ch.push(num("Use CSS transitions/springs only as fallbacks; the material vocabulary should remain the same even if the solver differs."));
ch.push(num("Keep RSD-specific limitations documented. RSD is under active development, so component parity must be tested against the supported API surface."));
ch.push(...code([
  "export function Button(props: ButtonProps) {",
  "  const interaction = useInteraction({ disabled: props.disabled });",
  "  const skin = useDesignLanguage();",
  "  const noop = () => {};",
  "  const visuals = skin.buttonVisuals(props.variant, props.size,",
  "    stateFromProps(props), interaction.snapshot);",
  "  return (",
  "    <html.button",
  "      role=\"button\"",
  "      aria-disabled={props.disabled ? true : false}",
  "      style={[styles.base, buttonStyleFromVisuals(visuals)]}",
  "      onClick={props.disabled ? noop : props.onPress}",
  "      {...interaction.handlers}",
  "    >{props.children}</html.button>",
  "  );",
  "}",
]));

ch.push(h2("9.8 Reconciler Milestones"));
ch.push(p("Build the React path in stages so correctness is proven before optimization."));
ch.push(tbl(["Milestone","Goal","Exit Criteria"],[
  ["M1: JSON full-tree commit","Prove React -> host tree -> Compose rendering","Button click updates React state and Compose re-renders."],
  ["M2: Handler registry","Prove native event -> JS callback -> React update","Handlers register/unregister without leaks across unmounts."],
  ["M3: FlexBuffer full-tree commit","Replace JSON serialization with compact binary format","Same tests pass; commit size/time measured."],
  ["M4: Op batches","Avoid full tree replacement on every commit","Create/insert/update/remove/reorder ops apply correctly."],
  ["M5: Concurrent/error boundaries","Handle JS errors and partial commits safely","Bad bundle cannot corrupt the native tree; rollback works."],
  ["M6: DevTools/inspector","Make host tree inspectable in Reaktor desktop","Inspect props, handlers, visual specs, snapshots, and pane placement."],
],[2200,3400,3760]));
pb();

// ═══════════════════════════════════════════════════════════════════════════════
// 10. REAKTOR GRAPH INTEGRATION
// ═══════════════════════════════════════════════════════════════════════════════
ch.push(h1("10. Reaktor Graph Integration"));
ch.push(sp(80));

ch.push(h2("10.1 What Tactile Uses As-Is"));
ch.push(tbl(["Component","Used How","Modified?"],[
  ["Graph","Navigation host, DI scope, lifecycle owner","No"],
  ["ComposeContainer","Interface for adaptive containers","No\u2014extended"],
  ["ComposeContent","Interface for tactile screens","No\u2014extended"],
  ["GraphContent","Recursive renderer","No"],
  ["ContainerNode","Base for adaptive containers","No\u2014subclassed"],
  ["ObservableStack","Back stack observation","No"],
  ["WindowSize","Drives adaptive layout decisions","No"],
  ["RouteBinding","Payload delivery","No"],
  ["Koin DI","Scoped dependency injection","No"],
  ["Port wiring","ProviderPort/ConsumerPort for skin injection","No"],
],[2000,4000,3360]));

ch.push(h2("10.2 TactileGraphApplication"));
ch.push(...code([
  "@Composable",
  "fun TactileGraphApplication(",
  "    graph: Graph, skin: DesignLanguage = MaterialSkin,",
  "    baseTokens: DesignTokens = DesignTokens(),",
  ") {",
  "    TactileTheme(skin = skin, baseTokens = baseTokens) {",
  "        Scaffold(Modifier.safeDrawingPadding()) { GraphContent(graph) }",
  "    }",
  "}",
]));

ch.push(h2("10.3 Port-Based Skin Injection"));
ch.push(p("Different subgraphs can use different skins via reaktor-graph\u2019s port system:"));
ch.push(...code([
  "// Provider node exposes a skin",
  "class ThemeNode(graph: Graph) : BasicNode(graph) {",
  "    val skin by provides<DesignLanguage>(NeoBrutalismSkin)",
  "}",
  "",
  "// Container reads skin from port and wraps children with TactileTheme",
  "class ThemedContainer(...) : ContainerNode(...), ComposeContainer {",
  "    val skin by consumes<DesignLanguage>()",
  "    @Composable override fun Content(renderer: ...) {",
  "        TactileTheme(skin = skin.impl) { renderer(activeGraph!!, true) }",
  "    }",
  "}",
]));

ch.push(h2("10.4 Runtime Services Provided by Graph"));
ch.push(p("Tactile should use graph services for anything app-scoped or route-scoped. This keeps the design system compatible with the rest of Reaktor instead of inventing a second runtime."));
ch.push(tbl(["Service","Provided Through","Used By"],[
  ["DesignLanguage","Port or DI scoped to graph/container","TactileTheme, T-components, host-tree renderer"],
  ["TactileField","DI or CompositionLocal per screen/container","InteractionController, shader backgrounds, physical effects"],
  ["HapticEngine","Feature slot / DI actual per platform","Interaction outcomes and physical impacts"],
  ["TactileCapabilities","DI or platform adapter","Component fallbacks, reduced motion, hover/pressure support"],
  ["ActionRegistry","Graph-scoped service","React host nodes and server-driven UI actions"],
  ["Route bindings","RouteBinding payload providers","Pane route rendering and screen content"],
  ["Telemetry","reaktor-telemetry graph spans","Interaction, render, bridge, and adaptive layout diagnostics"],
],[2400,3000,3960]));

ch.push(h2("10.5 Action Registry Boundary"));
ch.push(p("React, AI-generated UI, and host-tree components must not mutate graph internals directly. They call named actions. Kotlin resolves actions to graph dispatches, service calls, or state updates."));
ch.push(...code([
  "data class ActionRef(",
  "    val id: String,                 // bestbuds.chat.open",
  "    val payloadSchema: String,      // versioned schema id",
  ")",
  "",
  "interface TactileActionRegistry {",
  "    fun dispatch(action: ActionRef, payload: TactileValue.Obj): ActionResult",
  "}",
  "",
  "// React side",
  "<Button onPress={actions.dispatch('bestbuds.chat.open', { chatId })}>Open</Button>",
  "",
  "// Kotlin side",
  "registry.register(\"bestbuds.chat.open\") { payload ->",
  "    chatGraph.dispatch(Push(chatEdge, ChatScreenPayload(payload.chatId)))",
  "}",
]));
ch.push(p("This boundary is especially important for server-driven and AI-generated UI. The server can ask for allowed actions; it cannot execute arbitrary Kotlin or reach into graph state."));

ch.push(h2("10.6 TactileContent Route Nodes"));
ch.push(p("The future React/Hermes path should appear to reaktor-graph as another content type, not as a separate navigation framework."));
ch.push(...code([
  "interface TactileContent {",
  "    val hostTree: TactileHostTree",
  "    val bridge: TactileBridge",
  "}",
  "",
  "class TactileRouteNode<P : Payload>(",
  "    graph: Graph,",
  "    pattern: String,",
  "    private val bundleId: String,",
  ") : RouteNode<P, RouteBinding<P>>(graph, pattern), TactileContent {",
  "    override val hostTree = TactileHostTree()",
  "    override val bridge = TactileBridge(hostTree, graph.actionRegistry)",
  "    override fun onAttach() {",
  "        bridge.install()",
  "        graph.hermes.loadBundle(bundleId)",
  "        bridge.renderRoute(routeBinding.payload.value)",
  "    }",
  "}",
]));
ch.push(p("GraphRouteContent can render ComposeContent, ComposeContainer, and TactileContent through one recursive renderer. This is the reason Tactile does not need a new navigation system."));

ch.push(h2("10.7 Reaktor Desktop Workbench Role"));
ch.push(p("The desktop workbench is not only an app; it should become the main development tool for the design system. It already has graph/tree/preview/inspector concepts that align with Tactile."));
ch.push(tbl(["Workbench Panel","Tactile Extension"],[
  ["Graph tree","Show route nodes, pane containers, active graph, focused pane, and route payload."],
  ["Preview","Render selected component/screen under each skin, window size class, and reduced-motion setting."],
  ["Inspector","Show InteractionSnapshot, visual spec output, material physics, field impulses, and semantic role."],
  ["Host tree panel","For React/Hermes routes, inspect TactileHostNode tree, props, handler refs, and commit timing."],
  ["Token panel","Compare DesignTokens, TactileTokens, and resolved component visuals."],
  ["Adaptive panel","Simulate compact/medium/expanded/large widths and pane collapse/back behavior."],
],[2400,6960]));

ch.push(h2("10.8 Observability"));
ch.push(p("A tactile runtime will be hard to debug without structured observability. Emit diagnostics around the boundaries, not every frame."));
ch.push(bul([B("Interaction traces: "), N("event -> snapshot -> outcome, sampled per component when debug is enabled.")]));
ch.push(bul([B("Visual traces: "), N("component props + snapshot + skin -> visual spec, captured for previews and bug reports.")]));
ch.push(bul([B("Adaptive traces: "), N("window info -> pane policy -> visible panes -> focused pane -> back result.")]));
ch.push(bul([B("Bridge traces: "), N("React commit size, serialization time, decode time, renderer apply time, handler dispatch latency.")]));
ch.push(bul([B("Graph traces: "), N("action id -> graph dispatch -> route update, tied to reaktor-telemetry spans.")]));
pb();

// ═══════════════════════════════════════════════════════════════════════════════
// 11. AI & SDUI
// ═══════════════════════════════════════════════════════════════════════════════
ch.push(h1("11. AI & Server-Driven UI"));
ch.push(sp(80));
ch.push(p("Future layer building on the React reconciler path. Because React IS the programming model, AI-generated UI is React code\u2014no custom schema language."));
ch.push(bul("Component props exposed as tool/function schemas for LLMs (like Anthropic\u2019s Tambo)"));
ch.push(bul("Server ships signed React bundles via Cloudflare R2"));
ch.push(bul("Client executes on Hermes; calls named actions (ActionRef) resolved by Kotlin ActionRegistry"));
ch.push(bul("No JSON SDUI\u2014React is authoring + execution model"));

ch.push(h2("11.1 Why Not Static JSON SDUI"));
ch.push(p("The design explicitly avoids a static JSON server-driven UI model. Static SDUI tends to grow a partial programming language: expressions, bindings, loops, conditionals, slots, lifecycle hooks, action DSLs, and validation rules. The result is often less powerful than React but harder to debug because the boundary between server schema and client widgets is unclear."));
ch.push(tbl(["Problem in Static SDUI","React/Reconciler Approach"],[
  ["Conditionals require a custom expression DSL","Use normal JavaScript/TypeScript conditionals."],
  ["Loops require a schema convention","Use array.map with keys."],
  ["Local state requires a binding language","Use useState/useReducer."],
  ["Effects require a lifecycle DSL","Use useEffect within sandbox policy."],
  ["Slots/templates become ad hoc","Use React composition and children."],
  ["Client widget boundary is unclear","Host components are explicit versioned node types."],
  ["Every new UI behavior expands the schema language","New behavior is implemented as React component code using stable host components."],
],[4000,5360]));

ch.push(h2("11.2 AI Tool Manifest"));
ch.push(p("AI should not receive the entire implementation. It should receive a constrained manifest of allowed components, props, events, examples, and action refs."));
ch.push(...code([
  "type ComponentManifest = {",
  "  id: 'reaktor.tactile.Button@1',",
  "  importName: 'Button',",
  "  props: {",
  "    variant: ['filled', 'outlined', 'text', 'tonal', 'elevated'],",
  "    size: ['small', 'medium', 'large'],",
  "    disabled: 'boolean',",
  "  },",
  "  events: { onPress: { payload: 'Unit', allowedActions: ['*.open', '*.submit'] } },",
  "  allowedChildren: ['Text', 'Icon', 'Row'],",
  "  accessibility: { requiresLabel: true },",
  "  examples: ['<Button onPress={actions.openChat}>...</Button>']",
  "}",
]));
ch.push(p("The manifest should be generated from the same host component registry used by the reconciler, so documentation, AI tooling, server-driven bundles, and renderer validation all share one source of truth."));

ch.push(h2("11.3 Bundle Lifecycle and Security"));
ch.push(tbl(["Stage","Requirement"],[
  ["Authoring","React source imports only approved packages and @reaktor/tactile components."],
  ["Build","Bundle is compiled for Hermes with deterministic dependency versions."],
  ["Validation","Static checks ensure only approved host components, action refs, and imports are present."],
  ["Signing","Bundle and manifest are signed. Client verifies signature before execution."],
  ["Loading","Client pins bundle version per route or experiment; failed load falls back to native route."],
  ["Execution","Hermes runtime has CPU/time/memory limits, console/error forwarding, and no unrestricted network access."],
  ["Rollback","If rendering or bridge commit fails, host tree rolls back to previous committed tree."],
  ["Telemetry","Bundle id/version, commit timing, errors, and action dispatches are traced."],
],[1800,7560]));

ch.push(h2("11.4 What AI Is Allowed to Do"));
ch.push(p("AI-generated UI can compose existing concrete design-system components. It should not invent new low-level primitives at runtime."));
ch.push(tbl(["Allowed","Not Allowed"],[
  ["Compose Button, Card, Text, ListItem, Grid, ListDetail, and product-approved organisms","Define new host component types unknown to the client."],
  ["Call approved ActionRefs with schema-valid payloads","Call arbitrary URLs, access graph internals, or execute Kotlin."],
  ["Use local React state for purely local UI state like expanded/collapsed sections","Persist data directly without going through approved actions/services."],
  ["Use conditional rendering and mapping over provided data","Ship a new SDUI expression language or hidden interpreter."],
  ["Render fallback UI for missing data","Bypass accessibility requirements for labels, roles, and focus."],
],[4800,4560]));
pb();

// ═══════════════════════════════════════════════════════════════════════════════
// 12. ROADMAP
// ═══════════════════════════════════════════════════════════════════════════════
ch.push(h1("12. Implementation Roadmap"));
ch.push(sp(80));

ch.push(h2("Phase 1: Foundation (Weeks 1\u20134)"));
ch.push(p("Shibasis: tokens/modifiers. Kedarnath: interaction/composables."));
ch.push(num("InteractionSnapshot + InteractionController + Modifier.tactileInteraction"));
ch.push(num("MaterialPhysics + Materials presets + SpringSimulation"));
ch.push(num("TactileTokens extending DesignTokens"));
ch.push(num("DesignLanguage interface + all visual spec data classes"));
ch.push(num("MaterialSkin (default, backward-compatible)"));
ch.push(num("TactileTheme + CompositionLocals"));

ch.push(h2("Phase 2: Core Components (Weeks 5\u20138)"));
ch.push(num("TButton, TCard, TText, TInput, TIcon, TSwitch/TCheckbox/TRadio"));
ch.push(num("TAvatar, TBadge, TChip variants, TProgress, TDivider"));
ch.push(num("TListItem, TSearchBar, TAppBar, TSnackbar"));
ch.push(num("NeoBrutalismSkin, GlassSkin"));
ch.push(num("BestBuds proof: replace 3\u20135 R-components"));

ch.push(h2("Phase 3: Adaptive & Physics (Weeks 9\u201312)"));
ch.push(num("AdaptiveNavigationContainer + AdaptivePaneContainer"));
ch.push(num("GraphRouteContent"));
ch.push(num("TactileField + HapticEngine (Android + iOS)"));
ch.push(num("FBM shader (Manab)"));
ch.push(num("BestBuds full migration"));

ch.push(h2("Phase 4: Extended (Weeks 13\u201316)"));
ch.push(num("NeoMorphismSkin, NeoRetroSkin, CellShadingSkin"));
ch.push(num("Organisms + Templates"));
ch.push(num("Runtime skin switching + Dev Tools picker"));

ch.push(h2("Phase 5: Cross-Platform (Weeks 17\u201324)"));
ch.push(num("React reconciler + RSD + FFI integration"));
ch.push(num("TypeScript skin mirrors + AI schemas"));
ch.push(box("Phasing Rationale","Phases 1\u20134 deliver a complete design system in pure Compose. BestBuds ships on Phases 1\u20134. Phase 5 adds cross-platform but is NOT required for production use."));

ch.push(h2("12.1 Critical Path by Dependency"));
ch.push(p("The order below is stricter than the calendar phases. Do not start a dependent item until its contract is stable enough for other programmers to build against it."));
ch.push(tbl(["Order","Deliverable","Unblocks"],[
  ["1","Interaction event vocabulary + InteractionSnapshot v1","All component machines, skin state mapping, RSD useInteraction, host renderer events"],
  ["2","DesignLanguage interface + visual specs v1","Material/NeoBrutalism/LiquidGlass/custom skins, Compose atoms, RSD style mapping"],
  ["3","TactileTheme + runtime locals","All finished Compose components and skin switching"],
  ["4","Core atoms: TButton, TText, TSurface, TCard, TInput, TIcon, TToggle","Molecules and product migration"],
  ["5","Molecules: TListItem, TSearchBar, TAppBar, TTabRow, TNavItem","Organisms and adaptive navigation chrome"],
  ["6","AdaptiveNavigationContainer + GraphRouteContent","BestBuds top-level adaptive navigation and desktop/tablet shells"],
  ["7","AdaptivePaneContainer + pane policies","Chat list-detail, supporting panes, inspector layouts"],
  ["8","TactileField + haptics + shader capability fallback","Full tactile feel and visual effects"],
  ["9","RSD implementation of atoms/molecules/adaptive policies","Web/React Native parity"],
  ["10","React reconciler + host tree + bridge","Hermes authoring path and server/AI-generated UI"],
],[700,4300,4360]));

ch.push(h2("12.2 Definition of Done for the First Useful Release"));
ch.push(p("The first useful release is not the full AI/server-driven system. It is a production-usable Compose design system that proves the architecture through BestBuds."));
ch.push(bul("At least MaterialSkin, NeoBrutalismSkin, and LiquidGlassSkin exist and pass the skin test matrix."));
ch.push(bul("Core atoms and common molecules render correctly in Android, iOS, Desktop, and WebCanvas where supported."));
ch.push(bul("Interaction machines are covered by unit tests without Compose."));
ch.push(bul("AdaptiveNavigationContainer replaces BottomNavigationContainer behavior without breaking compact phone navigation."));
ch.push(bul("AdaptivePaneContainer demonstrates BestBuds chat list/detail on tablet/desktop widths."));
ch.push(bul("TactileTheme can switch skins at runtime in the workbench without recreating the graph."));
ch.push(bul("Reduced motion and no-haptics/no-shader fallbacks are verified."));
ch.push(bul("Reaktor desktop workbench can inspect a selected component's snapshot, visual spec, and skin."));

ch.push(h2("12.3 Work Allocation Guidance"));
ch.push(tbl(["Owner Type","Best Initial Work"],[
  ["Core/runtime engineer","InteractionSnapshot, event model, component machines, TactileField, capability detection."],
  ["Design-system engineer","DesignLanguage interface, visual specs, Material/NeoBrutalism/LiquidGlass skins, skin matrix tests."],
  ["Compose engineer","TButton/TCard/TInput/TListItem/TSearchBar/TAppBar and preview/workbench surfaces."],
  ["Graph/navigation engineer","AdaptiveNavigationContainer, AdaptivePaneContainer, GraphRouteContent, back/focus policy."],
  ["Web/React engineer","RSD implementation, TypeScript visual specs, useInteraction hook, responsive CSS equivalents."],
  ["Interop engineer","Hermes runtime lifecycle, host tree bridge, handler registry, FlexBuffer encoding, bundle loading."],
],[2500,6860]));
pb();

// ═══════════════════════════════════════════════════════════════════════════════
// 13. REFERENCES
// ═══════════════════════════════════════════════════════════════════════════════
ch.push(h1("13. Annotated References & Further Reading"));
ch.push(sp(80));

ch.push(h2("13.1 Interaction & Physics"));
ch.push(bul([L("Apple WWDC 2018: Designing Fluid Interfaces","https://developer.apple.com/videos/play/wwdc2018/803/"), N(" \u2014 Foundation for spring-based UI.")]));
ch.push(bul([L("Compose Animation: Spring","https://developer.android.com/develop/ui/compose/animation/customize#spring"), N(" \u2014 SpringSpec API we use directly.")]));
ch.push(bul([L("Compose Custom Modifier.Node","https://developer.android.com/develop/ui/compose/custom-modifiers"), N(" \u2014 Zero-allocation modifier for production.")]));
ch.push(bul([L("iOS CHHapticEngine","https://developer.apple.com/documentation/corehaptics/chhapticengine"), N(" \u2014 iOS haptic API mapping.")]));
ch.push(bul([L("Android VibrationEffect","https://developer.android.com/reference/android/os/VibrationEffect"), N(" \u2014 Android haptic API mapping.")]));

ch.push(h2("13.2 Design Systems & Tokens"));
ch.push(bul([L("Material Design 3","https://m3.material.io/"), N(" \u2014 Token hierarchy reference.")]));
ch.push(bul([L("Material Adaptive Layouts","https://developer.android.com/develop/ui/compose/layouts/adaptive"), N(" \u2014 Window size classes, pane patterns.")]));
ch.push(bul([L("Compose Adaptive: Window Size Classes","https://developer.android.com/develop/ui/compose/layouts/adaptive/use-window-size-classes"), N(" \u2014 Official adaptive sizing guidance.")]));
ch.push(bul([L("Compose Adaptive: Supporting Pane Layout","https://developer.android.com/develop/ui/compose/layouts/adaptive/build-a-supporting-pane-layout"), N(" \u2014 Canonical supporting-pane pattern.")]));
ch.push(bul([L("Compose Adaptive: Flexbox","https://developer.android.com/develop/ui/compose/layouts/adaptive/flexbox"), N(" \u2014 FlowRow/FlowColumn adaptive wrapping reference.")]));
ch.push(bul([L("Compose Adaptive: Grid","https://developer.android.com/develop/ui/compose/layouts/adaptive/grid"), N(" \u2014 Adaptive grid guidance for repeated content.")]));
ch.push(bul([L("Atomic Design (Brad Frost)","https://bradfrost.com/blog/post/atomic-web-design/"), N(" \u2014 Atoms \u2192 molecules \u2192 organisms methodology.")]));
ch.push(bul([L("Design Tokens W3C","https://www.w3.org/community/design-tokens/"), N(" \u2014 Token format standard.")]));
ch.push(bul([L("Neumorphism.io","https://neumorphism.io/"), N(" \u2014 NeoMorphism visual reference.")]));

ch.push(h2("13.3 Compose Multiplatform"));
ch.push(bul([L("Compose Multiplatform","https://www.jetbrains.com/compose-multiplatform/"), N(" \u2014 Official docs.")]));
ch.push(bul([L("Skia Shaders (AGSL)","https://developer.android.com/develop/ui/views/graphics/agsl"), N(" \u2014 GPU shading language.")]));

ch.push(h2("13.4 React & Reconciler"));
ch.push(bul([L("react-reconciler","https://github.com/facebook/react/tree/main/packages/react-reconciler"), N(" \u2014 Custom renderer API.")]));
ch.push(bul([L("React Strict DOM Docs","https://facebook.github.io/react-strict-dom/learn/"), N(" \u2014 Web APIs for web and native through html.* and css.create.")]));
ch.push(bul([L("React Strict DOM Repository","https://github.com/facebook/react-strict-dom"), N(" \u2014 Official source repository.")]));
ch.push(bul([L("Hermes Engine","https://hermesengine.dev/"), N(" \u2014 Embedded JS engine.")]));

ch.push(h2("13.5 Industry Comparisons"));
ch.push(bul([L("Radix UI","https://www.radix-ui.com/"), N(" \u2014 Headless component pattern. Contrast: Tactile is opinionated + skinnable.")]));
ch.push(bul([L("Shopify Polaris","https://polaris.shopify.com/"), N(" \u2014 Token-driven, single skin. Contrast: Tactile supports multiple skins.")]));
ch.push(bul([L("Tambo (Anthropic)","https://github.com/anthropics/tambo"), N(" \u2014 AI generative UI. Schemas-as-tools pattern.")]));

ch.push(h2("13.6 Capability, Environment & Adaptive Runtime"));
ch.push(bul([L("Android Performance Class","https://developer.android.com/topic/performance/performance-class"), N(" \u2014 Coarse Android device capability tiering model.")]));
ch.push(bul([L("Android Network State","https://developer.android.com/develop/connectivity/network-ops/reading-network-state"), N(" \u2014 ConnectivityManager and NetworkCapabilities model.")]));
ch.push(bul([L("MDN Navigator.connection","https://developer.mozilla.org/en-US/docs/Web/API/Navigator/connection"), N(" \u2014 Limited-availability web network hints.")]));
ch.push(bul([L("MDN Navigator.hardwareConcurrency","https://developer.mozilla.org/en-US/docs/Web/API/Navigator/hardwareConcurrency"), N(" \u2014 Approximate logical processor hint.")]));
ch.push(bul([L("MDN Navigator.deviceMemory","https://developer.mozilla.org/en-US/docs/Web/API/Navigator/deviceMemory"), N(" \u2014 Approximate memory hint where available.")]));
ch.push(bul([L("MDN Navigator.onLine","https://developer.mozilla.org/en-US/docs/Web/API/Navigator/onLine"), N(" \u2014 Broad online/offline hint, not a quality measurement.")]));
ch.push(bul([L("MDN Navigator.platform","https://developer.mozilla.org/en-US/docs/Web/API/Navigator/platform"), N(" \u2014 Platform strings are brittle; prefer feature detection.")]));
ch.push(bul([L("MDN MediaCapabilities","https://developer.mozilla.org/en-US/docs/Web/API/Navigator/mediaCapabilities"), N(" \u2014 Media suitability and codec capability hints.")]));
ch.push(bul([L("Apple ProcessInfo Low Power Mode","https://developer.apple.com/documentation/foundation/processinfo/islowpowermodeenabled"), N(" \u2014 iOS/macOS power-mode signal.")]));
ch.push(bul([L("Apple NWPathMonitor","https://developer.apple.com/documentation/network/nwpathmonitor"), N(" \u2014 Network path observation on Apple platforms.")]));

pb();

// ═══════════════════════════════════════════════════════════════════════════════
// 14. CHECKLISTS, GLOSSARY, DECISION RECORDS
// ═══════════════════════════════════════════════════════════════════════════════
ch.push(h1("14. Implementation Checklists, Glossary & Decision Records"));
ch.push(sp(80));

ch.push(h2("14.1 Programmer Checklist: Building a New Atom"));
ch.push(num("Name the component and decide whether it is an atom, molecule, organism, template, or product-only wrapper."));
ch.push(num("Choose the interaction machine. If none fits, add a reusable machine before writing the component."));
ch.push(num("Define public props with defaults and map them to ComponentVariant, ComponentSize, ComponentState where possible."));
ch.push(num("Define accessibility semantics and keyboard behavior before visual styling."));
ch.push(num("Add visual spec fields only when the existing specs cannot express the component."));
ch.push(num("Implement the Compose component by reading InteractionSnapshot and DesignLanguage, never by hard-coding colors or shadows."));
ch.push(num("Write unit tests for the machine and skin matrix tests for visual specs."));
ch.push(num("Add workbench previews for all official skins and major interaction states."));
ch.push(num("If it is part of the cross-platform API, add host registry and RSD implementation entries."));

ch.push(h2("14.2 Expert Checklist: Reviewing an Implementation"));
ch.push(bul("Does the component separate interaction, visual resolution, and rendering?"));
ch.push(bul("Can the interaction machine be tested without Compose or RSD?"));
ch.push(bul("Can every visual value be explained as DesignTokens -> TactileTokens -> DesignLanguage -> VisualSpec?"));
ch.push(bul("Does reduced motion change high-amplitude effects and field/shader behavior?"));
ch.push(bul("Does keyboard/accessibility focus behave independently from hover and press?"));
ch.push(bul("Does the component preserve stable dimensions across hover/press/focus states?"));
ch.push(bul("Does the adaptive container preserve graph ownership of navigation and route payloads?"));
ch.push(bul("Does the React/RSD implementation expose the same public props and semantic behavior as Compose?"));
ch.push(bul("Can the Reaktor workbench inspect snapshots, visuals, pane policy, and host tree state?"));

ch.push(h2("14.3 Glossary"));
ch.push(tbl(["Term","Definition"],[
  ["InteractionSnapshot","Immutable state describing concurrent interaction channels such as focus, hover, press, drag, enabled, selected, checked, pointer position, and velocity."],
  ["Interaction machine","Reusable policy object that consumes normalized events and emits snapshots plus semantic outcomes like Click, ValueChange, Open, Dismiss."],
  ["DesignLanguage","Pure interface that maps tokens, component props, component state, and InteractionSnapshot to renderer-neutral visual specs."],
  ["Visual spec","Primitive-only data object such as ButtonVisuals or CardVisuals consumed by renderers."],
  ["TactileTokens","Extension of reaktor-ui DesignTokens with material, physics, motion, field, haptic, and skin defaults."],
  ["TactileField","Spatial impulse bus that lets interactions affect neighboring elements and shader backgrounds."],
  ["Pane","Graph-backed adaptive surface with role, route, payload, focus policy, and collapse policy."],
  ["GraphRouteContent","Composable primitive for rendering a specific graph route into a pane instead of only rendering the top of a global back stack."],
  ["Host tree","Internal React reconciler output consumed by the Compose renderer. It is not a product-authored schema."],
  ["ActionRef","Named, schema-validated action that React/server/AI UI can call; resolved by Kotlin graph action registry."],
],[2300,7060]));

ch.push(h2("14.4 Architecture Decision Records"));
ch.push(tbl(["Decision","Status","Rationale"],[
  ["Use InteractionSnapshot instead of sealed InteractionState","Accepted","Concurrent channels preserve information; skins decide visual composition."],
  ["Use DesignLanguage as pure data resolver","Accepted","Enables testing, renderer portability, serialization, and runtime skin switching."],
  ["Build Compose design system first","Accepted","Provides production value without blocking on Hermes, reconciler, RSD, or AI infrastructure."],
  ["Use reaktor-graph, not Compose Navigation","Accepted","Graph already owns app navigation, DI, routes, ports, services, and lifecycle."],
  ["Extend ComposeContainer/ComposeContent","Accepted","Preserves existing recursive rendering and avoids a parallel container hierarchy."],
  ["Avoid static JSON SDUI","Accepted","React already provides conditionals, loops, state, effects, and composition; host tree is internal IR."],
  ["Keep physics native/local","Accepted","Per-frame animation, haptics, and shader work must not cross FFI boundaries."],
  ["Mirror skins in TS for RSD","Accepted with caution","Needed for web/RN parity; generated or heavily tested mirrors should prevent drift."],
],[2600,1500,5260]));

pb();

// ═══════════════════════════════════════════════════════════════════════════════
// 15. REAKTOR CAPABILITY, ENVIRONMENT & STRATEGY RUNTIME
// ═══════════════════════════════════════════════════════════════════════════════
ch.push(h1("15. Reaktor Capability, Environment & Strategy Runtime"));
ch.push(sp(80));
ch.push(p("The next-generation UI runtime cannot scale if every component, service, and feature asks raw questions like: is this Android? how much RAM does this phone have? is the network metered? does this browser expose navigator.connection? That creates brittle codepaths and privacy-hostile fingerprints. The missing platform layer is reaktor-capability: a normalized capability and environment runtime that turns platform-specific signals into coarse, stable profiles and strategy tiers."));

ch.push(h2("15.1 Why reaktor-capability Exists"));
ch.push(p("Existing Reaktor already has two important primitives: Feature is a global slot registry, and Capability is a small AutoCloseable interface used by graph lifecycle, concurrency, navigation, and DI capabilities. reaktor-capability should not replace these. It should formalize product-facing and runtime-facing capabilities on top of them."));
ch.push(tbl(["Existing Primitive","Current Reality","New Role in reaktor-capability"],[
  ["Feature","Global dependency slot object using CreateSlot. Modules install adapters such as Auth, Database, Theme, Telemetry, Work, File, Permission.","Keep as installation mechanism, but register typed FeatureContract instances such as EnvironmentFeature, CameraFeature, UploadFeature, MediaFeature."],
  ["Capability","Minimal AutoCloseable marker with AtomicCapability close guard.","Keep as lifecycle primitive, but add richer public capability contracts with availability, tier, strategy, requirements, and degradation reason."],
  ["Adapter","WeakRef-based bridge to platform controllers, currently used by auth, storage, permissions, telemetry, etc.","Continue for privileged platform operations; capability strategies select which adapter behavior/tier is active."],
  ["Graph DI / ports","Graph-scoped dependency and ProviderPort/ConsumerPort wiring.","Use for scoped capability overrides: a graph can consume a skin, policy, action registry, or local capability implementation."],
],[1700,3700,3960]));

ch.push(h2("15.2 Feature, Capability, Environment: Three Different Things"));
ch.push(p("Do not blur these terms. They answer different questions and should have different APIs."));
ch.push(tbl(["Concept","Question It Answers","Examples"],[
  ["Feature","What installed service/adapter exists in this runtime?","Feature.Auth, Feature.Database, Feature.Environment, Feature.Camera"],
  ["Capability","Can this app perform this privileged or expensive action, and at what tier?","Camera preview, location watch, media upload, haptic feedback, shader rendering"],
  ["Environment","What kind of device, execution state, and network state are we currently running under?","PerformanceClass.Medium, ThermalClass.Hot, NetworkQuality.Poor, low power mode"],
  ["Strategy","Which implementation should this capability use under the current environment and requirements?","Full camera preview vs still capture only; parallel upload vs queued upload"],
  ["Policy","What does the product/runtime allow under this environment?","Disable shaders under low power; queue uploads on expensive network; reduce motion for accessibility"],
],[1700,3100,4560]));

ch.push(h2("15.3 Public API Shape"));
ch.push(p("The public API should expose normalized profiles and capability tiers. Raw OS signals stay internal to collectors."));
ch.push(...code([
  "interface FeatureContract : AutoCloseable {",
  "    val id: String",
  "}",
  "",
  "interface EnvironmentFeature : FeatureContract {",
  "    val current: StateFlow<EnvironmentSnapshot>",
  "    suspend fun refresh(): EnvironmentSnapshot",
  "}",
  "",
  "interface RuntimeCapability : FeatureContract {",
  "    val availability: StateFlow<CapabilityAvailability>",
  "    val tier: StateFlow<CapabilityTier>",
  "    suspend fun refresh(): CapabilityAvailability",
  "}",
  "",
  "data class CapabilityAvailability(",
  "    val available: Boolean,",
  "    val tier: CapabilityTier = CapabilityTier.Unavailable,",
  "    val reason: DegradationReason? = null,",
  "    val requiredPermission: PermissionRef? = null,",
  ")",
  "",
  "enum class CapabilityTier { Unavailable, Fallback, Basic, Standard, Full }",
]));

ch.push(h2("15.4 EnvironmentFeature"));
ch.push(p("EnvironmentFeature is not a privileged action feature like camera or location. It is a profiling and observation feature. It should be installed on every supported platform, including web and desktop, even if some signals are missing."));
ch.push(...code([
  "data class EnvironmentSnapshot(",
  "    val device: DeviceProfile,",
  "    val execution: ExecutionProfile,",
  "    val network: NetworkProfile,",
  ")",
  "",
  "data class DeviceProfile(",
  "    val platform: PlatformFamily,",
  "    val formFactor: FormFactor,",
  "    val performanceClass: PerformanceClass = PerformanceClass.Unknown,",
  "    val cpuClass: CpuClass = CpuClass.Unknown,",
  "    val memoryClass: MemoryClass = MemoryClass.Unknown,",
  "    val touchClass: TouchClass = TouchClass.Unknown,",
  "    val mediaClass: MediaClass = MediaClass.Unknown,",
  ")",
  "",
  "data class ExecutionProfile(",
  "    val powerMode: PowerMode = PowerMode.Normal,",
  "    val thermalClass: ThermalClass = ThermalClass.Normal,",
  "    val appState: AppState = AppState.Foreground,",
  ")",
  "",
  "data class NetworkProfile(",
  "    val connectivity: ConnectivityState,",
  "    val transport: Set<NetworkTransport>,",
  "    val cost: NetworkCost = NetworkCost.Unknown,",
  "    val estimatedQuality: NetworkQuality = NetworkQuality.Unknown,",
  "    val downlinkMbpsHint: Double? = null,",
  "    val rttMsHint: Double? = null,",
  ")",
]));

ch.push(h2("15.5 Normalized Classes, Not Raw Device Trivia"));
ch.push(p("The main public contract must be normalized buckets. Raw facts such as model strings, exact CPU names, user agent platform strings, and vendor identifiers should not drive product logic. Android has coarse performance classes; web APIs intentionally expose approximate or limited hints; platform strings are brittle. Reaktor should model what capabilities need to know, not what the OS happens to reveal."));
ch.push(...code([
  "enum class PerformanceClass { Unknown, Low, Medium, High, Premium }",
  "enum class MemoryClass { Unknown, Tight, Normal, Large }",
  "enum class CpuClass { Unknown, Small, Moderate, Strong }",
  "enum class NetworkQuality { Unknown, Offline, Poor, Moderate, Good, Excellent }",
  "enum class NetworkCost { Unknown, Unmetered, Metered, Expensive, Constrained }",
  "enum class ThermalClass { Normal, Warm, Hot, Critical }",
  "enum class PowerMode { Normal, LowPower, BatterySaver, Performance }",
  "enum class FormFactor { Phone, Tablet, Desktop, Foldable, TV, Watch, Unknown }",
  "enum class TouchClass { None, CoarseTouch, FinePointer, Hybrid, Unknown }",
]));
ch.push(warnBox("Privacy Rule","No public API should expose raw model strings, vendor identifiers, user agent platform strings, or raw hardware scores as business-logic switches. Convert them into coarse normalized classes, then discard or keep raw signals only in private debug logs with explicit privacy policy."));

ch.push(h2("15.6 Internal Engine Architecture"));
ch.push(p("The environment engine composes platform collectors, observed app telemetry, and a common normalizer. Collectors are platform-specific; normalization policy is common code."));
ch.push(...code([
  "interface EnvironmentEngine {",
  "    val snapshot: StateFlow<EnvironmentSnapshot>",
  "    suspend fun refresh(): EnvironmentSnapshot",
  "}",
  "",
  "interface DeviceSignalCollector {",
  "    suspend fun collectDeviceSignals(): RawDeviceSignals",
  "}",
  "",
  "interface ExecutionSignalCollector {",
  "    suspend fun collectExecutionSignals(): RawExecutionSignals",
  "    val updates: Flow<RawExecutionSignals>",
  "}",
  "",
  "interface NetworkSignalCollector {",
  "    suspend fun collectNetworkSignals(): RawNetworkSignals",
  "    val updates: Flow<RawNetworkSignals>",
  "}",
  "",
  "interface NetworkTelemetryCollector {",
  "    fun recordSuccess(rttMs: Double, bytes: Long, durationMs: Long)",
  "    fun recordFailure(errorKind: NetworkFailureKind)",
  "    fun current(): ObservedNetworkTelemetry",
  "}",
  "",
  "interface EnvironmentNormalizer {",
  "    fun normalize(",
  "        device: RawDeviceSignals,",
  "        execution: RawExecutionSignals,",
  "        network: RawNetworkSignals,",
  "        observed: ObservedNetworkTelemetry,",
  "    ): EnvironmentSnapshot",
  "}",
]));

ch.push(h2("15.7 Static, Dynamic and Measured Signals"));
ch.push(tbl(["Signal Class","Examples","Used For"],[
  ["Static / slow-changing","Android Performance Class, form factor, approximate CPU/memory class, touch/pointer class, media capability support","Installation-time or route-start strategy choice: enable high-end shader skin by default, choose image decode strategy, decide default list density."],
  ["Dynamic runtime","Power mode, thermal state, app foreground/background, network transport, metering/constrained network, online/offline","Ongoing adaptation: reduce shader intensity, lower haptic strength, pause expensive background work, switch upload strategy."],
  ["Measured app telemetry","Rolling backend RTT, throughput from real transfers, failure rate, timeout rate, websocket stability","Real quality estimation when platform hints are missing, partial, inaccurate, or stale."],
],[2300,3700,3360]));
ch.push(p("NetworkQuality should be derived from platform hints plus app-observed telemetry with conservative smoothing. A browser that reports online may still have unusable connectivity. A mobile device on Wi-Fi may still have a poor path to the app backend. The runtime should trust recent app-observed behavior more than raw labels."));

ch.push(h2("15.8 Capability Strategy Selection"));
ch.push(p("Capabilities should declare requirements and preferences. The strategy selector compares those requirements against the current EnvironmentSnapshot and returns the active tier and implementation."));
ch.push(...code([
  "data class CapabilityStrategyPolicy(",
  "    val minPerformanceClass: PerformanceClass = PerformanceClass.Unknown,",
  "    val maxThermalClass: ThermalClass = ThermalClass.Critical,",
  "    val minNetworkQuality: NetworkQuality = NetworkQuality.Offline,",
  "    val allowMetered: Boolean = true,",
  "    val allowExpensive: Boolean = true,",
  "    val allowLowPower: Boolean = true,",
  "    val requiresForeground: Boolean = false,",
  ")",
  "",
  "interface CapabilityStrategy<I, O> {",
  "    val id: String",
  "    val tier: CapabilityTier",
  "    val policy: CapabilityStrategyPolicy",
  "    suspend fun execute(input: I): O",
  "}",
  "",
  "interface StrategySelector<I, O> {",
  "    fun select(",
  "        strategies: List<CapabilityStrategy<I, O>>,",
  "        environment: EnvironmentSnapshot,",
  "        requirements: CapabilityRequirements,",
  "    ): CapabilityStrategy<I, O>",
  "}",
]));

ch.push(h2("15.9 UI Runtime Consumption"));
ch.push(p("The UI runtime should consume normalized capability/environment state through a policy object. Components should not read EnvironmentFeature directly except in advanced custom cases. TactileTheme should produce a TactileRuntimePolicy from EnvironmentSnapshot, app settings, and accessibility preferences."));
ch.push(...code([
  "data class TactileRuntimePolicy(",
  "    val motion: MotionPolicy,",
  "    val haptics: HapticPolicy,",
  "    val shaders: ShaderPolicy,",
  "    val media: MediaPolicy,",
  "    val adaptive: AdaptiveLayoutPolicy,",
  "    val generatedUi: GeneratedUiPolicy,",
  "    val performance: PerformanceBudgetPolicy,",
  ")",
  "",
  "fun EnvironmentSnapshot.toTactilePolicy(",
  "    accessibility: AccessibilityPreferences,",
  "    product: ProductUiPolicy,",
  "): TactileRuntimePolicy",
]));
ch.push(tbl(["Environment Change","Policy Response","User-Visible Result"],[
  ["ThermalClass.Hot","ShaderPolicy.disabled, MotionPolicy.clamped","LiquidGlass falls back to translucent surfaces; press animations remain but with lower amplitude."],
  ["PowerMode.LowPower","HapticPolicy.lightOnly, ShaderPolicy.tier2, PerformanceBudgetPolicy.tight","UI remains responsive while expensive tactile effects reduce."],
  ["NetworkQuality.Poor","GeneratedUiPolicy.noRemoteBundles, MediaPolicy.lowResolution, UploadPolicy.serialized","Existing screens keep working; images and remote/generated UI degrade gracefully."],
  ["NetworkCost.Expensive","UploadPolicy.queueLargeMedia, PrefetchPolicy.disabled","BestBuds avoids burning mobile data on media uploads/prefetch."],
  ["PerformanceClass.Low","MotionPolicy.simpleSprings, VirtualizationPolicy.aggressive","Lists and chat remain usable without heavy per-item effects."],
],[2300,2700,4360]));

ch.push(h2("15.10 BestBuds Capability Examples"));
ch.push(tbl(["Feature","Full","Standard","Basic","Fallback"],[
  ["Chat","WebSocket live updates + optimistic send + media previews + background revalidation","WebSocket + optimistic text send + limited media","HTTP polling/revalidation + serialized send","Cached messages + queued sends until network improves"],
  ["Media upload","Parallel multipart upload + thumbnails + retry","Limited concurrency upload + retry","Serialized resumable upload","Queue until unmetered/good network"],
  ["Events/campaign feed","Adaptive grid + image prefetch + live refresh","Adaptive grid + on-demand image loading","Single-column list + no prefetch","Cached feed + retry panel"],
  ["Tactile UI","Full field impulses + haptics + shaders","Springs + light haptics + no heavy shaders","Minimal springs + no haptics","Static visuals, explicit loading states"],
],[1200,2300,2100,1800,1960]));

ch.push(h2("15.11 Reaktor Desktop Capability Examples"));
ch.push(tbl(["Workbench Area","Capability-Aware Behavior"],[
  ["Graph editor","Large graphs use virtualization and reduced edge animation on low CPU/memory; premium devices can render richer live edge flow."],
  ["Preview panel","Component previews can simulate environment profiles: low power, poor network, high contrast, reduced motion, compact phone, large desktop."],
  ["Host tree inspector","Generated React bundles are disabled or sandboxed according to GeneratedUiPolicy and environment state."],
  ["Telemetry panel","NetworkTelemetryCollector and frame budget traces surface real runtime health instead of raw device trivia."],
  ["Capability dashboard","Shows EnvironmentSnapshot, installed features, active strategy tiers, and degradation reasons for the running app."],
],[2500,6860]));

ch.push(h2("15.12 Platform Collector Mapping"));
ch.push(tbl(["Platform","Collector Inputs","Important Caveat"],[
  ["Android","Performance Class, ConnectivityManager/NetworkCapabilities, power/thermal APIs where available, touch/pointer/media feature detection","Prefer coarse classes and feature detection. Do not branch on raw model strings."],
  ["iOS / macOS","ProcessInfo low power and thermal state, NWPathMonitor, UIDevice/form-factor class, media support","Use device identity only to derive coarse buckets; avoid fingerprint-style public contracts."],
  ["Web","navigator.onLine, navigator.connection where available, hardwareConcurrency, deviceMemory, maxTouchPoints, mediaCapabilities","Every signal is partial, approximate, or limited by browser support. Feature detection and observed telemetry matter more."],
  ["JVM Desktop","OS family, memory budget, CPU core count, window size, network telemetry, optional platform-specific power signals","Treat desktop as variable: laptops can be thermally constrained; servers can run headless."],
  ["Cloudflare / Edge","Request metadata, colo/region, service binding health, durable object/queue telemetry","No device UI, but strategy selection still matters for generated UI, bundle serving, and network quality."],
],[1600,5000,2760]));

ch.push(h2("15.13 Module Placement"));
ch.push(p("reaktor-capability should be a foundational module between reaktor-core and higher platform modules. It should not depend on reaktor-ui or reaktor-graph. Graph and UI consume it; capability itself stays product-neutral."));
ch.push(...code([
  "reaktor-core",
  "    \u2514\u2500 reaktor-capability",
  "         \u251c\u2500 commonMain: contracts, normalized profiles, normalizer, strategy selection",
  "         \u251c\u2500 androidMain: Android collectors",
  "         \u251c\u2500 iosMain/darwinMain: Apple collectors",
  "         \u251c\u2500 jsMain: web collectors",
  "         \u2514\u2500 jvmMain: desktop/server collectors",
  "",
  "reaktor-media, reaktor-location, reaktor-work, reaktor-notification",
  "    \u2514\u2500 consume reaktor-capability for strategy selection",
  "",
  "reaktor-ui / reaktor-tactile",
  "    \u2514\u2500 consume EnvironmentFeature through policy, not raw collectors",
]));

ch.push(h2("15.14 Runtime Loop"));
ch.push(p("The runtime loop is simple and should be documented as the mental model for every capability author."));
ch.push(num("Platform collectors emit raw device, execution, and network signals."));
ch.push(num("NetworkTelemetryCollector records real request/transfer success, latency, throughput, failures, and timeouts."));
ch.push(num("EnvironmentNormalizer converts raw + observed signals into EnvironmentSnapshot with coarse classes."));
ch.push(num("Capability strategy selectors reevaluate availability and tier against the new snapshot."));
ch.push(num("TactileRuntimePolicy is recalculated from environment, accessibility, product policy, and active feature tiers."));
ch.push(num("Compose/RSD/reconciler renderers react to policy changes with graceful degradation, not crashes or hidden behavior changes."));
ch.push(num("Workbench and telemetry record why a tier changed, so developers can explain runtime behavior."));

pb();

// ═══════════════════════════════════════════════════════════════════════════════
// 16. REAKTOR + BESTBUDS CURRENT STATE ANALYSIS
// ═══════════════════════════════════════════════════════════════════════════════
ch.push(h1("16. Reaktor + BestBuds Current State Analysis"));
ch.push(sp(80));
ch.push(p("This section grounds the architecture in the current repositories. The goal is not to migrate code here; the goal is to identify what already exists, what should be reused, and what must be added so the next-generation UI runtime can power BestBuds first and Reaktor Desktop alongside it."));

ch.push(h2("16.1 Reaktor: What Already Exists"));
ch.push(tbl(["Subsystem","Current State","Runtime Opportunity"],[
  ["reaktor-core Feature","Simple global CreateSlot registry installed by modules such as Auth, Database, Theme, Work, Telemetry, Analytics, Permission, File, Storage.","Keep the install point. Add typed FeatureContract and EnvironmentFeature without breaking existing slots."],
  ["reaktor-core Capability","Minimal AutoCloseable / AtomicCapability primitive used by lifecycle-like capabilities.","Good base for lifecycle cleanup, but product-facing capabilities need availability, tier, policy, and strategy selection."],
  ["reaktor-graph-port","Typed ProviderPort/ConsumerPort, keys, edges, and auto-wiring substrate.","Ideal for graph-scoped UI services: DesignLanguage, ActionRegistry, TactileField, EnvironmentFeature override."],
  ["reaktor-graph","Graph, Node, RouteNode, ContainerNode, navigation commands, scoped DI, lifecycle, concurrency, GraphContent recursive renderer.","This is the navigation/runtime spine. Tactile should extend ComposeContainer/ComposeContent instead of replacing them."],
  ["reaktor-ui","Old Theme wrapper plus newer DesignTokens and ComponentSpec experiments. Current ComponentSpec still imports Compose types like Color/ImageVector/Dp.","Use as migration seed. Tactile should make renderer-neutral visual specs primitive-only."],
  ["reaktor-tactile","Included module, build wired, but README says brainstorming placeholder with no source files.","This is the correct module to become the next-gen UI runtime, but it must be given real contracts."],
  ["reaktor-ffi / flexbuffer","Hermes/C++/FlexBuffer proof paths exist; DevScreen can call nativeHermesHello and nativeFlexPreview.","Useful for future React reconciler and host tree, but should not block the pure Compose Tactile runtime."],
  ["reaktor-telemetry","GraphTelemetry and analytics/crash adapters exist.","Extend to trace interactions, visual spec resolution, capability tier changes, host tree commits, and adaptive pane decisions."],
  ["reaktor-work","TaskManager exists and DevScreen can test background work.","Should consume EnvironmentFeature for battery/network/foreground-aware work strategy."],
],[2100,3900,3360]));

ch.push(h2("16.2 Reaktor Graph Rendering Reality"));
ch.push(p("GraphContent currently renders the top entry of a graph back stack. If the attached node is ComposeContainer, it calls node.Content with a renderer lambda; if it is ComposeContent, it calls node.Content directly. This is already the right recursive architecture for nested containers."));
ch.push(tbl(["Current Graph UI Primitive","Behavior","Tactile Extension Needed"],[
  ["GraphApplication","Wraps GraphContent in themed + MaterialTheme + Scaffold.","Add TactileGraphApplication that wraps TactileTheme, EnvironmentFeature policy, and capability providers before GraphContent."],
  ["GraphContent","Observes graph.backStack.entries and renders the top route's attached node.","Add GraphRouteContent for rendering a specific route into a pane, and optionally TactileContent handling for host trees."],
  ["ComposeContainer","Container interface receives renderer(childGraph, focused).","AdaptiveNavigationContainer and AdaptivePaneContainer should implement this directly."],
  ["BottomNavigationContainer","Phone-centric Scaffold with bottom bar, topBar, keyboard hiding, active child graph by selected key.","Replace or subclass with AdaptiveNavigationContainer that switches bar/rail/drawer and preserves selected graph behavior."],
  ["TabbedContainer","Nested graph tabs for events private/public.","Keep as a specialized container; Tactile TabRow can improve visuals/semantics."],
  ["ContainerNode","Holds child graphs and activates graph for route based on contained RouteNode.","Use as base for adaptive pane containers. Do not create a separate Tactile navigation model."],
],[2300,3600,3460]));

ch.push(h2("16.3 Reaktor UI Reality"));
ch.push(p("reaktor-ui currently has two overlapping design-system tracks. The older Theme class wraps Material3 components through extension functions. The newer DesignTokens and ComponentSpec files move toward a real token/spec system, but some specs still include Compose platform types. Tactile should be the convergence layer, not a third unrelated style system."));
ch.push(tbl(["Current Asset","Keep","Change"],[
  ["Theme / Feature.Theme","Useful compatibility wrapper and current BestBuds integration point.","Treat as legacy compatibility. New code should use TactileTheme and DesignLanguage."],
  ["DesignTokens","Good starting token hierarchy: palette, semantic colors, typography, spacing, shape, elevation, sizing, breakpoints, motion.","Move any Tactile-specific physics/environment policy into TactileTokens; preserve base tokens."],
  ["ComponentSpec","Useful concept for variants/sizes/states.","Remove Compose types from shared spec layer or keep them only in Compose-specific APIs."],
  ["R-prefixed components","Migration bridge for existing screens.","Do not extend them into the next-gen runtime. Build T-prefixed components with interaction machines and DesignLanguage."],
  ["Responsive utilities","Useful for simple responsive layout.","Adaptive pane runtime should use graph-aware containers and official adaptive APIs; simple responsive utilities can stay for local layout."],
],[2400,3200,3760]));

ch.push(h2("16.4 BestBuds Application Graph"));
ch.push(p("BestBuds is already a meaningful flagship target. The root graph provides data/service nodes, then composes feature graphs under a /home container. The current graph has the exact structure needed to prove adaptive UI because it contains nested routes, cross-graph edges, data nodes, and product-specific components."));
ch.push(tbl(["Graph / Route","Current Purpose","Tactile Runtime Opportunity"],[
  ["Root BestBuds graph","Provides MessageRepository, UserRepository, ChatInteractor, SocialRepository, UserInteractor; defines onboarding and start routes.","Install app-level DesignLanguage, EnvironmentFeature, ActionRegistry, TactileField, and telemetry providers here."],
  ["ChatGraph /chats","Chat list root route.","Primary list pane for ListDetailPane."],
  ["ChatGraph /chats/{id}","Active chat detail route.","Detail pane on tablet/desktop, pushed route on phone."],
  ["Friend/Group profile routes","Contextual profile screens from chat.","Supporting/extra pane on expanded layouts; pushed route on compact."],
  ["ProfileGraph","User profile/create/edit routes.","Good form-system and profile-card migration target."],
  ["CampaignGraph / DiscoverGraph","Campaign/current and discover routes.","Adaptive grid/list surfaces; network/media policy proof target."],
  ["EventGraph","Tabbed private/public events under /events.","Tactile tabs + adaptive event grid; public/private as pane/tab policy."],
  ["FriendGraph","Friends list/profile route linked to chat route.","Cross-graph action/intent test: friend profile can open chat through typed graph action."],
  ["DevGraph","Dev tools route testing FFI, FlexBuffer, Work, Analytics, Crashlytics.","First place to expose EnvironmentSnapshot, capability tiers, policy, and host-tree diagnostics."],
],[2300,3000,4060]));

ch.push(h2("16.5 BestBuds UI Surface"));
ch.push(tbl(["Surface","Current Implementation","Next Runtime Mapping"],[
  ["Start / onboarding","Compose screens with auth and onboarding flow.","TAuthTemplate, TForm, TButton, TAsyncContent, ActionRef-based graph transitions."],
  ["Chat list","ChatInteractor.fetchChats, message grouping, Compose list screen.","TVirtualList or TChatList, loading/error/empty states, network-aware refresh, list pane."],
  ["Chat detail","Cache-first openChat, WebSocket PartySocket, optimistic messages, pagination older messages.","TChatThread, TVirtualList, optimistic state contract, WebSocket capability tier, supporting profile pane."],
  ["Campaign / discover","Search, cards, campaign components, likely feed/grid patterns.","TAdaptiveGrid, TSearchBar, TCampaignCard product organism, media policy."],
  ["Events","EventCard/EventRow, TabLayout, public/private tabs.","TTabRow, TEventCard organism, flex/grid layout, event type segmented control."],
  ["Friends/profile","FriendProfile, GroupProfile, Profile, Create/Edit profile screens.","TProfileHeader, TForm, supporting pane, action registry for open chat/friend actions."],
  ["Dev tools","Buttons for native Hermes/FlexBuffer/Work/Analytics/Crashlytics tests.","Capability dashboard, environment panel, graph action log, host tree inspector."],
],[2200,3800,3360]));

ch.push(h2("16.6 BestBuds Design Surface"));
ch.push(p("BestBuds design already contains reusable components, but they are product-level Compose components. They are valuable migration references; they should not be copied wholesale into the generic runtime without extracting their interaction, visual, and semantic contracts."));
ch.push(tbl(["BestBuds Component","Generic Runtime Extraction","Product-Specific Remainder"],[
  ["Card2","TCard visual/state behavior, border/radius/elevation visual specs.","BestBudsSkin card defaults and product card spacing."],
  ["StatusChip","TBadge/TChip with semantic color roles.","BestBuds relation labels and campaign/event meaning."],
  ["BBText","TText roles and typography mapping.","BestBuds copy conventions."],
  ["GradientButton / JoinButton / ActionButton","TButton variants, icon policy, press/haptic behavior.","Brand gradient, default icon choices, product analytics tags."],
  ["SearchBar","TSearchBar interaction, focus, clear, submit, semantics.","Search domain behavior and placeholder text."],
  ["EventRow / EventCard","TCard + TAvatar + TText + TButton composition pattern.","Event domain data, relation label, invited-count logic."],
  ["TabLayout","TTabRow and pager/tab machine.","Event-specific private/public page composition."],
  ["BBTextField / SegmentedSwitch","TInput and TSegmentedControl machines/visuals.","Product form labels and validation copy."],
],[2400,3400,3560]));

ch.push(h2("16.7 BestBuds Data and Capability Pressure Points"));
ch.push(tbl(["Pressure Point","Current Code Signal","Capability/Runtime Need"],[
  ["Realtime chat","ChatInteractor owns PartySocket, connection job, optimistic messages, parsing failures.","NetworkQuality and WebSocket capability tier should decide live socket vs polling vs queued send."],
  ["Cache-first chat","MessageRepository uses RepositoryNode write-through flow and cache updates.","TAsyncContent and loading/error/empty patterns should recognize cached stale data vs fresh data."],
  ["Media-heavy surfaces","Event cards and profiles use AsyncImage and remote avatars.","MediaPolicy should choose image resolution, prefetch, and placeholder strategy based on network/cost/performance."],
  ["Dev-only features","DevScreen gated by user.canAccessDevTools and tests Work/Telemetry/FFI.","Add capability dashboard here first without affecting normal users."],
  ["Adaptive navigation","BottomNavigationContainer has overflow via topBar buttons for Friends/Profile/Dev.","AdaptiveNavigationContainer should surface overflow as rail/drawer destinations on larger widths."],
  ["Parameterized route preview","Workbench preview currently refuses parameterized routes.","Route fixtures or sample payload registry needed for screen previews, AI examples, and generated UI tests."],
],[2600,3600,3160]));

ch.push(h2("16.8 Reaktor Desktop Workbench Opportunity"));
ch.push(p("The Reaktor Desktop app is not a side project. It is the UI runtime's editor, inspector, and proof surface. Current code already has Graph/Run App modes, app switching, graph document generation, node inspection, and screen preview. Tactile should turn it into a Blueprint-style control plane."));
ch.push(tbl(["Existing Workbench Capability","Next Tactile/Capability Panel"],[
  ["Graph mode renders app graph through composeflow / reaktor-flow","Overlay adaptive pane boundaries, active route state, selected graph, capability providers, and data/service edges."],
  ["Run App mode renders GraphApplication","Add TactileGraphApplication and environment simulation toggles."],
  ["Inspect/Preview/My Graph tabs","Add Snapshot, Visual Spec, Environment, Capability, Host Tree, Accessibility, Performance tabs."],
  ["ReaktorGraphDocument emits nodes/edges JSON","Extend manifest with semantic nodes, component capabilities, action refs, pane roles, and fixture payloads."],
  ["PreviewPanel renders ComposeContent and skips dynamic routes","Add route fixture registry so parameterized screens can be previewed safely."],
  ["Workbench tokens already separate host chrome styling","Migrate chrome to DesignLanguage or a dedicated ReaktorWorkbenchSkin for consistency."],
],[3000,6360]));

ch.push(h2("16.9 Current Gaps to Close Before Migration"));
ch.push(num("Create reaktor-capability with EnvironmentFeature, normalized profiles, observed network telemetry, and strategy selection."));
ch.push(num("Give reaktor-tactile real commonMain source contracts before building many components."));
ch.push(num("Define SemanticNode and ComponentCapabilityRegistry before enabling AI/server-generated UI."));
ch.push(num("Add GraphRouteContent and route fixture registry so adaptive panes and workbench previews can render specific routes."));
ch.push(num("Converge Theme/DesignTokens/ComponentSpec into TactileTheme, DesignLanguage, VisualSpec, and T-prefixed components."));
ch.push(num("Build BestBudsSkin from existing BestBuds design tokens and components, then migrate a small proof surface."));
ch.push(num("Instrument workbench with environment/capability panels early so runtime decisions remain explainable."));

pb();

// ═══════════════════════════════════════════════════════════════════════════════
// 17. NEXT-GENERATION UI RUNTIME CONTRACTS
// ═══════════════════════════════════════════════════════════════════════════════
ch.push(h1("17. Next-Generation UI Runtime Contracts"));
ch.push(sp(80));
ch.push(p("A normal design system gives you components, tokens, and themes. A next-generation UI runtime needs stronger contracts: semantics, capabilities, layout constraints, validation, toolability, versioning, performance budgets, and policy-driven degradation. This section defines the missing spine that lets Tactile scale across BestBuds, Reaktor Desktop, React/Hermes, RSD, AI-generated UI, and future apps."));

ch.push(h2("17.1 Semantic UI Contract"));
ch.push(p("Components and host nodes should publish semantic meaning in addition to props and visuals. A button is not just a rectangle with onPress; it is a semantic role with action intent, accessibility requirements, layout behavior, telemetry, and safety constraints."));
ch.push(...code([
  "data class SemanticNode(",
  "    val role: SemanticRole,",
  "    val intent: SemanticIntent? = null,",
  "    val state: SemanticState = SemanticState(),",
  "    val actions: List<SemanticAction> = emptyList(),",
  "    val accessibility: AccessibilityContract,",
  "    val layout: LayoutContract? = null,",
  "    val telemetry: TelemetryContract? = null,",
  ")",
  "",
  "enum class SemanticRole {",
  "    Button, Link, Text, Image, TextField, Checkbox, Switch, Radio,",
  "    Slider, Tab, TabPanel, Menu, MenuItem, Dialog, Sheet, List,",
  "    ListItem, Grid, Card, Navigation, Pane, Form, Section, Status",
  "}",
]));
ch.push(p("SemanticNode should be emitted by Compose components, RSD components, and host tree renderers. It enables accessibility validation, AI guardrails, workbench inspection, telemetry, and adaptive layout decisions."));

ch.push(h2("17.2 Component Capability Registry"));
ch.push(p("The registry is the source of truth for every reusable UI component. It should generate docs, AI manifests, host tree validation, RSD parity tests, accessibility matrices, and workbench previews."));
ch.push(...code([
  "data class ComponentCapability(",
  "    val id: String,                         // reaktor.tactile.Button@1",
  "    val atomicLevel: AtomicLevel,",
  "    val props: PropSchema,",
  "    val events: EventSchema,",
  "    val allowedChildren: ChildPolicy,",
  "    val accessibility: AccessibilityContract,",
  "    val layout: LayoutContract,",
  "    val adaptive: AdaptiveBehaviorContract,",
  "    val rendererSupport: RendererSupportMatrix,",
  "    val ai: AIGenerationRules,",
  "    val examples: List<ComponentExample>,",
  ")",
  "",
  "enum class AtomicLevel { Atom, Molecule, Organism, Template, Page }",
]));
ch.push(tbl(["Registry Consumer","What It Gets"],[
  ["Compose runtime","Typed metadata for previews, semantics, test generation, and host-node rendering."],
  ["RSD runtime","Same prop/event/semantic contract to prevent web/native drift."],
  ["React reconciler","Host component validation and version compatibility."],
  ["AI tool manifest","Allowed components, props, children, examples, and action constraints."],
  ["Server bundle validator","Static validation before signed bundles are shipped."],
  ["Workbench","Catalogue, preview matrix, visual spec inspector, semantic tree, accessibility audit."],
  ["Docs site","Generated component reference pages and migration notes."],
],[2500,6860]));

ch.push(h2("17.3 Action and Intent System"));
ch.push(p("Generated UI, RSD, and Compose components should all call typed actions rather than reaching into graph internals. Actions are the bridge from UI intent to graph/domain effects."));
ch.push(...code([
  "data class SemanticAction(",
  "    val id: String,                 // bestbuds.chat.open",
  "    val label: String,",
  "    val payloadSchema: SchemaId,",
  "    val permission: PermissionRequirement? = null,",
  "    val environmentPolicy: CapabilityStrategyPolicy? = null,",
  ")",
  "",
  "sealed class ActionResult {",
  "    data object Accepted : ActionResult()",
  "    data class Rejected(val reason: String) : ActionResult()",
  "    data class Failed(val error: UiError) : ActionResult()",
  "    data class Deferred(val reason: DegradationReason) : ActionResult()",
  "}",
]));
ch.push(tbl(["Action Example","Graph/Domain Effect"],[
  ["bestbuds.chat.open","Dispatch Push(chatEdge, ChatPayload(chatId)); focus detail pane on expanded layouts."],
  ["bestbuds.message.send","If network tier is Full/Standard send immediately; if Basic/Fallback queue optimistic message."],
  ["bestbuds.profile.open","Open supporting profile pane on expanded layouts; push profile route on compact."],
  ["reaktor.workbench.inspectNode","Select graph node and populate inspector, semantic tree, visual spec, and ports panels."],
  ["reaktor.skin.switch","Update graph-scoped DesignLanguage provider and re-render previews without recreating the graph."],
],[2800,6560]));

ch.push(h2("17.4 Layout Constraint Model"));
ch.push(p("Adaptive layout cannot scale if every container guesses child size. Components should publish layout constraints and adaptive behavior so panes, grids, AI-generated UI, and server bundles can be validated before rendering."));
ch.push(...code([
  "data class LayoutContract(",
  "    val minWidthDp: Int? = null,",
  "    val preferredWidthDp: Int? = null,",
  "    val maxWidthDp: Int? = null,",
  "    val minHeightDp: Int? = null,",
  "    val preferredHeightDp: Int? = null,",
  "    val aspectRatio: Float? = null,",
  "    val canCollapse: Boolean = true,",
  "    val canScroll: Boolean = false,",
  "    val density: ContentDensity = ContentDensity.Comfortable,",
  "    val priority: LayoutPriority = LayoutPriority.Normal,",
  ")",
  "",
  "enum class LayoutPriority { Low, Normal, High, Critical }",
  "enum class ContentDensity { Compact, Comfortable, Spacious }",
]));
ch.push(p("For example, a chat detail pane might declare preferredWidthDp=560 and priority=High, while a profile inspector declares preferredWidthDp=360 and canCollapse=true. The adaptive container then has enough information to make deterministic pane decisions."));

ch.push(h2("17.5 Accessibility as a Gate"));
ch.push(p("Accessibility should not be a best-effort pass after UI is built. It should be a validation gate for every component, host node, AI-generated bundle, and adaptive pane layout."));
ch.push(...code([
  "data class AccessibilityContract(",
  "    val role: SemanticRole,",
  "    val labelRequired: Boolean,",
  "    val stateDescriptionRequired: Boolean = false,",
  "    val keyboardBindings: List<KeyBinding> = emptyList(),",
  "    val minTouchTargetDp: Int = 48,",
  "    val supportsFocus: Boolean = true,",
  "    val supportsScreenReaderAction: Boolean = true,",
  ")",
]));
ch.push(tbl(["Validation","Failure Example"],[
  ["Role validation","Icon-only button without label."],
  ["Keyboard validation","Slider without arrow-key behavior."],
  ["Focus validation","Dialog without focus trap or dismiss path."],
  ["Contrast validation","Skin returns unreadable content color on enabled surface."],
  ["Touch target validation","Interactive chip below minimum target size."],
  ["Pane validation","Expanded layout traps focus in hidden/collapsed pane."],
  ["Generated UI validation","AI emits unlabeled action button or unsupported nesting."],
],[2300,7060]));

ch.push(h2("17.6 Forms as a Runtime Domain"));
ch.push(p("Forms are where most SDUI systems accidentally invent a broken programming language. Tactile should provide a real form runtime early: field state, validation, dirty tracking, async submit, optimistic result, and graph action integration."));
ch.push(...code([
  "interface FormController<T> {",
  "    val value: StateFlow<T>",
  "    val errors: StateFlow<Map<FieldPath, ValidationError>>",
  "    val dirtyFields: StateFlow<Set<FieldPath>>",
  "    val submitting: StateFlow<Boolean>",
  "    fun update(path: FieldPath, value: TactileValue)",
  "    fun validate(): ValidationResult",
  "    suspend fun submit(): SubmitResult",
  "}",
  "",
  "sealed class SubmitResult {",
  "    data object Success : SubmitResult()",
  "    data class ValidationFailed(val errors: Map<FieldPath, ValidationError>) : SubmitResult()",
  "    data class Deferred(val reason: DegradationReason) : SubmitResult()",
  "    data class Failure(val error: UiError) : SubmitResult()",
  "}",
]));
ch.push(tbl(["Form Surface","BestBuds Use"],[
  ["TForm / TFormField","Profile create/edit, onboarding questions, event creation."],
  ["TSelect / TCombobox","Friend/group selection, event visibility, campaign filters."],
  ["TDatePicker / TTimePicker","Event creation."],
  ["TFileInput / TMediaPicker","Profile image, event image, chat media upload."],
  ["TSubmitButton","Environment-aware submit: immediate, queued, disabled, or deferred."],
],[2400,6960]));

ch.push(h2("17.7 Data-Aware UI and Virtualization"));
ch.push(p("The runtime needs first-class primitives for loading, empty, error, cached, stale, retrying, and optimistic states. BestBuds chat and feeds already need this; generated UI will need it even more."));
ch.push(...code([
  "sealed class Loadable<out T> {",
  "    data object Idle : Loadable<Nothing>()",
  "    data object Loading : Loadable<Nothing>()",
  "    data class Data<T>(",
  "        val value: T,",
  "        val freshness: DataFreshness = DataFreshness.Fresh,",
  "    ) : Loadable<T>()",
  "    data class Empty(val reason: EmptyReason) : Loadable<Nothing>()",
  "    data class Error(val error: UiError, val cached: TactileValue? = null) : Loadable<Nothing>()",
  "}",
  "",
  "enum class DataFreshness { Fresh, Cached, Stale, Optimistic }",
]));
ch.push(tbl(["Primitive","Purpose"],[
  ["TAsyncContent","Single wrapper for loading/data/empty/error/retry/cached stale content."],
  ["TVirtualList","Chat history, feeds, search results, friend lists; stable keys and scroll restoration."],
  ["TVirtualGrid","Events, campaigns, dashboard cards, media galleries."],
  ["TDataTable","Reaktor desktop diagnostics, future admin/ops dashboards."],
  ["TTreeView","Workbench graph tree and component/host tree inspection."],
  ["TTimeline","Chat events, activity logs, deployment/workflow traces."],
  ["TInfiniteFeed","Discover/campaign/event feeds with prefetch and offline-aware pagination."],
],[2300,7060]));

ch.push(h2("17.8 Generated UI Validation Pipeline"));
ch.push(p("Server-driven and AI-generated React should be powerful, but only inside a validation envelope. The validation pipeline should run before signing bundles and again on the client before execution when feasible."));
ch.push(num("Validate imports: only approved packages and @reaktor/tactile entry points."));
ch.push(num("Validate component registry: every host component exists at a supported version."));
ch.push(num("Validate prop schemas and child policies."));
ch.push(num("Validate action refs against route/app-scoped ActionRegistry manifest."));
ch.push(num("Validate layout constraints: depth, pane roles, grid/list item size, max tree size."));
ch.push(num("Validate accessibility: labels, roles, focus, keyboard, touch targets."));
ch.push(num("Validate capability requirements: generated UI may not require unavailable renderer/device features without fallback."));
ch.push(num("Validate effects: no unbounded timers, forbidden network access, unsafe storage, or arbitrary native calls."));
ch.push(num("Sign bundle and manifest together; client verifies signature and compatibility before execution."));

ch.push(h2("17.9 Renderer Capability Negotiation"));
ch.push(p("Renderer capability negotiation is distinct from device environment. A premium desktop and a mobile browser may both be high performance, but one renderer may support runtime shaders and the other may not."));
ch.push(...code([
  "data class RendererCapabilities(",
  "    val renderer: RendererKind,",
  "    val supportsHover: Boolean,",
  "    val supportsPressure: Boolean,",
  "    val supportsAdvancedHaptics: Boolean,",
  "    val supportsRuntimeShaders: Boolean,",
  "    val supportsBackdropBlur: Boolean,",
  "    val supportsNativeTextInput: Boolean,",
  "    val supportsVirtualizedLists: Boolean,",
  "    val supportsServerDrivenBundles: Boolean,",
  ")",
  "",
  "enum class RendererKind { ComposeNative, ComposeWasm, RsdWeb, RsdNative, HostTreeCompose }",
]));
ch.push(p("DesignLanguage and TactileRuntimePolicy should combine EnvironmentSnapshot with RendererCapabilities. That is how LiquidGlass can degrade intentionally from shader/refraction to blur/translucency to static surfaces."));

ch.push(h2("17.10 Performance Budgets"));
ch.push(p("Tactile UI can become expensive. Budgeting is part of the architecture."));
ch.push(tbl(["Operation","Budget","Runtime Response if Exceeded"],[
  ["Pointer down to first visual response","< 16ms","Disable expensive field/shader effects for that interaction class."],
  ["DesignLanguage visual resolution","< 1ms per component batch where practical","Cache visual specs by props + snapshot class + skin + environment tier."],
  ["Adaptive pane recalculation","< 4ms","Throttle window resize policy and avoid rebuilding graphs."],
  ["Host tree commit decode","< 4ms typical route","Switch from full-tree commit to op batches; surface warning in workbench."],
  ["Frame allocation during drag","Near-zero avoidable allocations","Use Modifier.Node / stable state objects for hot paths."],
  ["Virtual list scroll","No dropped frames on target tier","Reduce per-item tactile effects and prefetch."],
  ["Shader uniform upload","Within render thread budget","Clamp impulse count and decay old impulses aggressively."],
],[3000,1700,4660]));

ch.push(h2("17.11 Versioning and Compatibility"));
ch.push(p("Once server bundles and AI-generated components exist, clients and servers evolve independently. Versioning must be a first-class contract."));
ch.push(tbl(["Versioned Surface","Rule"],[
  ["Host component IDs","Use explicit IDs such as reaktor.tactile.Button@1. Add @2 for breaking changes."],
  ["Prop schemas","Adding optional props is minor; removing/renaming/changing type is major."],
  ["Visual specs","Primitive fields need defaults; unit changes are breaking."],
  ["Action schemas","Payload schema version is part of ActionRef. Old clients reject unknown required payload fields."],
  ["Design tokens","Base tokens can grow; changing semantic meaning requires migration notes and tests."],
  ["Renderer capabilities","Generated bundles declare minimum renderer capability version."],
  ["Environment profiles","Normalized enum values can grow; consumers must handle Unknown."],
  ["Graph routes","Route/action manifests need semantic version and compatibility checks."],
],[2500,6860]));

ch.push(h2("17.12 Governance: How This Stays Coherent"));
ch.push(p("The runtime is large enough that governance matters. Without conformance tests and generated manifests, every product and renderer will drift."));
ch.push(num("Every official component has registry metadata, accessibility contract, layout contract, examples, and preview cases."));
ch.push(num("Every official skin passes the skin matrix across idle/hover/focus/press/disabled/error/selected/busy/reduced-motion states."));
ch.push(num("Every renderer publishes a capability matrix and parity status."));
ch.push(num("Every generated bundle is validated against component/action/capability/accessibility manifests before signing."));
ch.push(num("Every adaptive container has deterministic back/focus policy tests for compact, medium, expanded, and desktop widths."));
ch.push(num("Every performance-sensitive runtime path emits debug telemetry that the workbench can inspect."));
ch.push(num("BestBuds and Reaktor Desktop are the first conformance apps; Nexergy/Manna do not drive requirements until the foundation holds."));

ch.push(h2("17.13 The Runtime Spine"));
ch.push(p("The final mental model for the next-generation UI runtime:"));
ch.push(...code([
  "EnvironmentFeature + RendererCapabilities",
  "        \u2502",
  "        \u25BC",
  "TactileRuntimePolicy",
  "        \u2502",
  "        \u251C\u2500 Interaction machines      (what the user is doing)",
  "        \u251C\u2500 DesignLanguage           (how it should look under policy)",
  "        \u251C\u2500 Component registry       (what components are allowed to be)",
  "        \u251C\u2500 Semantic UI contract     (what the UI means)",
  "        \u251C\u2500 Action registry          (what the UI may do)",
  "        \u251C\u2500 Layout constraints       (where it may live)",
  "        \u251C\u2500 Graph runtime            (navigation, DI, data/service nodes)",
  "        \u251C\u2500 Renderer paths           (Compose, RSD, HostTreeCompose)",
  "        \u2514\u2500 Workbench/devtools       (why the runtime behaved that way)",
]));
ch.push(box("Final Design Rule","Do not let the host tree, React API, Compose components, or generated UI become the source of truth. The source of truth is the component capability registry plus semantic/action/layout/accessibility/capability contracts. Renderers are implementations of those contracts."));

pb();

// ═══════════════════════════════════════════════════════════════════════════════
// 18. APPLICATIONS AND EXPERIENCES
// ═══════════════════════════════════════════════════════════════════════════════
ch.push(h1("18. Applications and Experiences This Runtime Enables"));
ch.push(sp(80));
ch.push(p("This chapter answers the product question: what can people build with this runtime that is hard, fragile, or uneconomical with ordinary UI frameworks? The answer is not simply 'prettier buttons.' The differentiator is that UI becomes graph-aware, capability-aware, tactile, semantic, adaptive, inspectable, and safely generatable."));

ch.push(h2("18.1 The Core Application Thesis"));
ch.push(p("Most UI frameworks are good at rendering screens. Reaktor Tactile should be good at running UI systems. A UI system is a long-lived surface that adapts to device, network, power, role, graph state, route state, generated content, and live data while remaining explainable in tooling."));
ch.push(tbl(["Traditional UI Framework","Reaktor Tactile Runtime"],[
  ["Screen tree is the main model.","The app graph is the main model; screens, services, repositories, actions, routes, and panes are inspectable nodes and edges."],
  ["Theme decides colors and typography.","DesignLanguage decides renderer-neutral visual specs from tokens, interaction state, and runtime policy."],
  ["Responsive layout usually means breakpoints inside screens.","Adaptive layout is graph-aware: panes render routes, preserve route payloads, handle focus/back policy, and collapse deterministically."],
  ["Platform adaptation is scattered through if-statements.","EnvironmentFeature normalizes device, execution, and network profiles; policies select graceful degradation tiers."],
  ["AI/server UI usually becomes a JSON DSL.","React is the authoring language; host tree is internal IR; registry/action/accessibility validation keeps it safe."],
  ["Debugging means logs and screenshots.","Workbench shows graph, route, pane, semantic node, interaction snapshot, visual spec, capability tier, host tree, and telemetry."],
],[4300,5060]));

ch.push(h2("18.2 Application Classes That Become Easier"));
ch.push(tbl(["Application Class","Why It Is Hard Elsewhere","Why Reaktor Tactile Helps"],[
  ["Realtime social apps","Chat, presence, profiles, feeds, media, offline cache, and adaptive layouts usually live in separate architectural silos.","Graph nodes model chat services, repositories, sockets, routes, and panes together. Capability tiers decide socket vs polling vs queued sends. Tactile components provide consistent loading, optimistic, stale, and offline states."],
  ["Desktop/mobile workbenches","Inspector layouts, graph editors, route previews, plugin panels, and live diagnostics require custom infrastructure.","Adaptive panes and GraphRouteContent make inspector/workbench UI a first-class pattern. The component registry and semantic tree make panels inspectable instead of bespoke."],
  ["Capability-aware media apps","Camera, upload, image decode, network cost, thermal pressure, and low power mode create many fragile branches.","reaktor-capability centralizes environment and strategy selection. UI degrades by tier: full preview, basic capture, pick existing media, or queue upload."],
  ["AI-assisted applications","Generated UI needs constraints, action safety, accessibility checks, and renderer compatibility.","AI receives component/action manifests generated from the registry. Bundles are validated, signed, and rendered through the same host contracts as hand-written UI."],
  ["Adaptive productivity apps","List/detail/supporting panes, keyboard focus, route state, and desktop/tablet behavior are difficult to keep consistent.","Graph-backed panes own route payloads and focus/back behavior. Compose adaptive handles measurement; Reaktor graph owns navigation."],
  ["Operational dashboards","Large tables, trees, timelines, live telemetry, and degraded network modes usually become one-off UI systems.","Virtualized TDataTable/TTreeView/TTimeline plus telemetry-aware capability policies give dashboards standard building blocks."],
],[2100,3600,3660]));

ch.push(h2("18.3 BestBuds: What This Enables"));
ch.push(p("BestBuds is the first proof because it already combines realtime chat, profiles, events, campaigns, media, auth, cache, and graph navigation. The runtime should make these surfaces more adaptive and more reliable without rewriting product logic."));
ch.push(tbl(["BestBuds Experience","Runtime-Enabled Upgrade"],[
  ["Chat list + chat detail","Phone remains push navigation. Tablet/desktop becomes list-detail. Profiles open as supporting panes. Messages keep cache-first behavior and optimistic sends."],
  ["Weak network chat","NetworkQuality drives socket strategy: live WebSocket, polling, serialized send, or queued offline send. UI labels the tier clearly instead of silently failing."],
  ["Events and campaigns","Adaptive grid on tablets/desktop, single-column list on compact/low-performance devices, no heavy prefetch on expensive network."],
  ["Profile creation/editing","TForm gives validation, dirty state, async submit, deferred submit, and accessibility rules without inventing form binding ad hoc."],
  ["Media-heavy cards","MediaPolicy chooses image quality, thumbnail strategy, and prefetch based on network/cost/performance."],
  ["Dev tools","DevGraph becomes a capability dashboard: EnvironmentSnapshot, installed features, active tiers, FFI/FlexBuffer status, Work/Telemetry status, host tree diagnostics."],
],[2600,6760]));

ch.push(h2("18.4 Reaktor Desktop: What This Enables"));
ch.push(p("Reaktor Desktop is the second proof because it is the engine UI. It should become the place where a developer understands and edits the runtime, similar in spirit to Unreal's editor and Blueprint tooling."));
ch.push(tbl(["Workbench Experience","Runtime-Enabled Upgrade"],[
  ["Graph inspector","Graph nodes show not only type/ports, but active capability providers, route payload fixtures, action refs, semantic roles, and adaptive pane roles."],
  ["Component catalogue","Every Tactile component can be previewed across skins, states, environment profiles, renderer capabilities, and reduced-motion/high-contrast settings."],
  ["Adaptive simulator","A screen can be previewed as compact phone, tablet list-detail, desktop drawer layout, low network, high thermal pressure, or low-power mode."],
  ["Host tree debugger","React/Hermes bundles expose host node tree, handler refs, commit timings, validation results, and rollback state."],
  ["Accessibility auditor","Workbench validates role, label, focus traversal, keyboard bindings, touch target, and color contrast from the semantic contract."],
  ["Performance panel","Frame budget, visual spec resolution time, bridge commit time, network telemetry, and capability tier changes are visible in one place."],
],[2600,6760]));

ch.push(h2("18.5 UIs That Are Much Easier Than With Static SDUI"));
ch.push(p("Static SDUI is useful for simple remote configuration, but it becomes painful when UI needs real composition, state, effects, adaptive layout, or tool-safe generation. The Reaktor path is different: React or Kotlin composes real components, but the runtime validates the resulting host/component contract."));
ch.push(tbl(["UI Need","Static JSON SDUI Pain","Reaktor Tactile Path"],[
  ["Conditional cards based on user/activity state","Invent expression syntax and binding rules.","Use React/Kotlin conditionals; validate resulting component tree."],
  ["Dynamic repeated content","Invent list schema, item templates, variable scopes, and key behavior.","Use map/lazy lists/virtualized components with stable key contracts."],
  ["Local UI state","Invent two-way binding and lifecycle rules.","Use React state or Kotlin controllers, bounded by action and capability contracts."],
  ["Multi-pane generated panels","JSON does not naturally know route payloads, graph focus, or back behavior.","Generated UI targets pane roles and action refs; graph/adaptive container owns route/focus/back policy."],
  ["Accessibility enforcement","Every schema component needs bespoke validation rules.","Component registry publishes accessibility contracts and validation is automatic."],
  ["Graceful degradation","Server must know too much about client environment.","Client EnvironmentFeature and RendererCapabilities select tiers locally."],
],[2700,3100,3560]));

ch.push(h2("18.6 Concrete Application Blueprints"));
ch.push(p("These are examples of applications or surfaces that this runtime should make straightforward to build. They are not separate products in the roadmap; they are capability demonstrations."));
ch.push(bul([B("Social command center: "), N("A live chat/productivity UI where conversations, profiles, groups, events, and recommendations share one adaptive graph. Phone shows one pane; desktop shows list, detail, profile, and AI summary panes.")]));
ch.push(bul([B("AI-generated contextual panels: "), N("A server or AI can generate a React component that summarizes a chat, offers next actions, or builds a comparison table, while only using approved components and action refs.")]));
ch.push(bul([B("Graph-native admin console: "), N("Operations UI where every backend service, worker, route, queue, and telemetry stream appears as a graph node that can be inspected and acted on.")]));
ch.push(bul([B("Capability-aware field app: "), N("Camera, location, upload, sync, and forms degrade automatically under low battery, hot device, metered network, or offline mode.")]));
ch.push(bul([B("Realtime collaborative workspace: "), N("Presence, optimistic updates, timelines, comments, and live cursors can use shared interaction/action/data-aware contracts instead of one-off widgets.")]));
ch.push(bul([B("Design-system workbench: "), N("Designers and engineers can switch skins, inspect visual specs, simulate environment tiers, audit accessibility, and preview all components without running a product app.")]));

ch.push(h2("18.7 What This Runtime Is Not"));
ch.push(p("Being explicit about non-goals keeps the system coherent."));
ch.push(bul([B("Not just a component library: "), N("Buttons and cards are only the visible edge. The runtime includes graph, capability, semantic, action, layout, validation, and tooling contracts.")]));
ch.push(bul([B("Not a JSON SDUI framework: "), N("The system should not invent a half-programming language. React/Kotlin handle composition; registries and validators keep it safe.")]));
ch.push(bul([B("Not a skin-only theming layer: "), N("DesignLanguage is important, but it is only one stage in policy -> interaction -> semantics -> visuals -> renderer.")]));
ch.push(bul([B("Not a replacement for platform affordances: "), N("Platform policy decides when to feel native, when to feel Tactile, and when to degrade based on capabilities.")]));
ch.push(bul([B("Not an excuse to over-animate: "), N("Physical effects are budgeted, capability-aware, and disabled when accessibility/performance/environment requires it.")]));

ch.push(h2("18.8 Application Fit Checklist"));
ch.push(p("A product is a strong fit for Reaktor Tactile if it answers yes to several of these questions:"));
ch.push(num("Does the app have multiple related surfaces that should become panes on larger screens?"));
ch.push(num("Does UI behavior depend on network quality, offline state, battery, thermal pressure, or device class?"));
ch.push(num("Does the app benefit from live graph/service/route inspection in development or operations?"));
ch.push(num("Does the app need both hand-authored UI and safely generated/contextual UI?"));
ch.push(num("Does the app need rich forms, media, realtime updates, optimistic state, or offline queues?"));
ch.push(num("Does the team want one design-system contract across Compose, web/RSD, and future React/Hermes rendering?"));
ch.push(num("Does the product need multiple visual languages or runtime skin switching without duplicating components?"));
ch.push(num("Would debugging be easier if UI, graph, capabilities, actions, and telemetry were visible in one workbench?"));

pb();

// ═══════════════════════════════════════════════════════════════════════════════
// 19. MOTIVATION, ARCHITECTURE RATIONALE & PROBLEMS SOLVED
// ═══════════════════════════════════════════════════════════════════════════════
ch.push(h1("19. Motivation, Architecture Rationale & Problems Solved"));
ch.push(sp(80));
ch.push(p("This chapter is the high-level argument for the system. It is written for a reader who wants to understand why this architecture is worth building before reading individual APIs. The short version: Reaktor Tactile is not trying to compete with a button library. It is trying to make UI a first-class runtime concern across graph, capability, design, adaptive layout, renderer, AI, and tooling."));

ch.push(h2("19.1 The Real Problem: UI Is No Longer Just Rendering"));
ch.push(p("Older UI architectures could treat a screen as a function from state to pixels. That model still matters, but it is no longer enough for the apps Reaktor needs to power. A BestBuds chat surface is not only pixels: it includes route payloads, repositories, realtime transport, offline cache, media policies, network quality, focus behavior, accessibility semantics, device capability, and potentially generated contextual panels."));
ch.push(p("A Reaktor Desktop workbench surface is even more demanding: it must render graph nodes, inspect lifecycle, simulate environment profiles, show host tree commits, audit accessibility, and visualize service telemetry. These are not separate UI problems. They are one runtime problem."));
ch.push(box("Design Motivation","The system should make the difficult parts explicit: what the UI means, what it may do, where it lives, what environment it is running in, how it should look, how it degrades, and why the runtime made those choices."));

ch.push(h2("19.2 Problems in Current Mainstream Approaches"));
ch.push(tbl(["Approach","What It Solves","Where It Breaks Down for Reaktor"],[
  ["Component libraries","Provide reusable buttons, cards, fields, modals, and layout helpers.","They rarely own graph navigation, capability strategy, generated UI constraints, adaptive route panes, telemetry, or runtime explanation."],
  ["Token-based design systems","Make color, typography, spacing, and shape consistent.","Tokens alone do not model behavior, interaction state, physical material, renderer capability, policy tiers, or app actions."],
  ["Headless primitives","Separate behavior from styling and improve accessibility.","Consumers still assemble product-level state, physics, haptics, adaptive graph layout, capability degradation, and visual language mapping themselves."],
  ["Static SDUI","Allow server-configured UI for known component schemas.","Complex products need conditionals, loops, local state, effects, validation, generated composition, and action safety. Static schemas drift toward half-languages."],
  ["React or Compose alone","Give strong composition and rendering models.","They do not, by themselves, define cross-renderer component contracts, normalized environment policy, graph-integrated panes, generated UI validation, or design language switching."],
  ["Platform-native adaptive APIs","Provide excellent layout primitives for specific platforms.","They do not own Reaktor graph routes, payloads, services, ports, or cross-target renderer parity."],
],[2300,2700,4360]));

ch.push(h2("19.3 The Reaktor-Specific Opportunity"));
ch.push(p("Reaktor has a rare advantage: the framework already models the app as a graph with lifecycle, ports, DI scopes, navigation, services, repositories, telemetry, FFI, and platform adapters. That means the UI runtime does not need to invent a parallel world. It can become the visual and interaction layer of the graph."));
ch.push(p("This is why graph integration is a critical requirement rather than a later convenience. A multi-pane chat layout should not create its own navigation model. It should render multiple graph routes at once. A generated contextual panel should not call arbitrary functions. It should dispatch registered actions. A degraded upload UI should not inspect raw device trivia. It should observe capability tiers derived from EnvironmentSnapshot."));
ch.push(...code([
  "Reaktor graph already knows:",
  "  - which route is active",
  "  - what payload opened it",
  "  - which graph owns the route",
  "  - which services and repositories are connected",
  "  - which capabilities are installed",
  "  - which lifecycle state the node is in",
  "",
  "Tactile should add:",
  "  - how that graph is presented",
  "  - what the UI means semantically",
  "  - how the UI reacts physically",
  "  - how the UI adapts to panes and environment",
  "  - how generated content is constrained",
  "  - how developers inspect the whole system",
]));

ch.push(h2("19.4 The Core Architecture in One Page"));
ch.push(p("The architecture can be summarized as six cooperating contracts. Each contract answers one question and hands a stable result to the next contract."));
ch.push(tbl(["Contract","Question It Answers","Output"],[
  ["Environment contract","What is this device/runtime/network capable of right now?","EnvironmentSnapshot, RendererCapabilities, AccessibilityProfile, TactileRuntimePolicy."],
  ["Graph/action contract","What app state, route, service, repository, and allowed action does this UI connect to?","Route payloads, pane route bindings, action refs, repository flows, service nodes."],
  ["Component contract","What is this UI element allowed to be, contain, expose, and emit?","Component manifest, semantic node, state machine definition, event/action map, accessibility contract."],
  ["Interaction contract","What is happening to the component right now?","InteractionSnapshot, gesture state, field impulses, material response, haptic request."],
  ["Visual contract","How should this component look under this design language and policy?","Renderer-neutral VisualSpec with colors, radii, shadows, transforms, typography, spacing, depth, effects."],
  ["Renderer contract","How does this VisualSpec and semantic tree become pixels on this target?","Compose nodes, RSD nodes, host tree commits, semantics/ARIA, focus behavior, layout measurements."],
],[2200,4300,2860]));
ch.push(p("The architecture is intentionally not a single schema. It is a set of contracts with clear ownership. This lets beginner users stay at the component level while expert users extend the runtime safely."));

ch.push(h2("19.5 Why React Reconciler Instead of Static SDUI"));
ch.push(p("The user-facing authoring model for dynamic UI should be React components, not JSON. The internal representation can be a host tree, but the product-facing programming model should remain a real programming model with composition, conditionals, loops, local state, effects, and testable components."));
ch.push(p("This avoids the main failure mode of static SDUI frameworks: they start simple, then gradually add expressions, bindings, templates, lifecycle hooks, list scopes, event routing, and validation rules until they become a weaker, less tooled programming language."));
ch.push(tbl(["Need","Static SDUI Tendency","React Reconciler Path"],[
  ["Composition","Invent slots, templates, includes, and overrides.","Use normal React components and children."],
  ["Branching","Invent expression syntax or server-side precomputed variants.","Use if statements, ternaries, and component boundaries."],
  ["Lists","Invent item schemas, variable scope, key behavior, and diffing.","Use map, keys, virtualization contracts, and React reconciliation."],
  ["Local state","Invent two-way binding or remote state slots.","Use useState/useReducer where appropriate, bounded by action/capability rules."],
  ["Effects","Invent lifecycle hooks or polling fields.","Use React effects in the Hermes/runtime sandbox, with policy-controlled APIs."],
  ["Safety","Trust or over-restrict schema fields.","Validate the reconciler output against component, action, accessibility, and capability manifests."],
],[2000,3600,3760]));
ch.push(p("The important distinction is this: React is the authoring and composition language; TactileHostTree is the internal renderer contract. The host tree is not a product-facing schema language."));

ch.push(h2("19.6 Why Capability-Aware UI Must Be Core"));
ch.push(p("A tactile UI runtime can easily become wasteful if it ignores device and environment constraints. Rich physics, shaders, haptics, images, network-heavy generated bundles, and realtime transports must respect the environment. That is why reaktor-capability is part of the story from the beginning."));
ch.push(p("Capability-aware UI is not only about disabling expensive effects. It is about choosing correct strategy tiers. On a strong unmetered device, BestBuds can show live previews, high-quality media, animated surfaces, WebSocket chat, and generated contextual panels. On constrained devices, the same graph can still work through simpler visuals, lower media quality, serialized uploads, queued sends, and no shader layer."));
ch.push(tbl(["Signal","Normalized Runtime Meaning","Example UI Decision"],[
  ["PerformanceClass Low","Device budget is limited.","Prefer static shadows, fewer simultaneous springs, no expensive shaders by default."],
  ["ThermalClass Hot","Sustained compute should reduce quickly.","Disable background field shader, reduce animation intensity, lower image decode concurrency."],
  ["PowerMode LowPower","Battery conservation is preferred.","Avoid prefetch, reduce motion, avoid live previews unless critical."],
  ["NetworkQuality Poor","Realtime and large transfers are unreliable.","Queue messages/uploads, show stale cache state, avoid generated bundle fetches unless already cached."],
  ["NetworkCost Expensive","Bandwidth should be treated as costly.","Lower media quality, avoid auto-play, defer nonessential sync."],
  ["ReducedMotion true","Motion can harm accessibility.","Use state transitions without springy movement, haptic alternatives only when appropriate."],
],[2300,3300,3760]));

ch.push(h2("19.7 Why Atomic Design Still Matters"));
ch.push(p("Atomic Design prevents the runtime from becoming too abstract. Developers still need components they can actually use: buttons, inputs, cards, list items, sheets, tabs, forms, chat rows, profile headers, inspectors, tables, and panes. The atomic hierarchy gives the system a learning path and an implementation path."));
ch.push(bul([B("Atoms teach the rules: "), N("TButton, TText, TIcon, TAvatar, TBadge, TInput, TToggle, and TProgress are where interaction snapshots and visual specs are easiest to understand.")]));
ch.push(bul([B("Molecules make the rules useful: "), N("TListItem, TSearchBar, TFormField, TToolbar, TMetricCard, and TMessageBubble combine atoms with common product semantics.")]));
ch.push(bul([B("Organisms prove app-scale patterns: "), N("TChatList, TChatThread, TProfileHeader, TEventCardGrid, TInspectorPanel, and TDataTable compose many states and actions together.")]));
ch.push(bul([B("Templates connect to graph and adaptive layout: "), N("List-detail, supporting pane, dashboard, wizard, form editor, and workbench templates place organisms into route-aware panes.")]));
ch.push(bul([B("Pages remain product-owned: "), N("BestBudsChatPage or ReaktorGraphInspectorPage can use templates and organisms without forcing every product concern into the core library.")]));

ch.push(h2("19.8 What This Makes Possible That Is Hard Elsewhere"));
ch.push(p("The value is not one individual feature. The value is that multiple hard requirements become composable rather than mutually conflicting."));
ch.push(num("A generated AI panel can appear as a supporting pane next to a live chat, use only approved actions, inherit the current design language, adapt to reduced motion, and be inspected in the workbench."));
ch.push(num("A BestBuds profile editor can run as a compact phone screen, a desktop side pane, or an offline-capable form, while preserving validation, dirty state, submit policy, and accessibility."));
ch.push(num("A Reaktor Desktop graph inspector can render routes, services, ports, telemetry, host tree commits, and component visual specs in one adaptive workbench instead of several separate debugging tools."));
ch.push(num("A product can experiment with NeoBrutalism, Material, LiquidGlass, or a custom brand style without rewriting behavior, accessibility, forms, graph actions, or adaptive layout."));
ch.push(num("A media-heavy surface can gracefully degrade from live camera plus upload preview to still capture, existing media picker, queued upload, or read-only state based on runtime policy."));

ch.push(h2("19.9 Architecture Decision Summary"));
ch.push(tbl(["Decision","Why It Is Chosen","Risk If Ignored"],[
  ["Interaction machines are first-class","Behavior must be reusable and testable independent of visuals.","Each component invents its own state handling and edge cases diverge."],
  ["DesignLanguage returns pure visual specs","Appearance must be switchable, portable, and snapshot-testable.","Skins become platform-specific component forks."],
  ["Compose Atomic Design ships before reconciler","The core design system must be useful without React, Hermes, or FFI.","The architecture becomes blocked on the hardest subsystem."],
  ["Adaptive layout integrates with graph","Reaktor graph owns routes and payloads; layout should present graph state, not replace it.","Multi-pane UI creates a second navigation model and inconsistent back/focus behavior."],
  ["EnvironmentFeature drives policy","Device and network reality must be normalized before UI decisions.","Screens branch on raw device trivia and become brittle."],
  ["React is authoring, host tree is IR","Dynamic UI needs a real programming model but a constrained renderer contract.","Static JSON grows into a weak programming language or dynamic UI becomes unsafe."],
  ["Workbench is part of the runtime story","A powerful runtime must explain itself to be trusted.","Advanced behavior becomes opaque and hard to debug."],
],[2500,3800,3060]));

ch.push(h2("19.10 North-Star Requirement"));
ch.push(p("The north-star requirement is simple to state and difficult to achieve: a developer should be able to build a rich adaptive app by composing graph-backed, capability-aware, tactile components, and later allow AI or server bundles to compose the same approved components without changing the rendering, accessibility, action, capability, or tooling model."));
ch.push(p("If the system reaches that point, it will not be merely a design system. It will be a UI runtime: one that can power BestBuds, Reaktor Desktop, future products, AI-generated contextual interfaces, and operational workbenches through the same set of contracts."));

// ═══════════════════════════════════════════════════════════════════════════════
// BUILD
// ═══════════════════════════════════════════════════════════════════════════════
const doc = new Document({
  numbering: nc,
  styles: {
    default: { document: { run: { font: "Arial", size: 22 } } },
    paragraphStyles: [
      { id: "Heading1", name: "Heading 1", basedOn: "Normal", next: "Normal", quickFormat: true,
        run: { size: 36, bold: true, font: "Arial" },
        paragraph: { spacing: { before: 360, after: 240 }, outlineLevel: 0 } },
      { id: "Heading2", name: "Heading 2", basedOn: "Normal", next: "Normal", quickFormat: true,
        run: { size: 30, bold: true, font: "Arial" },
        paragraph: { spacing: { before: 280, after: 200 }, outlineLevel: 1 } },
      { id: "Heading3", name: "Heading 3", basedOn: "Normal", next: "Normal", quickFormat: true,
        run: { size: 26, bold: true, font: "Arial" },
        paragraph: { spacing: { before: 240, after: 160 }, outlineLevel: 2 } },
    ],
  },
  sections: [{
    properties: {
      page: {
        size: { width: PAGE_WIDTH, height: PAGE_HEIGHT },
        margin: { top: MARGIN, right: MARGIN, bottom: MARGIN, left: MARGIN },
      },
    },
    headers: {
      default: new Header({
        children: [new Paragraph({
          border: { bottom: { style: BorderStyle.SINGLE, size: 1, color: C.bdr, space: 4 } },
          children: [
            new TextRun({ text: "Reaktor Tactile Design System | Version 4.0", font: "Arial", size: 18, color: C.lt }),
          ],
        })],
      }),
    },
    footers: {
      default: new Footer({
        children: [new Paragraph({
          alignment: AlignmentType.CENTER,
          children: [
            new TextRun({ text: "Page ", font: "Arial", size: 18, color: C.lt }),
            new TextRun({ children: [PageNumber.CURRENT], font: "Arial", size: 18, color: C.lt }),
          ],
        })],
      }),
    },
    children: ch,
  }],
});

const OUTPUT = "Reaktor-Tactile-Design-System-Architecture.docx";
Packer.toBuffer(doc).then(buffer => {
  fs.writeFileSync(OUTPUT, buffer);
  console.log(`\u2705 Generated ${OUTPUT} (${(buffer.length / 1024).toFixed(0)} KB)`);
}).catch(err => {
  console.error("\u274C Generation failed:", err);
  process.exit(1);
});
