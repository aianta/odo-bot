/* Trajectory Debugger: watch, cut, annotate, import and export charted timelines recorded by hand with OdoBot. */
(function () {
  'use strict';
  const { createApp, ref, reactive, computed, watch, onMounted, onBeforeUnmount, nextTick } = Vue;

  const KINDS = {
    ClickEvent: { color: 'var(--k-click)', name: 'Click' },
    DataEntry: { color: 'var(--k-entry)', name: 'Data entry' },
    CheckboxEvent: { color: 'var(--k-check)', name: 'Checkbox' },
    SelectEvent: { color: 'var(--k-select)', name: 'Select' },
    Effect: { color: 'var(--k-effect)', name: 'DOM effect' },
    NetworkEvent: { color: 'var(--k-network)', name: 'Network' },
    ApplicationLocationChange: { color: 'var(--k-location)', name: 'Location' },
    Observation: { color: 'var(--k-observation)', name: 'Observation' },
    TaskAnswer: { color: 'var(--k-answer)', name: 'Answer' },
  };
  const kindColor = (kind) => (KINDS[kind] || { color: 'var(--k-other)' }).color;
  const IMAGE_KINDS = new Set(['ClickEvent', 'DataEntry', 'CheckboxEvent', 'SelectEvent', 'Observation']);

  async function api(path, options = {}) {
    const init = { method: options.method || 'GET', headers: {} };
    if (options.json !== undefined) {
      init.headers['content-type'] = 'application/json';
      init.body = JSON.stringify(options.json);
    } else if (options.body !== undefined) {
      init.body = options.body;
    }
    const response = await fetch('/api' + path, init);
    if (response.status === 304) return null;
    const type = response.headers.get('content-type') || '';
    const body = type.includes('application/json') ? await response.json() : await response.text();
    if (!response.ok) {
      const message = body && body.error ? body.error : (typeof body === 'string' && body) || response.statusText;
      throw new Error(message);
    }
    return body;
  }

  function ago(ms, now) {
    if (ms == null) return 'never';
    const s = Math.max(0, Math.round(((now || Date.now()) - ms) / 1000));
    if (s < 60) return s + ' s ago';
    if (s < 3600) return Math.round(s / 60) + ' min ago';
    return Math.round(s / 3600) + ' h ago';
  }
  function time(ms) {
    if (ms == null) return '';
    const d = new Date(ms);
    return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' }) + '.' + String(d.getMilliseconds()).padStart(3, '0');
  }
  function debounce(fn, ms) {
    const timers = new Map();
    return (key, ...args) => {
      clearTimeout(timers.get(key));
      timers.set(key, setTimeout(() => { timers.delete(key); fn(key, ...args); }, ms));
    };
  }

  /* ---------------------------------------------------------------- JSON tree */
  const JsonTree = {
    name: 'JsonTree',
    props: { value: null, name: String, depth: { type: Number, default: 0 } },
    data() { return { open: this.depth < 1 }; },
    computed: {
      isObject() { return this.value !== null && typeof this.value === 'object'; },
      entries() { return this.isObject ? Object.entries(this.value) : []; },
      preview() { return Array.isArray(this.value) ? `[${this.value.length}]` : `{${Object.keys(this.value || {}).length}}`; },
      kind() { return this.value === null ? 'null' : typeof this.value; },
    },
    template: `
      <li>
        <template v-if="isObject">
          <span class="json-toggle" @click="open = !open">{{ open ? '▾' : '▸' }}</span>
          <span v-if="name !== undefined" class="json-key">{{ name }}: </span>
          <span v-if="!open" class="json-null">{{ preview }}</span>
          <ul v-if="open"><json-tree v-for="[k, v] in entries" :key="k" :name="k" :value="v" :depth="depth + 1"/></ul>
        </template>
        <template v-else>
          <span v-if="name !== undefined" class="json-key">{{ name }}: </span>
          <span :class="kind === 'string' ? 'json-str' : kind === 'number' || kind === 'boolean' ? 'json-num' : 'json-null'">{{ kind === 'string' ? JSON.stringify(value) : String(value) }}</span>
        </template>
      </li>`,
  };

  /* ---------------------------------------------------------------- screenshot with bbox */
  const Shot = {
    props: { src: String, bbox: Object, caption: String, full: Boolean },
    emits: ['zoom'],
    data() { return { w: 0, h: 0 }; },
    methods: { loaded(e) { this.w = e.target.naturalWidth; this.h = e.target.naturalHeight; } },
    template: `
      <figure class="shot" :class="{ full }" style="margin:0" @click="$emit('zoom', { src, bbox })">
        <div style="position:relative">
          <img :src="src" @load="loaded" alt="">
          <svg v-if="bbox && w" :viewBox="'0 0 ' + w + ' ' + h" preserveAspectRatio="none">
            <rect :x="bbox.x" :y="bbox.y" :width="bbox.width" :height="bbox.height" fill="rgba(225,29,72,.12)" stroke="#e11d48" stroke-width="3"/>
          </svg>
        </div>
        <figcaption v-if="caption">{{ caption }}</figcaption>
      </figure>`,
  };

  /* ---------------------------------------------------------------- app */
  const App = {
    components: { JsonTree, Shot },
    setup() {
      const tab = ref('timelines');
      const state = reactive({ etag: null, session: null, workspace: { timelines: [], activeTimelineId: null } });
      const error = ref('');
      const notice = ref('');
      const cutting = ref(false);
      const selected = ref(new Set());
      const drawer = reactive({ kind: null, entityId: null, timelineId: null, detail: null, loading: false, error: '', dom: null, domOpen: false, notes: '' });
      const lightbox = ref(null);
      const importer = reactive({ open: false, tab: 'dataset', loading: false, error: '', source: null, picked: new Set() });
      const busy = ref(false);
      const seen = new Set();
      const fresh = ref(new Set());
      const saved = reactive({});
      const now = ref(Date.now());

      const timelines = computed(() => state.workspace.timelines || []);
      const maxLength = computed(() => Math.max(12, ...timelines.value.map((t) => t.entities.length)) + 2);
      const transmitting = computed(() => !!(state.session && state.session.transmitting));

      function flash(message) { notice.value = message; setTimeout(() => { if (notice.value === message) notice.value = ''; }, 4000); }
      function fail(e) { error.value = e.message || String(e); setTimeout(() => { error.value = ''; }, 7000); }

      /* ---------------- polling */
      let pollTimer = null;
      async function poll() {
        try {
          const data = await api('/workspace' + (state.etag ? '?since=' + encodeURIComponent(state.etag) : ''));
          if (data) {
            const newOnes = new Set();
            for (const t of data.workspace.timelines) for (const e of t.entities) { if (!seen.has(e.id)) { if (state.etag) newOnes.add(e.id); seen.add(e.id); } }
            fresh.value = newOnes;
            const activeBefore = state.workspace.activeTimelineId;
            state.etag = data.etag; state.session = data.session; state.workspace = data.workspace;
            if (newOnes.size) scrollActiveToEnd();
            if (activeBefore !== data.workspace.activeTimelineId && data.workspace.activeTimelineId) scrollActiveToEnd();
            if (drawer.kind === 'entity' && drawer.detail && drawer.detail.entity.live) refreshEntity();
          }
        } catch (e) { /* the server may be restarting */ }
        pollTimer = setTimeout(poll, 750);
      }
      async function refresh() { state.etag = null; clearTimeout(pollTimer); await poll(); }

      const stack = ref(null);
      function scrollActiveToEnd() {
        nextTick(() => {
          const id = state.workspace.activeTimelineId;
          if (!id || !stack.value) return;
          const row = stack.value.querySelector(`[data-timeline="${id}"]`);
          const last = row && row.querySelector('.cell:last-of-type');
          if (last) last.scrollIntoView({ block: 'nearest', inline: 'nearest', behavior: 'smooth' });
        });
      }

      /* ---------------- recording */
      async function setRecording(on) {
        busy.value = true;
        try { await api('/recording', { method: 'POST', json: { on } }); await refresh(); flash(on ? 'Recording.' : 'Recording paused: OdoX stopped transmitting.'); }
        catch (e) { fail(e); } finally { busy.value = false; }
      }
      async function endSession() {
        if (!confirm('End the recording session? The browser closes; the timelines stay here.')) return;
        busy.value = true;
        try { await api('/session', { method: 'DELETE' }); await refresh(); flash('Session ended.'); }
        catch (e) { fail(e); } finally { busy.value = false; }
      }

      /* ---------------- timelines */
      async function newTimeline() { try { await api('/timelines', { method: 'POST', json: {} }); await refresh(); } catch (e) { fail(e); } }
      const saveTitle = debounce(async (id, title) => {
        try { await api('/timelines/' + id, { method: 'PATCH', json: { title } }); } catch (e) { fail(e); }
      }, 500);
      async function setActive(t) {
        const active = state.workspace.activeTimelineId === t.id;
        try { await api('/timelines/' + t.id, { method: 'PATCH', json: { active: !active } }); await refresh(); } catch (e) { fail(e); }
      }
      async function deleteTimeline(t) {
        if (!confirm(`Delete "${t.title}" and its ${t.entities.length} entities? This deletes only the debugger's copy.`)) return;
        try {
          await api('/timelines/' + t.id, { method: 'DELETE' });
          selected.value.delete(t.id);
          if (drawer.timelineId === t.id) closeDrawer();
          await refresh();
        } catch (e) { fail(e); }
      }
      function toggleSelect(id) { const s = new Set(selected.value); s.has(id) ? s.delete(id) : s.add(id); selected.value = s; }

      async function cellClick(t, e) {
        if (cutting.value) {
          try {
            const result = await api(`/timelines/${t.id}/cut`, { method: 'POST', json: { entityId: e.id } });
            if (result.message) flash(result.message); else flash('Cut: the entities after it moved to a new timeline below.');
            cutting.value = false;
            await refresh();
          } catch (err) { fail(err); }
          return;
        }
        openEntity(e.id);
      }

      /* ---------------- drawer */
      async function openEntity(id) {
        Object.assign(drawer, { kind: 'entity', entityId: id, timelineId: null, detail: null, loading: true, error: '', dom: null, domOpen: false });
        await refreshEntity();
      }
      async function refreshEntity() {
        const id = drawer.entityId;
        try {
          const detail = await api('/entities/' + id);
          if (drawer.entityId !== id) return;
          const notes = drawer.detail && drawer.detail.entity.id === id ? drawer.detail.entity.notes : undefined;
          drawer.detail = detail;
          if (notes !== undefined && document.activeElement && document.activeElement.classList.contains('notes')) drawer.detail.entity.notes = notes;
          drawer.error = '';
        } catch (e) { if (drawer.entityId === id) drawer.error = e.message; }
        drawer.loading = false;
      }
      function openTimelineNotes(t) { Object.assign(drawer, { kind: 'timeline', timelineId: t.id, entityId: null, detail: null, error: '', notes: t.notes || '' }); }
      function closeDrawer() { Object.assign(drawer, { kind: null, entityId: null, timelineId: null, detail: null, dom: null, domOpen: false }); }
      const drawerTimeline = computed(() => timelines.value.find((t) => t.id === drawer.timelineId));
      const saveEntityNotes = debounce(async (id, notes) => {
        try { await api('/entities/' + id, { method: 'PATCH', json: { notes } }); saved[id] = Date.now(); } catch (e) { fail(e); }
      }, 600);
      const saveTimelineNotes = debounce(async (id, notes) => {
        try { await api('/timelines/' + id, { method: 'PATCH', json: { notes } }); saved[id] = Date.now(); } catch (e) { fail(e); }
      }, 600);
      async function loadDom() {
        drawer.domOpen = !drawer.domOpen;
        if (drawer.domOpen && drawer.dom == null) {
          try { drawer.dom = await api(`/entities/${drawer.entityId}/dom`); } catch (e) { drawer.dom = ''; fail(e); }
        }
      }
      function downloadDom() {
        const blob = new Blob([drawer.dom || ''], { type: 'text/html' });
        const a = document.createElement('a');
        a.href = URL.createObjectURL(blob); a.download = `entity-${drawer.entityId}.html`; a.click();
        setTimeout(() => URL.revokeObjectURL(a.href), 1000);
      }
      const rowFields = computed(() => {
        const row = drawer.detail && drawer.detail.row;
        if (!row) return [];
        const keys = ['kind', 'symbol', 'event_name', 'timestamp', 'clock', 'first_seq', 'last_seq', 'location', 'xpath', 'tag', 'html_id', 'dom_sha256'];
        return keys.filter((k) => row[k] !== undefined && row[k] !== null && row[k] !== '')
          .map((k) => [k, k === 'timestamp' ? `${row[k]} (${time(row[k])})` : row[k]]);
      });

      /* ---------------- export */
      async function download(ids) {
        if (!ids.length) { flash('Select the timelines to download first.'); return; }
        busy.value = true;
        try {
          const response = await fetch('/api/export', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ timelineIds: ids }) });
          if (!response.ok) { const body = await response.json().catch(() => ({})); throw new Error(body.error || response.statusText); }
          const blob = await response.blob();
          const name = (response.headers.get('content-disposition') || '').match(/filename="([^"]+)"/);
          const a = document.createElement('a');
          a.href = URL.createObjectURL(blob); a.download = name ? name[1] : 'timelines.zip'; a.click();
          setTimeout(() => URL.revokeObjectURL(a.href), 2000);
          flash(`Downloaded ${ids.length} timeline(s).`);
        } catch (e) { fail(e); } finally { busy.value = false; }
      }

      /* ---------------- import */
      function openImport() { Object.assign(importer, { open: true, tab: 'dataset', error: '', source: null, picked: new Set() }); }
      async function closeImport() {
        if (importer.source) api('/import/' + importer.source.importId, { method: 'DELETE' }).catch(() => {});
        importer.open = false; importer.source = null;
      }
      async function loadDataset() {
        importer.loading = true; importer.error = ''; importer.source = null; importer.picked = new Set();
        try { importer.source = await api('/import/dataset'); } catch (e) { importer.error = e.message; } finally { importer.loading = false; }
      }
      async function upload(event) {
        const file = event.target.files[0];
        if (!file) return;
        importer.loading = true; importer.error = ''; importer.source = null; importer.picked = new Set();
        const form = new FormData(); form.append('file', file, file.name);
        try { importer.source = await api('/import/upload', { method: 'POST', body: form }); } catch (e) { importer.error = e.message; } finally { importer.loading = false; event.target.value = ''; }
      }
      function togglePick(id) { const s = new Set(importer.picked); s.has(id) ? s.delete(id) : s.add(id); importer.picked = s; }
      function pickExperiment(exp, on) { const s = new Set(importer.picked); for (const t of exp.trajectories) on ? s.add(t.id) : s.delete(t.id); importer.picked = s; }
      async function doImport() {
        importer.loading = true; importer.error = '';
        try {
          const result = await api('/import/' + importer.source.importId, { method: 'POST', json: { trajectoryIds: [...importer.picked] } });
          importer.source = null; importer.open = false;
          await refresh();
          flash(`Imported ${result.imported.length} timeline(s) as copies.`);
        } catch (e) { importer.error = e.message; } finally { importer.loading = false; }
      }

      /* ---------------- diagnostics */
      const diag = reactive({ data: null, error: '', loading: false, at: null });
      let diagTimer = null;
      async function pollDiagnostics() {
        clearTimeout(diagTimer);
        if (tab.value !== 'diagnostics') return;
        if (!state.session) { diag.data = null; diag.error = ''; }
        else {
          diag.loading = true;
          try { diag.data = await api('/session/diagnostics'); diag.error = ''; diag.at = Date.now(); }
          catch (e) { diag.error = e.message; } finally { diag.loading = false; }
        }
        diagTimer = setTimeout(pollDiagnostics, 2000);
      }
      watch(tab, (t) => { if (t === 'diagnostics') pollDiagnostics(); });

      const odox = computed(() => (diag.data && diag.data.odox) || null);
      const odobot = computed(() => (diag.data && diag.data.odobot) || null);
      const page = computed(() => odox.value && odox.value.content && odox.value.content.page);
      const latest = (...values) => { const v = values.filter((x) => typeof x === 'number'); return v.length ? Math.max(...v) : null; };
      const hops = computed(() => {
        const o = odox.value || {}, c = o.content || {}, p = c.page || {}, b = o.background || {}, ob = odobot.value || {};
        const logui = p.logui || {}, de = p.domEffects || {};
        const list = [
          { name: 'Page scripts', what: 'LogUI dispatches, DOM effects emitted', at: latest(logui.lastDispatchAt, de.lastEmitAt),
            count: (logui.dispatched || 0) + (de.emitted || 0), known: !!o.supported && p.reachable !== false },
          { name: 'Content script', what: 'messages from the page', at: c.fromPage && c.fromPage.lastAt, count: c.fromPage && c.fromPage.count, known: !!o.supported && c.reachable !== false },
          { name: 'Background', what: 'messages from the content script', at: b.fromContent && b.fromContent.lastAt, count: b.fromContent && b.fromContent.count, known: !!o.supported },
          { name: 'OdoBot', what: 'page-side events received', at: ob.lastPageSideAt, count: ob.currentPage ? ob.currentPage.pageSideEvents : null, known: true },
        ];
        let stalled = -1;
        for (let i = 1; i < list.length; i++) {
          const up = list[i - 1], here = list[i];
          if (up.known && here.known && up.at && (!here.at || up.at - here.at > 2500)) { stalled = i; break; }
        }
        list.forEach((h, i) => { h.stalled = i === stalled; h.status = !h.known ? 'none' : h.stalled ? 'bad' : h.at ? 'ok' : 'warn'; });
        return list;
      });
      const scripts = computed(() => {
        const c = (odox.value && odox.value.content) || {}, marks = (page.value && page.value.marks) || {}, injected = c.scripts || {};
        return ['handlerMagic', 'logui', 'domEffects', 'guidance'].map((name) => ({
          name, file: { handlerMagic: 'handlerMagic.js', logui: 'logui.bundle.js', domEffects: 'dom-effects.js', guidance: 'guidance.js' }[name],
          injected: injected[name] ? injected[name].injected : null,
          loaded: injected[name] ? injected[name].loaded : null,
          error: injected[name] ? injected[name].error : null,
          ran: marks[name] != null ? marks[name] : null,
        }));
      });
      const loguiOk = computed(() => {
        const l = page.value && page.value.logui, ob = odobot.value;
        if (!l) return null;
        if (!l.present || !l.active) return false;
        if (!l.lastDispatchAt) return null;
        return ob && ob.lastPageSideAt != null && ob.lastPageSideAt >= l.lastDispatchAt - 2500;
      });
      function copyRaw() { navigator.clipboard && navigator.clipboard.writeText(JSON.stringify(diag.data, null, 2)).then(() => flash('Copied.')); }

      /* ---------------- keys and lifecycle */
      function onKey(e) {
        if (e.key === 'Escape') {
          if (lightbox.value) lightbox.value = null;
          else if (cutting.value) cutting.value = false;
          else if (importer.open) closeImport();
          else if (drawer.kind) closeDrawer();
        }
      }
      const clock = setInterval(() => { now.value = Date.now(); }, 1000);
      onMounted(() => { poll(); window.addEventListener('keydown', onKey); });
      onBeforeUnmount(() => { clearTimeout(pollTimer); clearTimeout(diagTimer); clearInterval(clock); window.removeEventListener('keydown', onKey); });

      const sampleRequest = JSON.stringify({
        startUrl: 'http://172.23.11.1/admin/admin/dashboard/',
        odoSightPath: './odo-sight.xpi',
        guidanceServiceHost: '172.23.0.2:7080',
        firefoxDockerGridURL: 'http://172.23.0.20:4444',
        targetHosts: ['*'],
        viewerUrl: 'http://localhost:7900/?autoconnect=1&resize=scale&password=secret',
      }, null, 2);

      return {
        tab, state, error, notice, cutting, selected, drawer, lightbox, importer, busy, fresh, saved, now,
        timelines, maxLength, transmitting, stack, diag, odox, odobot, page, hops, scripts, loguiOk,
        KINDS, kindColor, IMAGE_KINDS, ago, time, sampleRequest, rowFields, drawerTimeline,
        setRecording, endSession, newTimeline, saveTitle, setActive, deleteTimeline, toggleSelect, cellClick,
        openEntity, openTimelineNotes, closeDrawer, saveEntityNotes, saveTimelineNotes, loadDom, downloadDom,
        download, openImport, closeImport, loadDataset, upload, togglePick, pickExperiment, doImport, copyRaw,
      };
    },
    template: `
<header class="header">
  <div class="brand">
    <span class="brand-mark"><i style="background:var(--k-click)"></i><i style="background:var(--k-entry)"></i><i style="background:var(--k-effect)"></i></span>
    Trajectory Debugger
  </div>
  <nav class="tabs">
    <button class="tab" :class="{ on: tab === 'timelines' }" @click="tab = 'timelines'">Timelines</button>
    <button class="tab" :class="{ on: tab === 'diagnostics' }" @click="tab = 'diagnostics'">OdoX diagnostics</button>
  </nav>
  <div class="session">
    <template v-if="state.session">
      <span class="dot" :class="transmitting ? 'rec' : 'paused'"></span>
      <span>{{ transmitting ? 'Recording' : 'Paused' }} · <span class="mono">{{ state.session.sessionId }}</span></span>
      <a class="btn" :href="state.session.viewerUrl" target="_blank" rel="noopener">Open browser (noVNC) ↗</a>
      <button v-if="transmitting" class="btn" :disabled="busy" @click="setRecording(false)">■ Stop recording</button>
      <button v-else class="btn primary" :disabled="busy" @click="setRecording(true)">● Start recording</button>
      <button class="btn danger" :disabled="busy" @click="endSession">End session</button>
    </template>
    <template v-else>
      <span class="dot"></span><span class="hint">No recording session</span>
    </template>
  </div>
</header>

<div v-if="error" class="banner err">{{ error }}</div>
<div v-if="notice" class="banner">{{ notice }}</div>

<template v-if="tab === 'timelines'">
  <div class="toolbar">
    <button class="btn" @click="newTimeline">＋ New timeline</button>
    <button class="btn" @click="openImport">Import…</button>
    <span class="sep"></span>
    <button class="btn" :disabled="busy || !selected.size" @click="download([...selected])">Download selected ({{ selected.size }})</button>
    <button class="btn" :disabled="busy || !timelines.length" @click="download(timelines.map(t => t.id))">Download all</button>
    <span class="sep"></span>
    <button class="btn" :class="{ on: cutting }" @click="cutting = !cutting" title="Cut a timeline after the entity you click (Esc cancels)">✂ Cut</button>
    <span v-if="cutting" class="hint">Click the entity that ends the first timeline.</span>
    <div class="legend">
      <span v-for="(k, name) in KINDS" :key="name"><i :style="{ background: k.color }"></i>{{ k.name }}</span>
    </div>
  </div>

  <div class="main">
    <div class="stack" :class="{ cutting }" ref="stack">
      <div v-if="!timelines.length" class="empty-state">
        <h2>No timelines yet</h2>
        <p>Start a recording session on OdoBot's evaluation API. OdoBot opens Firefox with OdoX at the start page, starts recording, and answers with the noVNC link to drive it. Events appear here as you click and type.</p>
        <pre class="sample">curl -X POST http://localhost:8076/api/debug/record -H 'content-type: application/json' -d '{{ sampleRequest }}'</pre>
        <p>Or import timelines from OdoBot's trajectory dataset with <b>Import…</b>.</p>
      </div>
      <template v-else>
        <div class="row ruler">
          <div class="label">position</div>
          <div class="track"><span v-for="i in maxLength" :key="i" class="tick">{{ (i - 1) % 5 === 0 ? i - 1 : '' }}</span></div>
        </div>
        <div v-for="t in timelines" :key="t.id" class="row" :class="{ active: state.workspace.activeTimelineId === t.id }" :data-timeline="t.id">
          <div class="label">
            <div class="title-line">
              <input type="checkbox" :checked="selected.has(t.id)" @change="toggleSelect(t.id)" title="Select for download">
              <input class="title-input" :value="t.title" @input="saveTitle(t.id, $event.target.value)" :title="t.title">
            </div>
            <div class="meta-line">
              <span>{{ t.entities.length }} entities</span>
              <span v-if="state.workspace.activeTimelineId === t.id" class="badge live">● active</span>
              <span v-if="t.source" class="badge imported" :title="'Imported from ' + t.source.db + ': ' + t.source.experiment_id + ' / ' + t.source.eval_id">imported</span>
              <span style="margin-left:auto"></span>
              <button class="icon-btn" :class="{ has: state.workspace.activeTimelineId === t.id }" @click="setActive(t)"
                :title="state.workspace.activeTimelineId === t.id ? 'Stop sending recorded events here' : 'Send recorded events here'">◉</button>
              <button class="icon-btn" :class="{ has: t.notes }" @click="openTimelineNotes(t)" title="Timeline notes">✎</button>
              <button class="icon-btn" @click="deleteTimeline(t)" title="Delete timeline">🗑</button>
            </div>
          </div>
          <div class="track">
            <span v-if="!t.entities.length" class="empty-track">{{ state.workspace.activeTimelineId === t.id ? 'Waiting for recorded events…' : 'Empty' }}</span>
            <button v-for="(e, i) in t.entities" :key="e.id" class="cell"
              :class="{ sel: drawer.entityId === e.id, live: e.live, noted: e.notes, shot: e.summary.hasScreenshot, new: fresh.has(e.id) }"
              :style="{ background: kindColor(e.summary.kind) }"
              :title="i + ' · ' + (KINDS[e.summary.kind] ? KINDS[e.summary.kind].name : e.summary.kind) + (e.summary.label ? ': ' + e.summary.label : '') + (e.notes ? '\\n✎ ' + e.notes : '')"
              @click="cellClick(t, e)">{{ (e.summary.symbol || '?').slice(0, 3) }}</button>
          </div>
        </div>
      </template>
    </div>

    <aside v-if="drawer.kind" class="drawer">
      <template v-if="drawer.kind === 'entity'">
        <div class="drawer-head">
          <span v-if="drawer.detail" class="cell" :style="{ background: kindColor(drawer.detail.row.kind), cursor: 'default' }">{{ (drawer.detail.row.symbol || '?').slice(0, 3) }}</span>
          <h3>{{ drawer.detail ? (KINDS[drawer.detail.row.kind] ? KINDS[drawer.detail.row.kind].name : drawer.detail.row.kind) + ' · position ' + drawer.detail.position : 'Entity' }}</h3>
          <span v-if="drawer.detail && drawer.detail.entity.live" class="badge live">live</span>
          <button class="icon-btn" @click="closeDrawer" title="Close (Esc)">✕</button>
        </div>
        <div class="drawer-body">
          <div v-if="drawer.loading" class="hint">Loading…</div>
          <div v-if="drawer.error" class="banner err" style="margin:0">{{ drawer.error }}</div>
          <template v-if="drawer.detail">
            <div v-if="drawer.detail.entity.summary.label" style="font-weight:600">{{ drawer.detail.entity.summary.label }}</div>
            <section>
              <p class="section-title">Screenshots</p>
              <div v-if="drawer.detail.screenshotUrl || drawer.detail.cropUrl" class="shots">
                <shot v-if="drawer.detail.screenshotUrl" :src="drawer.detail.screenshotUrl" :bbox="drawer.detail.row.bbox" caption="Viewport, element outlined" full @zoom="lightbox = $event"/>
                <shot v-if="drawer.detail.cropUrl" :src="drawer.detail.cropUrl" caption="Element crop" @zoom="lightbox = $event"/>
              </div>
              <p v-else-if="drawer.detail.screenshotMissing" class="hint">The row names a screenshot, but its file is missing.</p>
              <p v-else-if="!IMAGE_KINDS.has(drawer.detail.row.kind)" class="hint">No screenshot for this kind of entity.</p>
              <p v-else class="hint">No screenshot was taken.</p>
            </section>
            <section>
              <p class="section-title">Notes</p>
              <textarea class="notes" v-model="drawer.detail.entity.notes" @input="saveEntityNotes(drawer.detail.entity.id, drawer.detail.entity.notes)" placeholder="What happened here?"></textarea>
              <span v-if="saved[drawer.detail.entity.id]" class="saved">Saved {{ ago(saved[drawer.detail.entity.id], now) }}</span>
            </section>
            <section>
              <p class="section-title">Row</p>
              <dl class="kv">
                <template v-for="[k, v] in rowFields" :key="k"><dt>{{ k }}</dt><dd class="mono">{{ v }}</dd></template>
                <template v-if="drawer.detail.row.bbox"><dt>bbox</dt><dd class="mono">{{ JSON.stringify(drawer.detail.row.bbox) }}</dd></template>
                <template v-if="drawer.detail.row.crop_box"><dt>crop_box</dt><dd class="mono">{{ JSON.stringify(drawer.detail.row.crop_box) }}</dd></template>
              </dl>
            </section>
            <section>
              <p class="section-title">Details</p>
              <ul class="json-tree" style="padding-left:0;border:0"><json-tree :value="drawer.detail.row.details"/></ul>
            </section>
            <section v-if="drawer.detail.hasDom">
              <p class="section-title">Page snapshot</p>
              <div style="display:flex;gap:6px">
                <button class="btn small" @click="loadDom">{{ drawer.domOpen ? 'Hide' : 'Show' }} page snapshot</button>
                <button class="btn small" :disabled="drawer.dom == null" @click="downloadDom">Download HTML</button>
              </div>
              <iframe v-if="drawer.domOpen && drawer.dom != null" class="dom" sandbox="" :srcdoc="drawer.dom" style="margin-top:6px"></iframe>
            </section>
            <details class="json"><summary>Full row JSON</summary><ul class="json-tree" style="padding-left:0;border:0"><json-tree :value="drawer.detail.row" :depth="1"/></ul></details>
          </template>
        </div>
      </template>
      <template v-else-if="drawer.kind === 'timeline' && drawerTimeline">
        <div class="drawer-head">
          <h3>{{ drawerTimeline.title }}</h3>
          <button class="icon-btn" @click="closeDrawer" title="Close (Esc)">✕</button>
        </div>
        <div class="drawer-body">
          <section>
            <p class="section-title">Timeline notes</p>
            <textarea class="notes" style="min-height:180px" v-model="drawer.notes" @input="saveTimelineNotes(drawerTimeline.id, drawer.notes)" placeholder="What was recorded, what to look at…"></textarea>
            <span v-if="saved[drawerTimeline.id]" class="saved">Saved {{ ago(saved[drawerTimeline.id], now) }}</span>
          </section>
          <section v-if="drawerTimeline.source">
            <p class="section-title">Imported from</p>
            <dl class="kv"><template v-for="(v, k) in drawerTimeline.source" :key="k"><dt>{{ k }}</dt><dd class="mono">{{ v }}</dd></template></dl>
          </section>
          <section>
            <p class="section-title">Timeline</p>
            <dl class="kv"><dt>id</dt><dd class="mono">{{ drawerTimeline.id }}</dd><dt>entities</dt><dd>{{ drawerTimeline.entities.length }}</dd><dt>created</dt><dd>{{ new Date(drawerTimeline.createdAt).toLocaleString() }}</dd></dl>
          </section>
        </div>
      </template>
    </aside>
  </div>
</template>

<template v-else>
  <div v-if="!state.session" class="empty-state">
    <h2>No recording session</h2>
    <p>Diagnostics come from a live session. Start one with <code>POST /api/debug/record</code> on OdoBot's evaluation API.</p>
  </div>
  <div v-else class="diag">
    <div v-if="diag.error" class="card wide"><div class="banner err" style="margin:0">{{ diag.error }}</div></div>
    <template v-if="diag.data">
      <div v-if="!odox.supported" class="card wide">
        <h4>OdoX <span class="status none">no report</span></h4>
        <p class="hint" style="margin:0">This OdoX build doesn't report diagnostics ({{ odox.error }}). OdoBot's own numbers are below.</p>
      </div>
      <div class="card wide">
        <h4>Pipeline <span class="hint" style="font-weight:400">page → content script → background → OdoBot · updated {{ ago(diag.at, now) }}</span></h4>
        <div class="pipeline">
          <template v-for="(h, i) in hops" :key="h.name">
            <span v-if="i" class="arrow">→</span>
            <div class="hop" :class="{ stalled: h.stalled }">
              <span class="name">{{ h.name }} <span class="status" :class="h.status">{{ h.status === 'ok' ? 'active' : h.status === 'bad' ? 'stopped here' : h.status === 'warn' ? 'no activity' : 'unknown' }}</span></span>
              <span class="age">{{ h.what }}</span>
              <span class="age">last {{ h.known ? ago(h.at, now) : '—' }}<template v-if="h.count != null"> · {{ h.count }}</template></span>
            </div>
          </template>
        </div>
      </div>
      <div class="card">
        <h4>Connection <span class="status" :class="odobot.socket === 'connected' ? 'ok' : 'bad'">{{ odobot.socket }}</span></h4>
        <dl class="kv">
          <dt>Transmitting</dt><dd :class="odobot.transmitting ? 'yes' : 'no'">{{ odobot.transmitting ? 'yes' : 'no' }}</dd>
          <dt>OdoX client</dt><dd class="mono">{{ odobot.clientId }}</dd>
          <dt>Last event</dt><dd>#{{ odobot.lastEventSeq }} · {{ ago(odobot.lastEventAt, now) }}</dd>
          <dt>Gaps</dt><dd :class="odobot.gaps ? 'no' : ''">{{ odobot.gaps }}</dd>
          <template v-if="odox.supported && odox.background">
            <dt>OdoX socket</dt><dd>{{ odox.background.socket && odox.background.socket.state }} <span class="hint" v-if="odox.background.socket">({{ odox.background.socket.reconnects || 0 }} reconnects)</span></dd>
            <dt>Events sent</dt><dd>{{ odox.background.eventsSent }} · last seq {{ odox.background.lastSeq }}</dd>
            <dt>Outbox</dt><dd>{{ odox.background.outbox }}</dd>
            <dt>Dropped</dt><dd :class="odox.background.dropped && odox.background.dropped.count ? 'no' : ''">{{ odox.background.dropped ? odox.background.dropped.count : 0 }}</dd>
            <dt>Other session</dt><dd :class="odox.background.wrongSession ? 'no' : ''" :title="odox.background.lastWrongSessionId ? 'last: ' + odox.background.lastWrongSessionId : ''">{{ odox.background.wrongSession || 0 }} dropped (page's LogUI session isn't OdoX's)</dd>
            <dt>Cached</dt><dd>{{ odox.background.cached || 0 }} (arrived while not transmitting)</dd>
          </template>
        </dl>
      </div>
      <div class="card">
        <h4>LogUI <span class="status" :class="loguiOk === true ? 'ok' : loguiOk === false ? 'bad' : 'none'">{{ loguiOk === true ? 'transmitting' : loguiOk === false ? 'not transmitting' : 'no clicks yet' }}</span></h4>
        <dl v-if="page && page.logui" class="kv">
          <dt>Present</dt><dd :class="page.logui.present ? 'yes' : 'no'">{{ page.logui.present ? 'yes' : 'no' }}</dd>
          <dt>Active</dt><dd :class="page.logui.active ? 'yes' : 'no'">{{ page.logui.active ? 'yes' : 'no' }}</dd>
          <dt>Dispatcher</dt><dd :class="page.logui.dispatcherActive ? 'yes' : 'no'">{{ page.logui.dispatcherActive ? 'active' : 'inactive' }}</dd>
          <dt>Elements bound</dt><dd>{{ page.logui.boundElements }}</dd>
          <dt>Dispatched</dt><dd>{{ page.logui.dispatched }} · last {{ ago(page.logui.lastDispatchAt, now) }}</dd>
          <dt>Started</dt><dd :class="page.logui.initError ? 'no' : ''">{{ page.logui.initError ? 'failed: ' + page.logui.initError : page.logui.initCompletedAt ? time(page.logui.initCompletedAt) : page.logui.initStartedAt ? 'starting since ' + time(page.logui.initStartedAt) : 'never' }}</dd>
          <template v-if="page.logui.initErrorStack"><dt>Init stack</dt><dd><pre class="mono" style="margin:0;white-space:pre-wrap;font-size:11px">{{ page.logui.initErrorStack }}</pre></dd></template>
          <template v-if="page.logui.elementInitialisationError"><dt>Element init</dt><dd class="no"><pre class="mono" style="margin:0;white-space:pre-wrap;font-size:11px">{{ page.logui.elementInitialisationError }}</pre></dd></template>
          <template v-if="page.logui.elementAnomalies && page.logui.elementAnomalies.length"><dt>Skipped keys</dt><dd class="mono">{{ page.logui.elementAnomalies.map(a => a.key + ' (' + a.owner + ')').join(', ') }}</dd></template>
          <dt>Watching DOM</dt><dd :class="page.logui.observing === false ? 'no' : ''">{{ page.logui.observing == null ? '?' : page.logui.observing ? 'yes' : 'no' }}</dd>
          <dt>Last error</dt><dd :class="page.logui.lastError ? 'no' : ''">{{ page.logui.lastError || 'none' }}</dd>
          <dt>DOM effects</dt><dd :class="page.domEffects && page.domEffects.skippedInactive ? 'no' : ''">{{ page.domEffects ? (page.domEffects.observing ? 'observing' : 'not observing') + ' · ' + page.domEffects.emitted + ' emitted' + (page.domEffects.skippedInactive ? ' · ' + page.domEffects.skippedInactive + ' skipped (LogUI inactive)' : '') : 'unknown' }}</dd>
        </dl>
        <p v-else class="hint" style="margin:0">{{ odox.supported ? (odox.content && odox.content.page && odox.content.page.error) || (odox.content && odox.content.error) || 'The page did not answer.' : 'Unknown.' }}</p>
      </div>
      <div class="card">
        <h4>Injected scripts</h4>
        <table class="t">
          <tr><th>Script</th><th>Injected</th><th>Loaded</th><th>Ran</th></tr>
          <tr v-for="s in scripts" :key="s.name">
            <td class="mono">{{ s.file }}</td>
            <td :class="s.injected == null ? 'unk' : s.injected ? 'yes' : 'no'">{{ s.injected == null ? '?' : s.injected ? 'yes' : 'no' }}</td>
            <td :class="s.error ? 'no' : s.loaded == null ? 'unk' : s.loaded ? 'yes' : 'no'">{{ s.error ? 'error: ' + s.error : s.loaded == null ? '?' : s.loaded ? 'yes' : 'no' }}</td>
            <td :class="s.ran == null ? 'unk' : 'yes'">{{ s.ran == null ? '?' : time(s.ran) }}</td>
          </tr>
        </table>
        <dl v-if="odox.content && odox.content.state" class="kv">
          <template v-for="(v, k) in odox.content.state" :key="k"><dt>{{ k }}</dt><dd class="mono">{{ v }}</dd></template>
        </dl>
      </div>
      <div class="card">
        <h4>Current page <span v-if="odobot.currentPage" class="status" :class="odobot.currentPage.deaf ? 'bad' : 'ok'">{{ odobot.currentPage.deaf ? 'deaf?' : 'reporting' }}</span></h4>
        <dl v-if="odobot.currentPage" class="kv">
          <dt>URL</dt><dd class="mono">{{ odobot.currentPage.url }}</dd>
          <dt>Loaded</dt><dd>{{ ago(odobot.currentPage.readyAt, now) }}</dd>
          <dt>Page-side events</dt><dd>{{ odobot.currentPage.pageSideEvents }} of {{ odobot.currentPage.events }} · last {{ ago(odobot.currentPage.lastPageSideAt, now) }}</dd>
        </dl>
        <p v-else class="hint" style="margin:0">OdoX hasn't reported a page yet.</p>
      </div>
      <div class="card wide">
        <h4>Page history</h4>
        <table class="t">
          <tr><th>Page</th><th>Loaded</th><th>Events</th><th>Page-side</th><th></th></tr>
          <tr v-for="p in [...odobot.pages].reverse()" :key="p.pageId + p.readyAt">
            <td class="mono">{{ p.url }}</td><td>{{ time(p.readyAt) }}</td><td>{{ p.events }}</td><td>{{ p.pageSideEvents }}</td>
            <td><span class="status" :class="p.deaf ? 'bad' : 'ok'">{{ p.deaf ? 'deaf?' : 'ok' }}</span></td>
          </tr>
        </table>
      </div>
      <div class="card">
        <h4>Events received by OdoBot</h4>
        <table class="t"><tr v-for="(n, name) in odobot.eventsByName" :key="name"><td class="mono">{{ name }}</td><td>{{ n }}</td></tr></table>
      </div>
      <div class="card">
        <h4>Page errors <span class="status" :class="page && page.errors && page.errors.length ? 'bad' : 'ok'">{{ page && page.errors ? page.errors.length : 0 }}</span></h4>
        <ul v-if="page && page.errors && page.errors.length" class="errors" style="margin:0;padding-left:16px">
          <li v-for="(e, i) in page.errors" :key="i"><span class="mono">{{ e.message }}</span><br><span class="hint">{{ e.source }}:{{ e.line }} · {{ time(e.at) }}</span></li>
        </ul>
        <p v-else class="hint" style="margin:0">None caught in the page.</p>
      </div>
      <div class="card wide">
        <details class="json"><summary>Raw report</summary>
          <div style="margin:6px 0"><button class="btn small" @click="copyRaw">Copy JSON</button></div>
          <ul class="json-tree" style="padding-left:0;border:0"><json-tree :value="diag.data"/></ul>
        </details>
      </div>
    </template>
    <div v-else-if="diag.loading" class="card wide hint">Asking OdoX…</div>
  </div>
</template>

<div v-if="importer.open" class="overlay" @click.self="closeImport">
  <div class="modal">
    <div class="modal-head">
      <h3>Import timelines</h3>
      <nav class="tabs">
        <button class="tab" :class="{ on: importer.tab === 'dataset' }" @click="importer.tab = 'dataset'; importer.source = null">OdoBot dataset</button>
        <button class="tab" :class="{ on: importer.tab === 'upload' }" @click="importer.tab = 'upload'; importer.source = null">Upload file</button>
      </nav>
      <button class="icon-btn" @click="closeImport">✕</button>
    </div>
    <div class="modal-body">
      <p class="hint" style="margin:0">Imported timelines are copies: renaming, notes, cuts and deletes never touch the source.</p>
      <div v-if="importer.tab === 'dataset' && !importer.source">
        <button class="btn primary" :disabled="importer.loading" @click="loadDataset">{{ importer.loading ? 'Reading…' : 'Read OdoBot\\'s trajectory dataset' }}</button>
        <p class="hint">OdoBot reads a snapshot of <code>db/trajectories.db</code>, never the live file.</p>
      </div>
      <div v-if="importer.tab === 'upload' && !importer.source">
        <input type="file" accept=".zip,.db" @change="upload" :disabled="importer.loading">
        <p class="hint">A debugger export (.zip) or a trajectory database (.db). A bare .db has no screenshots.</p>
      </div>
      <div v-if="importer.error" class="banner err" style="margin:0">{{ importer.error }}</div>
      <div v-if="importer.source" class="picker">
        <template v-for="exp in importer.source.experiments" :key="exp.experiment_id">
          <div class="exp">
            <input type="checkbox" :checked="exp.trajectories.every(t => importer.picked.has(t.id))" @change="pickExperiment(exp, $event.target.checked)">
            {{ exp.experiment_id }} <span class="hint" style="font-weight:400">{{ exp.trajectories.length }} trajectories</span>
          </div>
          <label v-for="t in exp.trajectories" :key="t.id">
            <input type="checkbox" :checked="importer.picked.has(t.id)" @change="togglePick(t.id)">
            <span class="mono" style="width:44px">{{ t.webarena_task_id != null ? '#' + t.webarena_task_id : '' }}</span>
            <span class="desc" :title="t.task_description">{{ t.task_description || t.eval_id }}</span>
            <span :class="t.eval_result === 'PASS' ? 'pass' : t.eval_result === 'FAIL' ? 'fail' : 'hint'">{{ t.eval_result || '' }}</span>
            <span class="hint">{{ t.charted_entities }} entities</span>
          </label>
        </template>
        <p v-if="!importer.source.experiments.length" class="hint" style="padding:10px">The database has no trajectories.</p>
      </div>
    </div>
    <div class="modal-foot">
      <button class="btn" @click="closeImport">Cancel</button>
      <button class="btn primary" :disabled="!importer.source || !importer.picked.size || importer.loading" @click="doImport">Import {{ importer.picked.size || '' }} as copies</button>
    </div>
  </div>
</div>

<div v-if="lightbox" class="overlay lightbox" @click="lightbox = null">
  <shot :src="lightbox.src" :bbox="lightbox.bbox"/>
</div>
`,
  };

  createApp(App).component('json-tree', JsonTree).mount('#app');
})();
