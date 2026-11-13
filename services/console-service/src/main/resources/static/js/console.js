/*
 * The only JavaScript in the console: three small behaviours, no framework, no build step.
 *
 * A library such as HTMX would do all of this and much more, but these are the only three things the
 * console actually needs, and this way there is nothing vendored, nothing to update, and the content
 * security policy stays at script-src 'self'.
 */
(() => {
    'use strict';

    /* 1. Refresh a region while something can still change on its own.
     *    Used on the payment list, a payment that is still in flight, and notifications being retried.
     *    The server decides whether it is worth polling (data-live-active) and how often. */
    document.querySelectorAll('[data-live-url]').forEach((region) => {
        if (region.dataset.liveActive !== 'true') {
            return;
        }
        const url = region.dataset.liveUrl;
        const seconds = Number(region.dataset.liveSeconds || '5');
        let stop = false;

        const tick = async () => {
            if (stop || document.hidden) {
                return;  // a background tab does not need refreshing
            }
            try {
                const response = await fetch(url, { headers: { 'X-Requested-With': 'console' } });
                if (response.status === 401 || response.status === 403) {
                    stop = true;        // the session ended: stop polling rather than hammer the login page
                    return;
                }
                if (!response.ok) {
                    return;             // a blip: keep the page as it is and try again next tick
                }
                const markup = await response.text();
                if (markup.trim()) {
                    region.innerHTML = markup;
                    bindCopyButtons(region);
                }
            } catch (ignored) {
                /* offline or the server restarted; the next tick will pick it up */
            }
        };

        const timer = setInterval(tick, Math.max(1, seconds) * 1000);
        window.addEventListener('beforeunload', () => clearInterval(timer));
    });

    /* 2. Copy to clipboard: checkout links, and secrets that are shown exactly once. */
    function bindCopyButtons(root) {
        root.querySelectorAll('[data-copy]').forEach((button) => {
            if (button.dataset.copyBound === 'true') {
                return;
            }
            button.dataset.copyBound = 'true';
            button.addEventListener('click', async () => {
                const original = button.textContent;
                try {
                    await navigator.clipboard.writeText(button.dataset.copy);
                    button.textContent = 'Copied';
                } catch (ignored) {
                    button.textContent = 'Press Ctrl+C';
                }
                setTimeout(() => { button.textContent = original; }, 1500);
            });
        });
    }
    bindCopyButtons(document);

    /* 3. Confirm anything that moves money or cannot be undone. */
    document.querySelectorAll('form[data-confirm]').forEach((form) => {
        form.addEventListener('submit', (event) => {
            if (!window.confirm(form.dataset.confirm)) {
                event.preventDefault();
            }
        });
    });
})();
