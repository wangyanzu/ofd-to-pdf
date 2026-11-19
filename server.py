"""Small, dependency-free OFD conversion service."""
import importlib.util
import json
import os
from pathlib import Path
import shutil
import socket
import uuid
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import quote, unquote, urlsplit

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('ofd', ROOT / 'vendor/ofd/scripts/convert.py')
ofd = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ofd)
LIMIT = 50 * 1024 * 1024
SLOTS = threading.BoundedSemaphore(int(os.environ.get('MAX_CONVERSIONS', '1')))
TIMEOUT = int(os.environ.get('CONVERSION_TIMEOUT', '300'))
RUNTIME = None


class Handler(BaseHTTPRequestHandler):
    # Close each request, so rejected upload bodies cannot be reused as requests.
    protocol_version = 'HTTP/1.0'

    def setup(self):
        super().setup()
        self.connection.settimeout(60)

    def reply(self, status, data, content_type='application/json; charset=utf-8', headers=None):
        if not isinstance(data, bytes):
            data = json.dumps(data, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header('Content-Type', content_type)
        self.send_header('Content-Length', str(len(data)))
        self.send_header('Cache-Control', 'no-store')
        self.send_header('X-Content-Type-Options', 'nosniff')
        for key, value in (headers or {}).items():
            self.send_header(key, value)
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        path = urlsplit(self.path).path
        if path == '/healthz':
            self.reply(200, {'status': 'ok'})
        elif path in ('/', '/index.html', '/app.js'):
            name = 'app.js' if path == '/app.js' else 'index.html'
            kind = 'text/javascript' if name.endswith('.js') else 'text/html'
            self.reply(200, (ROOT / 'static' / name).read_bytes(), kind + '; charset=utf-8')
        else:
            self.reply(404, {'error': '页面不存在'})

    def do_POST(self):
        if urlsplit(self.path).path != '/api/convert':
            self.reply(404, {'error': '接口不存在'})
            return
        if self.headers.get('Transfer-Encoding'):
            self.reply(400, {'error': '不支持此上传方式'})
            return
        try:
            size = int(self.headers.get('Content-Length', '0'))
        except ValueError:
            size = 0
        if not 0 < size <= LIMIT:
            self.reply(413, {'error': '请选择不超过 50 MB 的 OFD 文件'})
            return
        name = unquote(self.headers.get('X-Filename', ''))
        name = Path(name.replace('\\', '/')).name
        if not name.lower().endswith('.ofd') or any(ord(c) < 32 for c in name):
            self.reply(400, {'error': '请选择 OFD 格式的文件'})
            return
        if not SLOTS.acquire(blocking=False):
            self.reply(503, {'error': '正在处理其他文件，请稍后重试'}, headers={'Retry-After': '5'})
            return
        try:
            with tempfile.TemporaryDirectory(prefix='ofd-') as directory:
                source = Path(directory) / 'input.ofd'
                output = Path(directory) / 'output.pdf'
                with source.open('wb') as stream:
                    remaining = size
                    while remaining:
                        chunk = self.rfile.read(min(65536, remaining))
                        if not chunk:
                            raise ValueError('上传未完成，请重新选择文件')
                        stream.write(chunk)
                        remaining -= len(chunk)
                try:
                    result = ofd.convert(source, output, RUNTIME, 'auto', 300, TIMEOUT)
                except (ValueError, OSError) as exc:
                    reference = uuid.uuid4().hex[:12]
                    reason = str(exc)
                    report_path = output.with_suffix('.report.json')
                    if report_path.is_file():
                        try:
                            reason = json.loads(report_path.read_text()).get('error', reason)
                        except (ValueError, OSError):
                            pass
                    # Keep diagnostics in process output, without retaining uploaded files.
                    reason = reason.replace(str(directory), '[temporary]')
                    print(json.dumps({'conversion_error': reference, 'reason': reason},
                                     ensure_ascii=False), flush=True)
                    if 'timed out' in reason.lower():
                        message = '转换超时，请尝试页数更少的文件'
                    elif 'archive' in reason.lower() or 'zip' in reason.lower():
                        message = 'OFD 文件结构校验失败，请确认文件完整且格式正确'
                    else:
                        message = '转换失败，具体原因为 ' + reason[:500]
                    self.reply(422, {'error': message, 'reference': reference})
                    return
                report = result['conversion']
                if report.get('status') != 'success':
                    self.reply(422, {'error': '文件未通过转换校验'})
                    return
                warnings = report.get('warnings', [])
                font_substituted = any('font' in w.lower() and ('substitut' in w.lower() or 'replac' in w.lower()) for w in warnings)
                self.send_response(200)
                self.send_header('Content-Type', 'application/pdf')
                self.send_header('Content-Length', str(output.stat().st_size))
                self.send_header('Content-Disposition', "attachment; filename=converted.pdf; filename*=UTF-8''" + quote(name[:-4] + '.pdf', safe=''))
                self.send_header('Cache-Control', 'no-store')
                self.send_header('X-Content-Type-Options', 'nosniff')
                self.send_header('X-PDF-Mode', report.get('mode_used', 'unknown'))
                self.send_header('X-Font-Substituted', 'true' if font_substituted else 'false')
                self.end_headers()
                with output.open('rb') as stream:
                    shutil.copyfileobj(stream, self.wfile)
        except (socket.timeout, ConnectionError):
            pass
        except ValueError as exc:
            self.reply(400, {'error': str(exc)})
        finally:
            SLOTS.release()

    def log_message(self, fmt, *args):
        # Do not record client IPs, upload names, or document contents.
        pass


if __name__ == '__main__':
    RUNTIME = ofd.java_runtime()
    server = ThreadingHTTPServer(('0.0.0.0', int(os.environ.get('PORT', '8080'))), Handler)
    print('OFD to PDF listening on port ' + str(server.server_port), flush=True)
    server.serve_forever()
