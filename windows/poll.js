// Aus Fox Testpilot (Android) übernommen: sammelt Text und Produkt-Links, scrollt weiter.
() => {try{
  window.scrollBy(0, 1600);
  var items = [], seen = {};
  function clean(s){ return String(s || '').replace(/\s+/g, ' ').trim(); }
  function imgOf(a){
    var im = a.querySelector('img'); if (!im) return '';
    var s = im.currentSrc || im.getAttribute('src') || im.getAttribute('data-src') || '';
    if (!/^https?:/.test(s)) { var ss = im.getAttribute('srcset') || ''; s = ss.split(' ')[0] || ''; }
    return /^https?:/.test(s) ? s : '';
  }
  function nameOf(a){
    var im = a.querySelector('img');
    var n = clean(a.getAttribute('aria-label') || a.getAttribute('title') || '');
    if (!n) { var lines = String(a.innerText || '').split('\n').map(clean).filter(function(s){ return s.length > 3 && !/^[\d.,€\s*%-]+$/.test(s); }); n = lines[0] || ''; }
    var alt = false;
    if (!n && im) { n = clean(im.getAttribute('alt')); alt = true; }
    return { n: n.slice(0, 140), alt: alt };
  }
  function isTest(a){
    var ctx = '';
    for (var p = a, d = 0; p && d < 7; p = p.parentElement, d++) { ctx = p.innerText || ''; if (ctx.length > 500) break; }
    return /testger|testpilot|zu testen|testprodukt|bewerb|teilnahme/i.test(ctx);
  }
  [].slice.call(document.querySelectorAll('a[href]')).forEach(function(a){
    var h = a.href || '';
    if (h.indexOf('/product/') < 0) return;
    var m = h.match(/-(\d{5,10})\.html/); if (!m) return;
    var id = m[1], nm = nameOf(a), img = imgOf(a), test = isTest(a);
    if (seen[id] !== undefined) { var o = items[seen[id]]; if ((!o.name || o.alt) && nm.n && !nm.alt) { o.name = nm.n; o.alt = false; } else if (!o.name && nm.n) { o.name = nm.n; o.alt = nm.alt; } if (!o.img && img) o.img = img; o.test = o.test || test; return; }
    seen[id] = items.length;
    items.push({ id: id, url: h.split('?')[0].split('#')[0], name: nm.n, alt: nm.alt, img: img, test: test });
  });
  var b = document.body;
  var bottom = b ? (window.scrollY + window.innerHeight >= b.scrollHeight - 60) : false;
  return { t: b ? b.innerText : '', u: location.href, i: items, b: bottom };
}catch(e){ return null; }}
