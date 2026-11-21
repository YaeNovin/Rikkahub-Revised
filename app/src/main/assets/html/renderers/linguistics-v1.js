/* App-owned renderer for LinguisticRenderSpec v1. All user strings use textContent. */
(function(){
'use strict';
const NS='http://www.w3.org/2000/svg';
function el(tag,text,cls,parent){const n=document.createElement(tag);if(text!=null)n.textContent=text;if(cls)n.className=cls;if(parent)parent.append(n);return n;}
function se(tag,attrs,parent,text){const n=document.createElementNS(NS,tag);Object.entries(attrs||{}).forEach(([k,v])=>n.setAttribute(k,v));if(text!==undefined)n.textContent=text;if(parent)parent.append(n);return n;}
const canvas=document.createElement('canvas');
function width(text,font='18px RikkaIPA, system-ui, sans-serif'){const ctx=canvas.getContext('2d');ctx.font=font;return Math.ceil(ctx.measureText(text).width);}
function graph(parent,w,h,description){const scroll=el('div',null,'graph-scroll',parent),svg=se('svg',{width:w,height:h,viewBox:'0 0 '+w+' '+h,class:'ling-graph',role:'img','aria-label':description},scroll);se('title',{},svg,description);return svg;}
function tree(root,data){
 let deepest=0,count=0;
 function plan(n,d){deepest=Math.max(deepest,d);count++;const children=(n.children||[]).map(c=>plan(c,d+1));return {label:n.label,children,depth:d,width:Math.max(width(n.label)+36,children.reduce((s,c)=>s+c.width,0)+Math.max(0,children.length-1)*18)};}
 const top=plan(data.root,0),svg=graph(root,top.width+24,(deepest+1)*76+24,'Constituency tree ('+count+' nodes): '+data.root.label);
 function draw(n,left){const x=left+n.width/2,y=n.depth*76+38;let childLeft=left+(n.width-n.children.reduce((s,c)=>s+c.width,0)-Math.max(0,n.children.length-1)*18)/2;
 n.children.forEach(c=>{se('line',{x1:x,y1:y+10,x2:childLeft+c.width/2,y2:(n.depth+1)*76+13,class:'edge'},svg);draw(c,childLeft);childLeft+=c.width+18;});se('text',{x,y,'text-anchor':'middle'},svg,n.label);}
 draw(top,12);const details=el('details',null,null,root);el('summary','结构文本',null,details);
 const lines=[];function describe(n,d){lines.push('  '.repeat(d)+n.label);(n.children||[]).forEach(c=>describe(c,d+1));}describe(data.root,0);el('pre',lines.join('\n'),null,details);
}
function dependencies(root,data){
 data.sentences.forEach((sentence,index)=>{
 const section=el('section',null,null,root),text=sentence.comments.find(c=>/^# text\s*=/.test(c));
 el('h2',text?text.replace(/^# text\s*=\s*/,''):'Sentence '+(index+1),null,section);
 const tokens=[['0','ROOT','_','_']].concat(sentence.rows.filter(r=>!r[0].includes('-'))),centers=new Map();let cursor=12;
 tokens.forEach(r=>{const span=Math.max(70,width(r[1])+28,width(r[3]+' '+r[0],'12px system-ui')+20);centers.set(r[0],cursor+span/2);cursor+=span;});
 const layers=[],arcs=sentence.arcs.map(a=>({...a,start:Math.min(centers.get(a.from),centers.get(a.to)),end:Math.max(centers.get(a.from),centers.get(a.to))})).sort((a,b)=>(a.end-a.start)-(b.end-b.start));
 // Long UD subtypes need margins too; their labels must not escape the SVG.
 const minLabel=Math.min(12,...arcs.map(a=>(a.start+a.end)/2-width(a.label,'14px system-ui')/2-10));
 const maxLabel=Math.max(cursor,...arcs.map(a=>(a.start+a.end)/2+width(a.label,'14px system-ui')/2+10));
 const shift=12-minLabel;
 centers.forEach((value,key)=>centers.set(key,value+shift));
 arcs.forEach(a=>{a.start+=shift;a.end+=shift;});cursor=maxLabel+shift;
 arcs.forEach(a=>{const lw=width(a.label,'14px system-ui')+18,mid=(a.start+a.end)/2,left=Math.min(a.start-8,mid-lw/2),right=Math.max(a.end+8,mid+lw/2);let level=0;while(layers[level]&&layers[level].some(b=>left<=b[1]&&right>=b[0]))level++;(layers[level]||=[]).push([left,right]);a.level=level;});
 const baseline=layers.length*42+36,svg=graph(section,cursor+12,baseline+86,'UD dependencies: '+tokens.slice(1).map(r=>r[1]).join(' ')),defs=se('defs',{},svg),marker=se('marker',{id:'arrow-'+index,viewBox:'0 0 10 10',refX:9,refY:5,markerWidth:6,markerHeight:6,orient:'auto-start-reverse',markerUnits:'strokeWidth'},defs);
 se('path',{d:'M0 0 L10 5 L0 10 Z',fill:'currentColor'},marker);
 arcs.forEach(a=>{const from=centers.get(a.from),to=centers.get(a.to),top=baseline-(a.level+1)*42,sign=from<to?1:-1,start=from+sign*7,end=to-sign*7;
 se('path',{d:'M'+start+' '+baseline+' C'+start+' '+top+' '+end+' '+top+' '+end+' '+baseline,class:'edge'+(a.enhanced?' enhanced':''),'marker-end':'url(#arrow-'+index+')','data-head':a.from,'data-dependent':a.to,'data-relation':a.label},svg);
 const labelX=(start+end)/2,labelY=baseline-(baseline-top)*.75-5,labelWidth=width(a.label,'14px system-ui');
 se('rect',{x:labelX-labelWidth/2-3,y:labelY-14,width:labelWidth+6,height:18,rx:2,fill:'var(--bg)'},svg);
 se('text',{x:labelX,y:labelY,'text-anchor':'middle',class:'relation'},svg,a.label);});
 tokens.forEach(r=>{const x=centers.get(r[0]);se('text',{x,y:baseline+30,'text-anchor':'middle','data-token-id':r[0]},svg,r[1]);se('text',{x,y:baseline+54,'text-anchor':'middle',style:'font:12px system-ui'},svg,r[0]+(r[3]!=='_'?' · '+r[3]:''));});
 const multi=sentence.rows.filter(r=>r[0].includes('-'));if(multi.length)el('p','Multiword: '+multi.map(r=>r[0]+' '+r[1]).join('; '),'meta',section);
 el('p','实线：基本依存；虚线：增强依存。箭头由中心词指向依存词。','meta',section);
 const details=el('details',null,null,section);el('summary','CoNLL-U 原始字段与关系',null,details);el('pre',sentence.comments.join('\n'),null,details);
 const table=el('table',null,null,el('div',null,'graph-scroll',details)),header=el('tr',null,null,table);
 ['ID','FORM','LEMMA','UPOS','XPOS','FEATS','HEAD','DEPREL','DEPS','MISC'].forEach(n=>el('th',n,null,header));
 sentence.rows.forEach(r=>{const tr=el('tr',null,null,table);r.forEach(v=>el('td',v,null,tr));});
 });
}
window.rikkaRenderLinguistics=async function(root,data){
 if(data.version!==1)throw new Error('Unsupported linguistic schema');
 if(!document.fonts)throw new Error('当前 WebView 不支持离线 IPA 字体');
 let timer;
 try{const loaded=await Promise.race([document.fonts.load('20px RikkaIPA'),new Promise((_,reject)=>timer=setTimeout(()=>reject(new Error('IPA 字体加载超时')),8000))]);if(!loaded.length)throw new Error('IPA 字体不可用');}finally{clearTimeout(timer);}
 root.dataset.renderer=data.renderer;root.dataset.schemaVersion='1';root.lang=data.language||'';root.dir=data.dir||'auto';
 if(data.title)el('h2',data.title,null,root);if(data.id)el('p','('+data.id+')','meta',root);
 switch(data.renderer){
 case 'ipa':{const b=data.notation==='phonemic'?['/','/']:data.notation==='phonetic'?['[',']']:['',''];el('div',b[0]+data.text+b[1],'ipa',root).dir=data.dir||'auto';break;}
 case 'ruby_annotation':{const line=el('div',null,'ruby-line',root);data.segments.forEach(s=>{if(!s.reading){line.append(document.createTextNode(s.base));return;}const ruby=el('ruby',null,null,line);ruby.append(document.createTextNode(s.base));el('rp','(',null,ruby);el('rt',s.reading,null,ruby);el('rp',')',null,ruby);});break;}
 case 'gloss':{const list=el('div',null,'word-list',root);list.setAttribute('role','list');data.words.forEach((w,i)=>{const unit=el('div',null,'word',list);unit.setAttribute('role','listitem');unit.dataset.wordId=String(i+1);el('span',w.text,'original',unit);if(w.morphemes)el('span',w.morphemes,'morphemes',unit);el('span',w.gloss,'gloss',unit).dir='ltr';});break;}
 case 'syntax_tree':tree(root,data);break;
 case 'dependency':dependencies(root,data);break;
 default:throw new Error('Unknown linguistic renderer');
 }
 if(data.translation)el('p','“'+data.translation+'”','translation',root).dir='auto';
 if(data.legend)el('p',Object.entries(data.legend).map(([k,v])=>k+' = '+v).join('; '),'legend',root).dir='auto';
 if(data.source)el('p',data.source,'meta',root).dir='auto';
 window.__rikkaArtifact={schema_version:1,renderer:data.renderer,accessible_text:root.textContent};
 window.rikkaPrepareExport=function(mode){
 if(mode==='snapshot')return;
 if(root.dataset.export==='true')return;root.dataset.export='true';
 root.querySelectorAll('details').forEach(n=>n.open=true);
 root.querySelectorAll('.graph-scroll').forEach(n=>{n.style.overflow='visible';n.scrollLeft=n.scrollTop=0;});
 root.style.overflow='visible';root.scrollLeft=root.scrollTop=0;
 if(mode==='message'){
   const available=Math.max(100,root.clientWidth-24);
   root.querySelectorAll('svg').forEach(svg=>{svg.style.width=Math.min(available,svg.viewBox.baseVal.width)+'px';svg.style.height='auto';});
   root.querySelectorAll('table').forEach(table=>{table.style.tableLayout='fixed';table.style.width='100%';});
   root.querySelectorAll('td,th,pre,.word>span').forEach(node=>{node.style.whiteSpace='pre-wrap';node.style.overflowWrap='anywhere';});
   root.querySelectorAll('.word').forEach(node=>node.style.maxWidth='100%');
 }else{
   const widest=Math.max(root.clientWidth,...Array.from(root.querySelectorAll('svg,table,.word')).map(n=>n.getBoundingClientRect().width+24));
   root.style.width=Math.ceil(widest)+'px';document.body.style.width=root.style.width;
 }
 };
 await new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)));
};
})();
