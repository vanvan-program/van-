// SPDX-License-Identifier: GPL-3.0-only
// Original adapter. Read DOM text rather than visual wrapping in narrow timetable columns.
window.__vanReadDom = (root = document) => {
  try {
    const courses = [], failures = [];
    // Keep standard predicate semantics even when the page changes Array.prototype.
    const select = (items, predicate) => {
      const result = [];
      for (const item of items) if (predicate(item)) result.push(item);
      return result;
    };
    const any = (items, predicate) => {
      for (const item of items) if (predicate(item)) return true;
      return false;
    };
    const clean = value => String(value || '').replace(/\u00a0/g, ' ').replace(/\s+/g, ' ').trim()
      .replace(/([\u3400-\u9fff])\s+(?=[\u3400-\u9fff])/g, '$1');
    const compact = value => String(value || '').replace(/\s|\u00a0/g, '').replace(/（/g, '(').replace(/）/g, ')');
    const selector = '.timetable_con, .kbcontent';
    // The same Zhengfang document renders a grid and a hidden, differently shaped list.
    const scope = root.querySelector('#kbgrid_table_0') || root;
    const title = element => ['data-original-title', 'data-bs-original-title', 'title', 'aria-label']
      .map(key => element.getAttribute(key)).find(value => clean(value)) || '';
    const labelKeys = ['任课教师', '授课教师', '教师', '老师', '上课地点', '教室', '地点', '场地'];
    const stripLabel = value => clean(value).replace(new RegExp('^(?:' + labelKeys.join('|') + ')\\s*[:：]?\\s*'), '');
    // Preserve semantic rows / BRs; CSS line wrapping must never split numbers or names.
    const lines = element => {
      const read = node => {
        if (node.nodeType === 3) return node.textContent || '';
        if (node.nodeType !== 1 || /^(SCRIPT|STYLE|NOSCRIPT)$/.test(node.tagName)) return '';
        if (node.tagName === 'BR') return '\n';
        const value = [...node.childNodes].map(read).join('');
        return /^(P|DIV|LI|TR)$/.test(node.tagName) ? '\n' + value + '\n' : value;
      };
      return select(read(element).split('\n').map(clean), Boolean);
    };
    const metadata = (block, keys) => {
      const elements = [...block.querySelectorAll('[title], [data-original-title], [data-bs-original-title], [aria-label], .glyphicon-user, .glyphicon-map-marker')];
      for (const element of elements) {
        const hint = clean(title(element));
        const isIcon = keys.includes('教师') ? element.classList.contains('glyphicon-user') : element.classList.contains('glyphicon-map-marker');
        if (!isIcon && !any(keys, key => hint === key || hint.startsWith(key + '：') || hint.startsWith(key + ':'))) continue;
        const direct = stripLabel(element.textContent);
        if (direct) return direct;
        const row = element.closest('p, li, .timetable_info, .kbinfo');
        if (row && row !== block && block.contains(row)) {
          const value = stripLabel(row.textContent);
          if (value) return value;
        }
        let adjacent = '';
        for (let node = element.nextSibling; node; node = node.nextSibling) {
          if (node.nodeType === 1 && (node.tagName === 'BR' || labelKeys.includes(clean(title(node))) || node.matches('.glyphicon-user, .glyphicon-map-marker'))) break;
          adjacent += node.textContent || '';
        }
        if (stripLabel(adjacent)) return stripLabel(adjacent);
        const inline = stripLabel(hint);
        if (inline) return inline;
      }
      for (const line of lines(block)) {
        const match = line.match(/^([^:：]+)[:：]\s*(.+)$/);
        if (match && keys.includes(clean(match[1]))) return clean(match[2]);
      }
      return '';
    };
    const range = '\\d+(?:[-~—–至]\\d+)?';
    const parity = '(?:\\([单双](?:周)?\\))?';
    const weekToken = range + parity + '(?:\\(?周\\)?)?' + parity;
    const timing = block => {
      const text = compact(block.textContent);
      const paired = text.match(/\[(\d{2})(\d{2})节\]/);
      const section = paired || text.match(/(\d{1,3})(?:[-~—–至](\d{1,3}))?节/);
      if (!section) throw Error('缺少节次');
      const start = +section[1], end = +(section[2] || section[1]);
      if (start < 1 || end < start || end > 180) throw Error('节次范围无效');
      const after = text.slice(section.index + section[0].length).replace(/^[)\]：:]+/, '');
      const match = after.match(new RegExp('^\\(?(' + weekToken + '(?:[、,，]' + weekToken + ')*)'));
      if (!match || !match[1].includes('周') || /^[、,，]/.test(after.slice(match[0].length))) throw Error('缺少或无法识别周次');
      const parts = match[1].split(/[、,，]/);
      const globalParity = parts[parts.length - 1].match(/\(([单双])(?:周)?\)$/)?.[1];
      const weeks = new Set();
      for (const part of parts) {
        const values = part.match(/^(\d+)(?:[-~—–至](\d+))?/);
        const a = +values[1], b = +(values[2] || values[1]);
        if (a < 1 || b < a || b > 520) throw Error('周次范围无效');
        const kind = part.match(/\(([单双])(?:周)?\)/)?.[1] || globalParity;
        for (let i = a; i <= b; i++) if (!kind || kind === '单' && i % 2 === 1 || kind === '双' && i % 2 === 0) weeks.add(i);
      }
      if (!weeks.size) throw Error('周次为空');
      return {startSection: start, endSection: end, weeks: [...weeks].sort((a, b) => a - b)};
    };
    const weekday = value => {
      const word = compact(value);
      const named = word.match(/^(?:(?:星期|周|礼拜))?([一二三四五六日天])$/);
      return named ? '一二三四五六日'.indexOf(named[1] === '天' ? '日' : named[1]) + 1 : 0;
    };
    const tables = new Map();
    const tableMap = table => {
      if (tables.has(table)) return tables.get(table);
      const occupied = [], cells = new Map(), headers = [];
      const rows = select(table.rows, row => row.closest('table') === table);
      rows.forEach((row, r) => {
        occupied[r] ||= [];
        const labels = new Map();
        let c = 0;
        for (const cell of [...row.cells]) {
          while (occupied[r][c]) c++;
          const width = Math.max(1, cell.colSpan), height = cell.rowSpan || rows.length - r;
          cells.set(cell, {row: r, column: c, width});
          for (let y = r; y < Math.min(rows.length, r + height); y++) {
            occupied[y] ||= [];
            for (let x = c; x < c + width; x++) occupied[y][x] = true;
          }
          const day = !cell.querySelector(selector) && weekday(cell.textContent);
          if (day) for (let x = c; x < c + width; x++) labels.set(x, day);
          c += width;
        }
        // At least two distinct weekday labels distinguish a header from a period label.
        if (new Set(labels.values()).size >= 2 && !row.querySelector(selector)) headers.push({row: r, labels});
      });
      const mapping = {cells, headers};
      tables.set(table, mapping);
      return mapping;
    };
    const dayFor = (block, cell) => {
      const explicit = block.dataset.day || cell?.dataset.day;
      if (explicit) {
        if (!/^[1-7]$/.test(explicit)) throw Error('星期无效');
        return +explicit;
      }
      const ids = cell?.id.match(/^(?:xq|day)?([1-7])[-_]([0-9]+)$/);
      if (ids) return +ids[1];
      const table = cell?.closest('table');
      if (table) {
        const mapping = tableMap(table), location = mapping.cells.get(cell);
        if (location) {
          for (const header of [...mapping.headers].reverse()) {
            if (header.row >= location.row) continue;
            const days = Array.from({length: location.width}, (_, i) => header.labels.get(location.column + i));
            if (!any(days, day => !day) && new Set(days).size === 1) return days[0];
          }
        }
      }
      throw Error('缺少可确认的星期表头');
    };
    const extraTexts = [];
    for (const footer of scope.querySelectorAll('.timetable_title')) {
      const text = clean(footer.textContent);
      if (/实践课程[:：]|其[它他]课程[:：]/.test(text) && !extraTexts.includes(text)) extraTexts.push(text);
    }
    const extraCoursesText = extraTexts.join('\n');
    const blocks = select(scope.querySelectorAll(selector), block => !block.parentElement?.closest(selector));
    if (!blocks.length && !extraCoursesText) throw Error('未识别课表结构。请进入个人课表页后重试，或导入导出的文件。');
    for (const block of blocks) {
      if (!clean(block.textContent)) continue;
      const name = clean(block.querySelector('.title font,.title,[title="课程名称"]')?.textContent || lines(block)[0]);
      try {
        if (!name) throw Error('缺少课程名称');
        courses.push({name, day: dayFor(block, block.closest('td, th')), ...timing(block), teacher: metadata(block, ['任课教师', '授课教师', '教师', '老师']), position: metadata(block, ['上课地点', '教室', '地点', '场地'])});
      } catch (e) {
        failures.push(clean(name).slice(0, 24) + '：' + e.message);
      }
    }
    if (failures.length) throw Error('未保存课程：共' + failures.length + '项无法识别。' + failures.slice(0, 2).join('；') + (failures.length > 2 ? '；其余请核对页面或导入文件。' : ''));
    if (!courses.length && !extraCoursesText) throw Error('当前学期没有识别到课程');
    const result = {courses};
    if (extraCoursesText) result.extraCoursesText = extraCoursesText;
    for (const heading of scope.querySelectorAll('h6')) {
      const term = compact(heading.textContent).match(/^(\d{4})-(\d{4})学年第(\d+)学期$/);
      if (term) { result.term = term[1] + '-' + term[2] + '学年 · 第' + term[3] + '学期'; break; }
    }
    return result;
  } catch (e) {
    return {error: e.message || String(e)};
  }
};
window.__vanImportResult = window.__vanReadDom();
