/**
 * Веб-чат — одна страница, отдаётся с GET /. Без сборщиков и зависимостей:
 * история хранится в браузере (сервер stateless), токен — в localStorage,
 * общение с сервером — fetch POST /v1/chat тем же публичным API.
 */
object WebUi {
    val PAGE = """
<!doctype html>
<html lang="ru">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>AI Advent Challenge #8 — приватный чат</title>
<style>
  :root { --bg:#11151c; --panel:#1a2029; --line:#2a3342; --text:#dfe6f0; --dim:#8b97a8; --accent:#5eb1ff; }
  * { box-sizing:border-box; margin:0; }
  body { background:var(--bg); color:var(--text); font:15px/1.5 -apple-system,'Segoe UI',Roboto,sans-serif;
         display:flex; flex-direction:column; height:100dvh; }
  header { padding:10px 16px; background:var(--panel); border-bottom:1px solid var(--line);
           display:flex; gap:12px; align-items:center; flex-wrap:wrap; }
  header h1 { font-size:15px; font-weight:600; }
  header .sub { color:var(--dim); font-size:12px; }
  #status { width:9px; height:9px; border-radius:50%; background:#e05555; flex:none; }
  #status.ok { background:#4fc26b; }
  #token { margin-left:auto; background:var(--bg); color:var(--text); border:1px solid var(--line);
           border-radius:6px; padding:6px 10px; width:200px; font-size:13px; }
  #log { flex:1; overflow-y:auto; padding:16px; display:flex; flex-direction:column; gap:10px; }
  .msg { max-width:72ch; padding:10px 14px; border-radius:12px; white-space:pre-wrap; word-break:break-word; }
  .user { background:#24476b; align-self:flex-end; }
  .bot  { background:var(--panel); border:1px solid var(--line); align-self:flex-start; }
  .meta { font-size:11px; color:var(--dim); margin-top:8px; border-top:1px dashed var(--line); padding-top:6px; }
  .err  { background:#4a2328; border:1px solid #7c3a41; align-self:flex-start; }
  form { display:flex; gap:8px; padding:12px 16px; background:var(--panel); border-top:1px solid var(--line); }
  #q { flex:1; background:var(--bg); color:var(--text); border:1px solid var(--line); border-radius:8px;
       padding:10px 12px; font:inherit; resize:none; height:44px; }
  button { background:var(--accent); color:#06233f; border:0; border-radius:8px; padding:0 20px;
           font:inherit; font-weight:600; cursor:pointer; }
  button:disabled { opacity:.5; cursor:wait; }
</style>
</head>
<body>
<header>
  <div id="status" title="доступность сервиса"></div>
  <div>
    <h1>AI Advent Challenge #8 — приватный чат</h1>
    <div class="sub" id="modelinfo">локальная LLM · знает весь чат марафона 31.05–11.07</div>
  </div>
  <input id="token" type="password" placeholder="API-токен" autocomplete="off">
</header>
<div id="log">
  <div class="msg bot">Привет! Я отвечаю по переписке марафона: задания, уточнения тьютора, находки участников.
Вставьте API-токен справа сверху и спрашивайте — например, «что было в задании дня 21?»</div>
</div>
<form id="f">
  <textarea id="q" placeholder="Вопрос по чату марафона…" required></textarea>
  <button id="send" type="submit">→</button>
</form>
<script>
(function () {
  var log = document.getElementById('log');
  var form = document.getElementById('f');
  var q = document.getElementById('q');
  var sendBtn = document.getElementById('send');
  var tokenInput = document.getElementById('token');
  var history = [];

  tokenInput.value = localStorage.getItem('day30_token') || '';
  tokenInput.addEventListener('change', function () {
    localStorage.setItem('day30_token', tokenInput.value.trim());
  });

  function ping() {
    fetch('/healthz').then(function (r) { return r.json(); }).then(function (h) {
      document.getElementById('status').className = 'ok';
      document.getElementById('modelinfo').textContent =
        h.model + ' · uptime ' + h.uptime_sec + ' с · отвечено: ' + h.requests_served;
    }).catch(function () { document.getElementById('status').className = ''; });
  }
  ping(); setInterval(ping, 15000);

  function bubble(cls, text) {
    var div = document.createElement('div');
    div.className = 'msg ' + cls;
    div.textContent = text;
    log.appendChild(div);
    log.scrollTop = log.scrollHeight;
    return div;
  }

  form.addEventListener('submit', function (e) {
    e.preventDefault();
    var text = q.value.trim();
    if (!text) return;
    q.value = '';
    bubble('user', text);
    history.push({ role: 'user', content: text });
    var thinking = bubble('bot', '…думаю (локальная модель, может занять десятки секунд)');
    sendBtn.disabled = true;

    fetch('/v1/chat', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': 'Bearer ' + tokenInput.value.trim()
      },
      body: JSON.stringify({ messages: history })
    }).then(function (r) { return r.json().then(function (j) { return { s: r.status, j: j }; }); })
      .then(function (res) {
        thinking.remove();
        if (res.s !== 200) {
          history.pop();
          var msg = (res.j && res.j.error) ? res.j.error.message : ('HTTP ' + res.s);
          bubble('err', 'HTTP ' + res.s + ' — ' + msg);
          return;
        }
        history.push({ role: 'assistant', content: res.j.answer });
        var b = bubble('bot', res.j.answer);
        var meta = document.createElement('div');
        meta.className = 'meta';
        var srcs = res.j.sources.map(function (s) { return s.chunk_id + ' (' + s.date + ')'; }).join(', ');
        meta.textContent = 'источники: ' + srcs + '\n' +
          res.j.usage.answer_tokens + ' ткн за ' + res.j.timings.total_ms + ' мс (' +
          res.j.timings.tokens_per_sec + ' ток/с)' +
          (res.j.history_trimmed ? ' · история подрезана под max context' : '');
        b.appendChild(meta);
      })
      .catch(function (err) { thinking.remove(); history.pop(); bubble('err', 'Сеть: ' + err); })
      .finally(function () { sendBtn.disabled = false; q.focus(); });
  });

  q.addEventListener('keydown', function (e) {
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); form.requestSubmit(); }
  });
})();
</script>
</body>
</html>
""".trimIndent()
}
