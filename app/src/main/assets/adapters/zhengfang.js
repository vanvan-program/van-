// SPDX-License-Identifier: GPL-3.0-only
// Original implementation of the Zhengfang student timetable protocol.
// Requires dom.js; requests use the current page's session and selected term only.
window.__vanImportResult = null;
(async () => {
  const clean = value => String(value ?? '').replace(/\u00a0/g, ' ').replace(/\s+/g, ' ').trim();
  const compact = value => clean(value).replace(/\s/g, '').replace(/（/g, '(').replace(/）/g, ')');
  const fail = message => { throw Error(message); };
  // Zhengfang pages can replace filter/some with callbacks that receive an index first.
  const select = (items, predicate) => {
    const result = [];
    for (const item of items) if (predicate(item)) result.push(item);
    return result;
  };
  const any = (items, predicate) => {
    for (const item of items) if (predicate(item)) return true;
    return false;
  };
  try {
    const documents = [document];
    for (let i = 0; i < documents.length && i < 20; i++) {
      for (const frame of documents[i].querySelectorAll('iframe')) {
        try {
          if (frame.getClientRects().length && frame.contentDocument && !documents.includes(frame.contentDocument))
            documents.push(frame.contentDocument);
        } catch (_) { /* Cross-origin login frames cannot expose a timetable. */ }
      }
    }
    const candidates = select(documents, doc => doc.querySelector('#xnm, [name="xnm"]') && doc.querySelector('#xqm, [name="xqm"]'));
    if (candidates.length > 1) fail('同时打开了多个学期查询页，请只保留要导入的学生课表页');
    const root = candidates[0] || documents.find(doc => doc.querySelector('.timetable_con, .kbcontent')) || document;
    const page = root.defaultView;
    const location = /^https?:$/.test(page.location.protocol) ? page.location : window.location;
    if (/\/xtgl\/login_/i.test(location.pathname) || root.querySelector('#yhm') && root.querySelector('#mm'))
      fail('请先登录教务系统，再进入学生课表查询并选择学期');
    const yearInput = root.querySelector('#xnm, [name="xnm"]');
    const termInput = root.querySelector('#xqm, [name="xqm"]');
    const year = clean(yearInput?.value), term = clean(termInput?.value);
    const termLabel = select([yearInput, termInput].map(input => clean(input?.selectedOptions?.[0]?.textContent || input?.value)), Boolean).join(' · ');
    const unchanged = () => {
      if (yearInput && (clean(yearInput.value) !== year || clean(termInput?.value) !== term))
        fail('获取期间学期已改变，请查询目标学期后重新获取');
    };
    const domFallback = () => {
      unchanged();
      const result = window.__vanReadDom(root);
      if (result.error) fail(result.error);
      if (root.querySelector('#kbgrid_table_0') && yearInput && termInput) {
        const displayed = compact(result.term).match(/^(\d{4})-\d{4}学年·第(\d+)学期$/);
        const selected = compact(termInput.selectedOptions?.[0]?.textContent).match(/^(?:第)?(\d+)(?:学期)?$/);
        if (!displayed || !selected || displayed[1] !== year || displayed[2] !== selected[1])
          fail('所选学期与当前表格不一致，请点击查询后重新获取');
      }
      result.term ||= termLabel;
      window.__vanImportResult = result;
    };
    if (!yearInput || !termInput) { domFallback(); return; }
    if (!/^\d{4}$/.test(year) || !/^\d{1,2}$/.test(term)) fail('请在学生课表查询页选择有效的学年、学期并点击查询');
    // Import the queried grid, including its footer, without reading the duplicate list.
    if (root.querySelector('#kbgrid_table_0')) { domFallback(); return; }
    const path = location.pathname;
    const marker = path.indexOf('/kbcx/');
    if (marker < 0) { domFallback(); return; }
    const endpoint = new URL(path.slice(0, marker) + '/kbcx/xskbcx_cxXsKb.html', location.href);
    endpoint.searchParams.set('gnmkdm', new URL(location.href).searchParams.get('gnmkdm') || 'N2151');
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 12000);
    let data;
    try {
      const response = await page.fetch(endpoint.href, {
        method: 'POST', credentials: 'same-origin', signal: controller.signal,
        headers: {'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8', 'X-Requested-With': 'XMLHttpRequest'},
        body: new URLSearchParams({xnm: year, xqm: term}).toString()
      });
      if (response.redirected && /\/xtgl\/login_/i.test(new URL(response.url).pathname))
        fail('登录已失效，请重新登录并查询课表');
      if (response.status === 401 || response.status === 403) fail('登录已失效或无权查询，请重新登录并查询课表');
      if (!response.ok) throw Error('课表接口请求失败');
      const text = await response.text();
      if (/login_slogin|id\s*=\s*["'](?:yhm|mm)["']/i.test(text)) fail('登录已失效，请重新登录并查询课表');
      data = JSON.parse(text);
    } catch (e) {
      if (/登录已失效/.test(e.message)) throw e;
      domFallback(); return;
    } finally { clearTimeout(timeout); }
    unchanged();
    if (!Array.isArray(data?.kbList)) { domFallback(); return; }
    if (Array.isArray(data.sjkList) && any(data.sjkList, row => any(Object.values(row || {}), value => clean(value) && clean(value) !== '无')))
      fail('课表包含实践或时间未定课程，无法完整转换；请导出PDF或Excel后在识别核对页补充，尚未保存课程');
    if (!data.kbList.length) fail('所选学期没有返回课程，请确认学年、学期并查询');
    const weeksFor = value => {
      const text = compact(value);
      if (!text) fail('缺少周次');
      const tokens = text.split(/[,，、]/), weeks = new Set();
      const commonKind = text.match(/\(([单双])(?:周)?\)$/)?.[1];
      for (const token of tokens) {
        const match = token.match(/^(\d+)(?:[-~—–至](\d+))?(?:\(([单双])(?:周)?\))?(?:\(?周\)?)?(?:\(([单双])(?:周)?\))?$/);
        if (!match) fail('无法识别周次');
        const a = +match[1], b = +(match[2] || match[1]), kind = match[3] || match[4] || commonKind;
        if (a < 1 || b < a || b > 520) fail('周次范围无效');
        for (let n = a; n <= b; n++) if (!kind || kind === '单' && n % 2 === 1 || kind === '双' && n % 2 === 0) weeks.add(n);
      }
      if (!weeks.size) fail('周次为空');
      return [...weeks].sort((a, b) => a - b);
    };
    const sectionsFor = value => {
      const text = compact(value).replace(/节$/, '');
      const match = text.match(/^(\d{1,3})(?:[-~—–至](\d{1,3}))?$/);
      if (!match) fail('缺少或无法识别节次');
      const startSection = +match[1], endSection = +(match[2] || match[1]);
      if (startSection < 1 || endSection < startSection || endSection > 180) fail('节次范围无效');
      return {startSection, endSection};
    };
    const failures = [], courses = [];
    for (const row of data.kbList) {
      const name = clean(row?.kcmc);
      try {
        if (!name) fail('缺少课程名称');
        const dayText = compact(row.xqj);
        if (!/^[1-7]$/.test(dayText)) fail('缺少或无效的星期');
        courses.push({name, day: +dayText, ...sectionsFor(row.jc), weeks: weeksFor(row.zcd), teacher: clean(row.xm || row.jsxm), position: clean(row.cdmc)});
      } catch (e) { failures.push((name || '未命名课程').slice(0, 24) + '：' + e.message); }
    }
    if (failures.length) fail('未保存课程：共' + failures.length + '项无法识别。' + failures.slice(0, 2).join('；'));
    window.__vanImportResult = {courses, term: termLabel};
  } catch (e) { window.__vanImportResult = {error: e.message || String(e)}; }
})();
