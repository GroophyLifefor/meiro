#!/usr/bin/env node
// mockref: renders every screen of the Meiro Android HTML mock in both themes and
// writes the reference PNGs, per-screen element inventories, a self-audit of the mock
// (overflow / centring / row alignment / font) and the design tokens.
// Usage: node mockref.mjs [--only key1,key2] [--no-png]
import { chromium } from 'playwright';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath, pathToFileURL } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const repo = path.resolve(here, '../..');
// --mock FILE --out DIR: render another copy of the mock and write all outputs to DIR (negative tests).
const argv = process.argv.slice(2);
const argOf = n => argv.includes(n) ? argv[argv.indexOf(n) + 1] : null;
const mockDir = argOf('--out') ? path.resolve(argOf('--out')) : path.join(repo, 'docs/android/mock');
const mockFile = argOf('--mock') ? path.resolve(argOf('--mock')) : path.join(mockDir, 'index.html');
const refDir = path.join(mockDir, 'ref');
const invDir = path.join(mockDir, 'inventory');
const fontFile = path.join(repo, 'fonts/GoogleSans.ttf');
const THEMES = ['light', 'dark'];

const args = process.argv.slice(2);
const only = args.includes('--only') ? args[args.indexOf('--only') + 1].split(',') : null;
const noPng = args.includes('--no-png');

fs.mkdirSync(refDir, { recursive: true });
fs.mkdirSync(invDir, { recursive: true });

/* ------------------------------------------------------------------ in-page analysis */
// Runs inside the page. Self-contained (Playwright serialises it with toString()).
function analyze(theme) {
  const box = document.getElementById('box');
  const BR = box.getBoundingClientRect();
  const r05 = v => Math.round(v * 2) / 2;
  const r2 = v => Math.round(v * 100) / 100;
  const csCache = new Map();
  const CS = el => { let c = csCache.get(el); if (!c) { c = getComputedStyle(el); csCache.set(el, c); } return c; };
  const px = v => { const n = parseFloat(v); return Number.isFinite(n) ? n : 0; };

  // icon-name lookup from the mock's own P / LOGO tables
  const iconByPath = {};
  try { for (const [k, d] of Object.entries(P)) iconByPath[d] = k; } catch (e) { /* P missing */ }
  const logoByHtml = {};
  try {
    for (const [k, h] of Object.entries(LOGO)) {
      const t = document.createElement('template'); t.innerHTML = h;
      logoByHtml[t.content.firstChild.outerHTML] = 'logo:' + k;
    }
  } catch (e) { /* LOGO missing */ }
  function iconName(svg) {
    const paths = svg.querySelectorAll('path');
    if (paths.length === 1 && iconByPath[paths[0].getAttribute('d')]) return iconByPath[paths[0].getAttribute('d')];
    const h = svg.outerHTML;
    if (logoByHtml[h]) return logoByHtml[h];
    return 'svg:unknown';
  }

  function segment(el) {
    let s = el.tagName.toLowerCase();
    if (el.id) s += '#' + el.id;
    const cls = [...el.classList].join('.');
    if (cls) s += '.' + cls;
    const i = [...el.parentElement.children].indexOf(el) + 1;
    return s + ':nth-child(' + i + ')';
  }
  const pathCache = new Map();
  function pathOf(el) {
    if (el === box) return '#box';
    let p = pathCache.get(el);
    if (p) return p;
    p = pathOf(el.parentElement) + '>' + segment(el);
    pathCache.set(el, p);
    return p;
  }

  const isClip = v => v === 'hidden' || v === 'clip';
  const isScroll = v => v === 'auto' || v === 'scroll';

  // visibility + clipping walk
  function visInfo(el) {
    const cs = CS(el);
    if (cs.display === 'none' || cs.display === 'contents') return null;
    if (cs.visibility === 'hidden' || cs.visibility === 'collapse') return null;
    let op = 1;
    for (let a = el; a && a !== document.documentElement; a = a.parentElement) {
      const acs = CS(a);
      if (acs.display === 'none') return null;
      op *= parseFloat(acs.opacity);
      if (a === box) break;
    }
    if (op < 0.01) return null;
    const rect = el.getBoundingClientRect();
    if (rect.width <= 0 || rect.height <= 0) return null;
    const r = { l: rect.left, t: rect.top, r: rect.right, b: rect.bottom };
    const info = { rect, offscreen: false, clip: null, scrollAncestor: { x: false, y: false } };
    for (let a = el.parentElement; a; a = a.parentElement) {
      const acs = CS(a);
      const ar = a.getBoundingClientRect();
      const L = ar.left + a.clientLeft, T = ar.top + a.clientTop;
      const R = L + a.clientWidth, B = T + a.clientHeight;
      for (const ax of ['x', 'y']) {
        const o = ax === 'x' ? acs.overflowX : acs.overflowY;
        const lo = ax === 'x' ? L : T, hi = ax === 'x' ? R : B;
        const s = ax === 'x' ? 'l' : 't', e = ax === 'x' ? 'r' : 'b';
        if (isClip(o)) {
          if (r[e] <= lo || r[s] >= hi) return null;
          if (r[s] < lo - 0.5 || r[e] > hi + 0.5) {
            const amt = Math.max(lo - r[s], r[e] - hi);
            if (!info.clip || amt > info.clip.amount) info.clip = { by: a, axis: ax, amount: amt };
          }
          r[s] = Math.max(r[s], lo); r[e] = Math.min(r[e], hi);
        } else if (isScroll(o)) {
          info.scrollAncestor[ax] = true;
          if (r[e] <= lo || r[s] >= hi || r[s] < lo - 0.5 || r[e] > hi + 0.5) info.offscreen = true;
          if (r[e] <= lo || r[s] >= hi) { r[s] = lo; r[e] = hi; } else { r[s] = Math.max(r[s], lo); r[e] = Math.min(r[e], hi); }
        }
      }
      if (a === box) break;
    }
    return info;
  }

  const rel = rc => ({ x: r05(rc.left - BR.left), y: r05(rc.top - BR.top), w: r05(rc.width), h: r05(rc.height) });
  const colorKeys = ['color', 'backgroundColor', 'borderColor', 'fill'];
  function styleOf(el) {
    const cs = CS(el);
    return {
      fontFamily: cs.fontFamily, fontSize: cs.fontSize, fontWeight: cs.fontWeight, lineHeight: cs.lineHeight,
      letterSpacing: cs.letterSpacing, borderRadius: cs.borderRadius, opacity: cs.opacity,
      borderWidth: cs.borderTopWidth, textAlign: cs.textAlign, textTransform: cs.textTransform,
      color: cs.color, backgroundColor: cs.backgroundColor, borderColor: cs.borderTopColor, fill: cs.fill,
    };
  }
  const hasAlpha = c => { const m = c.match(/rgba?\(([^)]+)\)/); if (!m) return false; const p = m[1].split(/[ ,/]+/).filter(Boolean); return p.length < 4 || parseFloat(p[3]) > 0; };
  function ownText(el) {
    let t = '';
    for (const n of el.childNodes) if (n.nodeType === 3) t += n.nodeValue;
    return t.replace(/\s+/g, ' ').trim();
  }
  function textRect(el) {
    let l = Infinity, t = Infinity, r = -Infinity, b = -Infinity, any = false;
    for (const n of el.childNodes) {
      if (n.nodeType !== 3 || !n.nodeValue.trim()) continue;
      const rg = document.createRange(); rg.selectNodeContents(n);
      for (const q of rg.getClientRects()) { if (!q.width && !q.height) continue; any = true; l = Math.min(l, q.left); t = Math.min(t, q.top); r = Math.max(r, q.right); b = Math.max(b, q.bottom); }
    }
    return any ? { left: l, top: t, right: r, bottom: b, width: r - l, height: b - t } : null;
  }
  function pseudoGeom(el, ps) {
    const pcs = getComputedStyle(el, ps);
    const content = pcs.content;
    if (!content || content === 'none' || content === 'normal') return null;
    if (pcs.display === 'none') return { exists: true, rect: null, pcs };
    if (pcs.position !== 'absolute') return { exists: true, rect: null, pcs };
    let cb = el;
    while (cb && CS(cb).position === 'static') cb = cb.parentElement;
    if (!cb) return { exists: true, rect: null, pcs };
    const ccs = CS(cb), cr = cb.getBoundingClientRect();
    const pl = cr.left + px(ccs.borderLeftWidth), pt = cr.top + px(ccs.borderTopWidth);
    const pw = cr.width - px(ccs.borderLeftWidth) - px(ccs.borderRightWidth);
    const ph = cr.height - px(ccs.borderTopWidth) - px(ccs.borderBottomWidth);
    let w = px(pcs.width), h = px(pcs.height);
    let left = pl + px(pcs.left), top = pt + px(pcs.top);
    let cx = left + w / 2, cy = top + h / 2;
    const tr = pcs.transform;
    if (tr && tr !== 'none') {
      const m = tr.match(/matrix\(([^)]+)\)/);
      if (m) { const v = m[1].split(',').map(parseFloat); w *= Math.hypot(v[0], v[1]); h *= Math.hypot(v[2], v[3]); cx += v[4]; cy += v[5]; }
    }
    return { exists: true, rect: { left: cx - w / 2, top: cy - h / 2, right: cx + w / 2, bottom: cy + h / 2, width: w, height: h }, pcs, pad: { l: pl, t: pt, w: pw, h: ph } };
  }

  const inventory = [];
  const clippedText = [];
  const audit = [];
  const checked = { elements: 0, overflowHidden: 0, centringContainers: 0, centringIntended: 0, pseudo: 0, flexRowsCentre: 0, flexRowsLines: 0 };
  const find = (cat, heuristic, el, extra) => audit.push({ category: cat, heuristic, path: pathOf(el), ...extra });

  const all = [box, ...box.querySelectorAll('*')];
  const visMap = new Map();
  for (const el of all) {
    if (el.closest('svg') && el.tagName.toLowerCase() !== 'svg') continue;
    visMap.set(el, visInfo(el));
  }

  for (const el of all) {
    if (!visMap.has(el)) continue;
    const vi = visMap.get(el);
    if (!vi) continue;
    const cs = CS(el);
    checked.elements++;
    const tag = el.tagName.toLowerCase();
    const text = ownText(el);
    const role = el.getAttribute('role');
    const aria = el.getAttribute('aria-label');
    const isIcon = tag === 'svg';
    const control = ['button', 'input', 'textarea', 'select', 'a', 'label'].includes(tag) || !!role;
    const leaf = el.children.length === 0;
    const surface = hasAlpha(cs.backgroundColor) || (px(cs.borderTopWidth) > 0 && hasAlpha(cs.borderTopColor)) || cs.boxShadow !== 'none';
    const rect = vi.rect;

    if (el !== box && (leaf || text || isIcon || control || aria || surface)) {
      const kind = isIcon ? 'icon' : control ? 'control' : text ? 'text' : leaf ? 'leaf' : 'surface';
      const rec = {
        path: pathOf(el), tag, classes: [...el.classList], kind,
        role: role || null, ariaLabel: aria || null, text: text || null,
        icon: isIcon ? iconName(el) : null,
        bbox: rel(rect), offscreen: vi.offscreen, clipped: !!vi.clip,
        style: styleOf(el),
      };
      if (tag === 'input' || tag === 'textarea') { rec.value = el.value; rec.placeholder = el.getAttribute('placeholder'); }
      try { const top = document.elementFromPoint(Math.min(Math.max(rect.left + rect.width / 2, 0), innerWidth - 1), Math.min(Math.max(rect.top + rect.height / 2, 0), innerHeight - 1)); rec.occluded = !vi.offscreen && !!top && !el.contains(top) && !top.contains(el) && top !== document.documentElement && top !== document.body; } catch (e) { rec.occluded = false; }
      inventory.push(rec);
    }
    for (const ps of ['::before', '::after']) {
      const g = pseudoGeom(el, ps);
      if (!g || !g.exists) continue;
      const pcs = g.pcs;
      inventory.push({
        path: pathOf(el) + ps, tag: ps, classes: [...el.classList], kind: 'pseudo', role: null, ariaLabel: null,
        text: pcs.content.replace(/^"|"$/g, '') || null, icon: null,
        bbox: g.rect ? rel(g.rect) : null, offscreen: vi.offscreen, clipped: false,
        style: { fontFamily: pcs.fontFamily, fontSize: pcs.fontSize, fontWeight: pcs.fontWeight, lineHeight: pcs.lineHeight, letterSpacing: pcs.letterSpacing, borderRadius: pcs.borderRadius, opacity: pcs.opacity, borderWidth: pcs.borderTopWidth, textAlign: pcs.textAlign, textTransform: pcs.textTransform, color: pcs.color, backgroundColor: pcs.backgroundColor, borderColor: pcs.borderTopColor, fill: pcs.fill },
      });
    }

    /* ---------- audit (a): overflow / clipping ---------- */
    const ellipsis = cs.textOverflow === 'ellipsis' && cs.whiteSpace === 'nowrap';
    const clamp = cs.webkitLineClamp && cs.webkitLineClamp !== 'none';
    for (const ax of ['x', 'y']) {
      const o = ax === 'x' ? cs.overflowX : cs.overflowY;
      if (!isClip(o)) continue;
      checked.overflowHidden++;
      const sd = ax === 'x' ? el.scrollWidth - el.clientWidth : el.scrollHeight - el.clientHeight;
      if (sd <= 0.5) continue;
      const tc = (el.textContent || '').replace(/\s+/g, ' ').trim();
      if ((ellipsis && ax === 'x') || (clamp && ax === 'y')) {
        clippedText.push({ path: pathOf(el), text: tc.slice(0, 80), reason: ellipsis && ax === 'x' ? 'ellipsis' : 'line-clamp', overflowPx: r2(sd), axis: ax });
      } else if (el !== box) {
        const isText = !!tc;
        if (isText) clippedText.push({ path: pathOf(el), text: tc.slice(0, 80), reason: 'hidden-clip', overflowPx: r2(sd), axis: ax });
        find('overflow', 'scroll-size>client-size on overflow:hidden/clip, no ellipsis/line-clamp', el, { axis: ax, delta: r2(sd), detail: { scrollSize: ax === 'x' ? el.scrollWidth : el.scrollHeight, clientSize: ax === 'x' ? el.clientWidth : el.clientHeight, text: tc.slice(0, 40) } });
      }
    }
    if (vi.clip && el !== box) {
      const parentInfo = visMap.get(el.parentElement);
      const pc = parentInfo && parentInfo.clip;
      if (!(pc && pc.by === vi.clip.by)) {
        const byBox = vi.clip.by === box;
        find(byBox ? 'outside-screen' : 'clipped-by-ancestor', byBox ? 'element bbox extends beyond phone screen' : 'element rect partly outside an overflow:hidden/clip ancestor (topmost element only)', el, { axis: vi.clip.axis, delta: r2(vi.clip.amount), detail: { clippedBy: pathOf(vi.clip.by), bbox: rel(rect) } });
      }
    }

    /* ---------- audit (e): containment ---------- */
    // A visible element must lie inside every ancestor that visually encloses it (control, background,
    // border, overflow hidden/clip) within 0.5px. Exclusions (by design): the phone box itself; walking
    // stops at a scroll container (content scrolls); offscreen elements; abs/fixed elements with a
    // negative inset (badges, ripples) are skipped and their descendants are only measured up to them.
    if (el !== box && !vi.offscreen) {
      let stop = false;
      const exclAbs = e => { const c = CS(e); return (c.position === 'absolute' || c.position === 'fixed') && ['top', 'right', 'bottom', 'left'].some(k => px(c[k]) < 0); };
      if (exclAbs(el)) stop = true;
      for (let a = el.parentElement; a && a !== box && !stop; a = a.parentElement) {
        const acs = CS(a);
        if (isScroll(acs.overflowX) || isScroll(acs.overflowY)) break;
        const aControl = ['button', 'input', 'textarea', 'select', 'a', 'label'].includes(a.tagName.toLowerCase()) || !!a.getAttribute('role');
        const aSurface = hasAlpha(acs.backgroundColor) || (px(acs.borderTopWidth) > 0 && hasAlpha(acs.borderTopColor)) || isClip(acs.overflowX) || isClip(acs.overflowY);
        if ((aControl || aSurface) && acs.display !== 'contents' && acs.display !== 'inline') {
          const ar = a.getBoundingClientRect();
          if (ar.width > 0 && ar.height > 0) {
            const ov = { left: ar.left - rect.left, top: ar.top - rect.top, right: rect.right - ar.right, bottom: rect.bottom - ar.bottom };
            const worst = Object.entries(ov).sort((x, y) => y[1] - x[1])[0];
            if (worst[1] > 0.5) {
              const pi = visMap.get(el.parentElement);
              const parentSame = el.parentElement !== a && pi && (() => { const pr = pi.rect; return (ar.left - pr.left > 0.5 || ar.top - pr.top > 0.5 || pr.right - ar.right > 0.5 || pr.bottom - ar.bottom > 0.5); })();
              if (!parentSame) find('containment', 'element bbox extends beyond bbox of an enclosing control/background/border/overflow-clip ancestor by >0.5px', el, { axis: (worst[0] === 'left' || worst[0] === 'right') ? 'x' : 'y', delta: r2(worst[1]), detail: { side: worst[0], enclosedBy: pathOf(a), elementBox: rel(rect), ancestorBox: rel(ar), overflowBySide: Object.fromEntries(Object.entries(ov).map(([k, v]) => [k, r2(v)])) } });
              break;
            }
          }
        }
        if (exclAbs(a)) break;
      }
    }

    /* ---------- audit (d): font family ---------- */
    if (text && tag !== 'svg') {
      const fam = cs.fontFamily.split(',')[0].trim().replace(/["']/g, '');
      if (fam !== 'Google Sans') find('font', 'first computed font-family != Google Sans on element with text', el, { delta: 0, detail: { fontFamily: cs.fontFamily, text: text.slice(0, 40) } });
    }
    if ((tag === 'input' || tag === 'textarea') ) {
      const fam = cs.fontFamily.split(',')[0].trim().replace(/["']/g, '');
      if (fam !== 'Google Sans') find('font', 'input font-family != Google Sans', el, { delta: 0, detail: { fontFamily: cs.fontFamily } });
    }

    /* ---------- audit (b): centring ---------- */
    if (!isIcon && cs.display !== 'inline') {
      const kids = [];
      let pseudoCount = 0;
      for (const c of el.children) {
        const cvi = visMap.get(c);
        if (c.closest('svg') && c.tagName.toLowerCase() !== 'svg') continue;
        if (cvi) kids.push({ type: 'el', node: c, rect: cvi.rect });
      }
      const tr = textRect(el);
      if (tr) kids.push({ type: 'text', node: el, rect: tr });
      for (const ps of ['::before', '::after']) { const g = pseudoGeom(el, ps); if (g) pseudoCount++; }
      if (kids.length === 1 && pseudoCount === 0 && (cs.display === 'flex' || cs.display === 'inline-flex' || cs.display === 'grid' || cs.display === 'inline-grid' || cs.display === 'block' || cs.display === 'inline-block' || cs.display === 'list-item')) {
        const k = kids[0];
        checked.centringContainers++;
        const kcs = k.type === 'el' ? CS(k.node) : null;
        const er = rect;
        const L = er.left + px(cs.borderLeftWidth) + px(cs.paddingLeft), R = er.right - px(cs.borderRightWidth) - px(cs.paddingRight);
        const T = er.top + px(cs.borderTopWidth) + px(cs.paddingTop), B = er.bottom - px(cs.borderBottomWidth) - px(cs.paddingBottom);
        const cx = (L + R) / 2, cy = (T + B) / 2;
        const kx = (k.rect.left + k.rect.right) / 2, ky = (k.rect.top + k.rect.bottom) / 2;
        let ix = false, iy = false, why = '';
        const spaced = v => v === 'center' || v === 'space-around' || v === 'space-evenly';
        if (cs.display.endsWith('flex')) {
          const row = cs.flexDirection.startsWith('row');
          const main = spaced(cs.justifyContent), cross = cs.alignItems === 'center' || (kcs && kcs.alignSelf === 'center');
          ix = row ? main : cross; iy = row ? cross : main; why = 'flex centred';
        } else if (cs.display.endsWith('grid')) {
          ix = cs.justifyItems === 'center' || spaced(cs.justifyContent) || (kcs && kcs.justifySelf === 'center');
          iy = cs.alignItems === 'center' || spaced(cs.alignContent) || (kcs && kcs.alignSelf === 'center');
          why = 'grid centred';
        } else {
          const inlineKid = k.type === 'text' || (kcs && kcs.display.startsWith('inline'));
          if (cs.textAlign === 'center' && inlineKid) { ix = true; why = 'text-align:center'; }
          if (tag === 'button') { iy = true; why = (why ? why + ' + ' : '') + 'button UA vertical centring'; if (cs.textAlign === 'center' && !inlineKid) ix = true; }
          else if (k.type === 'text' && Math.abs(px(cs.lineHeight) - (B - T)) <= 1) { iy = true; why = (why ? why + ' + ' : '') + 'line-height == content height'; }
        }
        if (ix || iy) checked.centringIntended++;
        for (const [ax, d, on] of [['x', kx - cx, ix], ['y', ky - cy, iy]]) {
          if (on && Math.abs(d) > 0.5) find('centring', why, el, { axis: ax, delta: r2(d), detail: { child: k.type === 'text' ? 'text "' + ownText(el).slice(0, 20) + '"' : pathOf(k.node), childCentre: r2(ax === 'x' ? kx : ky), containerContentCentre: r2(ax === 'x' ? cx : cy), childBox: rel(k.rect), containerContentBox: { x: r05(L - BR.left), y: r05(T - BR.top), w: r05(R - L), h: r05(B - T) } } });
        }
      }
      // pseudo elements (switch knobs, tab indicators, radio dots): measured against the padding box
      for (const ps of ['::before', '::after']) {
        const g = pseudoGeom(el, ps);
        if (!g || !g.rect) continue;
        const pr = g.rect, pad = g.pad;
        checked.pseudo++;
        const kx = (pr.left + pr.right) / 2, ky = (pr.top + pr.bottom) / 2;
        const cx = pad.l + pad.w / 2, cy = pad.t + pad.h / 2;
        for (const [ax, d] of [['x', kx - cx], ['y', ky - cy]]) {
          const a = Math.abs(d);
          // near miss only: far offsets (switch knob at one end) are by design
          if (a > 0.5 && a <= 4) find('centring', 'abs-positioned ' + ps + ' within 4px of centre but not centred (near-miss band)', el, { axis: ax, delta: r2(d), detail: { pseudo: ps, pseudoBox: rel(pr), paddingBoxCentre: r2(ax === 'x' ? cx : cy) } });
        }
        // pill track with round knob: edge gap on x must equal edge gap on y
        const br = px(CS(el).borderTopLeftRadius);
        if (br >= pad.h / 2 - 0.5 && g.pcs.borderRadius !== '0px') {
          const gx = Math.min(pr.left - pad.l, pad.l + pad.w - pr.right), gy = Math.min(pr.top - pad.t, pad.t + pad.h - pr.bottom);
          if (Math.abs(gx - gy) > 0.5) find('centring', 'knob-in-pill: nearest horizontal edge gap != vertical edge gap', el, { axis: 'xy', delta: r2(gx - gy), detail: { pseudo: ps, gapX: r2(gx), gapY: r2(gy) } });
        }
      }
    }

    /* ---------- audit (c): rows ---------- */
    if ((cs.display === 'flex' || cs.display === 'inline-flex') && cs.flexDirection.startsWith('row')) {
      const kids = [];
      for (const c of el.children) {
        const cvi = visMap.get(c);
        if (!cvi) continue;
        const kc = CS(c);
        if (kc.position === 'absolute' || kc.position === 'fixed') continue;
        kids.push({ c, rect: cvi.rect, cs: kc });
      }
      if (kids.length >= 2 && cs.flexWrap !== 'nowrap') { /* wrapped rows span several lines: not comparable */ }
      else if (kids.length >= 2 && cs.alignItems === 'center') {
        const set = kids.filter(k => k.cs.alignSelf === 'auto' || k.cs.alignSelf === 'center' || k.cs.alignSelf === 'normal');
        if (set.length >= 2) {
          checked.flexRowsCentre++;
          const cys = set.map(k => (k.rect.top + k.rect.bottom) / 2);
          const sp = Math.max(...cys) - Math.min(...cys);
          if (sp > 0.5) find('row', 'flex row align-items:center, in-flow children centre-y spread', el, { axis: 'y', delta: r2(sp), detail: { children: set.map((k, i) => ({ path: pathOf(k.c), centreY: r2(cys[i] - BR.top), box: rel(k.rect) })) } });
        }
      }
      if (kids.length >= 2 && cs.flexWrap === 'nowrap' && cs.alignItems !== 'center') {
        checked.flexRowsLines++;
        const byClass = {};
        for (const k of kids) (byClass[k.c.className || k.c.tagName] ||= []).push(k);
        for (const [cls, g] of Object.entries(byClass)) {
          if (g.length < 2) continue;
          const tops = g.map(k => k.rect.top), bots = g.map(k => k.rect.bottom);
          const dt = Math.max(...tops) - Math.min(...tops), db = Math.max(...bots) - Math.min(...bots);
          const ai = cs.alignItems, chkT = ['stretch', 'normal', 'flex-start', 'start'].includes(ai), chkB = ['stretch', 'normal', 'flex-end', 'end'].includes(ai);
          if ((chkT && dt > 0.5) || (chkB && db > 0.5)) find('row', 'flex row siblings of same class: top/bottom lines differ', el, { axis: 'y', delta: r2(Math.max(chkT ? dt : 0, chkB ? db : 0)), detail: { alignItems: ai, siblingClass: String(cls), topSpread: r2(dt), bottomSpread: r2(db) } });
        }
      }
    }
  }

  return { size: { w: r05(BR.width), h: r05(BR.height) }, inventory, clippedText, audit, checked };
}

/* ------------------------------------------------------------------ tokens in-page */
function readTokens() {
  const names = { root: new Set(), light: new Set() };
  const fonts = [];
  const sheet0 = document.styleSheets[0]; // the mock's own <style>; the injected font override is a later sheet
  for (const rule of sheet0.cssRules) {
    if (rule.type === CSSRule.FONT_FACE_RULE) {
      fonts.push({
        family: rule.style.getPropertyValue('font-family').replace(/["']/g, ''),
        srcDeclared: rule.style.getPropertyValue('src'),
        srcUrls: [...rule.style.getPropertyValue('src').matchAll(/url\(["']?([^"')]+)["']?\)/g)].map(m => m[1]),
        weight: rule.style.getPropertyValue('font-weight'),
        style: rule.style.getPropertyValue('font-style') || 'normal',
        display: rule.style.getPropertyValue('font-display'),
      });
    }
    if (rule.type === CSSRule.STYLE_RULE) {
      const sel = rule.selectorText;
      const into = sel === ':root' || sel === 'html' ? names.root : sel.includes('data-theme="light"') ? names.light : null;
      if (into) for (const p of rule.style) if (p.startsWith('--')) into.add(p);
    }
  }
  const html = document.documentElement;
  const prev = html.dataset.theme;
  const probe = document.createElement('span');
  document.body.appendChild(probe);
  const out = {};
  const all = [...new Set([...names.root, ...names.light])];
  for (const th of ['dark', 'light']) {
    html.dataset.theme = th;
    const cs = getComputedStyle(html);
    out[th] = {};
    for (const n of all) {
      const v = cs.getPropertyValue(n).trim();
      const e = { value: v };
      if (CSS.supports('color', v)) { probe.style.color = ''; probe.style.color = v; e.rgb = getComputedStyle(probe).color; }
      out[th][n] = e;
    }
  }
  probe.remove();
  html.dataset.theme = prev;
  const bs = getComputedStyle(document.body);
  return {
    declaredOn: { root: [...names.root], lightOverride: [...names.light] },
    vars: out, fonts,
    body: { fontFamily: bs.fontFamily, fontSize: bs.fontSize, lineHeight: bs.lineHeight, letterSpacing: bs.letterSpacing },
  };
}

/* ------------------------------------------------------------------ driver */
function mergeInventory(perTheme) {
  const byPath = new Map();
  for (const th of THEMES) {
    for (const rec of perTheme[th].inventory) {
      let m = byPath.get(rec.path);
      if (!m) {
        m = { ...rec, style: {}, themes: [], bboxByTheme: {} };
        byPath.set(rec.path, m);
        m._first = rec;
      }
      m.themes.push(th);
      m.bboxByTheme[th] = rec.bbox;
      for (const [k, v] of Object.entries(rec.style)) {
        if (['color', 'backgroundColor', 'borderColor', 'fill'].includes(k)) (m.style[k] ||= {})[th] = v;
        else if (!(k in m.style)) m.style[k] = v;
        else if (m.style[k] !== v) (m.style._themeDiff ||= {})[k] = { ...(m.style._themeDiff?.[k] || { [m.themes[0]]: m.style[k] }), [th]: v };
      }
    }
  }
  const out = [];
  for (const m of byPath.values()) {
    const diff = m.themes.length === 2 && JSON.stringify(m.bboxByTheme.light) !== JSON.stringify(m.bboxByTheme.dark);
    m.bbox = m.bboxByTheme.dark || m.bboxByTheme.light;
    m.bboxThemeDiffers = diff ? m.bboxByTheme : undefined;
    m.visibleIn = m.themes;
    delete m.themes; delete m.bboxByTheme; delete m._first;
    out.push(m);
  }
  return out;
}

const mockUrl = pathToFileURL(mockFile).href;
const browser = await chromium.launch();
const ctx = await browser.newContext({ viewport: { width: 1400, height: 1000 }, deviceScaleFactor: 1, colorScheme: 'light', reducedMotion: 'reduce' });
const page = await ctx.newPage();
const pageErrors = [];
page.on('pageerror', e => pageErrors.push(String(e)));
page.on('requestfailed', r => pageErrors.push('requestfailed ' + r.url()));
await page.goto(mockUrl);
await page.addStyleTag({ content: '*,*::before,*::after{animation:none!important;transition:none!important;caret-color:transparent!important;scroll-behavior:auto!important}' });

// The mock's @font-face points at ../meiro/fonts/GoogleSans.ttf (valid only when the mock sits
// next to the repo checkout). From docs/android/mock/ that URL does not resolve, so a later
// @font-face with identical descriptors points at the repo's real font file. The mock file itself
// stays byte-identical.
const tokens = await page.evaluate(readTokens);
await page.addStyleTag({ content: `@font-face{font-family:"Google Sans"; src:url("${pathToFileURL(fontFile).href}") format("truetype"); font-weight:100 1000; font-display:block}` });
await page.evaluate(async () => { await Promise.allSettled([document.fonts.load('400 14px "Google Sans"'), document.fonts.load('500 14px "Google Sans"')]); await document.fonts.ready; });
const fontStatus = await page.evaluate(() => [...document.fonts].map(f => ({ family: f.family, status: f.status, weight: f.weight })));
const sha = crypto.createHash('sha256').update(fs.readFileSync(fontFile)).digest('hex');
for (const f of tokens.fonts) {
  f.resolvedFromMockLocation = new URL(f.srcUrls[0], mockUrl).href;
  f.resolvesFromRepoCopy = fs.existsSync(fileURLToPath(f.resolvedFromMockLocation));
  f.repoFile = path.relative(repo, fontFile);
  f.repoFileSha256 = sha;
  f.repoFileBytes = fs.statSync(fontFile).size;
  f.note = 'Declared URL is relative to the original mock location (OSS/mobile-mock). The app must use the repo file fonts/GoogleSans.ttf (variable font, weights 100..1000).';
}
tokens.fontLoadStatus = fontStatus;
const keys = await page.evaluate(() => SCREENS.flatMap(g => g[1].map(s => s[0])));
const todo = only ? keys.filter(k => only.includes(k)) : keys;

async function settle() {
  await page.evaluate(async () => { await document.fonts.ready; await new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r))); });
}

const result = { screens: {}, failed: [] };
const allFindings = [];
const sizes = {};
const checkedTotals = {};
for (const key of todo) {
  const perTheme = {};
  try {
    for (const th of THEMES) {
      await page.evaluate(t => { document.documentElement.dataset.theme = t; }, th);
      await page.evaluate(k => goTo(k), key);
      await settle();
      perTheme[th] = await page.evaluate(analyze, th);
      sizes[key] = perTheme[th].size;
      for (const [k, v] of Object.entries(perTheme[th].checked)) checkedTotals[k] = (checkedTotals[k] || 0) + v;
      if (!noPng) await page.locator('#box').screenshot({ path: path.join(refDir, `${key}-${th}.png`), animations: 'disabled', scale: 'css' });
      for (const f of perTheme[th].audit) allFindings.push({ screen: key, theme: th, ...f });
    }
    const merged = mergeInventory(perTheme);
    const ct = new Map();
    for (const th of THEMES) for (const c of perTheme[th].clippedText) { const k = c.path + c.axis; const e = ct.get(k) || { ...c, themes: [] }; e.themes.push(th); ct.set(k, e); }
    fs.writeFileSync(path.join(invDir, `${key}.json`), JSON.stringify({
      screen: key, phoneScreenCssSize: perTheme.dark.size, deviceScaleFactor: 1, themes: THEMES,
      note: 'bbox is relative to the phone screen (#box), CSS px, rounded to 0.5. offscreen=true: below/right of a scroll container viewport (natural position at scrollTop 0). color/backgroundColor/borderColor/fill carry per-theme values.',
      elementCount: merged.length, elements: merged, clippedText: [...ct.values()],
    }, null, 1));
    result.screens[key] = { elements: merged.length };
  } catch (e) {
    result.failed.push({ key, error: String(e) });
    console.error('FAILED', key, e);
  }
}

fs.writeFileSync(path.join(mockDir, 'tokens.json'), JSON.stringify(tokens, null, 1));

/* ------------------------------------------------------------------ audit output */
const cats = {};
for (const f of allFindings) (cats[f.category] ||= []).push(f);
const uniq = new Map();
for (const f of allFindings) {
  const k = [f.category, f.heuristic, f.path, f.axis, f.delta].join('|');
  const u = uniq.get(k) || { ...f, screens: [], themes: new Set() };
  u.screens.push(f.screen); u.themes.add(f.theme);
  uniq.set(k, u);
}
const uniqList = [...uniq.values()].map(u => ({ ...u, themes: [...u.themes], screens: [...new Set(u.screens)] }));
const summary = Object.fromEntries(Object.entries(cats).map(([c, l]) => [c, { total: l.length, unique: uniqList.filter(u => u.category === c).length }]));
const top = [...uniqList].sort((a, b) => Math.abs(b.delta) - Math.abs(a.delta)).slice(0, 10);
fs.writeFileSync(path.join(mockDir, 'audit.json'), JSON.stringify({
  generatedFor: path.relative(repo, mockFile), phoneScreenCssSize: sizes[todo[0]] || null, themes: THEMES, screens: todo.length,
  note: 'Mock self-audit. Heuristic-based; false positives are possible, each finding names the heuristic. The mock is NOT modified.',
  checkedTotals, summary, fontLoadStatus: fontStatus, pageErrors, findings: allFindings,
}, null, 1));

console.log('== mockref ==');
console.log('screens rendered:', Object.keys(result.screens).length, '/', todo.length, ' failed:', result.failed.map(f => f.key).join(',') || 'none');
console.log('phone screen CSS size:', JSON.stringify(sizes[todo[0]]));
console.log('fonts:', fontStatus.map(f => `${f.family}:${f.status}`).join(', '));
console.log('checks run (all screens x themes):', JSON.stringify(checkedTotals));
console.log('audit findings (total/unique):');
for (const [c, s] of Object.entries(summary)) console.log(`  ${c}: ${s.total} / ${s.unique}`);
if (!Object.keys(summary).length) console.log('  none');
console.log('top 10 by magnitude (px):');
for (const t of top) console.log(`  [${t.category}] ${t.screens.slice(0, 3).join(',')}${t.screens.length > 3 ? '+' : ''} ${t.themes.join('/')} ${t.path} axis=${t.axis || '-'} delta=${t.delta}  (${t.heuristic})`);
if (pageErrors.length) console.log('page errors:', pageErrors.join(' | '));
await browser.close();
if (result.failed.length) process.exit(1);
