// Production HTML and native-export JavaScript; no models, server or remote assets.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright');
const root = path.resolve(__dirname, '..');
const assets = path.join(root, 'app/src/main/assets');
const reports = path.join(root, 'app/build/reports/geometry-preview');
const probe = fs.readFileSync(path.join(reports, 'export-probe.js'), 'utf8');
async function main() {
  const browser = await chromium.launch({channel:'msedge', headless:true});
  const results = [];
  try {
    for (const name of ['compatibility', 'export-tall-svg', 'invalid']) {
      const page = await browser.newPage({viewport:{width:460,height:300}});
      const errors=[], requests=[], dialogs=[];
      page.on('pageerror',e=>errors.push(e.message));
      page.on('dialog',async d=>{ dialogs.push(d.message()); await d.dismiss(); });
      await page.route('**/*',route=>{
        const url=new URL(route.request().url());
        if(url.origin!=='https://rikkahub.local'){requests.push(url.href);return route.abort();}
        if(url.pathname==='/') return route.fulfill({path:path.join(reports,name+'.html'),contentType:'text/html'});
        const file=path.resolve(root,'app/src/main','.'+url.pathname);
        if(!file.startsWith(assets+path.sep)) return route.abort();
        return route.fulfill({path:file,contentType:file.endsWith('.css')?'text/css':'text/javascript'});
      });
      await page.goto('https://rikkahub.local/');
      await page.waitForFunction(()=>['ready','error'].includes(window.__rikkaRenderStatus));
      if(name==='compatibility') {
        assert.equal(await page.locator('input').inputValue(),'1');
        assert.equal(await page.locator('input').getAttribute('step'),'any');
        assert.match(await page.locator('#parameters').innerText(),/振幅/);
        assert.match(await page.locator('#legend').innerText(),/<img src=x onerror=evil>/);
        assert.equal(await page.locator('img,[onerror]').count(),0);
        await page.locator('input').fill('2');
        await page.waitForTimeout(180);
        await page.locator('#reset').click();
        assert.equal(await page.locator('input').inputValue(),'1');
      }
      let result;
      for(let attempt=0;attempt<15;attempt++) {
        result=JSON.parse(await page.evaluate(probe));
        if(result.status==='error') break;
        if(result.status==='ready'&&result.height<=result.viewport+2) break;
        if(result.height>result.viewport+2) await page.setViewportSize({width:460,height:result.height});
        await page.waitForTimeout(100);
      }
      if(name==='invalid') {assert.equal(result.status,'error');assert.match(result.error,/无法绘图/);}
      else {
        assert.equal(result.status,'ready');
        assert.ok(result.height<=result.viewport+2,'Natural content is fully visible');
        if(name==='export-tall-svg') {
          const end=await page.locator('#end').boundingBox();
          assert.ok(end.y+end.height<=result.viewport+2,'Last SVG element must be inside exported image');
          assert.ok(result.height>4000,'Tall diagram must not collapse to 200px viewport');
        } else {
          assert.equal(await page.locator('#controls').isVisible(),false);
          const legend=await page.locator('#legend').evaluate(e=>({h:e.clientHeight,scroll:e.scrollHeight}));
          assert.equal(legend.h,legend.scroll,'No clipped legend');
        }
      }
      assert.deepEqual(errors,[]);assert.deepEqual(requests,[]);assert.deepEqual(dialogs,[]);
      await page.screenshot({path:path.join(reports,name+'-export.png'),fullPage:true});
      results.push({name,status:'passed',probe:result});
      await page.close();
    }
    fs.writeFileSync(path.join(reports,'export-results.json'),JSON.stringify(results,null,2));
    console.log('PASS 3 export/compatibility browser scenarios; Android bitmap capture is separate.');
  } finally {await browser.close();}
}
main().catch(e=>{console.error(e);process.exitCode=1;});
