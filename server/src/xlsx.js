// Minimal Excel (.xlsx) writer with no external libraries.
// An .xlsx file is a ZIP archive of XML files; we build both the XML and the ZIP here.
const zlib = require('node:zlib');

// ---- Styles (index = cellXfs position in styles.xml) ----
const S = { default: 0, title: 1, header: 2, text: 3, present: 4, absent: 5, flagged: 6, center: 7, number1: 8, bold: 9 };

const esc = (s) => String(s)
  .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
  // remove characters that are illegal in XML
  .replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F]/g, '');

function colName(i) { // 0 -> A
  let s = '';
  i += 1;
  while (i > 0) { const m = (i - 1) % 26; s = String.fromCharCode(65 + m) + s; i = Math.floor((i - 1) / 26); }
  return s;
}

function cellXml(ref, cell) {
  if (cell === null || cell === undefined) return '';
  let v = cell, s = S.default;
  if (typeof cell === 'object') { v = cell.v; s = cell.s ?? S.default; }
  if (v === null || v === undefined || v === '') return s ? `<c r="${ref}" s="${s}"/>` : '';
  if (typeof v === 'number' && Number.isFinite(v)) return `<c r="${ref}" s="${s}"><v>${v}</v></c>`;
  return `<c r="${ref}" s="${s}" t="inlineStr"><is><t xml:space="preserve">${esc(v)}</t></is></c>`;
}

function sheetXml(sheet) {
  const rows = sheet.rows.map((row, r) => {
    const cells = row.map((cell, c) => cellXml(colName(c) + (r + 1), cell)).join('');
    return `<row r="${r + 1}">${cells}</row>`;
  }).join('');
  let views = '<sheetViews><sheetView workbookViewId="0"/></sheetViews>';
  if (sheet.freeze) {
    const { row, col } = sheet.freeze;
    const tl = colName(col) + (row + 1);
    const pane = row > 0 && col > 0 ? 'bottomRight' : row > 0 ? 'bottomLeft' : 'topRight';
    views = `<sheetViews><sheetView workbookViewId="0"><pane${col ? ` xSplit="${col}"` : ''}${row ? ` ySplit="${row}"` : ''} topLeftCell="${tl}" activePane="${pane}" state="frozen"/></sheetView></sheetViews>`;
  }
  const cols = sheet.cols && sheet.cols.length
    ? '<cols>' + sheet.cols.map((w, i) => `<col min="${i + 1}" max="${i + 1}" width="${w}" customWidth="1"/>`).join('') + '</cols>'
    : '';
  const merges = sheet.merges && sheet.merges.length
    ? `<mergeCells count="${sheet.merges.length}">` + sheet.merges.map((m) => `<mergeCell ref="${m}"/>`).join('') + '</mergeCells>'
    : '';
  return '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
    '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">' +
    `${views}<sheetFormatPr defaultRowHeight="15"/>${cols}<sheetData>${rows}</sheetData>${merges}` +
    '<pageMargins left="0.5" right="0.5" top="0.75" bottom="0.75" header="0.3" footer="0.3"/></worksheet>';
}

const STYLES = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
  '<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">' +
  '<numFmts count="1"><numFmt numFmtId="164" formatCode="0.0"/></numFmts>' +
  '<fonts count="4">' +
  '<font><sz val="11"/><name val="Calibri"/></font>' +
  '<font><b/><sz val="11"/><name val="Calibri"/></font>' +
  '<font><b/><sz val="11"/><color rgb="FFFFFFFF"/><name val="Calibri"/></font>' +
  '<font><b/><sz val="14"/><color rgb="FF0F5E4B"/><name val="Calibri"/></font>' +
  '</fonts>' +
  '<fills count="6">' +
  '<fill><patternFill patternType="none"/></fill>' +
  '<fill><patternFill patternType="gray125"/></fill>' +
  '<fill><patternFill patternType="solid"><fgColor rgb="FF0F7B5F"/><bgColor indexed="64"/></patternFill></fill>' +
  '<fill><patternFill patternType="solid"><fgColor rgb="FFD7F2E3"/><bgColor indexed="64"/></patternFill></fill>' +
  '<fill><patternFill patternType="solid"><fgColor rgb="FFFADBD8"/><bgColor indexed="64"/></patternFill></fill>' +
  '<fill><patternFill patternType="solid"><fgColor rgb="FFFFF1C2"/><bgColor indexed="64"/></patternFill></fill>' +
  '</fills>' +
  '<borders count="2">' +
  '<border><left/><right/><top/><bottom/><diagonal/></border>' +
  '<border><left style="thin"><color rgb="FFBFBFBF"/></left><right style="thin"><color rgb="FFBFBFBF"/></right>' +
  '<top style="thin"><color rgb="FFBFBFBF"/></top><bottom style="thin"><color rgb="FFBFBFBF"/></bottom><diagonal/></border>' +
  '</borders>' +
  '<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>' +
  '<cellXfs count="10">' +
  '<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>' +                                                   // 0 default
  '<xf numFmtId="0" fontId="3" fillId="0" borderId="0" xfId="0" applyFont="1"/>' +                                     // 1 title
  '<xf numFmtId="0" fontId="2" fillId="2" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="center" wrapText="1"/></xf>' + // 2 header
  '<xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyBorder="1"/>' +                                   // 3 text
  '<xf numFmtId="0" fontId="1" fillId="3" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center"/></xf>' + // 4 present
  '<xf numFmtId="0" fontId="1" fillId="4" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center"/></xf>' + // 5 absent
  '<xf numFmtId="0" fontId="1" fillId="5" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center"/></xf>' + // 6 flagged
  '<xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyBorder="1" applyAlignment="1"><alignment horizontal="center"/></xf>' + // 7 center
  '<xf numFmtId="164" fontId="1" fillId="0" borderId="1" xfId="0" applyNumberFormat="1" applyFont="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center"/></xf>' + // 8 number 0.0
  '<xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/>' +                                     // 9 bold
  '</cellXfs>' +
  '<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>' +
  '</styleSheet>';

function workbookFiles(sheets) {
  const files = {};
  files['[Content_Types].xml'] = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
    '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">' +
    '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>' +
    '<Default Extension="xml" ContentType="application/xml"/>' +
    '<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>' +
    '<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>' +
    sheets.map((_, i) => `<Override PartName="/xl/worksheets/sheet${i + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>`).join('') +
    '</Types>';
  files['_rels/.rels'] = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
    '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">' +
    '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>' +
    '</Relationships>';
  files['xl/workbook.xml'] = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
    '<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">' +
    '<sheets>' + sheets.map((s, i) => `<sheet name="${esc(s.name.replace(/[\\/?*[\]:]/g, ' ').slice(0, 31))}" sheetId="${i + 1}" r:id="rId${i + 1}"/>`).join('') + '</sheets></workbook>';
  files['xl/_rels/workbook.xml.rels'] = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
    '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">' +
    sheets.map((_, i) => `<Relationship Id="rId${i + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${i + 1}.xml"/>`).join('') +
    `<Relationship Id="rId${sheets.length + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>` +
    '</Relationships>';
  files['xl/styles.xml'] = STYLES;
  sheets.forEach((s, i) => { files[`xl/worksheets/sheet${i + 1}.xml`] = sheetXml(s); });
  return files;
}

// ---- ZIP writer (deflate) ----
function zip(files) {
  const locals = [];
  const centrals = [];
  let offset = 0;
  const d = new Date();
  const dosTime = (d.getHours() << 11) | (d.getMinutes() << 5) | Math.floor(d.getSeconds() / 2);
  const dosDate = ((d.getFullYear() - 1980) << 9) | ((d.getMonth() + 1) << 5) | d.getDate();
  for (const [name, content] of Object.entries(files)) {
    const data = Buffer.from(content, 'utf8');
    const comp = zlib.deflateRawSync(data);
    const crc = zlib.crc32(data);
    const nameBuf = Buffer.from(name, 'utf8');
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(20, 4);
    local.writeUInt16LE(0x0800, 6); // UTF-8 names
    local.writeUInt16LE(8, 8);      // deflate
    local.writeUInt16LE(dosTime, 10);
    local.writeUInt16LE(dosDate, 12);
    local.writeUInt32LE(crc >>> 0, 14);
    local.writeUInt32LE(comp.length, 18);
    local.writeUInt32LE(data.length, 22);
    local.writeUInt16LE(nameBuf.length, 26);
    local.writeUInt16LE(0, 28);
    locals.push(local, nameBuf, comp);

    const central = Buffer.alloc(46);
    central.writeUInt32LE(0x02014b50, 0);
    central.writeUInt16LE(20, 4);
    central.writeUInt16LE(20, 6);
    central.writeUInt16LE(0x0800, 8);
    central.writeUInt16LE(8, 10);
    central.writeUInt16LE(dosTime, 12);
    central.writeUInt16LE(dosDate, 14);
    central.writeUInt32LE(crc >>> 0, 16);
    central.writeUInt32LE(comp.length, 20);
    central.writeUInt32LE(data.length, 24);
    central.writeUInt16LE(nameBuf.length, 28);
    central.writeUInt32LE(offset, 42);
    centrals.push(central, nameBuf);
    offset += local.length + nameBuf.length + comp.length;
  }
  const centralBuf = Buffer.concat(centrals);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0);
  const count = Object.keys(files).length;
  end.writeUInt16LE(count, 8);
  end.writeUInt16LE(count, 10);
  end.writeUInt32LE(centralBuf.length, 12);
  end.writeUInt32LE(offset, 16);
  return Buffer.concat([...locals, centralBuf, end]);
}

function buildXlsx(sheets) {
  return zip(workbookFiles(sheets));
}

module.exports = { buildXlsx, S, colName };
