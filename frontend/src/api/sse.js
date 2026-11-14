export async function* parseSse(stream) {
  const reader = stream.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  try {
    while (true) {
      const { value, done } = await reader.read();
      buffer += decoder.decode(value || new Uint8Array(), { stream: !done });
      const blocks = buffer.split(/\r?\n\r?\n/);
      buffer = blocks.pop() || '';
      for (const block of blocks) {
        let event = 'message';; let data = '';
        for (const line of block.split(/\r?\n/)) { if (line.startsWith('event:')) event = line.slice(6).trim(); if (line.startsWith('data:')) data += line.slice(5).trim(); }
        if (data) { let parsed; try { parsed = JSON.parse(data); } catch { parsed = { raw: data }; } yield { event, data: parsed }; }
      }
      if (done) break;
    }
  } finally { reader.releaseLock(); }
}
