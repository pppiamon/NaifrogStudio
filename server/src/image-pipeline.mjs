import sharp from 'sharp';

export const PROMPT = `以第一张图片作为不可改变的基础图。
第二张图片仅作为“奶蛙”的角色外观参考。
只替换第一张图片中角色自身的面部/身体特征，使其准确变成第二张参考图中的奶蛙。
不得重新设计奶蛙，不得根据“奶蛙”这个词自行想象角色外观。
保留第一张图中的头发、服装、姿势、手势、背景、光照、构图和画面比例。`;

function decode(value, label) {
  if (typeof value !== 'string' || !value.length || value.length > 24000000 || !/^[A-Za-z0-9+/\r\n]*={0,2}$/.test(value)) {
    throw new Error(`${label}图片数据无效`);
  }
  return Buffer.from(value, 'base64');
}

export async function prepareImages(payload) {
  const originalInput = decode(payload.original, '原图');
  const referenceInput = decode(payload.reference, '参考');
  const selectionInput = decode(payload.selection, '选区');
  const opts = { limitInputPixels: 18000000, failOn: 'error' };
  const source = await sharp(originalInput, opts).rotate().ensureAlpha().raw().toBuffer({ resolveWithObject: true });
  const { width, height } = source.info;
  if (Math.max(width, height) > 4096 || Math.min(width, height) < 64) throw new Error('原图边长需在 64–4096 像素之间');
  const selection = await sharp(selectionInput, opts).ensureAlpha().raw().toBuffer({ resolveWithObject: true });
  if (selection.info.width !== width || selection.info.height !== height) throw new Error('选区尺寸与原图不一致');
  let selected = 0;
  for (let i = 3; i < selection.data.length; i += 4) selected += selection.data[i];
  if (selected < 255 * 16) throw new Error('请先选择需要转换的区域');
  const original = await sharp(source.data, { raw: { width, height, channels: 4 } }).png().toBuffer();
  const reference = await sharp(referenceInput, opts).rotate().resize({ width: 1536, height: 1536, fit: 'inside', withoutEnlargement: true }).png().toBuffer();
  const ratio = width / height;
  const canvasWidth = ratio > 1.2 ? 1536 : 1024;
  const canvasHeight = ratio < 0.83 ? 1536 : 1024;
  const scale = Math.min(canvasWidth / width, canvasHeight / height);
  const innerWidth = Math.round(width * scale), innerHeight = Math.round(height * scale);
  const left = Math.floor((canvasWidth - innerWidth) / 2), top = Math.floor((canvasHeight - innerHeight) / 2);
  const padding = { left, top, right: canvasWidth - innerWidth - left, bottom: canvasHeight - innerHeight - top };
  const padded = await sharp(original).resize(innerWidth, innerHeight).extend({ ...padding, background: '#e8e8e8' }).png().toBuffer();
  // API alpha=0 is editable; the app's selection alpha=255 is editable.
  const maskPixels = Buffer.alloc(width * height * 4, 255);
  for (let i = 3; i < maskPixels.length; i += 4) maskPixels[i] = 255 - selection.data[i];
  const mask = await sharp(maskPixels, { raw: { width, height, channels: 4 } })
    .resize(innerWidth, innerHeight).extend({ ...padding, background: { r: 255, g: 255, b: 255, alpha: 1 } }).png().toBuffer();
  return { source, selection: selection.data, padded, reference, mask, width, height,
    canvasWidth, canvasHeight, innerWidth, innerHeight, left, top };
}

export async function mergeResult(prepared, image) {
  const p = prepared;
  const meta = await sharp(image, { limitInputPixels: 18000000 }).metadata();
  if (Math.abs(meta.width / meta.height - p.canvasWidth / p.canvasHeight) > 0.02) throw new Error('图像服务返回的画面比例不匹配，请重试');
  const normalized = await sharp(image).resize(p.canvasWidth, p.canvasHeight).png().toBuffer();
  const cropped = await sharp(normalized)
    .extract({ left: p.left, top: p.top, width: p.innerWidth, height: p.innerHeight }).png().toBuffer();
  const generated = await sharp(cropped).resize(p.width, p.height).ensureAlpha().raw().toBuffer();
  const output = Buffer.from(p.source.data);
  for (let i = 0; i < output.length; i += 4) {
    const a = p.selection[i + 3] / 255;
    if (a === 0) continue;
    for (let c = 0; c < 3; c++) output[i + c] = Math.round(output[i + c] * (1 - a) + generated[i + c] * a);
  }
  return sharp(output, { raw: { width: p.width, height: p.height, channels: 4 } }).png().toBuffer();
}

export async function editImage(payload, config, signal, fetchImpl = fetch) {
  const p = await prepareImages(payload);
  const form = new FormData();
  form.set('model', config.model);
  form.append('image[]', new Blob([p.padded], { type: 'image/png' }), '01-original.png');
  form.append('image[]', new Blob([p.reference], { type: 'image/png' }), '02-naifrog-reference.png');
  form.set('mask', new Blob([p.mask], { type: 'image/png' }), 'selection-mask.png');
  form.set('prompt', PROMPT + '\n目标可以是真人、卡通或拟人化角色。只修改透明遮罩指定的区域，只处理选区中的目标角色；其他人物或角色保持不变。第一张图可能带有灰色留白边框，必须保留边框、原始内容的位置和大小，不要缩放、平移或裁切原始内容。第二张图片不得作为背景、服装或构图参考。');
  form.set('size', `${p.canvasWidth}x${p.canvasHeight}`);
  form.set('quality', config.quality);
  form.set('output_format', 'png');
  form.set('n', '1');
  if (/^gpt-image-1(?:\.5)?$/.test(config.model)) form.set('input_fidelity', 'high');
  const response = await fetchImpl(`${config.baseUrl.replace(/\/$/, '')}/images/edits`, {
    method: 'POST', headers: { Authorization: `Bearer ${config.apiKey}` }, body: form, signal
  });
  const body = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(body.error?.message || `图像服务返回 ${response.status}`);
  if (!body.data?.[0]?.b64_json) throw new Error('图像接口未返回 b64_json 图片，请检查服务配置');
  return mergeResult(p, Buffer.from(body.data[0].b64_json, 'base64'));
}
