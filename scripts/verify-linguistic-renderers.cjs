// Validates production Kotlin-generated HTML, with all remote resources blocked.
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const { chromium } = require('playwright');
const root = path.resolve(__dirname, '..');
const dir = path.join(root, 'app/build/reports/linguistic-renderers');
async function main() {
  const browser = await chromium.launch({ headless: true, channel: 'msedge' });
  const results = [];
  try {
    for (const name of fs.readdirSync(dir).filter(n => n.endsWith('.html'))) {
      const page = await browser.newPage({ viewport: { width: 360, height: 640 }, isMobile: true, hasTouch: true });
      const errors = [], remote = [];
      page.on('pageerror', e => errors.push(e.message));
      await page.route('**/*', async route => {
        const url = new URL(route.request().url());
        if (url.host !== 'rikkahub.local') { remote.push(url.href); return route.abort(); }
        if (url.pathname === '/') return route.fulfill({contentType:'text/html',path:path.join(dir,name)});
        const file = path.resolve(root, 'app/src/main', '.' + url.pathname);
        if (!file.startsWith(path.join(root,'app/src/main/assets')+path.sep) || !fs.existsSync(file)) return route.fulfill({status:404,body:'missing'});
        return route.fulfill({path:file,contentType:file.endsWith('.js')?'text/javascript':file.endsWith('.woff2')?'font/woff2':'text/plain'});
      });
      await page.goto('https://rikkahub.local/');
      await page.waitForFunction(() => ['ready','error'].includes(window.__rikkaRenderStatus));
      const status = await page.evaluate(() => window.__rikkaRenderStatus);
      if (name === 'invalid.html') {
        assert.equal(status,'error'); assert.ok(await page.locator('#render-error').isVisible());
      } else {
        assert.equal(status,'ready',name+': '+await page.locator('#render-error').textContent());
        // A transparent WebView is composited over the host theme in Android.
        await page.addStyleTag({content:'html{background:var(--bg)!important}'});
        assert.equal(await page.evaluate(() => document.fonts.check('20px RikkaIPA')),true);
        const id = await page.locator('#renderer').getAttribute('data-renderer');
        if (id === 'ruby_annotation') {
          assert.ok(await page.locator('ruby rt').count());
          assert.equal(await page.locator('#renderer img, #renderer script').count(),0);
          if(name==='unsafe.html')assert.ok((await page.locator('ruby').textContent()).includes('<img'));
        }
        if (id === 'gloss') {
          assert.equal(await page.locator('.gloss').first().evaluate(n=>getComputedStyle(n).fontVariantCaps),'normal',name+' lexical gloss must preserve lowercase');
          if(name==='gloss-rtl.html')assert.equal(await page.locator('.translation').evaluate(n=>getComputedStyle(n).direction),'ltr','English translation must keep its own direction');
          const aligned=await page.locator('.word').evaluateAll(words=>words.every(word=>{
            const children=[...word.children].map(n=>n.getBoundingClientRect());
            return children.every((r,i)=>i===0||r.top>=children[i-1].bottom-1)&&children.every(r=>Math.abs(r.left-children[0].left)<1);
          }));assert.ok(aligned,name+' word tiers must stay together');
        }
        if (id === 'syntax_tree' || id === 'dependency') {
          assert.ok(await page.locator('.ling-graph').count());
          const within = await page.locator('.ling-graph').evaluateAll(svgs=>svgs.every(svg=>[...svg.querySelectorAll('text')].every(t=>{
            const b=t.getBBox(),v=svg.viewBox.baseVal;return b.x>=-1&&b.y>=-1&&b.x+b.width<=v.width+1&&b.y+b.height<=v.height+1;
          })));assert.ok(within,name+' labels must fit in SVG');
        }
        if (id === 'dependency') {
          const direction=await page.locator('.ling-graph').evaluateAll(svgs=>svgs.every(svg=>[...svg.querySelectorAll('path.edge')].every(arc=>{
            const text=svg.querySelector('[data-token-id="'+arc.dataset.dependent+'"]');
            const end=arc.getPointAtLength(arc.getTotalLength());
            return text&&Math.abs(end.x-Number(text.getAttribute('x')))<=8&&arc.getAttribute('marker-end');
          })));assert.ok(direction,name+' arrow must terminate at the dependent');
        }
        for (const width of [320,412]) {
          await page.setViewportSize({width,height:640});
          assert.ok(await page.locator('#renderer').isVisible());
        }
        await page.screenshot({path:path.join(dir,name.replace('.html','-browser.png'))});
        // Use the real message-export probe. Include the final rows/columns, not a viewport crop.
        const probe=fs.readFileSync(path.join(dir,'export-probe.js'),'utf8');
        let exported;
        for(let n=0;n<4;n++) { exported=JSON.parse(await page.evaluate(probe));if(exported.status==='ready')break;await page.waitForTimeout(100); }
        assert.equal(exported.status,'ready',name+' export readiness');
        assert.ok(await page.locator('details').evaluateAll(nodes=>nodes.every(n=>n.open)),name+' details must be included');
        assert.equal(await page.evaluate(()=>window.__rikkaArtifact.schema_version),1);
        // Simulate the native export host expanding from a compact viewport to natural height.
        // Font zoom/percentage min-height must not cause an endless measure/resize loop.
        const height=Math.max(640,exported.height);
        assert.ok(height<=16000,name+' bounded export height');
        await page.setViewportSize({width:412,height});
        const resized=JSON.parse(await page.evaluate(probe));
        assert.ok(resized.height<=height+2,name+' export layout must stabilize');
        assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+2),name+' export must include all columns');
        if(name==='gloss-long.html'||name==='tree-wide.html')await page.screenshot({path:path.join(dir,name.replace('.html','-export.png')),fullPage:true});
      }
      assert.deepEqual(errors,[],name);assert.deepEqual(remote,[],name);
      results.push({name,status});await page.close();
    }
  } finally { await browser.close(); }
  fs.writeFileSync(path.join(dir,'browser-results.json'),JSON.stringify(results,null,2));
  console.log(`${results.length} production linguistic HTML scenarios passed`);
}
main().catch(e=>{console.error(e);process.exitCode=1;});
