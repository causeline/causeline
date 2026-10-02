// SPDX-License-Identifier: Apache-2.0
// Progressive enhancement only: without this script every section is fully visible.
(function () {
  var root = document.documentElement;
  root.classList.add('js');

  // Dark is the default. A saved choice of light is applied here, in <head>, before first paint.
  var THEME_KEY = 'causeline-theme';
  function savedTheme() { try { return localStorage.getItem(THEME_KEY); } catch (e) { return null; } }
  function applyTheme(theme) {
    if (theme === 'light') root.setAttribute('data-theme', 'light'); else root.removeAttribute('data-theme');
    var meta = document.querySelector('meta[name="theme-color"]');
    if (meta) meta.setAttribute('content', theme === 'light' ? '#f3f0e8' : '#0f0e0c');
  }
  applyTheme(savedTheme());
  var reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches;

  function ready(fn) {
    if (document.readyState !== 'loading') fn();
    else document.addEventListener('DOMContentLoaded', fn);
  }

  ready(function () {
    // Hero entrance, once fonts are in so the headline doesn't reflow mid-animation.
    var started = false;
    var start = function () {
      if (started) return;
      started = true;
      requestAnimationFrame(function () { document.body.classList.add('loaded'); });
      // A link like /#developer jumps before the web fonts load; once they change the page height,
      // line the target up again.
      var target = location.hash && document.getElementById(location.hash.slice(1));
      if (target) target.scrollIntoView({ block: 'start', behavior: 'instant' });
    };
    if (document.fonts && document.fonts.ready) document.fonts.ready.then(start); else start();
    setTimeout(start, 900); // never wait long for fonts

    // Nav gets a backdrop once the page scrolls.
    var nav = document.querySelector('.nav');
    if (nav) {
      var onScroll = function () { nav.classList.toggle('scrolled', window.scrollY > 8); };
      onScroll();
      window.addEventListener('scroll', onScroll, { passive: true });
    }

    // Scroll reveals.
    var targets = document.querySelectorAll('.reveal, .tools, .reveal-bars, .flow');
    if ('IntersectionObserver' in window && !reduce) {
      var io = new IntersectionObserver(function (entries) {
        entries.forEach(function (e) {
          if (e.isIntersecting) { e.target.classList.add('in'); io.unobserve(e.target); }
        });
      }, { rootMargin: '0px 0px -12% 0px', threshold: 0.12 });
      targets.forEach(function (t) { io.observe(t); });
    } else {
      targets.forEach(function (t) { t.classList.add('in'); });
    }

    applyTheme(savedTheme()); // the theme-color meta tag exists only now
    var toggle = document.querySelector('.theme-toggle');
    if (toggle) {
      var label = function () {
        toggle.setAttribute('aria-label', root.getAttribute('data-theme') === 'light' ? 'Switch to dark theme' : 'Switch to light theme');
      };
      label();
      toggle.addEventListener('click', function () {
        var next = root.getAttribute('data-theme') === 'light' ? 'dark' : 'light';
        root.classList.add('theming');
        applyTheme(next);
        try { localStorage.setItem(THEME_KEY, next); } catch (e) { /* private mode: not remembered */ }
        label();
        setTimeout(function () { root.classList.remove('theming'); }, 600);
      });
    }

    setupPlayer(document.querySelector('.player'));
    setupCopy();
    setupTabs();
  });

  function setupPlayer(player) {
    if (!player) return;
    var clock = player.querySelector('[data-clock]');
    var total = Number(clock.getAttribute('data-total'));
    var T = parseFloat(getComputedStyle(player).getPropertyValue('--T')) * 1000 || 3200;
    var raf;

    // The playhead moves across the bar column; measure it so it lines up at any width.
    function measure() {
      var track = player.querySelector('.row .track');
      if (!track) return;
      var p = player.getBoundingClientRect();
      var t = track.getBoundingClientRect();
      player.style.setProperty('--track-left', (t.left - p.left) + 'px');
      player.style.setProperty('--track-width', t.width + 'px');
    }

    function tick(t0) {
      cancelAnimationFrame(raf);
      var step = function (now) {
        var k = Math.min(1, (now - t0) / T);
        clock.textContent = Math.round(k * total) + ' ms';
        if (k < 1) raf = requestAnimationFrame(step);
      };
      raf = requestAnimationFrame(step);
    }

    function play() {
      measure();
      player.classList.remove('play', 'done');
      void player.offsetWidth; // restart the CSS animations
      player.classList.add('play');
      tick(performance.now());
    }

    if (reduce || !('IntersectionObserver' in window)) {
      player.classList.add('done');
      clock.textContent = total + ' ms';
    } else {
      var seen = new IntersectionObserver(function (entries) {
        if (entries[0].isIntersecting) { play(); seen.disconnect(); }
      }, { threshold: 0.45 });
      seen.observe(player);
    }
    window.addEventListener('resize', measure);
    var btn = player.querySelector('.replay');
    if (btn) btn.addEventListener('click', function () {
      if (reduce) return;
      play();
    });
  }

  function copyText(text, el) {
    var done = function () {
      el.classList.add('copied');
      var label = el.querySelector('.state') || el;
      var before = label.textContent;
      label.textContent = 'Copied';
      setTimeout(function () { el.classList.remove('copied'); label.textContent = before; }, 1600);
    };
    if (navigator.clipboard) navigator.clipboard.writeText(text).then(done, function () {});
  }

  function setupCopy() {
    document.querySelectorAll('[data-copy]').forEach(function (el) {
      el.addEventListener('click', function () { copyText(el.getAttribute('data-copy'), el); });
    });
    document.querySelectorAll('.copy').forEach(function (btn) {
      btn.addEventListener('click', function () {
        var panel = btn.closest('.code-body').querySelector('pre:not([hidden])');
        copyText(panel.innerText.trim(), btn);
      });
    });
  }

  function setupTabs() {
    document.querySelectorAll('[role="tablist"]').forEach(function (list) {
      var tabs = list.querySelectorAll('[role="tab"]');
      function select(tab) {
        tabs.forEach(function (t) {
          var on = t === tab;
          t.setAttribute('aria-selected', on);
          t.tabIndex = on ? 0 : -1;
          document.getElementById(t.getAttribute('aria-controls')).hidden = !on;
        });
      }
      tabs.forEach(function (tab, i) {
        tab.addEventListener('click', function () { select(tab); });
        tab.addEventListener('keydown', function (e) {
          var d = e.key === 'ArrowRight' ? 1 : e.key === 'ArrowLeft' ? -1 : 0;
          if (d) { var next = tabs[(i + d + tabs.length) % tabs.length]; select(next); next.focus(); }
        });
      });
    });
  }
})();
