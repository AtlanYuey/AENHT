// Draws the app icon (vector) and renders every size the apps need.
//   node tools/icon/render.js <variant> [--apply] [--preview out.png]
// Variants: classic (the pages and the name), pages (the pages alone),
// violet (white pages on violet). --apply writes the iOS and Android icons.
// Needs Playwright with Chromium, and the Inter font for the name.
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '../..');
// The drawing's coordinates: the icon square is 95…1160 (1065 wide).
const BOX = { x: 95, y: 95, size: 1065 };
const CENTER = BOX.x + BOX.size / 2;

const VARIANTS = {
  classic: { name: true, scale: 1, back: '#8a72e3', backEnd: '#7a63d6', front: '#ffffff', bars: '#11111c', bg: ['#14131f', '#0b0b12'] },
  pages: { name: false, scale: 1.38, back: '#8f78ea', backEnd: '#7259d6', front: '#ffffff', bars: '#11111c', bg: ['#1b1830', '#0b0b13'], glow: true },
  violet: { name: false, scale: 1.38, back: 'rgba(255,255,255,0.42)', backEnd: 'rgba(255,255,255,0.30)', front: '#ffffff', bars: '#6c56d8', bg: ['#9a85f5', '#5a44c8'] },
};

/** The two pages: a white one in front, a violet one behind it (two sites, one app). */
function pages(v, mono) {
  const back = mono ? mono.back : 'url(#back)';
  const front = mono ? mono.front : v.front;
  const bars = mono ? mono.bars : v.bars;
  const frontPath = 'M437 377 L668 466 Q686 473 686 492 L686 730 Q686 752 664 744 L438 664 Q420 657 420 640 L420 394 Q420 371 437 377 Z';
  const id = mono ? 'gapm' : 'gap';
  // A gap between the pages where they overlap: cut out of the back page.
  return `
    <mask id="${id}" maskUnits="userSpaceOnUse" x="0" y="0" width="1300" height="1300">
      <rect x="0" y="0" width="1300" height="1300" fill="#fff"/>
      <path d="${frontPath}" fill="#000" stroke="#000" stroke-width="28" stroke-linejoin="round"/>
    </mask>
    <path d="M574 300 L818 384 Q836 391 836 410 L836 636 Q836 655 818 662 L724 703 Q712 708 712 694 L712 492 L562 434 L562 312 Q562 296 574 300 Z" fill="${back}" mask="url(#${id})"/>
    <path d="${frontPath}" fill="${front}"/>
    <path d="M478 507 L578 547 M478 579 L578 621" stroke="${bars}" stroke-width="24" stroke-linecap="round"/>`;
}

function glyph(v, mono) {
  // Centered on the square, larger when there's no name under it.
  const gx = 628, gy = v.name ? 524 : 524;
  const s = v.scale;
  const ty = v.name ? 0 : (CENTER - gy);
  return `<g transform="translate(${gx} ${gy + ty}) scale(${s}) translate(${-gx} ${-gy})">${pages(v, mono)}</g>`;
}

function wordmark(fill) {
  return `<text x="${CENTER - 2}" y="946" text-anchor="middle" font-family="Inter" font-weight="800" font-size="204" letter-spacing="4" fill="${fill}">AENHT</text>`;
}

/**
 * One layer of the icon as SVG. [layer]: full (iOS), tinted (iOS, grey on
 * black), background / foreground / monochrome (Android adaptive icon).
 */
function svg(variantName, layer) {
  const v = VARIANTS[variantName];
  const defs = `<defs>
      <linearGradient id="bg" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="${v.bg[0]}"/><stop offset="1" stop-color="${v.bg[1]}"/></linearGradient>
      <linearGradient id="back" x1="0" y1="0" x2="0.4" y2="1"><stop offset="0" stop-color="${v.back}"/><stop offset="1" stop-color="${v.backEnd}"/></linearGradient>
      <radialGradient id="glow" cx="0.5" cy="0.47" r="0.5"><stop offset="0" stop-color="#7b62e6" stop-opacity="0.32"/><stop offset="1" stop-color="#7b62e6" stop-opacity="0"/></radialGradient>
    </defs>`;
  const background = `<rect x="${BOX.x - 10}" y="${BOX.y - 10}" width="${BOX.size + 20}" height="${BOX.size + 20}" fill="url(#bg)"/>` +
    (v.glow ? `<rect x="${BOX.x}" y="${BOX.y}" width="${BOX.size}" height="${BOX.size}" fill="url(#glow)"/>` : '');
  let body;
  // Android shows the middle 72 of 108 dp: the drawing goes in at 2/3
  // (smaller still with the name, which a round mask would cut).
  const android = (content) => {
    const k = v.name ? 0.6 : 0.667;
    return `<g transform="translate(${CENTER} ${CENTER}) scale(${k}) translate(${-CENTER} ${-CENTER})">${content}</g>`;
  };
  switch (layer) {
    case 'full':
      body = background + glyph(v) + (v.name ? wordmark('#ffffff') : '');
      break;
    case 'tinted': {
      const mono = { back: '#8c8c8c', front: '#ffffff', bars: '#000000' };
      body = `<rect x="0" y="0" width="1300" height="1300" fill="#000"/>` + glyph(v, mono) + (v.name ? wordmark('#ffffff') : '');
      break;
    }
    case 'background':
      body = background;
      break;
    case 'foreground':
      body = android(glyph(v) + (v.name ? wordmark('#ffffff') : ''));
      break;
    case 'monochrome': {
      const mono = { back: 'rgba(255,255,255,0.55)', front: '#ffffff', bars: '#000000' };
      // Black means see-through after the mask below.
      body = `<mask id="m"><rect x="0" y="0" width="1300" height="1300" fill="#000"/>${android(glyph(v, mono) + (v.name ? wordmark('#ffffff') : ''))}</mask>
        <rect x="0" y="0" width="1300" height="1300" fill="#fff" mask="url(#m)"/>`;
      break;
    }
  }
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="${BOX.x} ${BOX.y} ${BOX.size} ${BOX.size}">${defs}${body}</svg>`;
}

async function render(page, svgText, size, file) {
  await page.setViewportSize({ width: size, height: size });
  await page.setContent(`<html><body style="margin:0;background:transparent">
    <div style="width:${size}px;height:${size}px">${svgText.replace('<svg ', `<svg width="${size}" height="${size}" `)}</div></body></html>`);
  await page.evaluate(() => document.fonts.ready);
  fs.mkdirSync(path.dirname(file), { recursive: true });
  await page.screenshot({ path: file, omitBackground: true, clip: { x: 0, y: 0, width: size, height: size } });
}

async function main() {
  const args = process.argv.slice(2);
  const variant = args[0];
  if (!VARIANTS[variant]) throw new Error(`variant: ${Object.keys(VARIANTS).join(', ')}`);
  const browser = await chromium.launch();
  const page = await browser.newPage({ deviceScaleFactor: 1 });
  fs.mkdirSync(path.join(__dirname, 'svg'), { recursive: true });
  for (const layer of ['full', 'tinted', 'background', 'foreground', 'monochrome']) {
    fs.writeFileSync(path.join(__dirname, 'svg', `${variant}-${layer}.svg`), svg(variant, layer));
  }
  const previewIndex = args.indexOf('--preview');
  if (previewIndex >= 0) {
    await render(page, svg(variant, 'full'), 1024, args[previewIndex + 1]);
    for (const layer of ['background', 'foreground', 'monochrome']) {
      await render(page, svg(variant, layer), 432, args[previewIndex + 1].replace('.png', `-${layer}.png`));
    }
  }
  if (args.includes('--apply')) {
    const ios = path.join(ROOT, 'iosApp/AENHT/Resources/Assets.xcassets/AppIcon.appiconset');
    await render(page, svg(variant, 'full'), 1024, path.join(ios, 'AppIcon.png'));
    await render(page, svg(variant, 'full'), 1024, path.join(ios, 'AppIcon-Dark.png'));
    await render(page, svg(variant, 'tinted'), 1024, path.join(ios, 'AppIcon-Tinted.png'));
    const densities = { mdpi: 108, hdpi: 162, xhdpi: 216, xxhdpi: 324, xxxhdpi: 432 };
    for (const [density, size] of Object.entries(densities)) {
      for (const layer of ['background', 'foreground', 'monochrome']) {
        await render(page, svg(variant, layer), size,
          path.join(ROOT, `androidApp/src/main/res/mipmap-${density}/ic_launcher_${layer}.png`));
      }
    }
  }
  await browser.close();
}

main().catch((e) => { console.error(e); process.exit(1); });
