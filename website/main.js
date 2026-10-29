// SPDX-License-Identifier: Apache-2.0
// Progressive enhancement only: without this script every screenshot, step and answer is still on the page.
(function () {
  var root = document.documentElement;
  root.classList.add('js');
  var reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches;

  function ready(fn) {
    if (document.readyState !== 'loading') fn();
    else document.addEventListener('DOMContentLoaded', fn);
  }

  function copyText(text, stateEl, idle) {
    function done(ok) {
      stateEl.textContent = ok ? 'Copied' : 'Select and copy';
      setTimeout(function () { stateEl.textContent = idle; }, 1600);
    }
    if (navigator.clipboard && navigator.clipboard.writeText) navigator.clipboard.writeText(text).then(function () { done(true); }, function () { done(false); });
    else done(false);
  }

  // Something that advances on its own: pauses while hovered, focused, off screen or in a hidden tab.
  function autoplay(el, interval, advance) {
    var timer = null, hovered = false, focused = false, visible = true;
    el.style.setProperty('--interval', interval + 'ms');
    function running() { return !reduce && !hovered && !focused && visible && !document.hidden; }
    function schedule() {
      clearTimeout(timer);
      if (reduce) { el.removeAttribute('data-playing'); return; }
      el.setAttribute('data-playing', '');
      if (running()) { el.removeAttribute('data-paused'); timer = setTimeout(advance, interval); }
      else el.setAttribute('data-paused', '');
    }
    // Pausing a CSS progress bar mid-way and resuming a fresh timer would desync them; restart both instead.
    function resume() { if (running()) restart(); else schedule(); }
    function restart() {
      el.removeAttribute('data-playing');
      void el.offsetWidth;
      schedule();
    }
    el.addEventListener('mouseenter', function () { hovered = true; schedule(); });
    el.addEventListener('mouseleave', function () { hovered = false; resume(); });
    // Only keyboard focus pauses: a mouse click leaves focus on the button, which would hold it forever.
    el.addEventListener('focusin', function (e) {
      var keyboard = true;
      try { keyboard = e.target.matches(':focus-visible'); } catch (err) { /* no :focus-visible support */ }
      if (keyboard) { focused = true; schedule(); }
    });
    el.addEventListener('focusout', function (e) { if (!el.contains(e.relatedTarget)) { focused = false; resume(); } });
    document.addEventListener('visibilitychange', resume);
    if ('IntersectionObserver' in window) {
      new IntersectionObserver(function (entries) { visible = entries[0].isIntersecting; resume(); }, { threshold: 0.25 }).observe(el);
    }
    return { restart: restart };
  }

  ready(function () {
    // Phone menu. The back gesture closes it, and focus stays inside while it is open.
    var menu = document.getElementById('site-menu');
    var menuBtn = document.querySelector('.nav .menu-btn');
    if (menu && menuBtn) {
      var closeBtn = menu.querySelector('[data-menu-close]');
      var here = location.pathname.replace(/index\.html$/, '');
      menu.querySelectorAll('.site-menu-links a').forEach(function (a) {
        if (a.getAttribute('href') === here) a.setAttribute('aria-current', 'page');
      });
      var isOpen = false;
      function focusables() { return menu.querySelectorAll('a[href], button'); }
      function openMenu() {
        if (isOpen) return;
        isOpen = true;
        menu.hidden = false;
        void menu.offsetWidth; // so the fade-in runs
        menu.classList.add('open');
        document.body.classList.add('menu-open');
        menuBtn.setAttribute('aria-expanded', 'true');
        history.pushState({ causelineMenu: true }, '');
        closeBtn.focus();
      }
      function closeMenu(fromHistory, keepFocus) {
        if (!isOpen) return;
        isOpen = false;
        menu.classList.remove('open');
        document.body.classList.remove('menu-open');
        menuBtn.setAttribute('aria-expanded', 'false');
        setTimeout(function () { if (!isOpen) menu.hidden = true; }, reduce ? 0 : 230);
        if (!fromHistory && history.state && history.state.causelineMenu) history.back();
        if (!keepFocus) menuBtn.focus();
      }
      menuBtn.addEventListener('click', openMenu);
      closeBtn.addEventListener('click', function () { closeMenu(false); });
      window.addEventListener('popstate', function () { if (isOpen) closeMenu(true); });
      menu.addEventListener('click', function (e) {
        var a = e.target.closest('a[href]');
        if (!a) return;
        // Leave the menu's history entry behind without going back, then follow the link.
        e.preventDefault();
        var href = a.href;
        if (history.state && history.state.causelineMenu) history.replaceState(null, '');
        closeMenu(true, true);
        location.href = href;
      });
      menu.addEventListener('keydown', function (e) {
        if (e.key === 'Escape') { e.preventDefault(); closeMenu(false); return; }
        if (e.key !== 'Tab') return;
        var f = focusables(), first = f[0], last = f[f.length - 1];
        if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
        else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
      });
      window.matchMedia('(min-width: 721px)').addEventListener('change', function (m) { if (m.matches) closeMenu(false, true); });
    }

    // Hero: a dot field that bends towards the pointer, like a sheet under a weight. Canvas 2D, mouse only.
    var field = document.querySelector('.hero-field');
    if (field && field.getContext) {
      var ctx = field.getContext('2d'), hero = field.parentElement;
      var GAP = 26, SIGMA = 120, PULL = 26;
      var w = 0, h = 0, dots = [], px = -1e4, py = -1e4, cx = -1e4, cy = -1e4, strength = 0, target = 0, raf = 0, onScreen = true;
      function build() {
        var r = field.getBoundingClientRect(), dpr = Math.min(2, window.devicePixelRatio || 1);
        w = r.width; h = r.height;
        field.width = Math.round(w * dpr); field.height = Math.round(h * dpr);
        ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
        dots = [];
        for (var y = GAP / 2; y < h; y += GAP) for (var x = GAP / 2; x < w; x += GAP) dots.push(x, y);
        draw();
      }
      function draw() {
        ctx.clearRect(0, 0, w, h);
        var s2 = 2 * SIGMA * SIGMA;
        for (var i = 0; i < dots.length; i += 2) {
          var x = dots[i], y = dots[i + 1], dx = cx - x, dy = cy - y, d2 = dx * dx + dy * dy;
          var f = strength > 0.001 ? strength * Math.exp(-d2 / s2) : 0;
          if (f > 0.002) { var d = Math.sqrt(d2) + 1, m = Math.min(f * PULL, d * 0.6); x += dx / d * m; y += dy / d * m; }
          var size = 1.3 + f * 1.8;
          ctx.fillStyle = f > 0.25 ? 'rgba(255,106,47,' + (0.25 + f * 0.6).toFixed(3) + ')' : 'rgba(255,255,255,' + (0.09 + f * 0.5).toFixed(3) + ')';
          ctx.fillRect(x - size / 2, y - size / 2, size, size);
        }
      }
      function tick() {
        raf = 0;
        cx += (px - cx) * 0.16; cy += (py - cy) * 0.16; strength += (target - strength) * 0.08;
        draw();
        var settling = Math.abs(target - strength) > 0.004 || Math.abs(px - cx) > 0.5 || Math.abs(py - cy) > 0.5;
        if (settling && onScreen) raf = requestAnimationFrame(tick);
      }
      function wake() { if (!raf && !reduce && onScreen) raf = requestAnimationFrame(tick); }
      hero.addEventListener('pointermove', function (e) {
        if (e.pointerType !== 'mouse') return;
        var r = field.getBoundingClientRect();
        px = e.clientX - r.left; py = e.clientY - r.top;
        if (cx < -1e3) { cx = px; cy = py; }
        target = 1; wake();
      });
      hero.addEventListener('pointerleave', function () { target = 0; wake(); });
      if ('IntersectionObserver' in window) new IntersectionObserver(function (en) { onScreen = en[0].isIntersecting; if (onScreen) wake(); }).observe(field);
      if ('ResizeObserver' in window) new ResizeObserver(build).observe(hero); else window.addEventListener('resize', build);
      build();
    }

    // Why: the lead line comes into focus one word at a time.
    var lead = document.querySelector('.why-lead');
    if (lead && !reduce && 'IntersectionObserver' in window) {
      var words = [];
      (function split(node) {
        Array.prototype.slice.call(node.childNodes).forEach(function (c) {
          if (c.nodeType === 3) {
            var frag = document.createDocumentFragment();
            c.textContent.split(/(\s+)/).forEach(function (part) {
              if (!part) return;
              if (/^\s+$/.test(part)) { frag.appendChild(document.createTextNode(part)); return; }
              var sp = document.createElement('span'); sp.className = 'fw'; sp.textContent = part; frag.appendChild(sp); words.push(sp);
            });
            node.replaceChild(frag, c);
          } else if (c.nodeType === 1) split(c);
        });
      })(lead);
      var frame = document.createElement('span');
      frame.className = 'focus-frame'; frame.setAttribute('aria-hidden', 'true');
      lead.appendChild(frame);
      var focusIo = new IntersectionObserver(function (en) {
        if (!en[0].isIntersecting) return;
        focusIo.disconnect();
        var n = 0;
        setTimeout(function step() {
          if (n >= words.length) { frame.style.opacity = '0'; return; }
          var wd = words[n], base = lead.getBoundingClientRect(), r = wd.getBoundingClientRect(), pad = 4;
          frame.style.opacity = '1';
          frame.style.width = (r.width + pad * 2) + 'px';
          frame.style.height = (r.height + pad) + 'px';
          frame.style.transform = 'translate(' + (r.left - base.left - pad) + 'px,' + (r.top - base.top - pad / 2) + 'px)';
          wd.classList.add('sharp');
          n++;
          setTimeout(step, 115);
        }, 450);
      }, { threshold: 0.6 });
      focusIo.observe(lead);
    }

    // Hero screenshots.
    document.querySelectorAll('[data-carousel]').forEach(function (box) {
      var track = box.querySelector('.shots-track');
      var slides = box.querySelectorAll('.shot');
      var dots = box.querySelectorAll('.shots-dots button');
      var caption = box.querySelector('[data-caption]');
      var index = 0, play;
      var view = box.querySelector('.shots-view');
      // Centre the active slide in the view; its neighbours show at the edges.
      function place() {
        var s = slides[index];
        var offset = s.offsetLeft - (view.clientWidth - s.offsetWidth) / 2;
        track.style.transform = 'translateX(' + (-offset) + 'px)';
      }
      window.addEventListener('resize', place);
      slides.forEach(function (s, n) { s.addEventListener('click', function () { if (n !== index) go(n, true); }); });
      function go(i, user) {
        index = (i + slides.length) % slides.length;
        place();
        dots.forEach(function (d, n) { d.setAttribute('aria-current', String(n === index)); });
        slides.forEach(function (s, n) { s.setAttribute('aria-hidden', String(n !== index)); s.classList.toggle('is-active', n === index); });
        caption.innerHTML = '<strong>' + dots[index].getAttribute('data-title') + '</strong><span>' + dots[index].getAttribute('data-text') + '</span>';
        if (play) play.restart();
      }
      dots.forEach(function (d, n) { d.addEventListener('click', function () { go(n, true); }); });
      // Swipe on touch screens.
      var startX = null;
      box.querySelector('.shots-view').addEventListener('pointerdown', function (e) { startX = e.clientX; });
      box.querySelector('.shots-view').addEventListener('pointerup', function (e) {
        if (startX === null) return;
        var dx = e.clientX - startX; startX = null;
        if (Math.abs(dx) > 40) go(index + (dx < 0 ? 1 : -1), true);
      });
      play = autoplay(box, +box.getAttribute('data-interval') || 5000, function () { go(index + 1); });
      go(0);
    });

    // Step pages.
    document.querySelectorAll('[data-stepper]').forEach(function (box) {
      var tabs = box.querySelectorAll('[role="tab"]');
      var panels = box.querySelectorAll('.step-panel');
      var view = box.querySelector('.step-view');
      var track = box.querySelector('.step-track');
      var prev = box.querySelector('[data-step="prev"]');
      var next = box.querySelector('[data-step="next"]');
      var index = 0, play;
      function fit() { view.style.height = panels[index].offsetHeight + (prev ? prev.parentElement.offsetHeight + 28 : 0) + 'px'; }
      function go(i, focusTab) {
        index = (i + panels.length) % panels.length;
        track.style.transform = 'translateX(' + (-100 * index) + '%)';
        tabs.forEach(function (t, n) { var on = n === index; t.setAttribute('aria-selected', String(on)); t.tabIndex = on ? 0 : -1; });
        panels.forEach(function (p, n) {
          var on = n === index;
          p.classList.toggle('is-active', on);
          p.setAttribute('aria-hidden', String(!on));
          p.querySelectorAll('a, button, [tabindex]').forEach(function (c) { if (!on) c.setAttribute('tabindex', '-1'); else if (c.getAttribute('tabindex') === '-1' && !c.closest('[role="tablist"]')) c.removeAttribute('tabindex'); });
        });
        if (focusTab) tabs[index].focus();
        fit();
        if (play) play.restart();
      }
      tabs.forEach(function (t, n) {
        t.addEventListener('click', function () { go(n); });
        t.addEventListener('keydown', function (e) {
          var d = e.key === 'ArrowDown' || e.key === 'ArrowRight' ? 1 : e.key === 'ArrowUp' || e.key === 'ArrowLeft' ? -1 : 0;
          if (d) { e.preventDefault(); go(index + d, true); }
        });
      });
      if (prev) prev.addEventListener('click', function () { go(index - 1); });
      if (next) next.addEventListener('click', function () { go(index + 1); });
      window.addEventListener('resize', fit);
      if (document.fonts && document.fonts.ready) document.fonts.ready.then(fit);
      play = autoplay(box, +box.getAttribute('data-interval') || 7000, function () { go(index + 1); });
      var start = location.hash ? Array.prototype.findIndex.call(panels, function (p) { return '#' + p.id === location.hash; }) : 0;
      go(start > 0 ? start : 0);
    });

    // Copy chip in the hero.
    document.querySelectorAll('[data-copy]').forEach(function (el) {
      var state = el.querySelector('.state');
      el.addEventListener('click', function () { copyText(el.getAttribute('data-copy'), state, 'Copy'); });
    });

    // Code tabs (Maven / Gradle) and copy buttons.
    document.querySelectorAll('.code-head .tabs').forEach(function (list) {
      var tabs = list.querySelectorAll('[role="tab"]');
      function select(tab) {
        tabs.forEach(function (t) {
          var on = t === tab;
          t.setAttribute('aria-selected', String(on));
          t.tabIndex = on ? 0 : -1;
          document.getElementById(t.getAttribute('aria-controls')).hidden = !on;
        });
      }
      tabs.forEach(function (tab, i) {
        tab.addEventListener('click', function (e) { e.stopPropagation(); select(tab); });
        tab.addEventListener('keydown', function (e) {
          var d = e.key === 'ArrowRight' ? 1 : e.key === 'ArrowLeft' ? -1 : 0;
          if (d) { e.preventDefault(); e.stopPropagation(); var n = tabs[(i + d + tabs.length) % tabs.length]; select(n); n.focus(); }
        });
      });
    });
    document.querySelectorAll('.code .copy').forEach(function (btn) {
      btn.addEventListener('click', function () {
        var pre = btn.closest('.code').querySelector('pre:not([hidden])');
        copyText(pre.innerText.trim(), btn, 'Copy');
      });
    });

    // Features: pinned while you scroll, one card at a time; "See all seven" shows every card in a grid.
    document.querySelectorAll('[data-features]').forEach(function (sec) {
      var cards = sec.querySelectorAll('.feature-row');
      var count = cards.length;
      var countEl = sec.querySelector('[data-count]');
      var bar = sec.querySelector('[data-bar]');
      var prev = sec.querySelector('[data-feat="prev"]');
      var next = sec.querySelector('[data-feat="next"]');
      var all = sec.querySelector('[data-feat="all"]');
      var current = -1, queued = false;
      cards.forEach(function (c, n) {
        c.style.setProperty('--n', n);
        function open() { if (!sec.classList.contains('is-all')) return; cards.forEach(function (o) { o.classList.toggle('is-open', o === c); }); }
        c.addEventListener('mouseenter', open);
        c.addEventListener('focus', open);
        c.addEventListener('click', open);
      });
      function pinned() { return !reduce && !sec.classList.contains('is-all'); }
      function show(i) {
        if (i === current) return;
        current = i;
        cards.forEach(function (c, n) {
          c.classList.toggle('is-current', n === i);
          c.classList.toggle('is-before', n < i);
          c.setAttribute('aria-hidden', String(pinned() && n !== i));
        });
        countEl.textContent = i + 1;
        bar.style.transform = 'scaleX(' + (i + 1) / count + ')';
        prev.disabled = i === 0;
        next.disabled = i === count - 1;
        if (i === count - 1 && !sec.classList.contains('seen-all')) {
          sec.classList.add('seen-all');
          all.textContent = 'You’ve seen them all. Browse all seven';
        }
      }
      function travel() { return sec.offsetHeight - window.innerHeight; }
      function update() {
        queued = false;
        if (!pinned()) return;
        var p = Math.min(1, Math.max(0, -sec.getBoundingClientRect().top / travel()));
        show(Math.min(count - 1, Math.floor(p * count)));
      }
      // The work is a few rectangle reads, so it runs straight from the scroll event; no frame to wait for.
      function queue() { update(); }
      function scrollToCard(i) {
        var top = sec.getBoundingClientRect().top + window.scrollY + travel() * (i + 0.5) / count;
        window.scrollTo({ top: top, behavior: 'smooth' });
      }
      prev.addEventListener('click', function () { if (pinned()) scrollToCard(current - 1); });
      next.addEventListener('click', function () { if (pinned()) scrollToCard(current + 1); });
      all.addEventListener('click', function () {
        var showAll = !sec.classList.contains('is-all');
        sec.classList.toggle('is-all', showAll);
        all.setAttribute('aria-pressed', String(showAll));
        all.textContent = showAll ? 'Show one at a time' : (sec.classList.contains('seen-all') ? 'Browse all seven' : 'See all seven');
        cards.forEach(function (c, n) { c.setAttribute('aria-hidden', 'false'); c.tabIndex = showAll ? 0 : -1; c.classList.toggle('is-open', showAll && n === 0); });
        sec.querySelector('.features-ctrl').hidden = showAll;
        sec.scrollIntoView({ block: 'start', behavior: 'instant' });
        current = -1;
        queue();
      });
      if (reduce) { sec.classList.add('is-all'); return; }
      window.addEventListener('scroll', queue, { passive: true });
      window.addEventListener('resize', queue);
      show(0);
      update();
    });

    // Entrances, once each.
    var targets = document.querySelectorAll('[data-reveal]');
    if ('IntersectionObserver' in window && !reduce) {
      var io = new IntersectionObserver(function (entries) {
        entries.forEach(function (e) { if (e.isIntersecting) { e.target.classList.add('is-in'); io.unobserve(e.target); } });
      }, { rootMargin: '0px 0px -8% 0px', threshold: 0.08 });
      targets.forEach(function (t) { io.observe(t); });
    } else {
      targets.forEach(function (t) { t.classList.add('is-in'); });
    }

    // A link like /#faq jumps before the web font loads; line it up again once it has.
    if (location.hash && document.fonts && document.fonts.ready && !document.querySelector('[data-stepper]')) {
      document.fonts.ready.then(function () {
        var target = document.getElementById(location.hash.slice(1));
        if (target) target.scrollIntoView({ block: 'start', behavior: 'instant' });
      });
    }
  });
})();
