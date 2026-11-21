package com.android.wificalllocal.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.net.Uri;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class AvatarHelper {

    private static final int AVATAR_SIZE = 256;
    private static final int[] AVATAR_COLORS = {
            0xFF2979FF, 0xFF00B8D4, 0xFF00C853, 0xFF7C4DFF, 0xFFFF6D00, 0xFF00897B
    };

    public static String saveCircularAvatarFromUri(Context context, Uri sourceUri, String fileName) {
        try (InputStream is = context.getContentResolver().openInputStream(sourceUri)) {
            if (is == null) return null;
            Bitmap decoded = BitmapFactory.decodeStream(is);
            if (decoded == null) return null;
            return saveCircularBitmap(context, decoded, fileName);
        } catch (Exception e) {
            return null;
        }
    }

    public static String saveCircularBitmap(Context context, Bitmap source, String fileName) {
        try {
            int size = Math.min(source.getWidth(), source.getHeight());
            int x = (source.getWidth() - size) / 2;
            int y = (source.getHeight() - size) / 2;
            Bitmap square = Bitmap.createBitmap(source, x, y, size, size);
            Bitmap scaled = Bitmap.createScaledBitmap(square, AVATAR_SIZE, AVATAR_SIZE, true);

            Bitmap output = Bitmap.createBitmap(AVATAR_SIZE, AVATAR_SIZE, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(output);
            canvas.drawColor(Color.WHITE); // Clean JPEG backing
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            Rect rect = new Rect(0, 0, AVATAR_SIZE, AVATAR_SIZE);
            canvas.drawCircle(AVATAR_SIZE / 2f, AVATAR_SIZE / 2f, AVATAR_SIZE / 2f, paint);
            paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
            canvas.drawBitmap(scaled, rect, rect, paint);

            File dir = new File(context.getFilesDir(), "avatars");
            if (!dir.exists()) dir.mkdirs();
            File outFile = new File(dir, fileName + ".jpg");
            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                output.compress(Bitmap.CompressFormat.JPEG, 86, fos);
            }
            return outFile.getAbsolutePath();
        } catch (Exception e) {
            return null;
        }
    }

    public static Bitmap createLetterAvatar(String name, String seedId) {
        String letter = "?";
        if (name != null && !name.trim().isEmpty()) {
            letter = name.trim().substring(0, 1).toUpperCase();
        }
        int colorIndex = Math.abs((seedId != null ? seedId : letter).hashCode()) % AVATAR_COLORS.length;
        int bgColor = AVATAR_COLORS[colorIndex];

        Bitmap bmp = Bitmap.createBitmap(AVATAR_SIZE, AVATAR_SIZE, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setColor(bgColor);
        canvas.drawCircle(AVATAR_SIZE / 2f, AVATAR_SIZE / 2f, AVATAR_SIZE / 2f, bgPaint);

        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(108f);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setFakeBoldText(true);
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float baseline = AVATAR_SIZE / 2f - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(letter, AVATAR_SIZE / 2f, baseline, textPaint);
        return bmp;
    }

    public static String encodeAvatarBase64(String filePath) {
        if (filePath == null || filePath.isEmpty()) return "";
        try {
            File f = new File(filePath);
            if (!f.exists()) return "";
            Bitmap bmp = BitmapFactory.decodeFile(filePath);
            if (bmp == null) return "";
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, 75, baos);
            return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            return "";
        }
    }
}
