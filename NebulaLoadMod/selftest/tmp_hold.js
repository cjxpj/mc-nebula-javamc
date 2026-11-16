const mineflayer = require('mineflayer');
const bot = mineflayer.createBot({host:'127.0.0.1',port:25567,username:'NebBot',version:'1.21.11',auth:'offline'});
bot.once('spawn', () => console.log('SPAWNED', bot.entity.position.toString()));
bot.on('message', m => console.log('MSG:', m.toString()));
bot.on('error', e => console.log('ERROR', e.message));
bot.on('kicked', r => console.log('KICKED', JSON.stringify(r)));
setTimeout(()=>{try{bot.quit();}catch(_){} process.exit(0);}, 30000);
