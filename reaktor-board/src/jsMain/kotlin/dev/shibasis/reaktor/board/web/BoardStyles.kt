package dev.shibasis.reaktor.board.web

object BoardStyles {
    private const val Id = "reaktor-board-styles"

    fun install() {
        val document: dynamic = js("globalThis.document") ?: return
        if (document.getElementById(Id) != null) return
        val style = document.createElement("style")
        style.id = Id
        style.textContent = Sheet
        document.head.appendChild(style)
    }

    private val Sheet = """
.board-workbench { position:relative; --board-line:#e4e8f0; --board-accent:#7865d9; --board-accent-strong:#6753bf; --board-accent-soft:#f0edfc; --board-accent-line:#b5a5ef;
 --board-ink:#3c4861; --board-muted:#788399; --board-faint:#94a0b4; --board-surface:#fff; --board-raised:#f7f8fb; --board-tabs:#f4f3f9; --board-grid:#e0e4ee;
 --board-font:'DM Sans',system-ui,sans-serif; --board-card-font:Arial,sans-serif; --board-toast:#293d36; --board-toast-ink:#fff; --board-danger:#b35858; --board-danger-soft:#fff8f7; --board-danger-line:#eed6d5;
 --board-height:calc(100dvh - 72px); color:var(--board-ink); font:13px/1.5 var(--board-font); }
.board-workbench [hidden] { display:none!important; }
.board-studio { position:relative; display:flex; flex-direction:column; border:1px solid var(--board-line); border-radius:10px; background:var(--board-surface); overflow:hidden; height:var(--board-height); min-height:360px; box-shadow:0 3px 16px #243b5b05; }
.board-canvas-tools { display:flex; align-items:center; gap:12px; height:48px; padding:0 8px; border-bottom:1px solid var(--board-line); flex:none; z-index:60; }
.board-primary-tools { display:flex; align-items:center; gap:12px; min-width:0; }
.board-canvas-tools .surface-tabs { display:flex; background:var(--board-tabs); border-radius:7px; padding:2px; gap:2px; flex:none; }
.board-canvas-tools .surface-tabs button { font:500 12px/1.4 var(--board-font); padding:7px 12px; border:0; color:var(--board-muted); background:transparent; border-radius:5px; white-space:nowrap; min-height:32px; cursor:pointer; }
.board-canvas-tools .surface-tabs button.is-selected,.board-canvas-tools .surface-tabs button[aria-selected="true"] { background:var(--board-surface); color:var(--board-accent-strong); box-shadow:0 1px 4px #2d365b10; }
.board-interactions,.board-panel-actions { display:flex; align-items:center; gap:2px; flex:none; }
.board-panel-actions { margin-left:auto; }
.board-canvas-tools .surface-action { display:inline-flex; align-items:center; gap:6px; min-height:34px; font:500 12px/1.4 var(--board-font); padding:7px 10px; background:transparent; border:1px solid transparent; color:var(--board-muted); white-space:nowrap; border-radius:6px; cursor:pointer; }
.board-canvas-tools .surface-action:disabled { opacity:.45; cursor:default; }
.board-canvas-tools .surface-action[aria-pressed="true"],.board-canvas-tools .surface-action[aria-expanded="true"] { color:var(--board-accent-strong); background:var(--board-accent-soft); }
.board-canvas-options { position:relative; }
.board-canvas-options>summary { display:grid; place-items:center; list-style:none; cursor:pointer; width:30px; height:34px; color:var(--board-muted); font-size:20px; border-radius:6px; }
.board-canvas-options>summary::-webkit-details-marker { display:none; }
.board-canvas-options[open]>summary,.board-canvas-options>summary:hover { background:var(--board-accent-soft); color:var(--board-accent-strong); }
.board-canvas-options-content { position:absolute; top:calc(100% + 8px); right:0; z-index:65; width:270px; padding:10px; border:1px solid var(--board-line); border-radius:9px; background:var(--board-surface); box-shadow:0 8px 28px #253d3020; }
.board-canvas-options-content .surface-action { width:100%; justify-content:flex-start; display:flex; padding:7px 10px; border:0; background:transparent; color:var(--board-ink); font:12px/1.4 var(--board-font); cursor:pointer; border-radius:6px; }
.board-canvas-options-content .surface-action:hover { background:var(--board-accent-soft); }
.board-canvas-options-content p { padding:0 10px; font-size:11px; line-height:1.6; color:var(--board-faint); }
.board-workbench .surface-action:focus-visible,.board-canvas:focus-visible { outline:2px solid var(--board-accent-line); outline-offset:-2px; }
.board-canvas-panel { flex:1; display:flex; min-width:0; min-height:0; background:var(--board-surface); }
.board-canvas,.board-graph { position:relative; flex:1; min-width:0; min-height:0; height:100%; isolation:isolate; }
.board-canvas { background-color:var(--board-surface); background-image:radial-gradient(var(--board-grid) 1px,transparent 1px); background-size:22px 22px; touch-action:none; }
.board-canvas .excalidraw { background:transparent; --color-primary:var(--board-accent); --color-primary-darker:var(--board-accent-strong); --color-primary-light:var(--board-accent-soft); }
.board-canvas .excalidraw .layer-ui__wrapper,.board-canvas .excalidraw .App-bottom-bar { display:none; }
.board-canvas.tool-select .excalidraw canvas { cursor:default; }.board-canvas.tool-connect .excalidraw canvas { cursor:crosshair; }.board-canvas.tool-pan .excalidraw canvas { cursor:grab; }
.board-canvas-empty { position:absolute; z-index:4; inset:0; display:flex; flex-direction:column; align-items:center; justify-content:center; padding:25px; background:color-mix(in srgb,var(--board-surface) 80%,transparent); text-align:center; touch-action:auto; }
.board-canvas-empty h3 { font:600 22px/1.5 var(--board-font); color:var(--board-ink); margin:0; }
.board-canvas-empty p { color:var(--board-muted); font-size:13px; max-width:330px; line-height:1.7; margin:10px 0 22px; }
.board-canvas-empty .surface-action { min-height:38px; padding:8px 16px; border-radius:8px; border:1px solid var(--board-accent); background:var(--board-accent); color:#fff; font:600 13px/1.4 var(--board-font); cursor:pointer; }
.board-empty-mark { display:grid; place-items:center; width:58px; height:58px; background:var(--board-accent-soft); border:1px solid color-mix(in srgb,var(--board-accent) 18%,transparent); border-radius:16px; color:var(--board-accent); font-size:30px; margin-bottom:10px; }
.board-canvas-footer { position:absolute; bottom:12px; left:12px; right:12px; display:flex; justify-content:space-between; align-items:center; gap:12px; z-index:4; pointer-events:none; color:var(--board-faint); font-size:11px; }
.board-gesture-hint { padding:6px 10px; border-radius:6px; background:color-mix(in srgb,var(--board-surface) 88%,transparent); }
.board-zoom { display:flex; align-items:center; gap:2px; padding:2px; border:1px solid var(--board-line); border-radius:7px; background:var(--board-surface); pointer-events:auto; margin-left:auto; box-shadow:0 1px 5px #243b5b08; }
.board-zoom span { min-width:38px; text-align:center; font-variant-numeric:tabular-nums; }
.board-zoom .surface-action { min-height:30px; border:0; background:transparent; color:var(--board-muted); padding:5px 8px; font:12px/1.4 var(--board-font); cursor:pointer; border-radius:5px; }
.board-zoom .surface-action:hover { background:var(--board-accent-soft); color:var(--board-accent-strong); }
.board-drawer { position:absolute; z-index:50; top:48px; bottom:0; width:350px; max-width:100%; background:var(--board-surface); overflow:auto; box-shadow:0 5px 28px #253d3010; overscroll-behavior:contain; }
.board-index { left:0; display:flex; flex-direction:column; border-right:1px solid var(--board-line); }
.board-drawer-heading { position:sticky; top:0; display:flex; align-items:center; justify-content:space-between; gap:10px; padding:20px 24px; background:var(--board-surface); border-bottom:1px solid var(--board-line); z-index:1; margin-bottom:12px; }
.board-workbench .board-drawer-heading h2 { font:600 17px/1.4 var(--board-font); letter-spacing:0; margin:3px 0 0; }
.board-drawer-eyebrow { letter-spacing:.17em; font-size:9px; font-weight:600; color:var(--board-faint); }
.board-drawer-heading .surface-action { min-height:28px; font:10px/1.4 var(--board-font); padding:4px 7px; color:var(--board-muted); background:transparent; border:1px solid transparent; border-radius:6px; cursor:pointer; }
.board-index-list { min-height:0; overflow:auto; padding:0 12px 14px; }
.board-index .board-index-item { display:flex; flex-direction:column; gap:8px; width:100%; text-align:left; border:1px solid transparent; border-radius:9px; background:transparent; padding:14px; margin-bottom:5px; color:var(--board-ink); font:inherit; cursor:pointer; }
.board-index .board-index-item:hover { background:var(--board-raised); }
.board-index .board-index-item.is-selected { background:var(--board-accent-soft); border-color:color-mix(in srgb,var(--board-accent) 22%,transparent); }
.board-index-item>strong { font-size:13px; line-height:1.45; font-weight:600; overflow-wrap:anywhere; }
.board-index .board-index-meta { display:flex; align-items:center; gap:7px; font-size:10px; color:var(--board-faint); line-height:1.4; }
.board-index-meta i { height:6px; width:6px; border-radius:50%; flex:none; }
.board-index-item>small { font-size:11px; color:#9b7432; }
.board-index-next { color:var(--board-muted); font-size:11px; line-height:1.5; overflow-wrap:anywhere; }
.board-workbench .board-details { right:0; left:auto; display:block; border-left:1px solid var(--board-line); padding:0; }
.board-details .board-drawer-heading { margin-bottom:0; }
.board-feedback { position:absolute; z-index:70; left:50%; bottom:16px; transform:translateX(-50%); max-width:calc(100% - 32px); }
.board-feedback .surface-toasts:empty { display:none; }
.board-feedback .surface-toasts { position:static; display:flex; align-items:center; gap:16px; padding:8px 12px; background:var(--board-toast); color:var(--board-toast-ink); border-radius:7px; box-shadow:0 3px 14px #203d3520; font-size:12px; white-space:nowrap; }
.board-feedback .surface-toasts button { border:0; background:none; color:inherit; opacity:.8; font-size:18px; line-height:1; padding:0 3px; cursor:pointer; }
.board-sync-error { position:absolute; z-index:69; bottom:60px; left:50%; transform:translateX(-50%); width:max-content; max-width:calc(100% - 24px); padding:10px 14px; border:1px solid var(--board-danger-line); border-radius:8px; background:var(--board-danger-soft); color:var(--board-danger); display:grid; gap:4px; font-size:12px; }
.board-sync-error span { max-width:460px; overflow-wrap:anywhere; }
.board-interactions .board-add { background:var(--board-accent); color:#fff; border-color:var(--board-accent); margin-right:7px; font-weight:600; }
.board-interactions .board-add:hover { background:var(--board-accent-strong); }
.board-canvas-affordances { position:absolute; inset:0; overflow:hidden; pointer-events:none; z-index:3; }
.board-connect-handle { position:absolute; transform:translate(-50%,-50%); pointer-events:auto; display:grid; place-items:center; width:30px; height:30px; padding:0; border:1.5px solid var(--board-accent-line); border-radius:50%; background:var(--board-surface); color:var(--board-accent); font:500 21px/1 var(--board-font); cursor:crosshair; box-shadow:0 2px 7px #44387b12; touch-action:none; }
.board-connect-handle:hover,.board-connect-handle:focus-visible { background:var(--board-accent); color:#fff; border-color:var(--board-accent); outline:4px solid var(--board-accent-soft); }
.board-lane-add { position:absolute; transform:translate(-50%,0); pointer-events:auto; min-height:34px; padding:6px 8px; overflow:hidden; white-space:nowrap; text-overflow:ellipsis; border:1px dashed var(--board-line); border-radius:8px; background:color-mix(in srgb,var(--board-surface) 85%,transparent); color:var(--board-muted); font:500 12px/1.5 var(--board-font); cursor:pointer; }
.board-lane-add:hover,.board-lane-add:focus-visible { background:var(--board-accent-soft); color:var(--board-accent); border-color:var(--board-accent-line); }
.board-wire-preview { position:absolute; inset:0; z-index:4; color:var(--board-accent); pointer-events:none; }
.board-wire-preview.is-invalid { color:#c36b66; }
.board-wire-instruction,.board-connect-guidance { position:absolute; bottom:20px; left:50%; transform:translateX(-50%); max-width:calc(100% - 24px); padding:10px 16px; border:1px solid var(--board-line); border-radius:10px; background:var(--board-surface); box-shadow:0 3px 16px #3d2e7112; font-size:12px; text-align:center; margin:0; }
.board-connect-guidance { z-index:5; display:flex; align-items:center; gap:18px; color:var(--board-accent); }
.board-connect-guidance .surface-action { font:11px/1.4 var(--board-font); min-height:30px; padding:5px 8px; flex:none; border:1px solid var(--board-line); border-radius:6px; background:var(--board-surface); color:var(--board-ink); cursor:pointer; }
.board-inline-editor { position:absolute; width:300px; height:180px; transform-origin:0 0; z-index:8; pointer-events:none; font-family:var(--board-card-font); }
.board-inline-editor input,.board-inline-editor textarea,.board-inline-editor select,.board-inline-editor button { pointer-events:auto; }
.board-workbench .board-inline-title { position:absolute; left:22px; top:61px; width:256px; height:74px; min-height:0; padding:0; margin:0; border:0; border-radius:0; resize:none; overflow:auto; background:transparent; color:var(--board-ink); font:400 18px/1.35 var(--board-card-font); outline:none; box-shadow:none; caret-color:var(--board-accent); }
.board-workbench .board-inline-title:focus,.board-workbench .board-inline-title:focus-visible { outline:none; box-shadow:none; }
.board-workbench .board-inline-title::placeholder { color:#b1b8c8; }
.board-workbench .board-inline-field,.board-workbench .board-inline-label { position:absolute; left:22px; top:20px; width:256px; margin:0; padding:0; border:0; border-radius:0; background:transparent; color:#93a0b5; font:400 12px/1.35 var(--board-card-font); box-shadow:none; outline:none; }
.board-workbench .board-inline-field::placeholder { color:#a4aebe; }
.board-workbench .board-inline-field:focus-visible { outline:none; box-shadow:0 1px 0 var(--board-accent-line); }
.board-workbench .board-inline-label { pointer-events:none; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; text-transform:uppercase; }
.board-workbench .board-inline-lane { position:absolute; left:17px; top:141px; max-width:150px; padding:6px 5px; border:0; border-radius:5px; background:var(--board-accent-soft); color:var(--board-accent-strong); font:12px/1.35 var(--board-card-font); }
.board-inline-buttons { position:absolute; right:15px; top:137px; display:flex; gap:4px; pointer-events:auto; }
.board-inline-buttons button { display:grid; place-items:center; width:32px; height:32px; padding:0; border:1px solid var(--board-line); border-radius:7px; background:var(--board-surface); color:var(--board-muted); font:20px/1 var(--board-card-font); cursor:pointer; }
.board-inline-buttons .board-inline-commit { background:var(--board-accent); color:#fff; border-color:var(--board-accent); }
.board-inline-buttons button:disabled { opacity:.35; cursor:default; }
.board-inline-buttons button:hover:enabled { filter:brightness(.95); }
.board-inline-caption { position:absolute; top:196px; width:300px; display:flex; flex-direction:column; align-items:flex-start; gap:7px; pointer-events:auto; color:var(--board-muted); font:11px/1.4 var(--board-font); }
.board-inline-caption select { max-width:100%; border:1px solid var(--board-line); border-radius:6px; padding:6px 9px; color:var(--board-ink); background:var(--board-surface); font:11px/1.4 var(--board-font); }
.board-inline-caption small { font-size:10px; color:var(--board-faint); }
.board-inline-source { max-width:100%; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.board-inline-error { position:absolute; top:285px; width:300px; padding:10px 12px; border:1px solid var(--board-danger-line); border-radius:8px; background:var(--board-danger-soft); color:var(--board-danger); font:12px/1.5 var(--board-font); pointer-events:auto; }
.board-inline-error .surface-action { min-height:30px; padding:5px 8px; font-size:11px; margin-top:8px; }
.board-connect-popover { position:absolute; z-index:9; overflow:auto; padding:14px; border:1px solid var(--board-line); border-radius:12px; background:var(--board-surface); box-shadow:0 8px 32px #35295120; color:var(--board-ink); font:12px/1.5 var(--board-font); touch-action:pan-y; overscroll-behavior:contain; }
.board-connect-heading { display:flex; justify-content:space-between; align-items:center; gap:12px; }
.board-connect-heading strong { font-size:13px; font-weight:600; }
.board-connect-heading .surface-action { padding:3px; min-width:28px; min-height:28px; border:0; background:transparent; color:var(--board-muted); font-size:20px; cursor:pointer; }
.board-connect-source { font-size:11px; color:var(--board-muted); margin:1px 0 12px; overflow-wrap:anywhere; }
.board-connect-popover>input { width:100%; box-sizing:border-box; border:1px solid var(--board-line); border-radius:7px; background:var(--board-raised); padding:9px 10px; font:12px/1.5 var(--board-font); color:var(--board-ink); margin-bottom:10px; }
.board-connect-popover>input:focus { outline:2px solid var(--board-accent-line); outline-offset:1px; }
.board-connect-create { display:flex; width:100%; justify-content:center; font:12px/1.4 var(--board-font); min-height:36px; color:var(--board-accent); border:1px dashed var(--board-accent-line); border-radius:7px; background:var(--board-raised); margin:0 0 14px; cursor:pointer; }
.board-connect-options { display:flex; flex-direction:column; gap:5px; }
.board-connect-option { display:flex; align-items:center; justify-content:space-between; gap:12px; padding:10px; border:1px solid var(--board-line); border-radius:8px; background:var(--board-surface); color:var(--board-ink); text-align:left; cursor:pointer; flex-shrink:0; font:inherit; }
.board-connect-option>span:first-child { display:flex; flex-direction:column; gap:4px; }
.board-connect-option strong { font:500 12px/1.5 var(--board-font); }
.board-connect-option small { font:10px/1.5 var(--board-font); color:var(--board-faint); }
.board-connect-option:hover:enabled,.board-connect-option:focus-visible { background:var(--board-accent-soft); border-color:var(--board-accent-line); color:var(--board-accent); outline:none; }
.board-connect-option:disabled { cursor:default; opacity:.6; background:var(--board-raised); }
.board-quick-hint { color:var(--board-muted); font-size:12px; margin:5px 0; }
.board-edit-error { color:var(--board-danger); font-size:12px; margin-top:14px; }
@media(max-width:760px) {
 .board-workbench { --board-height:calc(100dvh - 136px); }
 .board-studio { min-height:300px; border-radius:8px; }
 .board-canvas-tools { gap:4px; padding:0 4px; }
 .board-primary-tools { gap:2px; flex:1; overflow-x:auto; scrollbar-width:none; }
 .board-primary-tools::-webkit-scrollbar { display:none; }
 .board-canvas-tools .surface-tabs button { padding:6px 5px; font-size:11px; min-height:40px; }
 .board-canvas-tools .surface-action { padding:6px 5px; min-height:40px; font-size:11px; }
 .board-canvas-tools .board-list-toggle { padding-inline:3px; }
 .board-tool-label { display:none; }.board-canvas-tools .board-tool { min-width:32px; }
 .board-canvas-tools .board-add { font-size:0; width:32px; margin-right:0; padding:6px; }.board-add::before { content:'+'; font:20px/1 var(--board-font); }
 .board-panel-actions { gap:0; }.board-canvas-options>summary { width:24px; height:40px; }
 .board-gesture-hint { display:none; }.board-canvas-footer { bottom:8px; left:8px; right:8px; }
 .board-drawer { width:340px; }.board-drawer-heading { padding:14px 18px; }
 .board-feedback { bottom:56px; }.board-feedback .surface-toasts { max-width:100%; white-space:normal; }
 .board-sync-error { bottom:96px; }
 .board-interactions { gap:1px; }.board-interactions .board-add { margin-right:1px; padding:6px; }.board-connect-handle { width:44px; height:44px; }
 .board-connect-guidance { width:calc(100% - 24px); gap:8px; padding:8px 10px; font-size:11px; }.board-wire-instruction { width:calc(100% - 24px); font-size:11px; }
 .board-inline-buttons { right:10px; top:134px; }.board-inline-buttons button { width:44px; height:44px; }
 .board-workbench .board-inline-lane { top:141px; font-size:16px; }.board-workbench .board-inline-field { font-size:16px; }
 .board-connect-popover { padding:12px; }.board-connect-option { min-height:44px; }
 .board-workbench .board-inline-caption select { font-size:16px; min-height:40px; }.board-workbench .board-connect-popover>input { font-size:16px; }
}
"""
}
