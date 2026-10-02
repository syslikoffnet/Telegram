package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.ViewConfiguration;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;

/**
 * Живой 3D-пингвин Pengram.
 *
 * Крутится пальцем вокруг своей оси (можно хоть десять оборотов), на отпускании
 * влетает в пружинную анимацию и мягко останавливается лицом к пользователю,
 * а если «перекрутить» — спиной. По тапу подпрыгивает, в покое дышит и моргает.
 *
 * Полностью процедурная геометрия: ни моделей, ни текстур в ассетах.
 */
public class PengramPenguinView extends TextureView implements TextureView.SurfaceTextureListener {

    // ---------------------------------------------------------------- GL plumbing

    private static final int EGL_OPENGL_ES2_BIT = 4;
    private static final int EGL_CONTEXT_CLIENT_VERSION = 0x3098;

    private EGL10 egl;
    private EGLDisplay eglDisplay;
    private EGLSurface eglSurface;
    private EGLContext eglContext;
    private EGLConfig eglConfig;

    private SurfaceTexture surfaceTexture;
    private RenderThread thread;
    private volatile boolean running;
    private volatile boolean paused;
    private volatile int surfaceWidth, surfaceHeight;

    private volatile boolean ready;
    private Runnable readyListener;

    private int program;
    private int aPosition, aNormal, aColor;
    private int uMvp, uModel, uAlpha, uLight;

    private Mesh bodyMesh, eyesMesh, wingLeftMesh, wingRightMesh, accessoryMesh;

    /** выбранный скин; перестраиваем сетку аксессуаров прямо в рендер-потоке */
    private volatile int skin;
    private volatile int builtSkin = -1;

    // ---------------------------------------------------------------- state

    /** текущий угол поворота вокруг оси Y, в градусах; может уходить за 360 */
    private volatile float angle;
    /** цель пружины */
    private volatile float targetAngle;
    /** угловая скорость, градусы в секунду */
    private volatile float velocity;
    private volatile boolean dragging;
    private volatile boolean settled = true;

    private volatile float jump;          // 0..1 фаза прыжка
    private volatile float jumpVelocity;
    private volatile float squash;        // сплющивание при приземлении

    private float time;
    private float blinkTimer = 2.5f;
    private float blink;                  // 0 — глаза открыты, 1 — закрыты

    private volatile float flip;          // 0..1 — сальто назад
    private volatile boolean flipping;
    private volatile float dance;         // сколько ещё секунд танцевать
    private volatile float wave;          // сколько ещё секунд махать крылом
    private float wingAngle;              // текущий угол крыльев, градусы

    private final float[] projection = new float[16];
    private final float[] view = new float[16];
    private final float[] model = new float[16];
    private final float[] tmp = new float[16];
    private final float[] mvp = new float[16];

    // touch
    private float lastTouchX, downX, downY;
    private long downTime;
    private boolean movedFar;
    private boolean horizontal;
    private final int touchSlop;

    private Runnable onTapListener;

    public PengramPenguinView(Context context) {
        super(context);
        setOpaque(false);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setSurfaceTextureListener(this);
    }

    public void setOnTapListener(Runnable listener) {
        onTapListener = listener;
    }

    /** надеть скин: шапка, шарф, очки и так далее */
    public void setSkin(int value) {
        skin = value;
        // маленький «переодевающийся» прыжок, чтобы обновка была заметна
        doJump();
        wave = 1.1f;
    }

    public int getSkin() {
        return skin;
    }

    /** сальто назад */
    public void doFlip() {
        if (!flipping) {
            flipping = true;
            flip = 0f;
            jumpVelocity = Math.max(jumpVelocity, 3.9f);
        }
    }

    /** потанцевать несколько секунд */
    public void doDance() {
        dance = Math.max(dance, 2.8f);
    }

    /** помахать крылом */
    public void doWave() {
        wave = Math.max(wave, 1.6f);
    }

    /** вызовется на UI-потоке, когда первый кадр реально нарисован */
    public void whenReady(Runnable runnable) {
        if (ready) {
            runnable.run();
        } else {
            readyListener = runnable;
        }
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        surfaceTexture = surface;
        surfaceWidth = width;
        surfaceHeight = height;
        paused = false;
        thread = new RenderThread();
        thread.start();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        surfaceWidth = width;
        surfaceHeight = height;
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        running = false;
        thread = null;
        ready = false;
        return false;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        paused = true;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        paused = false;
    }

    public void setPaused(boolean value) {
        paused = value;
    }

    // ---------------------------------------------------------------- touch

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        final int action = event.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                dragging = true;
                settled = false;
                velocity = 0;
                lastTouchX = event.getX();
                downX = event.getX();
                downY = event.getY();
                downTime = System.currentTimeMillis();
                movedFar = false;
                horizontal = false;
                scheduleLongPress();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                final float x = event.getX();
                final float dx = x - lastTouchX;
                lastTouchX = x;
                final float totalX = Math.abs(x - downX);
                final float totalY = Math.abs(event.getY() - downY);
                if (totalX > touchSlop || totalY > touchSlop) {
                    movedFar = true;
                    cancelLongPress2();
                }
                if (!horizontal && totalX > touchSlop && totalX > totalY) {
                    // крутим пингвина — список скроллиться не должен
                    horizontal = true;
                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                } else if (!horizontal && totalY > touchSlop && totalY > totalX) {
                    // палец поехал вертикально — отдаём жест списку
                    dragging = false;
                    return false;
                }
                final float width = Math.max(1, getWidth());
                // полный свайп по ширине вьюхи ≈ 290°, палец «приклеен» к пингвину
                final float delta = dx / width * 290f;
                angle += delta;
                final long now = System.currentTimeMillis();
                final float dt = Math.max(0.008f, (now - lastMoveTime) / 1000f);
                lastMoveTime = now;
                final float instant = delta / dt;
                velocity = velocity * 0.6f + instant * 0.4f;
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                dragging = false;
                horizontal = false;
                cancelLongPress2();
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(false);
                }
                final boolean isTap = !movedFar
                        && System.currentTimeMillis() - downTime < 260
                        && action == MotionEvent.ACTION_UP;
                if (isTap && !longPressFired) {
                    velocity = 0;
                    final long now = System.currentTimeMillis();
                    // пасхалка: 10 быстрых тапов подряд открывают настройки пингвина
                    if (now - lastAnyTapTime < 700) {
                        tapStreak++;
                    } else {
                        tapStreak = 1;
                    }
                    lastAnyTapTime = now;
                    if (tapStreak >= 10) {
                        tapStreak = 0;
                        lastTapTime = 0;
                        doDance();
                        doWave();
                        if (onSecretListener != null) {
                            onSecretListener.run();
                        }
                        return true;
                    }
                    if (now - lastTapTime < 320) {
                        // двойной тап — сальто назад
                        lastTapTime = 0;
                        doFlip();
                    } else {
                        lastTapTime = now;
                        doJump();
                    }
                    if (onTapListener != null) {
                        onTapListener.run();
                    }
                }
                longPressFired = false;
                // куда долетим по инерции — туда и «примагничиваемся»
                final float projected = angle + velocity * 0.14f;
                targetAngle = Math.round(projected / 180f) * 180f;
                settled = false;
                return true;
            }
        }
        return super.onTouchEvent(event);
    }

    private long lastMoveTime = System.currentTimeMillis();
    private long lastTapTime;
    private long lastAnyTapTime;
    private int tapStreak;
    private Runnable onSecretListener;

    /** что открыть, если быстро тапнуть по пингвину 10 раз подряд */
    public void setOnSecretListener(Runnable listener) {
        onSecretListener = listener;
    }
    private boolean longPressFired;
    private Runnable longPressRunnable;

    private void scheduleLongPress() {
        cancelLongPress2();
        longPressRunnable = () -> {
            longPressRunnable = null;
            if (!dragging || movedFar) {
                return;
            }
            longPressFired = true;
            doDance();
            doWave();
            if (onTapListener != null) {
                onTapListener.run();
            }
        };
        postDelayed(longPressRunnable, 420);
    }

    private void cancelLongPress2() {
        if (longPressRunnable != null) {
            removeCallbacks(longPressRunnable);
            longPressRunnable = null;
        }
    }

    private void doJump() {
        if (jump <= 0.001f) {
            jumpVelocity = 3.4f;
        }
    }

    /** развернуть пингвина к пользователю программно */
    public void resetRotation() {
        targetAngle = Math.round(angle / 360f) * 360f;
        settled = false;
    }

    // ---------------------------------------------------------------- physics

    private void update(float dt) {
        dt = Math.min(dt, 0.05f);
        time += dt;

        if (!dragging && !settled) {
            // затухающая пружина: пара перелётов туда-сюда и мягкая остановка
            final float stiffness = 46f;
            final float damping = 6.2f;
            final float diff = targetAngle - angle;
            velocity += (stiffness * diff - damping * velocity) * dt;
            angle += velocity * dt;
            if (Math.abs(targetAngle - angle) < 0.25f && Math.abs(velocity) < 3f) {
                angle = targetAngle;
                velocity = 0;
                settled = true;
            }
        } else if (dragging) {
            // пока палец на экране — пингвин строго следует за ним
            velocity *= Math.max(0f, 1f - dt * 2.4f);
        }

        // прыжок
        if (jumpVelocity != 0 || jump > 0) {
            jumpVelocity -= 11f * dt;
            jump += jumpVelocity * dt;
            if (jump <= 0) {
                jump = 0;
                if (jumpVelocity < -1.2f) {
                    squash = Math.min(0.22f, -jumpVelocity * 0.06f);
                    jumpVelocity = -jumpVelocity * 0.34f;
                    if (jumpVelocity < 0.9f) {
                        jumpVelocity = 0;
                    }
                } else {
                    jumpVelocity = 0;
                }
            }
        }
        squash = Math.max(0, squash - dt * 0.9f);

        // сальто
        if (flipping) {
            flip += dt * 1.45f;
            if (flip >= 1f) {
                flip = 0f;
                flipping = false;
                squash = Math.max(squash, 0.16f);
            }
        }

        // танец и приветствие крылом
        if (dance > 0) {
            dance = Math.max(0f, dance - dt);
        }
        if (wave > 0) {
            wave = Math.max(0f, wave - dt);
        }

        // крылья: в покое чуть дышат, в прыжке расправлены, в танце машут
        float targetWing;
        if (dance > 0) {
            targetWing = 34f + (float) Math.sin(time * 17f) * 30f;
        } else if (wave > 0) {
            targetWing = 52f + (float) Math.sin(time * 13f) * 26f;
        } else if (jump > 0.02f) {
            targetWing = 30f;
        } else {
            targetWing = 4f + (float) Math.sin(time * 1.7f) * 2.5f;
        }
        wingAngle += (targetWing - wingAngle) * Math.min(1f, dt * 14f);

        // моргание
        blinkTimer -= dt;
        if (blinkTimer <= 0) {
            blinkTimer = 2.6f + (float) Math.random() * 3.4f;
        }
        if (blinkTimer < 0.18f) {
            final float p = (0.18f - blinkTimer) / 0.18f;
            blink = (float) Math.sin(p * Math.PI);
        } else {
            blink = 0;
        }
    }

    // ---------------------------------------------------------------- drawing

    private void drawFrame(float dt) {
        update(dt);

        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight);
        GLES20.glClearColor(0, 0, 0, 0);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthFunc(GLES20.GL_LEQUAL);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glDisable(GLES20.GL_CULL_FACE);

        final float aspect = surfaceHeight == 0 ? 1f : (float) surfaceWidth / surfaceHeight;
        Matrix.perspectiveM(projection, 0, 34f, aspect, 1f, 12f);
        Matrix.setLookAtM(view, 0, 0, 0.05f, 5.4f, 0, 0.02f, 0, 0, 1, 0);

        GLES20.glUseProgram(program);

        if (builtSkin != skin) {
            try {
                buildAccessory(skin);
                builtSkin = skin;
            } catch (Throwable e) {
                FileLog.e(e);
                builtSkin = skin;
                accessoryMesh = null;
            }
        }

        final float dancing = Math.min(1f, dance);
        final float idleBob = (float) Math.sin(time * 1.7f) * 0.022f + dancing * (float) Math.abs(Math.sin(time * 8.5f)) * 0.10f;
        final float idleTilt = settled && !dragging ? (float) Math.sin(time * 0.85f) * 2.4f : 0f;
        final float danceTilt = dancing * (float) Math.sin(time * 8.5f) * 13f;
        final float breathe = 1f + (float) Math.sin(time * 1.7f) * 0.012f;
        final float jumpY = jump * 0.7f;
        final float sx = (1f + squash * 0.9f) * breathe;
        final float sy = (1f - squash) * breathe;

        Matrix.setIdentityM(model, 0);
        Matrix.translateM(model, 0, 0, idleBob + jumpY - 0.1f, 0);
        if (flipping) {
            // сальто крутим вокруг «пояса», иначе пингвин улетает за край
            Matrix.translateM(model, 0, 0, 0.1f, 0);
            Matrix.rotateM(model, 0, -flip * 360f, 1, 0, 0);
            Matrix.translateM(model, 0, 0, -0.1f, 0);
        }
        Matrix.rotateM(model, 0, angle + idleTilt, 0, 1, 0);
        Matrix.rotateM(model, 0, Math.max(-14f, Math.min(14f, -velocity * 0.012f)) + danceTilt, 0, 0, 1);
        Matrix.scaleM(model, 0, sx, sy, sx);

        System.arraycopy(model, 0, baseModel, 0, 16);

        Matrix.multiplyMM(tmp, 0, view, 0, model, 0);
        Matrix.multiplyMM(mvp, 0, projection, 0, tmp, 0);

        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0);
        GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0);
        GLES20.glUniform1f(uAlpha, 1f);
        GLES20.glUniform3f(uLight, 0.38f, 0.82f, 0.86f);

        drawMesh(bodyMesh);
        if (accessoryMesh != null) {
            drawMesh(accessoryMesh);
        }

        // крылья живут отдельно: их можно поднимать и махать
        drawWing(wingLeftMesh, -WING_PIVOT_X, wingAngle);
        drawWing(wingRightMesh, WING_PIVOT_X, -(wave > 0 ? wingAngle * 0.25f : wingAngle));

        System.arraycopy(baseModel, 0, model, 0, 16);
        Matrix.multiplyMM(tmp, 0, view, 0, model, 0);
        Matrix.multiplyMM(mvp, 0, projection, 0, tmp, 0);
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0);
        GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0);

        // глаза живут отдельной сеткой, чтобы их можно было «прикрыть» при моргании
        if (blink > 0.01f) {
            Matrix.setIdentityM(tmp, 0);
            Matrix.translateM(model, 0, 0, 0.827f, 0);
            Matrix.scaleM(model, 0, 1f, Math.max(0.08f, 1f - blink), 1f);
            Matrix.translateM(model, 0, 0, -0.827f, 0);
            Matrix.multiplyMM(tmp, 0, view, 0, model, 0);
            Matrix.multiplyMM(mvp, 0, projection, 0, tmp, 0);
            GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0);
            GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0);
        }
        drawMesh(eyesMesh);
    }

    private static final float WING_PIVOT_X = 0.60f;
    private static final float WING_PIVOT_Y = 0.16f;
    private final float[] baseModel = new float[16];

    /** крыло крутится вокруг «плеча» */
    private void drawWing(Mesh mesh, float pivotX, float degrees) {
        if (mesh == null) {
            return;
        }
        System.arraycopy(baseModel, 0, model, 0, 16);
        Matrix.translateM(model, 0, pivotX, WING_PIVOT_Y, 0);
        Matrix.rotateM(model, 0, degrees, 0, 0, 1);
        Matrix.translateM(model, 0, -pivotX, -WING_PIVOT_Y, 0);
        Matrix.multiplyMM(tmp, 0, view, 0, model, 0);
        Matrix.multiplyMM(mvp, 0, projection, 0, tmp, 0);
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0);
        GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0);
        drawMesh(mesh);
    }

    private void drawMesh(Mesh mesh) {
        if (mesh == null) {
            return;
        }
        mesh.buffer.position(0);
        GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, 9 * 4, mesh.buffer);
        GLES20.glEnableVertexAttribArray(aPosition);
        mesh.buffer.position(3);
        GLES20.glVertexAttribPointer(aNormal, 3, GLES20.GL_FLOAT, false, 9 * 4, mesh.buffer);
        GLES20.glEnableVertexAttribArray(aNormal);
        mesh.buffer.position(6);
        GLES20.glVertexAttribPointer(aColor, 3, GLES20.GL_FLOAT, false, 9 * 4, mesh.buffer);
        GLES20.glEnableVertexAttribArray(aColor);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, mesh.count);
    }

    // ---------------------------------------------------------------- shaders

    private static final String VERTEX_SHADER =
            "uniform mat4 uMvp;\n" +
            "uniform mat4 uModel;\n" +
            "attribute vec3 aPosition;\n" +
            "attribute vec3 aNormal;\n" +
            "attribute vec3 aColor;\n" +
            "varying vec3 vNormal;\n" +
            "varying vec3 vColor;\n" +
            "varying vec3 vPos;\n" +
            "void main() {\n" +
            "  vNormal = normalize((uModel * vec4(aNormal, 0.0)).xyz);\n" +
            "  vColor = aColor;\n" +
            "  vPos = (uModel * vec4(aPosition, 1.0)).xyz;\n" +
            "  gl_Position = uMvp * vec4(aPosition, 1.0);\n" +
            "}\n";

    private static final String FRAGMENT_SHADER =
            "precision mediump float;\n" +
            "uniform float uAlpha;\n" +
            "uniform vec3 uLight;\n" +
            "varying vec3 vNormal;\n" +
            "varying vec3 vColor;\n" +
            "varying vec3 vPos;\n" +
            "void main() {\n" +
            "  vec3 n = normalize(vNormal);\n" +
            "  vec3 l = normalize(uLight);\n" +
            "  vec3 v = normalize(vec3(0.0, 0.1, 1.0));\n" +
            "  float diff = max(dot(n, l), 0.0);\n" +
            "  float wrap = max(dot(n, normalize(vec3(-0.6, 0.2, 0.3))), 0.0) * 0.25;\n" +
            "  float rim = pow(1.0 - max(dot(n, v), 0.0), 2.6) * 0.33;\n" +
            "  float spec = pow(max(dot(reflect(-l, n), v), 0.0), 26.0) * 0.45;\n" +
            "  vec3 color = vColor * (0.42 + 0.66 * diff + wrap);\n" +
            "  color += vec3(0.45, 0.68, 1.0) * rim;\n" +
            "  color += vec3(1.0) * spec;\n" +
            "  gl_FragColor = vec4(clamp(color, 0.0, 1.0), uAlpha);\n" +
            "}\n";

    private int compile(int type, String source) {
        final int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        final int[] status = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) {
            FileLog.e("PengramPenguinView: shader error " + GLES20.glGetShaderInfoLog(shader));
            GLES20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    private void initProgram() {
        final int vs = compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
        final int fs = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
        program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vs);
        GLES20.glAttachShader(program, fs);
        GLES20.glLinkProgram(program);
        aPosition = GLES20.glGetAttribLocation(program, "aPosition");
        aNormal = GLES20.glGetAttribLocation(program, "aNormal");
        aColor = GLES20.glGetAttribLocation(program, "aColor");
        uMvp = GLES20.glGetUniformLocation(program, "uMvp");
        uModel = GLES20.glGetUniformLocation(program, "uModel");
        uAlpha = GLES20.glGetUniformLocation(program, "uAlpha");
        uLight = GLES20.glGetUniformLocation(program, "uLight");
    }

    // ---------------------------------------------------------------- geometry

    private static class Mesh {
        final FloatBuffer buffer;
        final int count;

        Mesh(ArrayList<Float> data) {
            final float[] array = new float[data.size()];
            for (int i = 0; i < data.size(); ++i) {
                array[i] = data.get(i);
            }
            buffer = ByteBuffer.allocateDirect(array.length * 4)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer();
            buffer.put(array).position(0);
            count = array.length / 9;
        }
    }

    /** цвет точки по её положению на единичной сфере */
    private interface Painter {
        float[] color(float ux, float uy, float uz);
    }

    private static final float[] BLACK = {0.102f, 0.121f, 0.153f};
    private static final float[] WHITE = {0.972f, 0.976f, 0.988f};
    private static final float[] ORANGE = {0.976f, 0.639f, 0.149f};
    private static final float[] DARK = {0.051f, 0.063f, 0.086f};
    private static final float[] SHINE = {1f, 1f, 1f};

    private static float smoothstep(float edge0, float edge1, float x) {
        float t = (x - edge0) / (edge1 - edge0);
        if (t < 0) t = 0; else if (t > 1) t = 1;
        return t * t * (3f - 2f * t);
    }

    private static float[] mix(float[] a, float[] b, float t) {
        if (t <= 0f) return a;
        if (t >= 1f) return b;
        return new float[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t};
    }

    private void buildMeshes() {
        final ArrayList<Float> body = new ArrayList<>();

        // туловище: чёрная спина + белое пузо (граница размыта, чтобы не было «лесенки»)
        addEllipsoid(body, 0f, -0.18f, 0f, 0.64f, 0.80f, 0.60f, 0f, 0f, 64, 44, (ux, uy, uz) -> {
            final float front = uz - uy * 0.28f;
            final float t = smoothstep(0.38f, 0.46f, front) * (1f - smoothstep(0.70f, 0.78f, uy));
            return mix(BLACK, WHITE, t);
        });

        // голова с белой «маской» на лице
        addEllipsoid(body, 0f, 0.70f, 0.015f, 0.505f, 0.475f, 0.475f, 0f, 0f, 56, 38, (ux, uy, uz) -> {
            final float front = uz - uy * 0.35f - Math.abs(ux) * 0.25f;
            final float t = smoothstep(0.36f, 0.44f, front) * (1f - smoothstep(0.50f, 0.60f, uy));
            return mix(BLACK, WHITE, t);
        });

        // клюв
        addCone(body, 0f, 0.655f, 0.40f, 0.145f, 0.30f, 20, ORANGE);

        // лапки
        addEllipsoid(body, -0.26f, -0.93f, 0.16f, 0.21f, 0.075f, 0.29f, 0f, 0f, 18, 12, (ux, uy, uz) -> ORANGE);
        addEllipsoid(body, 0.26f, -0.93f, 0.16f, 0.21f, 0.075f, 0.29f, 0f, 0f, 18, 12, (ux, uy, uz) -> ORANGE);

        // хвостик
        addEllipsoid(body, 0f, -0.58f, -0.52f, 0.20f, 0.13f, 0.22f, -24f, 0f, 16, 12, (ux, uy, uz) -> BLACK);

        bodyMesh = new Mesh(body);

        final ArrayList<Float> eyes = new ArrayList<>();
        // центры глаз вынесены почти на поверхность головы, иначе сферы тонут внутри неё
        addEllipsoid(eyes, -0.194f, 0.827f, 0.413f, 0.082f, 0.088f, 0.082f, 0f, 0f, 16, 12, (ux, uy, uz) -> DARK);
        addEllipsoid(eyes, 0.194f, 0.827f, 0.413f, 0.082f, 0.088f, 0.082f, 0f, 0f, 16, 12, (ux, uy, uz) -> DARK);
        addEllipsoid(eyes, -0.215f, 0.854f, 0.463f, 0.030f, 0.030f, 0.030f, 0f, 0f, 10, 8, (ux, uy, uz) -> SHINE);
        addEllipsoid(eyes, 0.173f, 0.854f, 0.463f, 0.030f, 0.030f, 0.030f, 0f, 0f, 10, 8, (ux, uy, uz) -> SHINE);
        eyesMesh = new Mesh(eyes);

        // крылья — отдельными сетками, чтобы ими можно было махать
        final ArrayList<Float> left = new ArrayList<>();
        addEllipsoid(left, -WING_PIVOT_X, -0.12f, 0f, 0.115f, 0.40f, 0.28f, 0f, 16f, 20, 14, (ux, uy, uz) -> BLACK);
        wingLeftMesh = new Mesh(left);

        final ArrayList<Float> right = new ArrayList<>();
        addEllipsoid(right, WING_PIVOT_X, -0.12f, 0f, 0.115f, 0.40f, 0.28f, 0f, -16f, 20, 14, (ux, uy, uz) -> BLACK);
        wingRightMesh = new Mesh(right);
    }

    // ---------------------------------------------------------------- скины

    private static final float[] RED = {0.847f, 0.173f, 0.192f};
    private static final float[] GOLD = {0.965f, 0.792f, 0.259f};
    private static final float[] PURPLE = {0.439f, 0.282f, 0.780f};
    private static final float[] BLUE = {0.231f, 0.525f, 0.925f};
    private static final float[] GRAY = {0.188f, 0.204f, 0.239f};
    private static final float[] CYAN = {0.329f, 0.800f, 0.871f};

    /** собрать «одежду» пингвина — вызывается в рендер-потоке при смене скина */
    private void buildAccessory(int value) {
        if (value <= 0) {
            accessoryMesh = null;
            return;
        }
        final ArrayList<Float> out = new ArrayList<>();
        switch (value) {
            case 1: { // колпак Санты
                addConeY(out, 0f, 1.03f, -0.02f, 0.40f, 0.62f, 0.10f, 0.30f, 24, RED);
                addTorus(out, 0f, 1.02f, -0.02f, 0.395f, 0.085f, 1, 26, 10, WHITE);
                addEllipsoid(out, 0.10f, 1.60f, 0.26f, 0.115f, 0.115f, 0.115f, 0f, 0f, 16, 12, (ux, uy, uz) -> WHITE);
                break;
            }
            case 2: { // шарф
                addTorus(out, 0f, 0.34f, 0f, 0.44f, 0.105f, 1, 30, 12, BLUE);
                addEllipsoid(out, 0.17f, 0.06f, 0.37f, 0.095f, 0.26f, 0.065f, 14f, -8f, 14, 12, (ux, uy, uz) -> BLUE);
                break;
            }
            case 3: { // кепка
                addEllipsoid(out, 0f, 0.90f, 0.01f, 0.52f, 0.33f, 0.50f, 0f, 0f, 30, 20, (ux, uy, uz) -> RED);
                addEllipsoid(out, 0f, 0.92f, 0.52f, 0.34f, 0.035f, 0.28f, -10f, 0f, 20, 8, (ux, uy, uz) -> GRAY);
                addEllipsoid(out, 0f, 1.22f, 0.01f, 0.065f, 0.065f, 0.065f, 0f, 0f, 10, 8, (ux, uy, uz) -> GRAY);
                break;
            }
            case 4: { // очки
                addEllipsoid(out, -0.195f, 0.830f, 0.425f, 0.145f, 0.105f, 0.055f, 0f, 0f, 18, 12, (ux, uy, uz) -> GRAY);
                addEllipsoid(out, 0.195f, 0.830f, 0.425f, 0.145f, 0.105f, 0.055f, 0f, 0f, 18, 12, (ux, uy, uz) -> GRAY);
                addEllipsoid(out, 0f, 0.830f, 0.430f, 0.085f, 0.022f, 0.022f, 0f, 0f, 10, 8, (ux, uy, uz) -> GRAY);
                addEllipsoid(out, -0.40f, 0.845f, 0.21f, 0.135f, 0.022f, 0.022f, 0f, 0f, 10, 8, (ux, uy, uz) -> GRAY);
                addEllipsoid(out, 0.40f, 0.845f, 0.21f, 0.135f, 0.022f, 0.022f, 0f, 0f, 10, 8, (ux, uy, uz) -> GRAY);
                break;
            }
            case 5: { // корона
                addTorus(out, 0f, 1.035f, 0f, 0.345f, 0.065f, 1, 26, 10, GOLD);
                for (int a = 0; a < 5; ++a) {
                    final double angleRad = 2 * Math.PI * a / 5;
                    addConeY(out, (float) Math.sin(angleRad) * 0.33f, 1.05f, (float) Math.cos(angleRad) * 0.33f,
                            0.085f, 0.21f, 0f, 0f, 12, GOLD);
                }
                break;
            }
            case 6: { // наушники
                addTorus(out, 0f, 0.80f, 0f, 0.56f, 0.055f, 2, 26, 10, GRAY);
                addEllipsoid(out, -0.545f, 0.80f, 0f, 0.10f, 0.185f, 0.165f, 0f, 0f, 16, 12, (ux, uy, uz) -> GRAY);
                addEllipsoid(out, 0.545f, 0.80f, 0f, 0.10f, 0.185f, 0.165f, 0f, 0f, 16, 12, (ux, uy, uz) -> GRAY);
                addEllipsoid(out, -0.615f, 0.80f, 0f, 0.045f, 0.145f, 0.125f, 0f, 0f, 14, 10, (ux, uy, uz) -> CYAN);
                addEllipsoid(out, 0.615f, 0.80f, 0f, 0.045f, 0.145f, 0.125f, 0f, 0f, 14, 10, (ux, uy, uz) -> CYAN);
                break;
            }
            case 7: { // бабочка
                addEllipsoid(out, -0.13f, 0.30f, 0.40f, 0.12f, 0.09f, 0.045f, 0f, 22f, 14, 10, (ux, uy, uz) -> RED);
                addEllipsoid(out, 0.13f, 0.30f, 0.40f, 0.12f, 0.09f, 0.045f, 0f, -22f, 14, 10, (ux, uy, uz) -> RED);
                addEllipsoid(out, 0f, 0.30f, 0.42f, 0.045f, 0.055f, 0.035f, 0f, 0f, 10, 8, (ux, uy, uz) -> GOLD);
                break;
            }
            case 8: { // колпак волшебника
                addConeY(out, 0f, 1.02f, 0f, 0.37f, 0.95f, 0.06f, 0.16f, 24, PURPLE);
                addEllipsoid(out, 0f, 1.01f, 0f, 0.62f, 0.035f, 0.62f, 0f, 0f, 28, 8, (ux, uy, uz) -> PURPLE);
                addTorus(out, 0f, 1.10f, 0f, 0.345f, 0.055f, 1, 24, 10, GOLD);
                addEllipsoid(out, 0.11f, 1.78f, 0.28f, 0.075f, 0.075f, 0.075f, 0f, 0f, 12, 10, (ux, uy, uz) -> GOLD);
                break;
            }
        }
        accessoryMesh = out.isEmpty() ? null : new Mesh(out);
    }

    /** конус вдоль +Y с возможным наклоном вершины (dx, dz) — шапки и зубцы короны */
    private void addConeY(ArrayList<Float> out, float cx, float cy, float cz,
                          float radius, float height, float tipX, float tipZ,
                          int slices, float[] color) {
        final float[] apex = {cx + tipX, cy + height, cz + tipZ};
        for (int i = 0; i < slices; ++i) {
            final double a0 = 2 * Math.PI * i / slices;
            final double a1 = 2 * Math.PI * (i + 1) / slices;
            final float[] p0 = {cx + (float) Math.sin(a0) * radius, cy, cz + (float) Math.cos(a0) * radius};
            final float[] p1 = {cx + (float) Math.sin(a1) * radius, cy, cz + (float) Math.cos(a1) * radius};
            final float[] n0 = {(float) Math.sin(a0), radius / height, (float) Math.cos(a0)};
            final float[] n1 = {(float) Math.sin(a1), radius / height, (float) Math.cos(a1)};
            normalize(n0);
            normalize(n1);
            addVertex(out, p0, n0, color);
            addVertex(out, p1, n1, color);
            addVertex(out, apex, n0, color);

            final float[] down = {0, -1, 0};
            final float[] center = {cx, cy, cz};
            addVertex(out, p1, down, color);
            addVertex(out, p0, down, color);
            addVertex(out, center, down, color);
        }
    }

    /**
     * Тор. axis: 0 — кольцо в плоскости YZ, 1 — в плоскости XZ (на голове),
     * 2 — в плоскости XY (дужка наушников).
     */
    private void addTorus(ArrayList<Float> out, float cx, float cy, float cz,
                          float bigRadius, float smallRadius, int axis,
                          int slices, int rings, float[] color) {
        for (int i = 0; i < slices; ++i) {
            for (int j = 0; j < rings; ++j) {
                final float[][] quad = new float[4][];
                final float[][] quadN = new float[4][];
                for (int k = 0; k < 4; ++k) {
                    final int si = i + (k == 1 || k == 2 ? 1 : 0);
                    final int rj = j + (k == 2 || k == 3 ? 1 : 0);
                    final double u = 2 * Math.PI * si / slices;
                    final double v = 2 * Math.PI * rj / rings;
                    final float ringX = (float) Math.cos(u);
                    final float ringY = (float) Math.sin(u);
                    final float r = bigRadius + smallRadius * (float) Math.cos(v);
                    final float h = smallRadius * (float) Math.sin(v);
                    float px, py, pz, nx, ny, nz;
                    if (axis == 1) {
                        px = ringX * r; pz = ringY * r; py = h;
                        nx = ringX * (float) Math.cos(v); nz = ringY * (float) Math.cos(v); ny = (float) Math.sin(v);
                    } else if (axis == 2) {
                        px = ringX * r; py = ringY * r; pz = h;
                        nx = ringX * (float) Math.cos(v); ny = ringY * (float) Math.cos(v); nz = (float) Math.sin(v);
                    } else {
                        py = ringX * r; pz = ringY * r; px = h;
                        ny = ringX * (float) Math.cos(v); nz = ringY * (float) Math.cos(v); nx = (float) Math.sin(v);
                    }
                    final float[] n = {nx, ny, nz};
                    normalize(n);
                    quad[k] = new float[]{px + cx, py + cy, pz + cz};
                    quadN[k] = n;
                }
                addVertex(out, quad[0], quadN[0], color);
                addVertex(out, quad[1], quadN[1], color);
                addVertex(out, quad[2], quadN[2], color);
                addVertex(out, quad[0], quadN[0], color);
                addVertex(out, quad[2], quadN[2], color);
                addVertex(out, quad[3], quadN[3], color);
            }
        }
    }

    private void addVertex(ArrayList<Float> out, float[] p, float[] n, float[] c) {
        out.add(p[0]); out.add(p[1]); out.add(p[2]);
        out.add(n[0]); out.add(n[1]); out.add(n[2]);
        out.add(c[0]); out.add(c[1]); out.add(c[2]);
    }

    /**
     * Эллипсоид с поворотом вокруг X и Z (в градусах) — хватает и на крылья, и на хвост.
     */
    private void addEllipsoid(ArrayList<Float> out, float cx, float cy, float cz,
                              float rx, float ry, float rz,
                              float rotX, float rotZ,
                              int slices, int stacks, Painter painter) {
        final float rxRad = (float) Math.toRadians(rotX);
        final float rzRad = (float) Math.toRadians(rotZ);
        final float[] p = new float[3];
        final float[] n = new float[3];

        for (int i = 0; i < stacks; ++i) {
            for (int j = 0; j < slices; ++j) {
                final float[][] quad = new float[4][];
                final float[][] quadN = new float[4][];
                final float[][] quadC = new float[4][];
                for (int k = 0; k < 4; ++k) {
                    final int si = i + (k == 2 || k == 3 ? 1 : 0);
                    final int sj = j + (k == 1 || k == 2 ? 1 : 0);
                    final double phi = Math.PI * si / stacks;        // 0..PI (сверху вниз)
                    final double theta = 2 * Math.PI * sj / slices;
                    final float ux = (float) (Math.sin(phi) * Math.sin(theta));
                    final float uy = (float) Math.cos(phi);
                    final float uz = (float) (Math.sin(phi) * Math.cos(theta));

                    p[0] = ux * rx; p[1] = uy * ry; p[2] = uz * rz;
                    n[0] = ux / rx; n[1] = uy / ry; n[2] = uz / rz;
                    normalize(n);
                    rotate(p, rxRad, rzRad);
                    rotate(n, rxRad, rzRad);

                    quad[k] = new float[]{p[0] + cx, p[1] + cy, p[2] + cz};
                    quadN[k] = new float[]{n[0], n[1], n[2]};
                    quadC[k] = painter.color(ux, uy, uz);
                }
                addVertex(out, quad[0], quadN[0], quadC[0]);
                addVertex(out, quad[1], quadN[1], quadC[1]);
                addVertex(out, quad[2], quadN[2], quadC[2]);

                addVertex(out, quad[0], quadN[0], quadC[0]);
                addVertex(out, quad[2], quadN[2], quadC[2]);
                addVertex(out, quad[3], quadN[3], quadC[3]);
            }
        }
    }

    /** конус вдоль +Z — клюв */
    private void addCone(ArrayList<Float> out, float cx, float cy, float cz,
                         float radius, float length, int slices, float[] color) {
        final float[] apex = {cx, cy, cz + length};
        for (int i = 0; i < slices; ++i) {
            final double a0 = 2 * Math.PI * i / slices;
            final double a1 = 2 * Math.PI * (i + 1) / slices;
            final float[] p0 = {cx + (float) Math.cos(a0) * radius, cy + (float) Math.sin(a0) * radius * 0.78f, cz};
            final float[] p1 = {cx + (float) Math.cos(a1) * radius, cy + (float) Math.sin(a1) * radius * 0.78f, cz};

            final float[] n0 = {(float) Math.cos(a0), (float) Math.sin(a0) * 0.78f, radius / length};
            final float[] n1 = {(float) Math.cos(a1), (float) Math.sin(a1) * 0.78f, radius / length};
            normalize(n0);
            normalize(n1);

            addVertex(out, p0, n0, color);
            addVertex(out, p1, n1, color);
            addVertex(out, apex, n0, color);

            // донышко, чтобы клюв не просвечивал
            final float[] back = {0, 0, -1};
            final float[] center = {cx, cy, cz};
            addVertex(out, p1, back, color);
            addVertex(out, p0, back, color);
            addVertex(out, center, back, color);
        }
    }

    private static void normalize(float[] v) {
        final float len = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        if (len > 0.00001f) {
            v[0] /= len; v[1] /= len; v[2] /= len;
        }
    }

    private static void rotate(float[] v, float rotX, float rotZ) {
        if (rotX != 0) {
            final float y = v[1] * (float) Math.cos(rotX) - v[2] * (float) Math.sin(rotX);
            final float z = v[1] * (float) Math.sin(rotX) + v[2] * (float) Math.cos(rotX);
            v[1] = y; v[2] = z;
        }
        if (rotZ != 0) {
            final float x = v[0] * (float) Math.cos(rotZ) - v[1] * (float) Math.sin(rotZ);
            final float y = v[0] * (float) Math.sin(rotZ) + v[1] * (float) Math.cos(rotZ);
            v[0] = x; v[1] = y;
        }
    }

    // ---------------------------------------------------------------- render thread

    private class RenderThread extends Thread {

        @Override
        public void run() {
            running = true;
            try {
                initGL();
                initProgram();
                buildMeshes();
            } catch (Throwable e) {
                FileLog.e(e);
                running = false;
                return;
            }

            final int targetFps = Math.max(30, (int) AndroidUtilities.screenRefreshRate);
            final long frameTime = Math.max(1, 1000L / targetFps);
            long last = System.currentTimeMillis();

            while (running) {
                final long now = System.currentTimeMillis();
                final float dt = Math.max(0.001f, (now - last) / 1000f);
                last = now;
                try {
                    if (!paused) {
                        drawFrame(dt);
                        egl.eglSwapBuffers(eglDisplay, eglSurface);
                        if (!ready) {
                            ready = true;
                            final Runnable listener = readyListener;
                            readyListener = null;
                            if (listener != null) {
                                AndroidUtilities.runOnUIThread(listener);
                            }
                        }
                    }
                } catch (Throwable e) {
                    FileLog.e(e);
                    break;
                }
                try {
                    final long spent = System.currentTimeMillis() - now;
                    if (paused) {
                        Thread.sleep(120);
                    } else if (spent < frameTime) {
                        Thread.sleep(frameTime - spent);
                    }
                } catch (InterruptedException ignore) {
                    break;
                }
            }
            releaseGL();
        }
    }

    private void initGL() {
        egl = (EGL10) EGLContext.getEGL();
        eglDisplay = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);
        if (eglDisplay == EGL10.EGL_NO_DISPLAY) {
            throw new RuntimeException("eglGetDisplay failed");
        }
        final int[] version = new int[2];
        if (!egl.eglInitialize(eglDisplay, version)) {
            throw new RuntimeException("eglInitialize failed");
        }
        final int[] configSpec = {
                EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
                EGL10.EGL_RED_SIZE, 8,
                EGL10.EGL_GREEN_SIZE, 8,
                EGL10.EGL_BLUE_SIZE, 8,
                EGL10.EGL_ALPHA_SIZE, 8,
                EGL10.EGL_DEPTH_SIZE, 16,
                EGL10.EGL_STENCIL_SIZE, 0,
                EGL10.EGL_SAMPLE_BUFFERS, 1,
                EGL10.EGL_NONE
        };
        final int[] configsCount = new int[1];
        final EGLConfig[] configs = new EGLConfig[1];
        if (!egl.eglChooseConfig(eglDisplay, configSpec, configs, 1, configsCount) || configsCount[0] <= 0) {
            // без мультисэмплинга — для совсем простых GPU
            final int[] simpleSpec = {
                    EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
                    EGL10.EGL_RED_SIZE, 8,
                    EGL10.EGL_GREEN_SIZE, 8,
                    EGL10.EGL_BLUE_SIZE, 8,
                    EGL10.EGL_ALPHA_SIZE, 8,
                    EGL10.EGL_DEPTH_SIZE, 16,
                    EGL10.EGL_NONE
            };
            if (!egl.eglChooseConfig(eglDisplay, simpleSpec, configs, 1, configsCount) || configsCount[0] <= 0) {
                throw new RuntimeException("eglChooseConfig failed");
            }
        }
        eglConfig = configs[0];
        final int[] attribList = {EGL_CONTEXT_CLIENT_VERSION, 2, EGL10.EGL_NONE};
        eglContext = egl.eglCreateContext(eglDisplay, eglConfig, EGL10.EGL_NO_CONTEXT, attribList);
        if (eglContext == null || eglContext == EGL10.EGL_NO_CONTEXT) {
            throw new RuntimeException("eglCreateContext failed");
        }
        eglSurface = egl.eglCreateWindowSurface(eglDisplay, eglConfig, surfaceTexture, null);
        if (eglSurface == null || eglSurface == EGL10.EGL_NO_SURFACE) {
            throw new RuntimeException("eglCreateWindowSurface failed " + GLUtils.getEGLErrorString(egl.eglGetError()));
        }
        if (!egl.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw new RuntimeException("eglMakeCurrent failed");
        }
    }

    private void releaseGL() {
        try {
            if (egl != null && eglDisplay != null) {
                egl.eglMakeCurrent(eglDisplay, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT);
                if (eglSurface != null) {
                    egl.eglDestroySurface(eglDisplay, eglSurface);
                }
                if (eglContext != null) {
                    egl.eglDestroyContext(eglDisplay, eglContext);
                }
                egl.eglTerminate(eglDisplay);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        eglSurface = null;
        eglContext = null;
        eglDisplay = null;
    }
}
