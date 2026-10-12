// SPDX-License-Identifier: GPL-3.0-only
// Protocol/parser regression tests. These do not replace Android WebView/DOM tests.
const {test} = require('node:test');
const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const {join} = require('node:path');
const vm = require('node:vm');
const source = readFileSync(join(__dirname, '../app/src/main/assets/adapters/hebiace.js'), 'utf8');
const row = {kcmc:'课程甲', xqj:'3', jc:'3-4节', zcd:'3-4周,6-8周', xm:'教师甲', cdmc:'朝阳校区 A414'};
const legacyArrays = `
Array.prototype.filter = function(predicate) {
  const result = [];
  for (let i=0; i<this.length; i++) if (predicate(i,this[i],this)) result.push(this[i]);
  return result;
};
Array.prototype.some = function(predicate) {
  for (let i=0; i<this.length; i++) if (predicate(i,this[i],this)) return true;
  return false;
};
Array.prototype.every = function() { return true; };
`;

async function extract(data = {kbList:[row]}, options = {}) {
  const yearInput = {value:'2024', selectedOptions:[{textContent:'2024-2025学年'}]};
  const termInput = {value:'12', selectedOptions:[{textContent:'第2学期'}]};
  if (options.emptyTerm) termInput.value = '';
  const root = {
    querySelector(selector) {
      if (selector.includes('xnm')) return options.noInputs ? null : yearInput;
      if (selector.includes('xqm')) return options.noInputs ? null : termInput;
      if (selector === '#kbgrid_table_0' && options.renderedGrid) return {};
      return null;
    },
    querySelectorAll() {return [];}
  };
  const requests = [];
  const window = {
    location: new URL('https://jwx.hebiace.edu.cn/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=N2151&layout=default'),
    __vanReadDom: doc => {
      assert.equal(doc, root);
      return options.fallback || {error:'No DOM fixture'};
    },
    async fetch(url, request) {
      requests.push({url, request});
      if (options.changedTerm) termInput.value='3';
      if (options.transportError) throw Error('Unavailable');
      return options.response || {ok:true, status:200, redirected:false, text:async()=>JSON.stringify(data)};
    }
  };
  root.defaultView=window;
  vm.runInNewContext((options.legacyArrays ? legacyArrays : '') + source, {window, document:root, URL, URLSearchParams, AbortController, setTimeout, clearTimeout});
  for (let i=0; i<50 && window.__vanImportResult === null; i++) await new Promise(resolve=>setImmediate(resolve));
  assert.notEqual(window.__vanImportResult, null, 'Extraction did not complete');
  return {result:JSON.parse(JSON.stringify(window.__vanImportResult)), requests};
}

test('uses the selected past term and same-origin query, preserving metadata', async () => {
  const {result, requests}=await extract();
  assert.equal(requests.length,1);
  assert.equal(requests[0].url,'https://jwx.hebiace.edu.cn/kbcx/xskbcx_cxXsKb.html?gnmkdm=N2151');
  assert.equal(requests[0].request.body,'xnm=2024&xqm=12');
  assert.equal(requests[0].request.method,'POST');
  assert.equal(requests[0].request.credentials,'same-origin');
  assert.equal(requests[0].request.headers['X-Requested-With'],'XMLHttpRequest');
  assert.equal(result.term,'2024-2025学年 · 第2学期');
  assert.deepEqual(result.courses,[{name:'课程甲',day:3,startSection:3,endSection:4,weeks:[3,4,6,7,8],teacher:'教师甲',position:'朝阳校区 A414'}]);
});

test('reads repeated week suffixes, both parity spellings, and sparse weeks', async () => {
  for (const [zcd, expected] of [
    ['2-8(双)周',[2,4,6,8]], ['1-8周(单)',[1,3,5,7]],
    ['1-3、7、11-15周(单)',[1,3,7,11,13,15]],
    ['1-3(周),7(周)',[1,2,3,7]], ['3周,6周',[3,6]],
    ['1-3周(单),2-6周(双)',[1,2,3,4,6]],
  ]) {
    const {result}=await extract({kbList:[{...row,zcd}]});
    assert.deepEqual(result.courses?.[0]?.weeks,expected,zcd);
  }
});

test('handles single sections and missing metadata without inventing values', async () => {
  const {result}=await extract({kbList:[{...row,jc:'1',xm:'',jsxm:'教师乙',cdmc:''}]});
  assert.equal(result.courses[0].startSection,1);
  assert.equal(result.courses[0].endSection,1);
  assert.equal(result.courses[0].teacher,'教师乙');
  assert.equal(result.courses[0].position,'');
});

test('rejects malformed rows atomically without hiding errors through DOM fallback', async () => {
  for (const bad of [{zcd:'1-16周,未知周'},{zcd:'0-16周'},{zcd:'8-2周'},{zcd:'1-521周'},
    {zcd:'2周(单)'},{jc:'3,5节'},{jc:'4-3节'},{jc:'0'},{jc:'181'},{xqj:'8'},{kcmc:''}]) {
    const {result}=await extract({kbList:[row,{...row,...bad}]}, {fallback:{courses:[row]}});
    assert.match(result.error,/未保存课程/);
    assert.equal(result.courses,undefined);
  }
});

test('expired login rejects stale visible data', async () => {
  for (const response of [
    {ok:false,status:401,redirected:false}, {ok:false,status:403,redirected:false},
    {ok:true,status:200,redirected:true,url:'https://jwx.hebiace.edu.cn/xtgl/login_slogin.html'},
    {ok:true,status:200,redirected:false,text:async()=>'<input id="yhm"><input id="mm">'},
  ]) {
    const {result}=await extract(undefined,{response,fallback:{courses:[row]}});
    assert.match(result.error,/登录已失效/);
    assert.equal(result.courses,undefined);
  }
});

test('empty or changing term is rejected without guessing', async () => {
  const empty=await extract(undefined,{emptyTerm:true});
  assert.match(empty.result.error,/选择有效/);
  assert.equal(empty.requests.length,0);
  const changed=await extract(undefined,{changedTerm:true});
  assert.match(changed.result.error,/学期已改变/);
  assert.equal(changed.result.courses,undefined);
});

test('empty semesters and unscheduled practice cannot be silently dropped', async () => {
  const empty=await extract({kbList:[]},{fallback:{courses:[row]}});
  assert.match(empty.result.error,/没有返回课程/);
  const practice=await extract({kbList:[row],sjkList:[{qtkcgs:'第17周课程设计'}]});
  assert.match(practice.result.error,/实践或时间未定课程/);
  assert.equal(practice.result.courses,undefined);
});

test('transport failures use the chosen document through the shared DOM fallback', async () => {
  const fallback={courses:[{name:'网页课程'}]};
  const {result}=await extract(undefined,{transportError:true,fallback});
  assert.equal(result.courses[0].name,'网页课程');
  assert.equal(result.term,'2024-2025学年 · 第2学期');
});

test('legacy pages without term inputs never request an inferred semester', async () => {
  const {requests,result}=await extract(undefined,{noInputs:true,fallback:{courses:[{name:'旧网页课程'}]}});
  assert.equal(requests.length,0);
  assert.equal(result.courses[0].name,'旧网页课程');
});

test('school overrides of array predicates preserve the term and reject practice courses', async () => {
  const {result}=await extract(undefined,{legacyArrays:true});
  assert.equal(result.term,'2024-2025学年 · 第2学期');
  assert.equal(result.courses[0].teacher,'教师甲');
  const practice=await extract({kbList:[row],sjkList:[{qtkcgs:'第17周课程设计'}]},{legacyArrays:true});
  assert.match(practice.result.error,/实践或时间未定课程/);
  assert.equal(practice.result.courses,undefined);
});

test('imports the queried grid and footer without fetching a different semester', async () => {
  const fallback={courses:[{name:'表格课程'}],term:'2024-2025学年 · 第2学期',extraCoursesText:'实践课程：认识实习教师甲(共2周)/1-2周;'};
  const {result,requests}=await extract(undefined,{renderedGrid:true,legacyArrays:true,fallback});
  assert.equal(requests.length,0);
  assert.equal(result.courses[0].name,'表格课程');
  assert.equal(result.extraCoursesText,fallback.extraCoursesText);
  const mismatch=await extract(undefined,{renderedGrid:true,fallback:{...fallback,term:'2025-2026学年 · 第2学期'}});
  assert.match(mismatch.result.error,/当前表格不一致/);
  assert.equal(mismatch.result.courses,undefined);
});
