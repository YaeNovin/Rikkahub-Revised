// Exercise production Kotlin-generated score HTML offline, including the real WASM Worker.
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {chromium}=require('playwright');
const root=path.resolve(__dirname,'..'),dir=path.join(root,'app/build/reports/music-renderers');
(async()=>{
  const browser=await chromium.launch({channel:'msedge',headless:true});const results=[];
  try{
    for(const name of fs.readdirSync(dir).filter(n=>n.endsWith('.html'))){
      const page=await browser.newPage({viewport:{width:360,height:640},isMobile:true,hasTouch:true});
      const errors=[],remote=[];page.on('pageerror',e=>errors.push(e.message));
      await page.route('**/*',async route=>{
        const url=new URL(route.request().url());
        if(url.host!=='rikkahub.local'){remote.push(url.href);return route.abort();}
        if(url.pathname==='/')return route.fulfill({path:path.join(dir,name),contentType:'text/html'});
        const file=path.resolve(root,'app/src/main','.'+url.pathname);
        if(!file.startsWith(path.join(root,'app/src/main/assets')+path.sep)||!fs.existsSync(file))return route.fulfill({status:404,body:'missing'});
        return route.fulfill({path:file,contentType:/\.m?js$/.test(file)?'text/javascript':file.endsWith('.wasm')?'application/wasm':'text/plain'});
      });
      const start=Date.now();await page.goto('https://rikkahub.local/');
      await page.waitForFunction(()=>['ready','error'].includes(window.__rikkaRenderStatus),{},{timeout:25000});
      const status=await page.evaluate(()=>window.__rikkaRenderStatus);
      if(name.startsWith('invalid-')){assert.equal(status,'error');assert.ok(await page.locator('#render-error').isVisible());}
      else{
        assert.equal(status,'ready',name+': '+await page.locator('#render-error').textContent());
        const initial=await page.evaluate(()=>window.rikkaScoreInfo());
        assert.ok(initial.pages>=1&&initial.pages<=64,name);
        if(!name.startsWith('mei-')||name.startsWith('mei-long'))assert.ok(initial.pages>1,name+' long score must paginate');
        assert.equal(await page.locator('.score-page > svg').count(),1,name+' display only selected page');
        if(initial.pages>1){
          const first=await page.locator('.score-page').innerHTML();
          await page.getByRole('button',{name:'下一页'}).click();
          assert.equal(await page.locator('.score-page').getAttribute('data-page'),'2');
          assert.notEqual(await page.locator('.score-page').innerHTML(),first);
          await page.getByRole('button',{name:'上一页'}).click();
          assert.equal(await page.locator('.score-page').getAttribute('data-page'),'1');
        }
        for(const width of [320,412]){
          const old=await page.evaluate(()=>window.rikkaScoreInfo().revision);
          await page.setViewportSize({width,height:640});
          await page.waitForFunction(old=>window.__rikkaRenderStatus==='ready'&&window.__rikkaScore?.revision>old,old,{timeout:25000});
          assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+2),name+' no horizontal clipping');
        }
        const info=await page.evaluate(()=>window.rikkaScoreInfo());
        for(let n=1;n<=info.pages;n++){
          const svg=await page.evaluate(({n,revision})=>window.rikkaScoreExportPage(n,revision),{n,revision:info.revision});
          assert.ok(svg.length>300,name+' page '+n);
          const parsed=await page.evaluate(svg=>{const d=new DOMParser().parseFromString(svg,'image/svg+xml');return {valid:!d.querySelector('parsererror'),ink:d.querySelectorAll('path,use,line,text').length};},svg);
          assert.ok(parsed.valid&&parsed.ink>0,name+' valid nonempty SVG '+n);
          assert.ok(!/class="[^"]*\bplaceholder\b/.test(svg),name+' hidden editor placeholders must not enter PDF');
          if([1,Math.ceil(info.pages/2),info.pages].includes(n))fs.writeFileSync(path.join(dir,name.replace('.html','-page-'+n+'.svg')),svg);
        }
        if(name.startsWith('musicxml')){
          const texts=await page.evaluate(info=>Array.from({length:info.pages},(_,n)=>window.rikkaScoreExportPage(n+1,info.revision)).join(''),info);
          assert.ok(texts.includes('V7'),name+' MusicXML numeral harmony must be visible');
        }
        if(name.startsWith('mei-neume')){
          assert.ok(await page.locator('.score-page .nc').count(),name+' genuine neume components');
        }
        await page.screenshot({path:path.join(dir,name.replace('.html','-browser.png'))});
        const before=await page.locator('.score-page').getAttribute('data-page');
        await page.evaluate(()=>window.rikkaPrepareExport('snapshot'));
        assert.equal(await page.locator('.score-page').getAttribute('data-page'),before);
        await page.evaluate(()=>window.rikkaPrepareExport('message'));
        assert.equal(await page.locator('.score-page > svg').count(),info.pages,name+' message export includes last page');
        results.push({name,status,pages:info.pages,readyMs:Date.now()-start});
      }
      assert.deepEqual(errors,[],name);assert.deepEqual(remote,[],name);await page.close();
    }
    fs.writeFileSync(path.join(dir,'browser-results.json'),JSON.stringify(results,null,2));
    console.log('Offline music scenarios passed:',results.length,'plus invalid-input fallbacks');
  }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
