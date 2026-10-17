// Independent acceptance harness. It talks to the production loopback server with its real cookie.
const {chromium} = require(process.env.SDH_PLAYWRIGHT_MODULE || '/home/joeld/projects/noted-web-editor/node_modules/playwright');

(async () => {
  const [origin, secret, media, cardText] = process.argv.slice(2);
  const browser = await chromium.launch({headless: true, args: ['--autoplay-policy=no-user-gesture-required']});
  try {
    const context = await browser.newContext();
    await context.addCookies([{name: 'sdh_loopback_session', value: secret, url: origin + '/', httpOnly: true, sameSite: 'Strict'}]);
    const page = await context.newPage();
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    page.on('response', response => {
      if (response.status() >= 400 && !response.url().includes('.flight-skip.json')) errors.push(`${response.status()} ${response.url()}`);
    });
    const started = Date.now();
    await page.goto(origin + '/', {waitUntil: 'domcontentloaded', timeout: 5000});
    const expected = Number(cardText);
    await page.waitForFunction(n => document.querySelectorAll('.video').length === n, expected, {timeout: 3000});
    const galleryMs = Date.now() - started;
    await page.locator('.video img').evaluateAll(images => images.forEach(image => image.loading = 'eager'));
    try {
      await page.waitForFunction(() => [...document.querySelectorAll('.video img')].every(image => image.complete && image.naturalWidth > 0), null, {timeout: 3000});
    } catch (error) {
      const images = await page.locator('.video img').evaluateAll(nodes => nodes.slice(0, 5).map(image => ({src: image.src, complete: image.complete, width: image.naturalWidth})));
      throw new Error(`${error.message}; images=${JSON.stringify(images)}; responses=${errors.join('; ')}`);
    }
    const thumbnailsMs = Date.now() - started;
    const watches = page.locator('button[data-url]').filter({hasText: 'Ansehen'});
    let chosen = -1;
    for (let index = 0; index < await watches.count(); index++) {
      if (await watches.nth(index).getAttribute('data-url') === media) { chosen = index; break; }
    }
    if (chosen < 0) throw new Error('Requested video absent from actual gallery');
    await watches.nth(chosen).click();
    await page.waitForFunction(() => {
      const video = document.getElementById('viewer-video');
      return !document.getElementById('viewer').hidden && video.readyState >= 2 && video.currentTime > 0.15;
    }, null, {timeout: 5000});
    const playbackMs = Date.now() - started;
    const before = await page.locator('video').evaluate(video => ({time: video.currentTime, duration: video.duration}));
    await page.locator('video').evaluate(video => { video.currentTime = Math.min(video.duration - 0.25, 2.1); });
    await page.waitForFunction(() => {
      const video = document.getElementById('viewer-video');
      return !video.seeking && video.currentTime >= 2;
    }, null, {timeout: 3000});
    const after = await page.locator('video').evaluate(video => video.currentTime);
    const refreshScript = process.argv[6];
    if (!refreshScript) throw new Error('Production refresh script was not supplied');
    const source = await page.locator('video').evaluate(video => video.currentSrc);
    if (await page.evaluate(refreshScript) !== true) throw new Error('Production state refresh did not run');
    await page.waitForFunction(({time, src}) => {
      const video = document.getElementById('viewer-video');
      return !document.getElementById('viewer').hidden && !video.paused && video.currentSrc === src && video.currentTime > time + 0.1;
    }, {time: after, src: source}, {timeout: 2000});
    if (before.duration < 3.5) throw new Error(`Fixture duration too short: ${before.duration}`);
    if (errors.length) throw new Error('Website JS errors: ' + errors.join('; '));
    console.log(JSON.stringify({cards: expected, galleryMs, thumbnailsMs, playbackMs, duration: before.duration, advanced: before.time, seeked: after}));
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
