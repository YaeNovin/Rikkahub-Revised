/* Fixed offline prose typesetter. Authored content is sanitized in Kotlin; no eval or fetch. */
window.rikkaTypesetAcademic=async function(root){'use strict';
 window.__rikkaRenderStatus='loading';
 function prepareExport(){
  if(root.dataset.exported)return;root.dataset.exported='true';
  root.querySelectorAll('details').forEach(n=>n.open=true);
  root.querySelectorAll('.vertical-scroll').forEach(wrap=>{
   const inner=wrap.firstElementChild,width=wrap.clientWidth,total=inner.getBoundingClientRect().width,height=inner.getBoundingClientRect().height;
   if(total<=width+1)return;
   const column=parseFloat(getComputedStyle(inner).lineHeight),page=Math.max(column,Math.floor(width/column)*column);
   if(Math.ceil(total/page)>32){wrap.insertAdjacentText('afterend','〔竖排过长，请分段导出〕');return;}
   const pages=document.createElement('div');
   for(let offset=0;offset<total;offset+=page){const panel=document.createElement('div');panel.className='vertical-export-panel';panel.style.height=height+'px';panel.style.width=Math.min(page,total-offset)+'px';
    const copy=inner.cloneNode(true);copy.style.position='absolute';copy.style.width=total+'px';copy.style.height=height+'px';copy.style.maxHeight='none';
    if(inner.classList.contains('vertical-lr'))copy.style.left=-offset+'px';else copy.style.right=-offset+'px';
    panel.append(copy);pages.append(panel);
   }wrap.replaceWith(pages);
  });
 }
 window.rikkaPrepareExport=prepareExport;
 function finish(){window.__rikkaRenderStatus='ready';}
  try{
   root.querySelectorAll('.prose-math').forEach(n=>{
    let formula=n.textContent;
    formula=formula.replace(/\\begin\{(?:equation\*?|displaymath)\}([\s\S]*?)\\end\{(?:equation\*?|displaymath)\}/g,'$1')
      .replace(/\\begin\{align\*?\}/g,'\\begin{aligned}').replace(/\\end\{align\*?\}/g,'\\end{aligned}')
      .replace(/\\(?:label|tag)\{[^{}]*\}/g,'');
    try{window.katex.render(formula,n,{displayMode:n.classList.contains('display-math'),throwOnError:true,trust:false,strict:'ignore',maxExpand:1000,maxSize:30,output:'htmlAndMathml'});}
    catch(error){n.classList.add('math-error');n.textContent='〔公式无法排版〕 '+formula;n.setAttribute('aria-label',n.textContent);}
   });
   for(const n of root.querySelectorAll('.syntax-tree')){const data=JSON.parse(n.dataset.spec);n.removeAttribute('data-spec');await window.rikkaRenderLinguistics(n,data);}
   if(document.fonts){let timer;try{await Promise.race([Promise.all([document.fonts.load('18px RikkaIPA'),document.fonts.ready]),new Promise(resolve=>timer=setTimeout(resolve,8000))]);}finally{clearTimeout(timer);}}
   root.querySelectorAll('.vertical-scroll').forEach(n=>{n.scrollLeft=0;});
   finish();
   const report=()=>{if(window.RikkaProse)window.RikkaProse.resized(Math.ceil(root.getBoundingClientRect().height));};
   report();
   if(window.ResizeObserver)new ResizeObserver(report).observe(root);
   // Details change intrinsic height after a user click. Notify the native view through a bounded DOM value.
   document.addEventListener('toggle',()=>{window.__rikkaProseHeight=Math.ceil(root.getBoundingClientRect().height);},true);
  }catch(error){root.append(document.createTextNode('〔排版失败〕 '+String(error.message).slice(0,180)));finish();}
};
if(document.getElementById('academic'))window.rikkaTypesetAcademic(document.getElementById('academic'));
