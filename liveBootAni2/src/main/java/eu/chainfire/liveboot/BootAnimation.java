/* Copyright (C) 2011-2024 Jorrit "Chainfire" Jongma
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package eu.chainfire.liveboot;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES20;
import android.opengl.GLUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import eu.chainfire.librootjava.Logger;

public class BootAnimation {
    private static final String TAG = "BootAnimation";

    private String mZipPath;
    private int mWidth = 0;
    private int mHeight = 0;
    private int mFps = 30;
    private List<Part> mParts = new ArrayList<>();
    private int mCurrentPartIndex = 0;
    private int mCurrentFrameIndex = 0;
    private int mCurrentLoop = 0;
    private long mLastFrameTime = 0;
    private int mPauseFramesRemaining = 0;
    private boolean mBootCompleted = false;

    private Map<String, int[]> mFrameTextures = new HashMap<>();

    // Shader program
    private int mShaderProgram = -1;
    private int mPositionHandle;
    private int mTexCoordHandle;
    private int mTextureHandle;
    private int mMatrixHandle;

    // Vertex and texture coordinates
    private static final float[] VERTEX_COORDS = {
        -1.0f,  1.0f,
        -1.0f, -1.0f,
         1.0f, -1.0f,
         1.0f,  1.0f,
    };

    private static final float[] TEX_COORDS = {
        0.0f, 1.0f,
        0.0f, 0.0f,
        1.0f, 0.0f,
        1.0f, 1.0f,
    };

    private static final short[] INDICES = {
        0, 1, 2,
        0, 2, 3
    };

    private FloatBuffer mVertexBuffer;
    private FloatBuffer mTexCoordBuffer;
    private ByteBuffer mIndexBuffer;

    public static class Part {
        String name;
        List<String> frameNames = new ArrayList<>();
        int count;
        int pause;
        float[] backgroundColor = new float[]{0.0f, 0.0f, 0.0f};
        List<int[]> textures = new ArrayList<>();
    }

    public BootAnimation(String zipPath) {
        mZipPath = zipPath;

        ByteBuffer bb = ByteBuffer.allocateDirect(VERTEX_COORDS.length * 4);
        bb.order(ByteOrder.nativeOrder());
        mVertexBuffer = bb.asFloatBuffer();
        mVertexBuffer.put(VERTEX_COORDS);
        mVertexBuffer.position(0);

        bb = ByteBuffer.allocateDirect(TEX_COORDS.length * 4);
        bb.order(ByteOrder.nativeOrder());
        mTexCoordBuffer = bb.asFloatBuffer();
        mTexCoordBuffer.put(TEX_COORDS);
        mTexCoordBuffer.position(0);

        mIndexBuffer = ByteBuffer.allocateDirect(INDICES.length * 2);
        mIndexBuffer.order(ByteOrder.nativeOrder());
        for (short index : INDICES) {
            mIndexBuffer.putShort(index);
        }
        mIndexBuffer.position(0);
    }

    public boolean load() {
        if (mZipPath == null || mZipPath.isEmpty()) {
            return false;
        }

        ZipFile zipFile = null;
        try {
            zipFile = new ZipFile(mZipPath);
        } catch (IOException e) {
            Logger.e(TAG, "Failed to open zip file: %s", mZipPath);
            return false;
        }

        if (!parseDescTxt(zipFile)) {
            try {
                zipFile.close();
            } catch (IOException e) {}
            return false;
        }

        if (!loadFrames(zipFile)) {
            try {
                zipFile.close();
            } catch (IOException e) {}
            return false;
        }

        try {
            zipFile.close();
        } catch (IOException e) {}

        return initShaders();
    }

    private boolean initShaders() {
        String vertexShaderCode =
            "uniform mat4 uMVPMatrix;" +
            "attribute vec4 aPosition;" +
            "attribute vec2 aTexCoord;" +
            "varying vec2 vTexCoord;" +
            "void main() {" +
            "  gl_Position = uMVPMatrix * aPosition;" +
            "  vTexCoord = aTexCoord;" +
            "}";

        String fragmentShaderCode =
            "precision mediump float;" +
            "varying vec2 vTexCoord;" +
            "uniform sampler2D uTexture;" +
            "void main() {" +
            "  gl_FragColor = texture2D(uTexture, vTexCoord);" +
            "}";

        int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode);
        int fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode);

        if (vertexShader == 0 || fragmentShader == 0) {
            return false;
        }

        mShaderProgram = GLES20.glCreateProgram();
        GLES20.glAttachShader(mShaderProgram, vertexShader);
        GLES20.glAttachShader(mShaderProgram, fragmentShader);
        GLES20.glLinkProgram(mShaderProgram);

        int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(mShaderProgram, GLES20.GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] == 0) {
            Logger.e(TAG, "Failed to link shader program");
            return false;
        }

        mPositionHandle = GLES20.glGetAttribLocation(mShaderProgram, "aPosition");
        mTexCoordHandle = GLES20.glGetAttribLocation(mShaderProgram, "aTexCoord");
        mTextureHandle = GLES20.glGetUniformLocation(mShaderProgram, "uTexture");
        mMatrixHandle = GLES20.glGetUniformLocation(mShaderProgram, "uMVPMatrix");

        return true;
    }

    private int loadShader(int type, String shaderCode) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, shaderCode);
        GLES20.glCompileShader(shader);

        int[] compileStatus = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0);
        if (compileStatus[0] == 0) {
            Logger.e(TAG, "Failed to compile shader: %s", GLES20.glGetShaderInfoLog(shader));
            GLES20.glDeleteShader(shader);
            return 0;
        }

        return shader;
    }

    private boolean parseDescTxt(ZipFile zipFile) {
        ZipEntry descEntry = zipFile.getEntry("desc.txt");
        if (descEntry == null) {
            Logger.e(TAG, "desc.txt not found in zip");
            return false;
        }

        BufferedReader reader = null;
        try {
            InputStream is = zipFile.getInputStream(descEntry);
            reader = new BufferedReader(new InputStreamReader(is));

            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                if (line.startsWith("#")) continue;

                String[] parts = line.split("\\s+");
                if (parts.length == 3) {
                    try {
                        mWidth = Integer.parseInt(parts[0]);
                        mHeight = Integer.parseInt(parts[1]);
                        mFps = Integer.parseInt(parts[2]);
                        Logger.d(TAG, "Animation size: %dx%d, fps: %d", mWidth, mHeight, mFps);
                        continue;
                    } catch (NumberFormatException e) {}
                }

                if (parts.length >= 4 && parts[0].startsWith("p")) {
                    try {
                        Part part = new Part();
                        part.name = parts[3];
                        part.count = Integer.parseInt(parts[1]);
                        part.pause = Integer.parseInt(parts[2]);

                        if (parts.length >= 5 && parts[4].startsWith("#") && parts[4].length() == 7) {
                            parseColor(parts[4], part.backgroundColor);
                        }

                        mParts.add(part);
                        Logger.d(TAG, "Part: %s, count: %d, pause: %d", part.name, part.count, part.pause);
                    } catch (NumberFormatException e) {
                        Logger.e(TAG, "Failed to parse part line: %s", line);
                    }
                }
            }
        } catch (IOException e) {
            Logger.e(TAG, "Error reading desc.txt: %s", e.getMessage());
            return false;
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {}
            }
        }

        return mParts.size() > 0;
    }

    private boolean loadFrames(ZipFile zipFile) {
        for (Part part : mParts) {
            List<String> frameNames = new ArrayList<>();

            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.startsWith(part.name + "/") && !name.endsWith("/")) {
                    String frameName = name.substring(part.name.length() + 1);
                    if (frameName.toLowerCase().endsWith(".png") ||
                        frameName.toLowerCase().endsWith(".jpg") ||
                        frameName.toLowerCase().endsWith(".jpeg")) {
                        frameNames.add(frameName);
                    }
                }
            }

            if (frameNames.isEmpty()) {
                Logger.e(TAG, "No frames found for part: %s", part.name);
                return false;
            }

            java.util.Collections.sort(frameNames);
            part.frameNames = frameNames;

            for (String frameName : frameNames) {
                String entryName = part.name + "/" + frameName;
                ZipEntry frameEntry = zipFile.getEntry(entryName);
                if (frameEntry == null) {
                    Logger.e(TAG, "Frame not found: %s", entryName);
                    return false;
                }

                try {
                    InputStream is = zipFile.getInputStream(frameEntry);
                    Bitmap bitmap = BitmapFactory.decodeStream(is);
                    if (bitmap == null) {
                        Logger.e(TAG, "Failed to decode bitmap: %s", entryName);
                        return false;
                    }

                    int[] textureIds = new int[1];
                    GLES20.glGenTextures(1, textureIds, 0);
                    if (textureIds[0] == 0) {
                        Logger.e(TAG, "Failed to generate texture: %s", entryName);
                        bitmap.recycle();
                        return false;
                    }

                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureIds[0]);
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
                    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
                    bitmap.recycle();

                    part.textures.add(textureIds[0]);
                    mFrameTextures.put(entryName, textureIds);

                    Logger.d(TAG, "Loaded frame: %s", entryName);
                } catch (IOException e) {
                    Logger.e(TAG, "Error loading frame %s: %s", entryName, e.getMessage());
                    return false;
                }
            }

            Logger.d(TAG, "Loaded %d frames for part %s", part.textures.size(), part.name);
        }

        return true;
    }

    private void parseColor(String colorStr, float[] color) {
        if (colorStr.startsWith("#") && colorStr.length() == 7) {
            try {
                int r = Integer.parseInt(colorStr.substring(1, 3), 16);
                int g = Integer.parseInt(colorStr.substring(3, 5), 16);
                int b = Integer.parseInt(colorStr.substring(5, 7), 16);
                color[0] = r / 255.0f;
                color[1] = g / 255.0f;
                color[2] = b / 255.0f;
            } catch (NumberFormatException e) {}
        }
    }

    public void update() {
        if (mBootCompleted) return;
        if (mParts.isEmpty()) return;

        long currentTime = System.currentTimeMillis();

        if (mLastFrameTime == 0) {
            mLastFrameTime = currentTime;
            return;
        }

        long frameDuration = 1000 / mFps;

        if (mPauseFramesRemaining > 0) {
            if (currentTime - mLastFrameTime >= frameDuration) {
                mPauseFramesRemaining--;
                mLastFrameTime = currentTime;
            }
            return;
        }

        if (currentTime - mLastFrameTime >= frameDuration) {
            advanceFrame();
            mLastFrameTime = currentTime;
        }
    }

    private void advanceFrame() {
        if (mCurrentPartIndex >= mParts.size()) {
            return;
        }

        Part currentPart = mParts.get(mCurrentPartIndex);
        mCurrentFrameIndex++;

        if (mCurrentFrameIndex >= currentPart.textures.size()) {
            mCurrentFrameIndex = 0;
            mCurrentLoop++;

            int loopCount = currentPart.count == 0 ? Integer.MAX_VALUE : currentPart.count;
            if (mCurrentLoop >= loopCount) {
                mPauseFramesRemaining = currentPart.pause;
                mCurrentLoop = 0;
                mCurrentPartIndex++;

                if (mCurrentPartIndex >= mParts.size()) {
                    mCurrentPartIndex = mParts.size() - 1;
                    mCurrentLoop = currentPart.count == 0 ? 0 : currentPart.count - 1;
                }
            }
        }
    }

    public void draw() {
        if (mParts.isEmpty() || mShaderProgram == -1) return;

        Part currentPart = mParts.get(mCurrentPartIndex);
        if (currentPart.textures.isEmpty()) return;

        GLES20.glClearColor(currentPart.backgroundColor[0],
                           currentPart.backgroundColor[1],
                           currentPart.backgroundColor[2], 1.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        if (mCurrentFrameIndex < currentPart.textures.size()) {
            int[] textures = currentPart.textures.get(mCurrentFrameIndex);
            if (textures != null && textures.length > 0) {
                int texture = textures[0];
                if (texture > 0) {
                    GLES20.glUseProgram(mShaderProgram);

                    GLES20.glEnableVertexAttribArray(mPositionHandle);
                    GLES20.glVertexAttribPointer(mPositionHandle, 2, GLES20.GL_FLOAT, false, 8, mVertexBuffer);

                    GLES20.glEnableVertexAttribArray(mTexCoordHandle);
                    GLES20.glVertexAttribPointer(mTexCoordHandle, 2, GLES20.GL_FLOAT, false, 8, mTexCoordBuffer);

                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
                    GLES20.glUniform1i(mTextureHandle, 0);

                    float[] mvpMatrix = new float[]{
                        1.0f, 0.0f, 0.0f, 0.0f,
                        0.0f, 1.0f, 0.0f, 0.0f,
                        0.0f, 0.0f, 1.0f, 0.0f,
                        0.0f, 0.0f, 0.0f, 1.0f
                    };
                    GLES20.glUniformMatrix4fv(mMatrixHandle, 1, false, mvpMatrix, 0);

                    GLES20.glDrawElements(GLES20.GL_TRIANGLES, 6, GLES20.GL_UNSIGNED_SHORT, mIndexBuffer);

                    GLES20.glDisableVertexAttribArray(mPositionHandle);
                    GLES20.glDisableVertexAttribArray(mTexCoordHandle);
                }
            }
        }
    }

    public void setBootCompleted(boolean completed) {
        mBootCompleted = completed;
    }

    public int getWidth() {
        return mWidth;
    }

    public int getHeight() {
        return mHeight;
    }

    public boolean isValid() {
        return mParts.size() > 0;
    }

    public void destroy() {
        if (mShaderProgram != -1) {
            GLES20.glDeleteProgram(mShaderProgram);
            mShaderProgram = -1;
        }

        for (int[] textures : mFrameTextures.values()) {
            if (textures != null && textures.length > 0) {
                for (int texture : textures) {
                    if (texture > 0) {
                        GLES20.glDeleteTextures(1, new int[]{texture}, 0);
                    }
                }
            }
        }
        mFrameTextures.clear();
        mParts.clear();
    }
}
