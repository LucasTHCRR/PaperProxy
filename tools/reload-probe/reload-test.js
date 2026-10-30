// node reload-test.js <proxy-dir> <port> [cycles]
// Laesst einen Bot zwischen zwei Servern wechseln, entlaedt und laedt ReloadProbe dazwischen
// und wertet danach das Proxy-Log aus.
const fs = require('fs');
const path = require('path');
const NM = process.env.NM || '/media/lucasthcr/Daten/mc-26-test/bots/node_modules/';
const mineflayer = require(NM + 'mineflayer');

const dir = process.argv[2];
const port = Number(process.argv[3] || 25565);
const cycles = Number(process.argv[4] || 3);
const VERSION = process.env.MC_VERSION || '1.21.11';
const SERVERS = (process.env.SERVERS || 'lobby,factions').split(',');
const PLUGINS = [
  { platform: 'bungee', name: 'ReloadProbeBungee', jar: 'ReloadProbe-Bungee.jar', tag: '[Bungee]' },
  { platform: 'velocity', name: 'reloadprobe', jar: 'ReloadProbe-Velocity.jar', tag: '[reloadprobe]' },
];

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const logFile = path.join(dir, 'log');
const console_ = (line) => fs.appendFileSync(path.join(dir, 'cmd'), line + '\n');
const logLines = () => fs.readFileSync(logFile, 'utf8').split('\n');

async function command(line) {
  console_(line);
  await sleep(1500);
  console_(line + ' confirm');
  await sleep(4000);
}

async function switchRound(bot, phase, marks) {
  for (const server of [SERVERS[1], SERVERS[0]]) {
    const at = logLines().length;
    marks.push({ phase, server, at });
    bot.chat('/server ' + server);
    // Warten, bis der Proxy den Wechsel meldet, sonst zaehlt ein langsamer Wechsel als fehlend
    const deadline = Date.now() + 10000;
    while (Date.now() < deadline
        && !logLines().slice(at).some((l) => l.includes('-> ' + server + ' has connected'))) {
      await sleep(250);
    }
    await sleep(1500);
  }
}

function analyse(start, marks, unloads) {
  const lines = logLines().slice(start);
  const result = {};
  for (const p of PLUGINS) {
    result[p.platform] = { fresh: [], reload: [] };
  }
  const bucket = (phase) => (phase === 0 ? 'fresh' : 'reload');

  for (const line of logLines().slice(start)) {
    for (const p of PLUGINS) {
      if (!line.includes(p.tag)) continue;
      if (line.includes('LEAK') || line.includes('DUPLICATE')) {
        result[p.platform].reload.push(line.replace(/^.*\[ReloadProbe\] /, ''));
      }
    }
  }
  // Pro Wechsel genau eine Zeile pro Plattform
  const all = logLines();
  marks.forEach((m, i) => {
    const window = all.slice(m.at, i + 1 < marks.length ? marks[i + 1].at : all.length);
    for (const p of PLUGINS) {
      const hits = window.filter((l) => l.includes(p.tag) && l.includes('switch generation') && l.includes('-> ' + m.server)).length;
      if (hits !== 1) {
        result[p.platform][bucket(m.phase)].push(`switch to ${m.server} seen ${hits} times`);
      }
    }
  });
  // Nach dem Entladen darf kein Tick der Plattform mehr kommen
  for (const u of unloads) {
    const window = logLines().slice(u.at, u.until);
    for (const p of PLUGINS) {
      const ticks = window.filter((l) => l.includes(p.tag) && l.includes('tick generation')).length;
      if (ticks > 0) {
        result[p.platform].reload.push(`${ticks} tick(s) while unloaded`);
      }
    }
  }
  return { result, checked: lines.length };
}

(async () => {
  const start = logLines().length;
  const marks = [];
  const unloads = [];
  const bot = mineflayer.createBot({ host: '127.0.0.1', port, username: 'ReloadProbe', version: VERSION });
  bot.on('kicked', (r) => console.log('kicked', JSON.stringify(r)));
  bot.on('error', (e) => console.log('error', e.message));
  await new Promise((res, rej) => { bot.once('spawn', res); setTimeout(() => rej(new Error('no spawn')), 30000); });
  await sleep(2000);

  await switchRound(bot, 0, marks);
  for (let c = 1; c <= cycles; c++) {
    for (const p of PLUGINS) await command(`pp plugin unload ${p.name}`);
    const at = logLines().length;
    await sleep(5000);
    unloads.push({ at, until: logLines().length });
    for (const p of PLUGINS) await command(`pp plugin load ${p.jar}`);
    await switchRound(bot, c, marks);
  }
  bot.quit();
  await sleep(1000);

  const { result } = analyse(start, marks, unloads);
  let failed = false;
  console.log(`\nReloadProbe on ${VERSION}, ${cycles} unload/load cycle(s), ${marks.length} server switches\n`);
  console.log('| Platform | Fresh start | After reload |');
  console.log('|---|---|---|');
  for (const p of PLUGINS) {
    const r = result[p.platform];
    const cell = (list) => (list.length ? 'FAIL: ' + [...new Set(list)].join('; ') : 'OK');
    if (r.fresh.length || r.reload.length) failed = true;
    console.log(`| ${p.platform} | ${cell(r.fresh)} | ${cell(r.reload)} |`);
  }
  process.exit(failed ? 1 : 0);
})().catch((e) => { console.error(e); process.exit(2); });
