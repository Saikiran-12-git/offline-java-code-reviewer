const code = document.querySelector('#code');
const output = document.querySelector('#output');
const state = document.querySelector('#state');
const notice = document.querySelector('#notice');
const submit = document.querySelector('#submit');
const stop = document.querySelector('#stop');
const example = document.querySelector('#example');
let active;
const bytes = () => new TextEncoder().encode(code.value).length;
const updateBudget = () => { document.querySelector('#budget').textContent = `${bytes().toLocaleString()} / 6,000 bytes`; };
code.addEventListener('input', updateBudget);
example.addEventListener('click', () => {
  code.value = `import java.util.List;

class InvoiceCalculator {
    int average(List<Integer> amounts) {
        int total = 0;
        for (Integer amount : amounts) {
            total += amount;
        }
        return total / amounts.size();
    }
}`;
  updateBudget();
});
stop.addEventListener('click', () => active?.abort());
document.querySelector('#review-form').addEventListener('submit', async event => {
  event.preventDefault();
  if (active) return;
  if (!code.value.trim() || bytes() > 6000) {
    notice.textContent = 'Enter a non-empty snippet of at most 6,000 UTF-8 bytes.';
    return;
  }
  active = new AbortController();
  submit.disabled = true;
  stop.disabled = false;
  example.disabled = true;
  output.textContent = '';
  notice.textContent = '';
  state.textContent = 'Connecting…';
  let completed = false;
  let failed = false;
  let reader;
  const consume = line => {
    if (!line.trim()) return;
    const item = JSON.parse(line);
    if (item.type === 'delta' || item.type === 'done') output.textContent += item.text || '';
    if (item.type === 'done') { completed = true; state.textContent = 'Complete'; }
    if (item.type === 'error') { failed = true; notice.textContent = item.text; state.textContent = 'Unable to finish'; }
    output.scrollTop = output.scrollHeight;
  };
  try {
    const response = await fetch('/api/reviews', {
      method: 'POST', headers: {'Content-Type': 'application/json', 'Accept': 'application/x-ndjson'},
      body: JSON.stringify({code: code.value, focus: document.querySelector('#focus').value}),
      signal: active.signal
    });
    if (!response.ok) throw new Error(`Request rejected (${response.status}). Check your input and try again.`);
    if (!response.body) throw new Error('This browser cannot read streamed responses.');
    state.textContent = 'Reviewing…';
    reader = response.body.getReader();
    const decoder = new TextDecoder();
    let pending = '';
    while (true) {
      const part = await reader.read();
      pending += decoder.decode(part.value, {stream: !part.done});
      const lines = pending.split('\n');
      pending = lines.pop();
      lines.forEach(consume);
      if (part.done) { consume(pending); break; }
    }
    if (!completed && !failed) throw new Error('The review ended early. Please try again.');
  } catch (error) {
    state.textContent = error.name === 'AbortError' ? 'Stopped' : 'Unable to finish';
    notice.textContent = error.name === 'AbortError' ? 'Review stopped. Any partial findings remain visible.' : error.message;
  } finally {
    reader?.releaseLock();
    active = null;
    submit.disabled = false;
    stop.disabled = true;
    example.disabled = false;
  }
});
