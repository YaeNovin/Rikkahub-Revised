const fs=require('fs'),path=require('path'),assert=require('assert/strict');
const {chromium}=require('playwright');
(async()=>{
 const root=path.resolve(__dirname,'..'),browser=await chromium.launch({channel:'msedge',headless:true});
 try {
 const page=await browser.newPage({viewport:{width:393,height:780}}),errors=[];
 page.on('pageerror',e=>errors.push(e.message));
 await page.route('**/*',route=>{
 const u=new URL(route.request().url());
 if(u.host!=='rikkahub.local')return route.abort();
 const file=u.pathname==='/'?'app/build/reports/fullscreen-pan/preview.html':'app/src/main/assets/html/fullscreen-pan.js';
 return route.fulfill({body:fs.readFileSync(path.join(root,file)),contentType:u.pathname==='/'?'text/html':'text/javascript'});
 });
 await page.goto('https://rikkahub.local/');
 const before=await page.locator('svg').boundingBox();
 assert.ok(Math.abs(before.y+before.height/2-390)<2,'initially centered');
 await page.mouse.move(before.x+70,before.y+70);await page.mouse.down();await page.mouse.move(before.x+110,before.y+130);await page.mouse.up();
 const after=await page.locator('svg').boundingBox();
 assert.ok(Math.abs(after.x-before.x-40)<2);assert.ok(Math.abs(after.y-before.y-60)<2);
 assert.equal(await page.locator('rect').evaluate(n=>n.style.transform),'','inner SVG untouched');
 await page.locator('svg').dblclick();
 assert.equal(await page.locator('svg').evaluate(n=>n.style.transform),'');
 await page.evaluate(()=>{
 const svg=document.querySelector('svg');document.querySelector('#rikkahub-pan-scene').setPointerCapture=()=>{};
 const fire=(type,id,x,y)=>svg.dispatchEvent(new PointerEvent(type,{bubbles:true,pointerId:id,clientX:x,clientY:y,button:0,pointerType:'touch'}));
 fire('pointerdown',11,100,100);fire('pointerdown',12,200,100);fire('pointermove',12,260,100);fire('pointerup',11,100,100);fire('pointerup',12,260,100);
 });
 assert.ok((await page.locator('#rikkahub-pan-scene').evaluate(n=>n.style.transform)).includes('scale(1.6)'));
 await page.evaluate(()=>window.rikkaResetPan());
 // Move from empty space, beyond the original preview's boundaries. It must remain visible.
 await page.mouse.move(20,100);await page.mouse.down();await page.mouse.move(40,300);await page.mouse.up();
 const moved=await page.locator('svg').boundingBox();
 assert.ok(Math.abs(moved.y-before.y-200)<2);
 const hit=await page.evaluate(({x,y})=>Boolean(document.elementFromPoint(x,y)?.closest('svg')),{x:moved.x+30,y:moved.y+30});
 assert.ok(hit,'scene visible beyond original diagram bounds');
 // Background toggles change styling only; they must not recreate the scene or reset the view.
 await page.evaluate(()=>{document.body.style.backgroundColor='rgb(20, 30, 40)';window.sceneBeforeToggle=document.querySelector('#rikkahub-pan-scene');});
 const transformBefore=await page.locator('#rikkahub-pan-scene').evaluate(n=>n.style.transform);
 await page.evaluate(()=>window.rikkaSetPreviewBackground(true));
 assert.equal(await page.locator('body').evaluate(n=>getComputedStyle(n).backgroundColor),'rgba(0, 0, 0, 0)');
 await page.evaluate(()=>window.rikkaSetPreviewBackground(false));
 assert.equal(await page.locator('body').evaluate(n=>getComputedStyle(n).backgroundColor),'rgb(20, 30, 40)');
 assert.equal(await page.locator('#rikkahub-pan-scene').evaluate(n=>n.style.transform),transformBefore);
 assert.ok(await page.evaluate(()=>window.sceneBeforeToggle===document.querySelector('#rikkahub-pan-scene')));
 assert.deepEqual(errors,[]);console.log('PASS center, drag, inner-node preservation, reset and two-pointer scaling');
 } finally {await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
