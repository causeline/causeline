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
    el.addEventListener('focusin', function () { focused = true; schedule(); });
    el.addEventListener('focusout', function (e) { if (!el.contains(e.relatedTarget)) { focused = false; resume(); } });
    document.addEventListener('visibilitychange', resume);
    if ('IntersectionObserver' in window) {
      new IntersectionObserver(function (entries) { visible = entries[0].isIntersecting; resume(); }, { threshold: 0.25 }).observe(el);
    }
    return { restart: restart };
  }

  ready(function () {
    // Hero screenshots.
    document.querySelectorAll('[data-carousel]').forEach(function (box) {
      var track = box.querySelector('.shots-track');
      var slides = box.querySelectorAll('.shot');
      var dots = box.querySelectorAll('.shots-dots button');
      var caption = box.querySelector('[data-caption]');
      var index = 0, play;
      function go(i, user) {
        index = (i + slides.length) % slides.length;
        track.style.transform = 'translateX(' + (-100 * index) + '%)';
        dots.forEach(function (d, n) { d.setAttribute('aria-current', String(n === index)); });
        slides.forEach(function (s, n) { s.setAttribute('aria-hidden', String(n !== index)); });
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

    // Features: pinned while you scroll, one card at a time; "See all six" shows every card in a grid.
    document.querySelectorAll('[data-features]').forEach(function (sec) {
      var cards = sec.querySelectorAll('.feature-row');
      var count = cards.length;
      var countEl = sec.querySelector('[data-count]');
      var bar = sec.querySelector('[data-bar]');
      var prev = sec.querySelector('[data-feat="prev"]');
      var next = sec.querySelector('[data-feat="next"]');
      var all = sec.querySelector('[data-feat="all"]');
      var current = -1, queued = false;
      cards.forEach(function (c, n) { c.style.setProperty('--n', n); });
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
          all.textContent = 'You’ve seen them all. Browse all six';
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
        all.textContent = showAll ? 'Show one at a time' : (sec.classList.contains('seen-all') ? 'Browse all six' : 'See all six');
        cards.forEach(function (c) { c.setAttribute('aria-hidden', 'false'); });
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
