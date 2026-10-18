// iPhone用 Scriptableウィジェット: sensor-receiver.js の最新値を表示する
// 初回はScriptableアプリ内で実行すると、トークンの入力を求められる(キーチェーンに保存)
const BASE_URL = 'https://sensor.nagifans.cc';
// 表示順。名前はサーバーに保存されたものを使う
const SENSOR_IDS = ['107A', '5593'];
const STALE_MS = 15 * 60 * 1000;
const REFRESH_MS = 5 * 60 * 1000;
const KEY = 'kiosk-sensor-token';

async function getToken() {
  if (Keychain.contains(KEY)) return Keychain.get(KEY);
  if (config.runsInWidget) return null;
  const a = new Alert();
  a.title = 'トークンを入力';
  a.addSecureTextField('Bearer token');
  a.addAction('保存');
  a.addCancelAction('キャンセル');
  if ((await a.present()) < 0) return null;
  const token = a.textFieldValue(0).trim();
  if (token) Keychain.set(KEY, token);
  return token || null;
}

async function fetchSensors(token) {
  const req = new Request(`${BASE_URL}/sensors`);
  req.headers = { Authorization: `Bearer ${token}` };
  req.timeoutInterval = 15;
  const list = await req.loadJSON();
  if (req.response.statusCode === 401) {
    Keychain.remove(KEY);
    throw new Error('トークンが違います。もう一度実行して入力し直してください');
  }
  return SENSOR_IDS.map((id) => list.find((s) => s.id === id)).filter(Boolean);
}

const fmtTemp = (s) => `${s.temperature.toFixed(1)}°`;
const fmtHum = (s) => `${Math.round(s.humidity)}%`;
const isStale = (s) => Date.now() - s.measuredAt > STALE_MS;
const label = (s) => s.name || s.id;

function addText(stack, text, size, opts = {}) {
  const t = stack.addText(text);
  t.font = opts.bold ? Font.boldSystemFont(size) : Font.systemFont(size);
  if (opts.color) t.textColor = opts.color;
  t.lineLimit = 1;
  t.minimumScaleFactor = 0.6;
  return t;
}

function buildWidget(sensors, error) {
  const w = new ListWidget();
  w.refreshAfterDate = new Date(Date.now() + REFRESH_MS);
  const family = config.widgetFamily || 'medium';
  const dim = Color.gray();

  if (family === 'accessoryInline') {
    w.addText(error ? '取得失敗' : sensors.map((s) => `${label(s)} ${fmtTemp(s)}`).join(' '));
    return w;
  }

  if (family === 'accessoryRectangular' || family === 'accessoryCircular') {
    if (error || sensors.length === 0) { w.addText('取得失敗'); return w; }
    const list = family === 'accessoryCircular' ? sensors.slice(0, 1) : sensors;
    for (const s of list) {
      const row = w.addStack();
      row.centerAlignContent();
      addText(row, `${label(s)} ${fmtTemp(s)} ${fmtHum(s)}`, 14, { bold: true });
      if (isStale(s)) addText(row, ' ⚠︎', 12);
    }
    return w;
  }

  w.backgroundColor = Color.dynamic(new Color('#fdf6e3'), new Color('#002b36'));
  w.setPadding(12, 14, 12, 14);
  if (error || sensors.length === 0) {
    addText(w, error || 'センサー値がありません', 14, { color: dim });
    return w;
  }

  const row = w.addStack();
  row.layoutHorizontally();
  row.centerAlignContent();
  sensors.forEach((s, i) => {
    if (i > 0) row.addSpacer();
    const col = row.addStack();
    col.layoutVertically();
    addText(col, label(s), 13, { color: dim });
    addText(col, fmtTemp(s), family === 'small' ? 30 : 38, {
      bold: true,
      color: isStale(s) ? dim : undefined,
    });
    addText(col, `湿度 ${fmtHum(s)}${isStale(s) ? ' (古い)' : ''}`, 13, { color: dim });
  });
  w.addSpacer();
  const t = new Date(Math.max(...sensors.map((s) => s.measuredAt)));
  const hm = `${t.getHours()}:${String(t.getMinutes()).padStart(2, '0')}`;
  addText(w, `${hm} 更新`, 10, { color: dim });
  return w;
}

const token = await getToken();
let sensors = [];
let error = null;
if (!token) {
  error = 'Scriptableアプリでこのスクリプトを実行してトークンを設定してください';
} else {
  try {
    sensors = await fetchSensors(token);
  } catch (e) {
    error = `取得失敗: ${e.message}`;
  }
}

const widget = buildWidget(sensors, error);
if (config.runsInWidget) {
  Script.setWidget(widget);
} else {
  await widget.presentMedium();
}
Script.complete();
