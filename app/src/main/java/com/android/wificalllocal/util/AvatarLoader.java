package com.android.wificalllocal.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.widget.ImageView;

import com.bumptech.glide.Glide;

import java.io.File;

public class AvatarLoader {

    public static void loadPeerAvatar(ImageView target, String name, String deviceId, String avatarBase64) {
        if (avatarBase64 != null && !avatarBase64.isEmpty()) {
            try {
                byte[] bytes = Base64.decode(avatarBase64, Base64.DEFAULT);
                Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (bmp != null) {
                    Glide.with(target.getContext()).load(bmp).circleCrop().into(target);
                    return;
                }
            } catch (Exception ignored) {
            }
        }
        Bitmap letter = AvatarHelper.createLetterAvatar(name, deviceId);
        target.setImageBitmap(letter);
    }

    public static void loadLocalAvatar(ImageView target, String filePath, String name, String deviceId) {
        if (filePath != null && !filePath.isEmpty()) {
            File f = new File(filePath);
            if (f.exists()) {
                Glide.with(target.getContext()).load(f).circleCrop().into(target);
                return;
            }
        }
        target.setImageBitmap(AvatarHelper.createLetterAvatar(name, deviceId));
    }
}
