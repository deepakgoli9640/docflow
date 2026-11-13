/** Hash navigation shared by all views, e.g. navigate('refunds', {paymentId}). */
export function navigate(page, params) {
  const clean = Object.fromEntries(Object.entries(params || {}).filter(([, v]) => v !== undefined && v !== null && v !== ''));
  const q = Object.keys(clean).length ? '?' + new URLSearchParams(clean) : '';
  location.hash = `#/${page}${q}`;
}

export function pageHeader(title, subtitle, ...actions) {
  const header = document.createElement('header');
  header.className = 'page-header';
  const text = document.createElement('div');
  const h1 = document.createElement('h1');
  h1.textContent = title;
  text.append(h1);
  if (subtitle) {
    const p = document.createElement('p');
    p.className = 'muted';
    p.textContent = subtitle;
    text.append(p);
  }
  const act = document.createElement('div');
  act.className = 'page-actions';
  act.append(...actions.filter(Boolean));
  header.append(text, act);
  return header;
}
