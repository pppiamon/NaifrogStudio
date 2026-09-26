package com.naifrog.studio;

import android.content.Context;
import android.graphics.*;
import android.net.Uri;
import java.io.*;

final class Images {
    static Bitmap load(Context context, Uri uri, int maxSide) throws IOException {
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.getContentResolver(), uri), (decoder, info, source) -> {
            int w = info.getSize().getWidth(), h = info.getSize().getHeight();
            float scale = Math.min(1f, maxSide / (float)Math.max(w, h));
            decoder.setTargetSize(Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale)));
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            decoder.setMutableRequired(true);
        });
    }
    static void save(Bitmap bitmap, File file) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IOException("图片保存失败");
        }
    }
    static byte[] bytes(Bitmap bitmap) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        return out.toByteArray();
    }
    static Bitmap thumbnail(File file) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true; BitmapFactory.decodeFile(file.getPath(), options);
        options.inSampleSize = Math.max(1, Math.max(options.outWidth, options.outHeight) / 320);
        options.inJustDecodeBounds = false;
        return BitmapFactory.decodeFile(file.getPath(), options);
    }
    static boolean hasSelection(Bitmap mask) {
        int[] row = new int[mask.getWidth()];
        for (int y = 0; y < mask.getHeight(); y++) {
            mask.getPixels(row, 0, row.length, 0, y, row.length, 1);
            for (int pixel : row) if (Color.alpha(pixel) > 0) return true;
        }
        return false;
    }
}
