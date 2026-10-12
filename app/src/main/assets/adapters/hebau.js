// SPDX-License-Identifier: GPL-3.0-only
// Independently implemented against the previously documented WDKB interface.
window.__vanImportResult=null;
(async()=>{
  try {
    const prefix='/jwapp/sys/wdkb/modules/';
    const post=async(path,args,key)=>{
      const response=await fetch(prefix+path,{method:'POST',credentials:'include',headers:{'Content-Type':'application/x-www-form-urlencoded;charset=UTF-8','X-Requested-With':'XMLHttpRequest'},body:new URLSearchParams(args)});
      if(!response.ok)throw Error('教务请求失败 '+response.status);
      const json=await response.json();const rows=json?.datas?.[key]?.rows;
      if(!Array.isArray(rows))throw Error('教务响应结构已改变或登录已失效');return rows;
    };
    const terms=await post('jshkcb/xnxqcx.do',{'*order':'-DM'},'xnxqcx');
    if(!terms.length)throw Error('无法读取学期列表');
    const now=new Date(), year=now.getFullYear(), month=now.getMonth()+1;
    const expected=month>=9?`${year}-${year+1}-1`:month<=1?`${year-1}-${year}-1`:`${year-1}-${year}-2`;
    const displayed=document.body.innerText;
    const selected=terms.find(t=>t.DM===expected)||terms.find(t=>displayed.includes(t.MC))||terms[0];
    const term=prompt('确认需要导入的学期代码（教务提供）：\n'+terms.map(t=>`${t.DM} ${t.MC||''}`).join('\n'),selected.DM);
    if(!term||!terms.some(t=>t.DM===term))throw Error('已取消或学期代码无效');
    const rows=await post('xskcb/cxxszhxqkb.do',{XNXQDM:term},'cxxszhxqkb');
    const courses=rows.map(r=>{
      const name=r.KCM||r.KCMC;const day=Number(r.SKXQ||r.XQJ);const first=Number(r.KSJC);const last=Number(r.JSJC);
      const bitmap=String(r.SKZC||'');if(!name||!day||!first||!last||!/^[01]+$/.test(bitmap))throw Error('课程字段与当前适配规则不同，请提供脱敏响应以修正');
      return {name,day,startSection:first,endSection:last,weeks:[...bitmap].flatMap((v,i)=>v==='1'?[i+1]:[]),teacher:r.JSM||r.JSXM||'',position:r.JASMC||r.JAS||r.SKDD||''};
    });
    let sections=[];
    try{const times=await post('jshkcb/jc.do',{},'jc');sections=times.map(t=>({number:Number(t.JC),start:String(t.KSSJ||'').slice(0,5),end:String(t.JSSJ||'').slice(0,5)}));if(sections.some(t=>!t.number||!/^\d{2}:\d{2}$/.test(t.start)||!/^\d{2}:\d{2}$/.test(t.end)))sections=[];}catch(e){}
    if(!courses.length)throw Error('该学期未返回课程');
    window.__vanImportResult={courses,sections,term};
  }catch(e){window.__vanImportResult={error:e.message||String(e)};}
})();
