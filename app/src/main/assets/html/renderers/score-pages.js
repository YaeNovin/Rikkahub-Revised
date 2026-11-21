/* App-owned score pagination/export contract. Engine SVGs remain data, never executable HTML. */
(function () {
  'use strict';
  const ns = 'http://www.w3.org/2000/svg';
  let pages = [], current = 0, revision = 0, stage, label, previous, next;
  const svgTag = attrs => { const s = document.createElementNS(ns, 'svg'); for (const [k,v] of Object.entries(attrs)) s.setAttribute(k,v); return s; };
  function viewBox(svg) {
    const v = svg.viewBox.baseVal;
    const width = v.width || parseFloat(svg.getAttribute('width'));
    const height = v.height || parseFloat(svg.getAttribute('height'));
    if (!(width > 0 && height > 0) || !Number.isFinite(width + height)) throw Error('谱面尺寸无效');
    return { x:v.x, y:v.y, width, height };
  }
  function clean(svg) {
    // Verovio's CSS-hidden editing placeholders are not score ink. AndroidSVG
    // can render their ellipse despite the upstream class rule; remove them explicitly.
    svg.querySelectorAll('script,foreignObject,image,a,iframe,g.placeholder,g.cursor').forEach(n=>n.remove());
    for (const node of [svg,...svg.querySelectorAll('*')]) {
      for (const attr of [...node.attributes]) {
        if (/^on/i.test(attr.name) || ((attr.localName === 'href') && !attr.value.startsWith('#')) || /url\(\s*['"]?(?!#)/i.test(attr.value)) node.removeAttributeNode(attr);
      }
    }
    svg.style.color = '#111'; svg.setAttribute('color','#111');
    svg.setAttribute('xmlns',ns); svg.setAttribute('xmlns:xlink','http://www.w3.org/1999/xlink');
    return svg;
  }
  function show(index) {
    if (!pages.length) return;
    current = Math.max(0,Math.min(pages.length-1,index));
    stage.replaceChildren(pages[current].cloneNode(true));
    stage.dataset.page = String(current+1);
    label.textContent = (current+1)+' / '+pages.length;
    previous.disabled = current === 0; next.disabled = current === pages.length-1;
    window.__rikkaScore = { version:1, pages:pages.length, page:current+1, revision };
  }
  // Pack complete systems, never crop a staff/lyric/chord across page boundaries.
  function pack(systems) {
    const width = Math.max(...systems.map(s=>viewBox(s).width));
    const height = width * 297 / 210;
    const result = []; let page, y;
    const start = () => { page=svgTag({viewBox:'0 0 '+width+' '+height,width,height,role:'img','aria-label':'乐谱页'});y=8;result.push(page); };
    start();
    for (const system of systems) {
      const v=viewBox(system), scale=Math.min(1,(height-16)/v.height);
      const h=v.height*scale;
      if(y+h>height-8 && page.children.length)start();
      const copy=system.cloneNode(true);
      copy.setAttribute('x',String((width-v.width*scale)/2));copy.setAttribute('y',String(y));
      copy.setAttribute('width',String(v.width*scale));copy.setAttribute('height',String(h));
      copy.style.width='';copy.style.height='';copy.style.maxWidth='';
      page.append(copy); y+=h+4;
    }
    return result;
  }
  window.rikkaScorePages = function(root, svgs, completePages) {
    const input = Array.from(svgs);
    if (!input.length) throw Error('谱面没有生成页面');
    pages=(completePages ? input.map(s=>s.cloneNode(true)) : pack(input)).map(clean);
    if (pages.length>64) { pages=[]; throw Error('谱面超过 64 页，请拆分乐谱'); }
    revision++;
    const toolbar=document.createElement('nav');toolbar.className='score-pager';toolbar.setAttribute('aria-label','乐谱翻页');
    previous=document.createElement('button');previous.textContent='上一页';previous.onclick=()=>show(current-1);
    next=document.createElement('button');next.textContent='下一页';next.onclick=()=>show(current+1);
    label=document.createElement('span');label.setAttribute('aria-live','polite');
    toolbar.append(previous,label,next);
    stage=document.createElement('div');stage.className='score-page';
    root.replaceChildren(toolbar,stage);
    if(root.dataset.scoreNotice){const notice=document.createElement('p');notice.className='score-notice';notice.textContent=root.dataset.scoreNotice;notice.style.cssText='font-size:13px;padding:8px;color:#333;background:#f3f3f3';root.insertBefore(notice,stage);}
    show(current);
  };
  // The numbered renderer exposes one SVG with complete top-level systems and labels.
  window.rikkaScoreSystems = function(svg) {
    const v=viewBox(svg), ranges=[];
    for (const node of svg.children) {
      if (['defs','style','title','desc'].includes(node.localName)) continue;
      const box=node.getBBox(); if(box.width||box.height)ranges.push({node,top:box.y-4,bottom:box.y+box.height+4});
    }
    ranges.sort((a,b)=>a.top-b.top);
    const groups=[];
    for(const item of ranges){const last=groups[groups.length-1];if(last&&item.top<=last.bottom){last.bottom=Math.max(last.bottom,item.bottom);last.nodes.push(item.node);}else groups.push({top:item.top,bottom:item.bottom,nodes:[item.node]});}
    return groups.map(group=>{
      const s=svgTag({viewBox:[v.x,group.top,v.width,group.bottom-group.top].join(' '),width:v.width,height:group.bottom-group.top});
      svg.querySelectorAll(':scope > defs,:scope > style').forEach(n=>s.append(n.cloneNode(true)));
      group.nodes.forEach(n=>s.append(n.cloneNode(true)));return s;
    });
  };
  window.rikkaScoreInfo = () => {
    if(window.__rikkaRenderStatus!=='ready'||!pages.length)throw Error('乐谱尚未就绪');
    return {pages:pages.length,revision};
  };
  window.rikkaScoreExportPage = (page, expectedRevision) => {
    if(expectedRevision!==revision||window.__rikkaRenderStatus!=='ready')throw Error('谱面尺寸发生变化，请重新导出');
    if(!Number.isInteger(page)||page<1||page>pages.length)throw Error('页码无效');
    const svg=clean(pages[page-1].cloneNode(true));
    svg.style.backgroundColor='#fff';svg.style.fontFamily='sans-serif';
    return new XMLSerializer().serializeToString(svg);
  };
  window.rikkaPrepareExport = purpose => {
    if(purpose!=='message')return;
    // Message screenshots include every page. Dedicated PDF export reads pages one at a time.
    stage.replaceChildren(...pages.map(p=>p.cloneNode(true)));
    document.querySelector('.score-pager').hidden=true;
  };
})();
