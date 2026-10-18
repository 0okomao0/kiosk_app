// KioskAppが送る温湿度を受け取り、最新値を保持する依存なしの小さなサーバー
// 使い方: TOKEN=xxxx node sensor-receiver.js
const http = require('http');
const fs = require('fs');
const crypto = require('crypto');

const PORT = Number(process.env.PORT || 8787);
const HOST = process.env.HOST || '127.0.0.1';
const TOKEN = process.env.TOKEN || '';
const DATA_FILE = process.env.DATA_FILE || './sensors.json';
// これより古い値はHomeKit側に「応答なし」として見せる
const MAX_AGE_MS = Number(process.env.MAX_AGE_SEC || 900) * 1000;
const MAX_BODY = 16 * 1024;

if (TOKEN.length < 16) {
  console.error('TOKEN環境変数に16文字以上のトークンを設定してください');
  process.exit(1);
}

let store = {};
try { store = JSON.parse(fs.readFileSync(DATA_FILE, 'utf8')); } catch (e) { /* 初回は空 */ }

function save() {
  fs.writeFile(DATA_FILE, JSON.stringify(store), (err) => {
    if (err) console.error('保存に失敗:', err.message);
  });
}

function authorized(req) {
  const given = Buffer.from(req.headers.authorization || '');
  const expected = Buffer.from(`Bearer ${TOKEN}`);
  return given.length === expected.length && crypto.timingSafeEqual(given, expected);
}

function send(res, status, body) {
  res.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
  res.end(JSON.stringify(body));
}

function ingest(body) {
  if (!body || !Array.isArray(body.sensors)) return 0;
  const now = Date.now();
  let count = 0;
  for (const s of body.sensors) {
    if (typeof s.id !== 'string' || !/^[0-9A-Za-z]{1,16}$/.test(s.id)) continue;
    if (typeof s.temperatureC !== 'number' || typeof s.humidity !== 'number') continue;
    const age = Math.max(0, Number(s.ageSec) || 0) * 1000;
    store[s.id] = {
      id: s.id,
      name: typeof s.name === 'string' ? s.name.slice(0, 32) : '',
      temperature: s.temperatureC,
      humidity: s.humidity,
      battery: Number.isFinite(s.battery) ? s.battery : null,
      rssi: Number.isFinite(s.rssi) ? s.rssi : null,
      measuredAt: now - age,
      receivedAt: now,
    };
    count++;
  }
  if (count > 0) save();
  return count;
}

http.createServer((req, res) => {
  if (!authorized(req)) return send(res, 401, { error: 'unauthorized' });
  const path = req.url.split('?')[0];

  if (req.method === 'POST' && path === '/ingest') {
    let size = 0;
    const chunks = [];
    req.on('data', (c) => {
      size += c.length;
      if (size > MAX_BODY) { req.destroy(); return; }
      chunks.push(c);
    });
    req.on('end', () => {
      let body;
      try { body = JSON.parse(Buffer.concat(chunks).toString('utf8')); } catch (e) {
        return send(res, 400, { error: 'invalid json' });
      }
      send(res, 200, { accepted: ingest(body) });
    });
    return;
  }

  if (req.method === 'GET' && path === '/sensors') {
    return send(res, 200, Object.values(store));
  }

  const m = req.method === 'GET' && path.match(/^\/sensors\/([0-9A-Za-z]{1,16})$/);
  if (m) {
    const s = store[m[1]];
    if (!s) return send(res, 404, { error: 'not found' });
    if (Date.now() - s.measuredAt > MAX_AGE_MS) return send(res, 503, { error: 'stale' });
    return send(res, 200, { temperature: s.temperature, humidity: s.humidity, battery: s.battery, name: s.name });
  }

  send(res, 404, { error: 'not found' });
}).listen(PORT, HOST, () => console.log(`listening on ${HOST}:${PORT}`));
