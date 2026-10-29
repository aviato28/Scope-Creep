// node render_png.js in.svg out.png widthPx heightPx  (Playwright, chromium with default options)
const { chromium } = require('playwright');
const fs = require('fs');
(async () => {
  const [, , svg, out, w, h] = process.argv;
  const b = await chromium.launch();
  const pg = await b.newPage({ viewport: { width: +w, height: +h } });
  await pg.setContent('<body style="margin:0;background:#05090a">' + fs.readFileSync(svg, 'utf8') + '</body>');
  await pg.screenshot({ path: out });
  await b.close();
})();
