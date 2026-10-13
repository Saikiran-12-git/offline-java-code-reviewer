const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

function ui(fetch) {
  const elements = new Map();
  const get = selector => {
    if (!elements.has(selector)) elements.set(selector, {
      value: selector === '#focus' ? 'BUGS' : '', textContent: '', disabled: false,
      listeners: {}, addEventListener(name, fn) { this.listeners[name] = fn; },
      scrollHeight: 20, scrollTop: 0
    });
    return elements.get(selector);
  };
  const context = vm.createContext({document:{querySelector:get}, TextEncoder, TextDecoder, AbortController, fetch});
  vm.runInContext(fs.readFileSync(path.join(__dirname,'../../main/resources/static/app.js'),'utf8'),context);
  get('#code').value='class Example {}';
  return {get, run:()=>get('#review-form').listeners.submit({preventDefault(){}})};
}
function response(chunks) {
  const encoder = new TextEncoder();
  let i=0;
  return {ok:true, body:{getReader:()=>({
    read:async()=>i<chunks.length?{value:encoder.encode(chunks[i++]),done:false}:{done:true},
    releaseLock(){}
  })}};
}
test('parses split NDJSON and preserves final content as plain text',async()=>{
  const page=ui(async()=>response(['{"type":"delta","te','xt":"<b>Finding</b>"}\n{"type":"done","text":" End."}\n']));
  await page.run();
  assert.equal(page.get('#output').textContent,'<b>Finding</b> End.');
  assert.equal(page.get('#state').textContent,'Complete');
  assert.equal(page.get('#submit').disabled,false);
});
test('UTF-8 budget blocks oversized code before any HTTP request',async()=>{
  let called=false;
  const page=ui(async()=>{called=true;return response([])});
  page.get('#code').value='é'.repeat(3100);
  await page.run();
  assert.equal(called,false);
  assert.match(page.get('#notice').textContent,/6,000/);
});
test('surfaces upstream error events and permits another review',async()=>{
  const page=ui(async()=>response(['{"type":"error","text":"Start Ollama."}\n']));
  await page.run();
  assert.equal(page.get('#notice').textContent,'Start Ollama.');
  assert.equal(page.get('#state').textContent,'Unable to finish');
  assert.equal(page.get('#submit').disabled,false);
});
test('does not call incomplete output a successful review',async()=>{
  const page=ui(async()=>response(['{"type":"delta","text":"Partial"}\n']));
  await page.run();
  assert.equal(page.get('#output').textContent,'Partial');
  assert.match(page.get('#notice').textContent,/ended early/);
});
test('Stop aborts the request and resets controls',async()=>{
  const page=ui((url,options)=>new Promise((resolve,reject)=>{
    options.signal.addEventListener('abort',()=>reject(Object.assign(new Error('cancelled'),{name:'AbortError'})));
  }));
  const running=page.run();
  page.get('#stop').listeners.click();
  await running;
  assert.equal(page.get('#state').textContent,'Stopped');
  assert.equal(page.get('#stop').disabled,true);
  assert.equal(page.get('#submit').disabled,false);
});
