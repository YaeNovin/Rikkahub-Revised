// Production Kotlin-generated prose pages, real local KaTeX/mhchem and W3C Ruby layout.
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {chromium}=require('playwright');
const root=path.resolve(__dirname,'..'),dir=path.join(root,'app/build/reports/academic-prose');
(async()=>{
 const browser=await chromium.launch({channel:'msedge',headless:true}),results=[];
 try{
  for(const name of fs.readdirSync(dir).filter(n=>n.endsWith('.html'))){
   for(const width of [320,412]){
    const page=await browser.newPage({viewport:{width,height:800},isMobile:true,hasTouch:true}),remote=[],errors=[];
    page.on('pageerror',e=>errors.push(e.message));
    await page.route('**/*',async route=>{
     const url=new URL(route.request().url());
     if(url.host!=='rikkahub.local'){remote.push(url.href);return route.abort();}
     if(url.pathname==='/')return route.fulfill({path:path.join(dir,name),contentType:'text/html'});
     const file=path.resolve(root,'app/src/main','.'+url.pathname);
     if(!file.startsWith(path.join(root,'app/src/main/assets')+path.sep)||!fs.existsSync(file))return route.fulfill({status:404,body:'missing'});
     return route.fulfill({path:file,contentType:file.endsWith('.js')?'text/javascript':file.endsWith('.css')?'text/css':file.endsWith('.woff2')?'font/woff2':'text/plain'});
    });
    await page.goto('https://rikkahub.local/');
    await page.waitForFunction(()=>window.__rikkaRenderStatus==='ready');
    await page.addStyleTag({content:'html{background:var(--bg)!important}'});
    const main=page.locator(name.startsWith('preview')?'#content':'#academic');
    assert.deepEqual(errors,[],name);assert.deepEqual(remote,[],name);
    assert.equal(await main.locator('script,img').count(),0,name);
    assert.equal(await page.locator('.math-error').count(),0,name);
    if(name.startsWith('inline')&&!name.includes('disabled')){
     assert.equal(await page.locator('ruby').count(),2);assert.equal(await page.locator('.ipa').count(),2);
     const ruby=await page.locator('ruby').first().evaluate(n=>({base:n.firstChild.textContent,reading:n.querySelector('rt').textContent,top:n.querySelector('rt').getBoundingClientRect().top,bottom:n.getBoundingClientRect().bottom}));
     assert.equal(ruby.base,'汉字');assert.equal(ruby.reading,'hàn zì');assert.ok(ruby.top<ruby.bottom);
     assert.ok(await page.evaluate(()=>document.fonts.check('18px RikkaIPA')));
    }
    if(name.includes('disabled'))assert.equal(await page.locator('ruby,.ipa').count(),0);
    if(name.startsWith('math'))assert.equal(await page.locator('.prose-math .katex').count(),4);
    if(name.startsWith('tree'))assert.ok(await page.locator('.syntax-tree svg text').count()>=3);
    if(name.startsWith('preview')){
     assert.equal(await main.locator('ruby').count(),1);assert.equal(await main.locator('.prose-math>.katex').count(),2);
     assert.equal(await main.locator('code').innerText(),'\\ce{literal}');
    }
    if(name.startsWith('apparatus')){
     const before=await page.locator('#academic').evaluate(n=>n.getBoundingClientRect().height);
     await page.locator('details>summary').first().click();
     assert.ok(await page.locator('details').first().evaluate(n=>n.open));
     assert.ok(await page.locator('#academic').evaluate(n=>n.getBoundingClientRect().height)>before);
     assert.match(await page.locator('.reading').first().innerText(),/A：花/);
    }
    if(name.startsWith('vertical')){
     assert.equal(await page.locator('.vertical-rl').evaluate(n=>getComputedStyle(n).writingMode),'vertical-rl');
     await page.setViewportSize({width,height:80});
     assert.ok(await page.locator('.vertical-rl').evaluate(n=>n.clientHeight>=300),'Bootstrap viewport must not collapse vertical columns');
     await page.setViewportSize({width,height:800});
     const value=await page.locator('.vertical-scroll').evaluate(n=>{const start=n.scrollLeft;n.scrollLeft=-100;return{moved:n.scrollLeft!==start,scroll:n.scrollWidth,width:n.clientWidth};});
     assert.ok(value.scroll>value.width);assert.ok(value.moved);
     const ruby=await page.locator('ruby').first().evaluate(n=>{const r=n.getBoundingClientRect(),t=n.querySelector('rt').getBoundingClientRect();return t.left>=r.left;});assert.ok(ruby);
     await page.evaluate(()=>window.rikkaPrepareExport('message'));
     assert.ok(await page.locator('.vertical-export-panel').count()>1);
     assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+2));
    }
    if(width===412&&name.includes('dark')&&!name.startsWith('long'))await page.screenshot({path:path.join(dir,name.replace('.html','.png')),fullPage:true});
    results.push({name,width,remote:remote.length,errors:errors.length,height:await main.evaluate(n=>n.getBoundingClientRect().height)});
    await page.close();
   }
  }
 }finally{await browser.close();}
 fs.writeFileSync(path.join(dir,'browser-results.json'),JSON.stringify(results,null,2));
 console.log(JSON.stringify({passed:results.length,remoteRequests:0,output:dir}));
})().catch(e=>{console.error(e);process.exitCode=1;});
