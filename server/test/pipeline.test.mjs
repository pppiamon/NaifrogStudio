import test from 'node:test';
import assert from 'node:assert/strict';
import sharp from 'sharp';
import { prepareImages, mergeResult, editImage, PROMPT } from '../src/image-pipeline.mjs';

async function fixture(w = 160, h = 96) {
  const original = Buffer.alloc(w * h * 4);
  const selection = Buffer.alloc(w * h * 4);
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
    const i = (y * w + x) * 4;
    original.set([x % 256, y % 256, (x + y) % 256, 255], i);
    selection.set([255, 255, 255, x > w / 3 && x < w / 2 && y > h / 3 && y < h / 2 ? 255 : 0], i);
  }
  const encode = buffer => sharp(buffer, { raw: { width: w, height: h, channels: 4 } }).png().toBuffer();
  return { original: (await encode(original)).toString('base64'), reference: (await encode(original)).toString('base64'), selection: (await encode(selection)).toString('base64') };
}

for (const [w, h] of [[160, 96], [96, 160], [128, 128], [240, 80]]) {
  test(`preserves every unselected pixel and source dimensions for ${w}x${h}`, async () => {
    const prepared = await prepareImages(await fixture(w, h));
    const generated = await sharp({ create: { width: prepared.canvasWidth, height: prepared.canvasHeight, channels: 4, background: '#ff0044' } }).png().toBuffer();
    const { data, info } = await sharp(await mergeResult(prepared, generated)).raw().toBuffer({ resolveWithObject: true });
    assert.equal(info.width, w); assert.equal(info.height, h);
    let selected = 0, protectedPixels = 0;
    for (let i = 0; i < data.length; i += 4) {
      if (prepared.selection[i + 3] === 0) { assert.deepEqual(data.subarray(i, i + 4), prepared.source.data.subarray(i, i + 4)); protectedPixels++; }
      else { assert.deepEqual([...data.subarray(i, i + 4)], [255, 0, 68, 255]); selected++; }
    }
    assert.ok(selected > 0 && protectedPixels > selected);
  });
}

test('mask alpha inversion and padding remain correct', async () => {
  const prepared = await prepareImages(await fixture(240, 80));
  const { data, info } = await sharp(prepared.mask).raw().toBuffer({ resolveWithObject: true });
  assert.equal(info.width, prepared.canvasWidth); assert.equal(info.height, prepared.canvasHeight);
  assert.equal(data[3], 255);
  const x = prepared.left + Math.floor(prepared.innerWidth * .42), y = prepared.top + Math.floor(prepared.innerHeight * .42);
  assert.equal(data[(y * info.width + x) * 4 + 3], 0);
});

test('rejects mismatched and empty selections', async () => {
  const input = await fixture();
  const empty = await sharp({ create: { width: 160, height: 96, channels: 4, background: '#00000000' } }).png().toBuffer();
  await assert.rejects(() => prepareImages({ ...input, selection: empty.toString('base64') }), /选择/);
  const small = await sharp(empty).resize(80, 48).png().toBuffer();
  await assert.rejects(() => prepareImages({ ...input, selection: small.toString('base64') }), /尺寸/);
});

test('sends original first, reference second, exact user prompt, high fidelity and editable mask', async () => {
  let called = 0;
  const mockedUpstream = async (url, request) => {
    called++;
    assert.equal(url, 'https://image.example/v1/images/edits');
    assert.equal(request.headers.Authorization, 'Bearer test');
    const form = request.body;
    assert.equal(form.get('prompt').slice(0, PROMPT.length), PROMPT);
    assert.match(form.get('prompt'), /拟人化角色/);
    assert.equal(form.get('input_fidelity'), 'high');
    assert.equal(form.getAll('image[]')[0].name, '01-original.png');
    assert.equal(form.getAll('image[]')[1].name, '02-naifrog-reference.png');
    const [width, height] = form.get('size').split('x').map(Number);
    const image = await sharp({ create: { width, height, channels: 4, background: '#88bb44' } }).png().toBuffer();
    return Response.json({ data: [{ b64_json: image.toString('base64') }] });
  };
  const result = await editImage(await fixture(), { baseUrl: 'https://image.example/v1/', model: 'gpt-image-1.5', quality: 'high', apiKey: 'test' }, undefined, mockedUpstream);
  assert.equal(called, 1); assert.equal((await sharp(result).metadata()).width, 160);
});

test('reports upstream failures instead of creating a substitute result', async () => {
  await assert.rejects(() => editImage(fixture, {}, undefined), /图片数据/);
  await assert.rejects(() => editImage({ ...{} }, {}, undefined), /图片数据/);
  await assert.rejects(() => editImageForFailure(), /quota/);
  async function editImageForFailure() { return editImage(await fixture(), { baseUrl: 'https://image.example', model: 'gpt-image-1.5' }, undefined, async () => Response.json({ error: { message: 'quota exceeded' } }, { status: 429 })); }
});
