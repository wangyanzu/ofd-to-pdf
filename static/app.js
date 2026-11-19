const drop = document.getElementById('drop');
const picker = document.getElementById('file');
const note = document.getElementById('footnote');
const icon = '<svg class="icon" viewBox="0 0 48 60" fill="none" aria-hidden="true"><path d="M8 2h21l12 12v40a4 4 0 0 1-4 4H8a4 4 0 0 1-4-4V6a4 4 0 0 1 4-4Z" stroke="currentColor" stroke-width="1.4"/><path d="M29 2v12h12M16 34h14m-7-7v14" stroke="currentColor" stroke-width="1.4" stroke-linecap="round"/></svg>';
let state = 'idle', downloadURL = '', downloadName = '';
function reset() {
  if (downloadURL) URL.revokeObjectURL(downloadURL);
  downloadURL = ''; picker.value = ''; state = 'idle';
  drop.setAttribute('aria-busy', 'false');
  drop.innerHTML = icon + '<p class="drop-title">将 OFD 文件拖到这里</p><p class="secondary">或 <span class="choose">点击选择文件</span></p><span class="formats">OFD 格式 · 单文件最大 50 MB</span>';
  note.textContent = '转换完成后自动下载，无需额外操作。';
}
function download() {
  const link = document.createElement('a');
  link.href = downloadURL; link.download = downloadName;
  document.body.append(link); link.click(); link.remove();
}
function fail(message) {
  reset(); note.textContent = message; note.style.color = 'var(--color-error)';
}
async function select(files) {
  if (state === 'busy' || !files.length) return;
  if (files.length !== 1) { fail('请每次选择一个 OFD 文件'); return; }
  const file = files[0];
  if (!/\.ofd$/i.test(file.name)) { fail('请选择 OFD 格式的文件'); return; }
  if (!file.size || file.size > 50 * 1024 * 1024) { fail('请选择不超过 50 MB 的非空 OFD 文件'); return; }
  reset(); state = 'busy'; note.style.color = '';
  drop.setAttribute('aria-busy', 'true');
  drop.innerHTML = icon + '<div class="file-label"></div><p class="drop-title">正在上传文件</p><p class="secondary">请稍候，完成后将自动下载</p><div class="progress"></div>';
  drop.querySelector('.file-label').textContent = file.name;
  note.textContent = '请保持页面打开。';
  const xhr = new XMLHttpRequest();
  xhr.open('POST', '/api/convert');
  xhr.responseType = 'blob'; xhr.timeout = 360000;
  xhr.setRequestHeader('Content-Type', 'application/octet-stream');
  xhr.setRequestHeader('X-Filename', encodeURIComponent(file.name));
  xhr.upload.onload = () => { if (state === 'busy') drop.querySelector('.drop-title').textContent = '正在转换为 PDF'; };
  xhr.onerror = () => fail('网络连接失败，请重新选择文件重试');
  xhr.ontimeout = () => fail('转换等待超时，请重新选择文件重试');
  xhr.onload = async () => {
    if (xhr.status !== 200) {
      let message = '转换失败，请稍后重试';
      try { message = JSON.parse(await xhr.response.text()).error || message; } catch {}
      fail(message); return;
    }
    if (xhr.getResponseHeader('Content-Type') !== 'application/pdf') { fail('未收到有效 PDF，请重试'); return; }
    downloadURL = URL.createObjectURL(xhr.response);
    downloadName = file.name.replace(/\.ofd$/i, '.pdf'); state = 'done';
    drop.setAttribute('aria-busy', 'false');
    drop.innerHTML = '<svg class="icon success" viewBox="0 0 48 60" fill="none" aria-hidden="true"><circle cx="24" cy="30" r="21" stroke="currentColor" stroke-width="1.5"/><path d="m14 30 7 7 14-14" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"/></svg><p class="drop-title">转换完成，已开始下载</p><p class="secondary">没有看到文件？可以再次下载。</p><button class="download">下载 PDF</button><button class="reset">转换另一个文件</button>';
    drop.querySelector('.download').onclick = e => { e.stopPropagation(); download(); };
    drop.querySelector('.reset').onclick = e => { e.stopPropagation(); reset(); };
    const notices = ['请核对转换后的文字与印章；原 OFD 数字签名不会迁移。'];
    if (xhr.getResponseHeader('X-PDF-Mode') === 'image') notices.push('本文件已转为图片式 PDF，文字不可直接复制。');
    if (xhr.getResponseHeader('X-Font-Substituted') === 'true') notices.push('使用了替代字体，字形可能存在差异。');
    note.textContent = notices.join('');
    download();
  };
  xhr.send(file);
}
drop.onclick = () => { if (state !== 'busy') picker.click(); };
drop.onkeydown = e => { if (e.target === drop && ['Enter', ' '].includes(e.key)) { e.preventDefault(); if (state !== 'busy') picker.click(); } };
picker.onchange = () => select(Array.from(picker.files));
['dragenter', 'dragover'].forEach(event => drop.addEventListener(event, e => { e.preventDefault(); if (state !== 'busy') drop.classList.add('drag'); }));
drop.ondragleave = () => drop.classList.remove('drag');
drop.ondrop = e => { e.preventDefault(); drop.classList.remove('drag'); select(Array.from(e.dataTransfer.files)); };
document.addEventListener('dragover', e => e.preventDefault());
document.addEventListener('drop', e => e.preventDefault());
reset();
