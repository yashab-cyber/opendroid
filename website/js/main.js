// ── Theme Toggle ──
(function () {
  const toggle = document.getElementById('theme-toggle');
  if (!toggle) return;
  const root = document.documentElement;
  const STORAGE_KEY = 'opendroid-theme';

  const updateA11y = () => {
    const isLight = root.getAttribute('data-theme') === 'light';
    toggle.setAttribute('aria-pressed', String(isLight));
    toggle.setAttribute('aria-label', isLight ? 'Enable dark mode' : 'Enable light mode');
  };
  updateA11y();

  toggle.addEventListener('click', () => {
    const next = root.getAttribute('data-theme') === 'light' ? 'dark' : 'light';
    root.setAttribute('data-theme', next);
    try { localStorage.setItem(STORAGE_KEY, next); } catch (e) { /* storage unavailable, ignore */ }
    updateA11y();
  });
})();

// ── Hamburger Menu ──
const hamburger = document.getElementById('nav-hamburger');
const navLinks = document.getElementById('nav-links');
if (hamburger && navLinks) {
  const setMenuState = (open) => {
    hamburger.classList.toggle('active', open);
    navLinks.classList.toggle('open', open);
    hamburger.setAttribute('aria-expanded', String(open));
  };
  hamburger.addEventListener('click', () => {
    setMenuState(!navLinks.classList.contains('open'));
  });
  document.addEventListener('click', (e) => {
    if (!hamburger.contains(e.target) && !navLinks.contains(e.target)) {
      setMenuState(false);
    }
  });
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && navLinks.classList.contains('open')) {
      setMenuState(false);
      hamburger.focus();
    }
  });
}

// ── Scroll Reveal ──
const revealElements = document.querySelectorAll('.reveal');
const revealObserver = new IntersectionObserver((entries) => {
  entries.forEach(entry => {
    if (entry.isIntersecting) {
      entry.target.classList.add('visible');
    }
  });
}, { threshold: 0.1, rootMargin: '0px 0px -40px 0px' });
revealElements.forEach(el => revealObserver.observe(el));

// ── FAQ Accordion ──
document.querySelectorAll('.faq-question').forEach(btn => {
  btn.addEventListener('click', () => {
    const item = btn.closest('.faq-item');
    const answer = item.querySelector('.faq-answer');
    const isOpen = item.classList.contains('open');
    document.querySelectorAll('.faq-item.open').forEach(openItem => {
      openItem.classList.remove('open');
      openItem.querySelector('.faq-answer').style.maxHeight = '0';
    });
    if (!isOpen) {
      item.classList.add('open');
      answer.style.maxHeight = answer.scrollHeight + 'px';
    }
  });
});

// ── Navbar scroll effect ──
const navbar = document.getElementById('navbar');
if (navbar) {
  window.addEventListener('scroll', () => {
    navbar.classList.toggle('scrolled', window.scrollY > 10);
  });
}

// ── Copy-to-clipboard ──
// Any <code> wrapped in <span class="code-copy"> gets a copy button
// appended automatically — no per-page wiring needed.
document.querySelectorAll('.code-copy').forEach(wrapper => {
  const codeEl = wrapper.querySelector('code');
  if (!codeEl) return;

  const btn = document.createElement('button');
  btn.type = 'button';
  btn.className = 'copy-btn';
  btn.setAttribute('aria-label', 'Copy to clipboard');
  btn.innerHTML = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path></svg>';

  let opId = 0;
  let activeTimeout = null;

  btn.addEventListener('click', async () => {
    const myOp = ++opId;
    const text = codeEl.textContent;
    let copied = false;
    try {
      await navigator.clipboard.writeText(text);
      copied = true;
    } catch (e) {
      // Clipboard API unavailable (older browser / insecure context) — fall back.
      const ta = document.createElement('textarea');
      ta.value = text;
      ta.style.position = 'fixed';
      ta.style.opacity = '0';
      document.body.appendChild(ta);
      ta.select();
      try { copied = document.execCommand('copy'); } catch (e2) { copied = false; }
      document.body.removeChild(ta);
    }
    // A later click may have already started while this one was awaiting
    // the clipboard — ignore this result and let the newer one own the UI.
    if (myOp !== opId) return;
    if (activeTimeout) clearTimeout(activeTimeout);

    if (copied) {
      btn.setAttribute('data-copied', 'true');
      btn.removeAttribute('data-copy-failed');
      btn.setAttribute('aria-label', 'Copied');
    } else {
      btn.setAttribute('data-copy-failed', 'true');
      btn.removeAttribute('data-copied');
      btn.setAttribute('aria-label', 'Copy failed — copy manually');
    }
    activeTimeout = setTimeout(() => {
      if (myOp !== opId) return;
      btn.removeAttribute('data-copied');
      btn.removeAttribute('data-copy-failed');
      btn.setAttribute('aria-label', 'Copy to clipboard');
      activeTimeout = null;
    }, 1800);
  });

  wrapper.appendChild(btn);
});

// ── Hero video pause/play control ──
// Required for WCAG 2.2.2 (Pause, Stop, Hide) since the video
// autoplays and loops indefinitely.
const heroVideo = document.getElementById('hero-video');
const heroVideoToggle = document.getElementById('hero-video-toggle');
if (heroVideo && heroVideoToggle) {
  const syncState = () => {
    const paused = heroVideo.paused;
    heroVideoToggle.classList.toggle('paused', paused);
    heroVideoToggle.setAttribute('aria-label', paused ? 'Play video' : 'Pause video');
  };
  heroVideoToggle.addEventListener('click', () => {
    if (heroVideo.paused) {
      heroVideo.play();
    } else {
      heroVideo.pause();
    }
  });
  heroVideo.addEventListener('play', syncState);
  heroVideo.addEventListener('pause', syncState);
  syncState();
}

// ── Live GitHub stats ──
// Fetches star/fork counts for the upstream repo. Fails silently
// (element just stays hidden) if the API is unreachable or rate-limited.
const ghStats = document.getElementById('gh-stats');
if (ghStats) {
  fetch('https://api.github.com/repos/JMAN730/opendroid')
    .then(res => (res.ok ? res.json() : Promise.reject(res.status)))
    .then(data => {
      const stars = ghStats.querySelector('[data-stat="stars"]');
      const forks = ghStats.querySelector('[data-stat="forks"]');
      if (stars) stars.textContent = data.stargazers_count.toLocaleString();
      if (forks) forks.textContent = data.forks_count.toLocaleString();
      ghStats.setAttribute('data-loaded', 'true');
    })
    .catch(() => { /* leave hidden, no broken UI on failure */ });
}
