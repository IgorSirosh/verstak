#!/data/data/com.termux/files/usr/bin/bash
# Верстак — установка локальной нейросети в Termux.
# Ставит llama.cpp, скачивает модель, создаёт мост для инструментов и запускает всё.
V="$HOME/verstak"
WS="$HOME/verstak-workspace"
mkdir -p "$V/models" "$WS"
say(){ printf '\n\033[1;33m▶ %s\033[0m\n' "$1"; }

say "Обновляю пакеты Termux"
apt-get update -y
apt-get -y -o Dpkg::Options::="--force-confnew" upgrade || true

say "Ставлю python, curl, procps"
apt-get install -y python curl procps || { echo "Не удалось поставить пакеты"; exit 1; }

say "Ставлю llama.cpp"
apt-get install -y llama-cpp || true
if ! command -v llama-server >/dev/null 2>&1; then
  say "Готового пакета нет — собираю из исходников (10–30 минут, телефон будет греться)"
  apt-get install -y git cmake clang make || exit 1
  rm -rf "$V/llama.cpp"
  git clone --depth 1 https://github.com/ggml-org/llama.cpp "$V/llama.cpp" || exit 1
  cmake -S "$V/llama.cpp" -B "$V/llama.cpp/build" -DLLAMA_CURL=OFF || exit 1
  cmake --build "$V/llama.cpp/build" -j"$(nproc)" --target llama-server || exit 1
  ln -sf "$V/llama.cpp/build/bin/llama-server" "$PREFIX/bin/llama-server"
fi
command -v llama-server >/dev/null 2>&1 || { echo "llama-server так и не появился"; exit 1; }

say "Выбор модели"
echo "  1) Qwen3 1.7B — лёгкая, ~1.1 ГБ. Телефоны с 4–6 ГБ ОЗУ"
echo "  2) Qwen3 4B Instruct 2507 — умнее, лучше с инструментами, ~2.5 ГБ. От 8 ГБ ОЗУ"
echo "  3) Своя ссылка на .gguf (например, Gemma или Phi с Hugging Face)"
read -r -p "Номер [2]: " N
case "$N" in
  1) URL="https://huggingface.co/unsloth/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf"; CTX=8192 ;;
  3) read -r -p "Ссылка на .gguf: " URL; CTX=8192 ;;
  *) URL="https://huggingface.co/unsloth/Qwen3-4B-Instruct-2507-GGUF/resolve/main/Qwen3-4B-Instruct-2507-Q4_K_M.gguf"; CTX=16384 ;;
esac
FILE="$V/models/$(basename "${URL%%\?*}")"
if [ -s "$FILE" ] && [ "$(stat -c %s "$FILE")" -gt 300000000 ]; then
  echo "Модель уже скачана: $FILE"
else
  say "Скачиваю модель (можно свернуть Termux, но не закрывать)"
  curl -L --fail --retry 5 -C - -o "$FILE" "$URL" || { echo "Ошибка загрузки. Запусти установку ещё раз — докачается."; exit 1; }
fi

TOKEN="${VERSTAK_TOKEN:-$(head -c 16 /dev/urandom | od -An -tx1 | tr -d ' \n')}"
cat > "$V/config" <<EOF
MODEL="$FILE"
CTX=$CTX
THREADS=4
TOKEN="$TOKEN"
EOF

say "Создаю мост для инструментов (команды и файлы)"
cat > "$V/bridge.py" <<'PYEOF'
# Мост Верстака: даёт приложению выполнять команды и работать с файлами
# в папке ~/verstak-workspace. Слушает только 127.0.0.1 и требует токен.
import json, os, subprocess
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler

WS = os.path.realpath(os.path.expanduser('~/verstak-workspace'))
os.makedirs(WS, exist_ok=True)
TOKEN = os.environ.get('VERSTAK_TOKEN', '')
LIMIT = 12000

def safe(p):
    full = os.path.realpath(os.path.join(WS, p or '.'))
    if full != WS and not full.startswith(WS + os.sep):
        raise ValueError('путь вне рабочей папки ~/verstak-workspace')
    return full

def cut(s):
    return s if len(s) <= LIMIT else s[:LIMIT] + '\n…[обрезано, всего %d символов]' % len(s)

def do_exec(a):
    t = min(int(a.get('timeout') or 60), 600)
    try:
        r = subprocess.run(['bash', '-lc', a['command']], cwd=WS, capture_output=True, text=True, timeout=t)
        return {'exit_code': r.returncode, 'stdout': cut(r.stdout), 'stderr': cut(r.stderr)}
    except subprocess.TimeoutExpired as e:
        return {'exit_code': -1, 'stdout': cut(e.stdout or '') if isinstance(e.stdout, str) else '', 'stderr': 'таймаут %d с' % t}

def do_list(a):
    d = safe(a.get('path'))
    out = []
    for n in sorted(os.listdir(d)):
        f = os.path.join(d, n)
        out.append({'name': n, 'dir': os.path.isdir(f), 'size': os.path.getsize(f) if os.path.isfile(f) else None})
    return {'path': os.path.relpath(d, WS), 'entries': out[:300]}

def do_read(a):
    with open(safe(a['path']), encoding='utf-8', errors='replace') as f:
        return {'content': cut(f.read())}

def do_write(a):
    p = safe(a['path'])
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, 'w', encoding='utf-8') as f:
        f.write(a.get('content', ''))
    return {'ok': True, 'path': os.path.relpath(p, WS), 'bytes': os.path.getsize(p)}

ROUTES = {'/exec': do_exec, '/list': do_list, '/read': do_read, '/write': do_write}

class H(BaseHTTPRequestHandler):
    def _send(self, code, obj):
        b = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Access-Control-Allow-Headers', 'Content-Type, X-Verstak-Token')
        self.send_header('Content-Length', str(len(b)))
        self.end_headers()
        self.wfile.write(b)
    def _auth(self):
        return bool(TOKEN) and self.headers.get('X-Verstak-Token') == TOKEN
    def do_OPTIONS(self):
        self._send(204, {})
    def do_GET(self):
        if self.path == '/health':
            return self._send(200, {'ok': True, 'auth': self._auth(), 'workspace': WS})
        self._send(404, {'error': 'нет такого адреса'})
    def do_POST(self):
        if not self._auth():
            return self._send(401, {'error': 'неверный токен моста'})
        fn = ROUTES.get(self.path)
        if not fn:
            return self._send(404, {'error': 'нет такого адреса'})
        try:
            n = int(self.headers.get('Content-Length') or 0)
            a = json.loads(self.rfile.read(n) or b'{}')
            self._send(200, fn(a))
        except Exception as e:
            self._send(200, {'error': str(e)})
    def log_message(self, *a):
        pass

if __name__ == '__main__':
    ThreadingHTTPServer(('127.0.0.1', 8765), H).serve_forever()
PYEOF

cat > "$V/start.sh" <<'SHEOF'
#!/data/data/com.termux/files/usr/bin/bash
V="$HOME/verstak"; . "$V/config"
pkill -f "llama-server.*--port 8080" 2>/dev/null
pkill -f "verstak/bridge.py" 2>/dev/null
command -v termux-wake-lock >/dev/null 2>&1 && termux-wake-lock
nohup llama-server -m "$MODEL" --jinja -c "$CTX" -t "$THREADS" --host 127.0.0.1 --port 8080 > "$V/llama.log" 2>&1 &
VERSTAK_TOKEN="$TOKEN" nohup python "$V/bridge.py" > "$V/bridge.log" 2>&1 &
echo "Запускаю модель… (лог: $V/llama.log)"
for i in $(seq 1 90); do
  if curl -s 127.0.0.1:8080/health | grep -q '"ok"'; then echo "✅ Модель готова. Возвращайся в Верстак."; exit 0; fi
  sleep 2
done
echo "⚠ Модель не ответила за 3 минуты. Последние строки лога:"; tail -n 15 "$V/llama.log"
SHEOF

cat > "$V/stop.sh" <<'SHEOF'
#!/data/data/com.termux/files/usr/bin/bash
pkill -f "llama-server.*--port 8080"; pkill -f "verstak/bridge.py"
command -v termux-wake-unlock >/dev/null 2>&1 && termux-wake-unlock
echo "Остановлено."
SHEOF
chmod +x "$V/start.sh" "$V/stop.sh"

say "Разрешаю Верстаку запускать модель кнопкой"
mkdir -p "$HOME/.termux"
if grep -q '^allow-external-apps' "$HOME/.termux/termux.properties" 2>/dev/null; then
  sed -i 's/^allow-external-apps.*/allow-external-apps = true/' "$HOME/.termux/termux.properties"
else
  echo 'allow-external-apps = true' >> "$HOME/.termux/termux.properties"
fi
termux-reload-settings 2>/dev/null || true

say "Запускаю"
bash "$V/start.sh"
echo
echo "Дальше: запуск — bash ~/verstak/start.sh, остановка — bash ~/verstak/stop.sh"
