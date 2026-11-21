import createVerovioModule from './verovio-6.4.1/verovio-module.mjs';
import { VerovioToolkit, enableLog, LOG_OFF } from './verovio-6.4.1/verovio.mjs';

// One score per disposable Worker. Termination also releases the WASM heap.
self.onmessage = async function(event) {
  let toolkit;
  try {
    const {source,width}=event.data;
    if(typeof source!=='string'||source.length>524288)throw Error('MEI 数据过大');
    const module=await createVerovioModule();
    enableLog(LOG_OFF,module);
    toolkit=new VerovioToolkit(module);
    toolkit.setOptions({inputFrom:'mei',pageWidth:Math.round(width*100/60),pageHeight:Math.round(width*100/60*297/210),
      scale:60,adjustPageHeight:false,pageMarginTop:30,pageMarginBottom:30,pageMarginLeft:30,pageMarginRight:30,
      svgViewBox:true,svgHtml5:false,breaks:'auto',header:'auto',footer:'none'});
    if(!toolkit.loadData(source))throw Error('Verovio 无法读取此 MEI 乐谱，请检查结构和记谱法');
    const count=toolkit.getPageCount();
    if(count<1||count>64)throw Error('MEI 乐谱需为 1–64 页，请拆分长谱');
    const pages=[];let total=0;
    for(let page=1;page<=count;page++){
      const svg=toolkit.renderToSVG(page,false);total+=svg.length;
      if(!svg||svg.length>2000000||total>16000000)throw Error('MEI 谱面超出导出预算，请拆分长谱');
      pages.push(svg);
    }
    self.postMessage({pages,version:toolkit.getVersion()});
  } catch(error) { self.postMessage({error:String(error.message||error)}); }
  finally { if(toolkit)toolkit.destroy(); }
};
