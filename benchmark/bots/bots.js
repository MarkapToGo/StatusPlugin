// Lightweight protocol-level bots for the StatusPlugin benchmark. Usage:
//   node bots.js <count> <joinDelayMs> [serverPort] [controlPort]
// Control via HTTP on 127.0.0.1:<controlPort> (default 8099):
//   /status                    joined bots, kicks, login times
//   /reset, /stats             count tab list / team / chat packets received since /reset
//   /chat?n=100&rate=10        send n chat messages and measure delivery time to all bots
//   /say?bot=Bot001&msg=hi     chat as one bot
//   /inbox?bot=Bot001          chat/system messages that bot received since the last call
//   /quit
const mc = require('minecraft-protocol');
const http = require('http');
const url = require('url');

const COUNT = parseInt(process.argv[2] || '100', 10);
const JOIN_DELAY = parseInt(process.argv[3] || '50', 10);
const PORT = parseInt(process.argv[4] || '25565', 10);
const CONTROL_PORT = parseInt(process.argv[5] || '8099', 10);
const VERSION = '26.1';

const bots = [];
let joined = 0, ended = 0;
const kicks = [];
const joinTimes = [];
let firstConnectAt = 0, lastJoinAt = 0;

// packet counters since last /reset
let counts = {}, bytes = {}, windowStart = Date.now();
const WATCH = new Set(['player_info', 'teams', 'playerlist_header', 'player_chat', 'system_chat', 'profileless_chat']);

// chat latency tracking
const pending = new Map(); // id -> {sentAt, receipts: []}

function now() { return Number(process.hrtime.bigint() / 1000n) / 1000; } // ms, float

function startBot(i) {
  const name = 'Bot' + String(i).padStart(3, '0');
  const t0 = now();
  if (!firstConnectAt) firstConnectAt = t0;
  const client = mc.createClient({ host: '127.0.0.1', port: PORT, username: name, version: VERSION, auth: 'offline', hideErrors: true });
  const bot = { name, client, joined: false, inbox: [] };
  bots.push(bot);

  client.on('login', () => {
    bot.joined = true;
    joined++;
    const t = now();
    joinTimes.push(t - t0);
    lastJoinAt = t;
  });
  client.on('packet', (data, meta, buffer) => {
    const n = meta.name;
    if (WATCH.has(n)) {
      counts[n] = (counts[n] || 0) + 1;
      bytes[n] = (bytes[n] || 0) + (buffer ? buffer.length : 0);
    }
    if (n === 'player_chat' || n === 'system_chat' || n === 'profileless_chat') {
      const text = (data && typeof data.plainMessage === 'string') ? data.plainMessage : JSON.stringify(data);
      if (bot.inbox.length < 100) bot.inbox.push(n + ' ' + JSON.stringify(data).slice(0, 1500));
      const m = /lat#(\d+)#/.exec(text);
      if (m) {
        const p = pending.get(+m[1]);
        if (p) p.receipts.push(now() - p.sentAt);
      }
    }
    if (n === 'position' && data && data.teleportId !== undefined) {
      try { client.write('teleport_confirm', { teleportId: data.teleportId }); } catch (e) {}
    }
    if (n === 'chunk_batch_finished') {
      try { client.write('chunk_batch_received', { chunksPerTick: 9 }); } catch (e) {}
    }
  });
  client.on('kick_disconnect', (p) => kicks.push(name + ': ' + JSON.stringify(p).slice(0, 200)));
  client.on('disconnect', (p) => kicks.push(name + ' (disc): ' + JSON.stringify(p).slice(0, 200)));
  client.on('error', (e) => kicks.push(name + ' (err): ' + e.message));
  client.on('end', () => { ended++; if (bot.joined) joined--; bot.joined = false; });
}

for (let i = 1; i <= COUNT; i++) setTimeout(() => startBot(i), (i - 1) * JOIN_DELAY);

function pct(arr, p) {
  if (!arr.length) return null;
  const s = [...arr].sort((a, b) => a - b);
  return s[Math.min(s.length - 1, Math.max(0, Math.ceil(p * s.length) - 1))];
}
function summary(arr) {
  if (!arr.length) return { n: 0 };
  const mean = arr.reduce((a, b) => a + b, 0) / arr.length;
  return { n: arr.length, mean: +mean.toFixed(2), p50: +pct(arr, .5).toFixed(2), p90: +pct(arr, .9).toFixed(2), p99: +pct(arr, .99).toFixed(2), max: +pct(arr, 1).toFixed(2) };
}

let chatSeq = 0;
async function chatTest(n, rate) {
  pending.clear();
  const active = bots.filter(b => b.joined);
  for (let k = 0; k < n; k++) {
    const id = ++chatSeq;
    const bot = active[k % active.length];
    pending.set(id, { sentAt: now(), receipts: [] });
    try { bot.client.chat('lat#' + id + '# hello there, check https://example.com/page ok'); } catch (e) {}
    await new Promise(r => setTimeout(r, 1000 / rate));
  }
  await new Promise(r => setTimeout(r, 5000));
  const all = [], lastPerMsg = [], firstPerMsg = [];
  let delivered = 0;
  for (const p of pending.values()) {
    all.push(...p.receipts);
    if (p.receipts.length) { lastPerMsg.push(Math.max(...p.receipts)); firstPerMsg.push(Math.min(...p.receipts)); }
    delivered += p.receipts.length;
  }
  return { messages: n, expectedReceipts: n * active.length, delivered, allReceiptsMs: summary(all), firstReceiptMs: summary(firstPerMsg), lastReceiptMs: summary(lastPerMsg) };
}

http.createServer(async (req, res) => {
  const q = url.parse(req.url, true);
  let out;
  if (q.pathname === '/status') {
    out = { joined, ended, total: bots.length, kicks: kicks.slice(-5), kickCount: kicks.length,
      joinMs: summary(joinTimes), joinWindowMs: lastJoinAt && firstConnectAt ? +(lastJoinAt - firstConnectAt).toFixed(0) : null };
  } else if (q.pathname === '/reset') {
    counts = {}; bytes = {}; windowStart = Date.now(); out = { ok: true };
  } else if (q.pathname === '/stats') {
    const secs = (Date.now() - windowStart) / 1000;
    const perBotPerSec = {}, bytesPerBotPerSec = {};
    for (const k of Object.keys(counts)) {
      perBotPerSec[k] = +(counts[k] / joined / secs).toFixed(3);
      bytesPerBotPerSec[k] = +(bytes[k] / joined / secs).toFixed(1);
    }
    out = { secs: +secs.toFixed(1), joined, counts, bytes, perBotPerSec, bytesPerBotPerSec };
  } else if (q.pathname === '/chat') {
    out = await chatTest(parseInt(q.query.n || '100', 10), parseFloat(q.query.rate || '10'));
  } else if (q.pathname === '/say') {
    const bot = bots.find(b => b.name === q.query.bot);
    if (bot && bot.joined) { bot.client.chat(String(q.query.msg || 'hi')); out = { ok: true }; }
    else out = { error: 'bot not online' };
  } else if (q.pathname === '/inbox') {
    const bot = bots.find(b => b.name === q.query.bot);
    out = bot ? bot.inbox.splice(0) : { error: 'unknown bot' };
  } else if (q.pathname === '/quit') {
    bots.forEach(b => { try { b.client.end('bye'); } catch (e) {} });
    out = { ok: true };
    setTimeout(() => process.exit(0), 1000);
  } else {
    out = { error: 'unknown' };
  }
  res.setHeader('content-type', 'application/json');
  res.end(JSON.stringify(out));
}).listen(CONTROL_PORT, '127.0.0.1');

process.on('uncaughtException', (e) => { kicks.push('uncaught: ' + e.message); });
