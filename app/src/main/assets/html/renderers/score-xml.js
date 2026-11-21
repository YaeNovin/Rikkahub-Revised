/* MusicXML/MEI input checks shared by fences and validated attachments. No network resources. */
(function(){
  'use strict';
  window.rikkaParseScoreXml = function(source, kind) {
    if(source.length>524288)throw Error('乐谱超过 512 Ki 字符');
    const clean=source.replace(/^\uFEFF/,'').replace(/<!DOCTYPE\s+score-partwise\s+(?:PUBLIC\s+"[^"<>\[\]]*"\s+"[^"<>\[\]]*"|SYSTEM\s+"[^"<>\[\]]*")\s*>/g,'');
    if(/<!DOCTYPE|<!ENTITY/i.test(clean))throw Error('不支持 DTD 内部子集或实体声明');
    const doc=new DOMParser().parseFromString(clean,'application/xml');
    if(doc.querySelector('parsererror'))throw Error('乐谱 XML 格式无效');
    const root=doc.documentElement;
    if(kind==='musicxml' ? root.localName!=='score-partwise' : root.localName!=='mei'||root.namespaceURI!=='http://www.music-encoding.org/ns/mei')throw Error(kind==='mei'?'需要带 MEI 命名空间的 mei 文档':'需要 score-partwise MusicXML 乐谱');
    if(kind==='mei'&&root.getElementsByTagNameNS('*','mdiv').length!==1)throw Error('目前每份 MEI 需包含一个 mdiv，多个乐章请分文件预览');
    const elements=Array.from(doc.getElementsByTagName('*'));
    if(elements.length>40000)throw Error('乐谱 XML 节点过多');
    let notes=0;
    for(const node of elements){
      if(['note','rest','nc'].includes(node.localName))notes++;
      let depth=0,p=node;while(p&&depth<=65){depth++;p=p.parentNode;}if(depth>65)throw Error('乐谱 XML 嵌套过深');
      if(['script','image','graphic','binaryObject','include'].includes(node.localName))throw Error('乐谱不加载外部资源');
      for(const attr of node.attributes){
        if(/^on/i.test(attr.name))throw Error('乐谱不接受事件脚本');
        if(['href','target','facs','source'].includes(attr.localName)&&attr.value.split(/\s+/).some(v=>v&&!v.startsWith('#')))throw Error('乐谱不加载外部引用');
      }
    }
    if(notes<1||notes>16000)throw Error('乐谱需包含音符或休止符，最多 16000 个事件');
    if(kind==='musicxml'){
      const parts=root.querySelectorAll(':scope > part');
      if(!parts.length||parts.length>24)throw Error('乐谱最多 24 个声部');
      for(const part of parts)if(part.querySelectorAll(':scope > measure').length>512)throw Error('每个声部最多 512 小节');
    }
    return doc;
  };
  window.rikkaMusicXmlHarmony = function(doc) {
    // OSMD 2.1.2 renders root/kind chord symbols, but not MusicXML 4 numeral.
    // Convert numeral/function only in the display DOM to a standard timed direction.
    // Original XML (including analysis) remains unchanged for copy/source export.
    for(const harmony of Array.from(doc.querySelectorAll('harmony'))){
      const numeral=harmony.querySelector('numeral'),legacy=harmony.querySelector('function');
      if(!numeral&&!legacy)continue;
      const root=numeral&&numeral.querySelector('numeral-root'),kind=harmony.querySelector('kind');
      const n=root&&Number(root.textContent);
      const roman=['','I','II','III','IV','V','VI','VII'][n]||String(n||'');
      const type=kind?kind.textContent.trim():'';
      const alter=numeral&&Number(numeral.querySelector('numeral-alter')?.textContent||0);
      let value=legacy?legacy.textContent.trim():(alter<0?'♭':alter>0?'♯':'')+(root?.getAttribute('text')||(['minor','minor-seventh','diminished','half-diminished'].includes(type)?roman.toLowerCase():roman));
      const suffix={'dominant':'7','major-seventh':'maj7','minor-seventh':'7','diminished':'°','diminished-seventh':'°7','half-diminished':'ø7','augmented':'+','suspended-second':'sus2','suspended-fourth':'sus4'};
      value+=kind?.getAttribute('text')||suffix[type]||(['major','minor','none',''].includes(type)?'':' ['+type+']');
      const inversion=harmony.querySelector('inversion');if(inversion&&Number(inversion.textContent))value+=' (inv. '+inversion.textContent+')';
      for(const degree of harmony.querySelectorAll('degree'))value+=' ['+(degree.querySelector('degree-type')?.textContent||'')+' '+(degree.querySelector('degree-alter')?.textContent||'0')+':'+(degree.querySelector('degree-value')?.textContent||'')+']';
      const direction=doc.createElement('direction');direction.setAttribute('placement',harmony.getAttribute('placement')||'above');
      const directionType=doc.createElement('direction-type'),words=doc.createElement('words');words.textContent=value;directionType.append(words);direction.append(directionType);
      for(const tag of ['offset','staff']){const child=harmony.querySelector(':scope > '+tag);if(child)direction.append(child.cloneNode(true));}
      harmony.replaceWith(direction);
    }
    return doc;
  };
})();
