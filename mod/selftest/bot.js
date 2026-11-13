// 回归脚手架的假玩家：node bot.js <名字> [silent]
// silent 表示只保持在线、不执行 /自检（用于多玩家场景）。
const mineflayer = require('mineflayer');

const name = process.argv[2] || 'SelfBot';
const active = process.argv[3] !== 'silent';

const bot = mineflayer.createBot({
  host: '127.0.0.1',
  port: 25566,
  username: name,
  version: '1.21.1',
  auth: 'offline',
});

bot.on('spawn', () => {
  console.log('SPAWNED', name);
  if (active) {
    setTimeout(() => bot.chat('/自检'), 1500);
  }
});
bot.on('error', (err) => console.log('ERROR', name, err.message));
bot.on('kicked', (reason) => console.log('KICKED', name, JSON.stringify(reason)));
bot.on('end', () => console.log('END', name));

// 兜底退出，避免回归脚本异常时残留 node 进程。
setTimeout(() => {
  try { bot.quit(); } catch (_) { /* ignore */ }
  process.exit(0);
}, 180000);
