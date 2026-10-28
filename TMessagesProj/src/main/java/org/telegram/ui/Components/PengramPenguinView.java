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
    private volatile RenderThread thread;
    /**
     * Каждый показ пингвина — свой поток и свой EGL-контекст. Токен нужен,
     * чтобы старый поток не трогал поля нового: при сворачивании и возврате
     * поверхность пересоздаётся, и два потока на один TextureView — это
     * чёрный квадрат вместо пингвина и брошенные GL-контексты.
     */
    private final java.util.concurrent.atomic.AtomicInteger glToken = new java.util.concurrent.atomic.AtomicInteger();
    /** поля EGL общие для всех потоков, поэтому init и release идут под этим замком */
    private final Object glLock = new Object();
    private volatile boolean paused;
    /** пауза от экрана (фрагмент ушёл в фон) и пауза от системы (окно скрыто) — независимы */
    private volatile boolean pausedByScreen;
    private volatile boolean pausedByWindow;
    private volatile int surfaceWidth, surfaceHeight;

    private volatile boolean ready;
    private Runnable readyListener;

    private int program;
    private int aPosition, aNormal, aColor;
    private int uMvp, uModel, uAlpha, uLight, uTime;

    /** отдельная программа мягкой контактной тени под лапками */
    private int shadowProgram;
    private int aShadowPosition;
    private int uShadowMvp, uShadowAlpha;

    private Mesh bodyMesh, crownTuftMesh, eyesMesh, wingLeftMesh, wingRightMesh, accessoryMesh, shadowMesh;

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
    /** микродвижения взгляда: без них глазницы выглядят нарисованными */
    private float eyeMoveTimer = 1.2f;
    private float eyeX, eyeY, eyeTargetX, eyeTargetY;

    private volatile float flip;          // 0..1 — сальто назад
    private volatile boolean flipping;
    private volatile boolean sleeping;    // спит (режим призрака)
    private volatile boolean danceLoop;   // танцует, пока играет музыка
    private volatile float dance;         // сколько ещё секунд танцевать
    private volatile float wave;          // сколько ещё секунд махать крылом
    private volatile float beat;          // 0..1 — затухающий отклик на удар
    private volatile float energy;        // 0..1 — насколько сейчас громко играет
    private float wingAngle;              // текущий угол крыльев, градусы

    /**
     * Кадрирование сцены. Шапки уходят вверх на разную высоту (колпак
     * волшебника выше короны), поэтому камера подстраивается под надетый
     * аксессуар: иначе верх шляпы обрезался краем текстуры.
     */
    private float camCenter;
    private float camDistance;
    private static final float CAM_FOV_DEG = 34f;
    private static final float CAM_TAN_HALF_FOV = (float) Math.tan(Math.toRadians(CAM_FOV_DEG * 0.5f));
    /** на сколько пингвин реально поднимается в прыжке */
    private static final float CAM_JUMP_LIMIT = 0.26f;
    /** нижняя точка пингвина (лапки) в мировых координатах */
    private static final float CONTENT_BOTTOM = -1.11f;
    /**
     * Какую долю высоты кадра занимает содержимое. 1.0 — впритык к краям,
     * поэтому оставляем маленький воздух: на пике подскока ничего не
     * касается края текстуры.
     */
    private static final float CAM_FILL = 0.97f;
    /** воздух над шляпой и под лапками, в мировых единицах */
    private static final float CAM_MARGIN = 0.035f;

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

    /** танцевать без остановки, пока играет музыка */
    public void setDanceLoop(boolean value) {
        if (danceLoop == value) {
            return;
        }
        danceLoop = value;
        if (value) {
            sleeping = false;
            dance = Math.max(dance, 1.5f);
        }
    }

    public boolean isDanceLoop() {
        return danceLoop;
    }

    /** уснуть: глаза закрыты, дыхание медленнее, движений нет */
    public void setSleeping(boolean value) {
        if (sleeping == value) {
            return;
        }
        sleeping = value;
        if (value) {
            danceLoop = false;
            dance = 0;
            wave = 0;
        } else {
            doJump();
        }
    }

    public boolean isSleeping() {
        return sleeping;
    }

    /** помахать крылом */
    public void doWave() {
        wave = Math.max(wave, 1.6f);
    }

    /**
     * Удар бочки: пингвин подпрыгивает ровно настолько, насколько удар сильный.
     *
     * Прыжок добавляется импульсом к уже идущей физике, а не задаётся жёстко —
     * поэтому частые удары накапливаются в задорную тряску, а редкие дают
     * честный высокий прыжок, и ничего не дёргается рывками.
     */
    public void pulse(float power) {
        if (power <= 0) {
            return;
        }
        final float p = Math.min(1f, power);
        beat = Math.min(1f, Math.max(beat, 0.35f + p * 0.65f));
        if (jump <= 0.12f) {
            jumpVelocity = Math.max(jumpVelocity, 1.7f + p * 2.4f);
        } else {
            jumpVelocity += p * 0.6f;
        }
        if (p > 0.75f) {
            wave = Math.max(wave, 0.45f);   // на сильной доле ещё и крыльями
        }
        sleeping = false;
    }

    /** общая громкость: от неё зависит, насколько размашисто пингвин двигается */
    public void setEnergy(float value) {
        energy = value < 0 ? 0 : (value > 1f ? 1f : value);
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
        applyPaused();
        // старый поток (если ещё жив) обязан успеть освободить свой EGL —
        // иначе новый контекст затрёт его поля, а старый «зачистит» новый
        final RenderThread previous = thread;
        final int token = glToken.incrementAndGet();
        final RenderThread started = new RenderThread(token, previous);
        thread = started;
        started.start();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        surfaceWidth = width;
        surfaceHeight = height;
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        // токен «гасит» текущий поток: следующий кадр он не рисует и сам
        // освобождает EGL в своём потоке
        glToken.incrementAndGet();
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
        pausedByWindow = true;
        applyPaused();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        pausedByWindow = getWindowVisibility() != VISIBLE;
        applyPaused();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        // приложение свернули или экран погас — незачем крутить 3D и жечь батарею
        pausedByWindow = visibility != VISIBLE;
        applyPaused();
    }

    public void setPaused(boolean value) {
        pausedByScreen = value;
        applyPaused();
    }

    private void applyPaused() {
        paused = pausedByScreen || pausedByWindow;
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
                downTime = android.os.SystemClock.elapsedRealtime();
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
                final long now = android.os.SystemClock.elapsedRealtime();
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
                        && android.os.SystemClock.elapsedRealtime() - downTime < 260
                        && action == MotionEvent.ACTION_UP;
                if (isTap && !longPressFired) {
                    velocity = 0;
                    final long now = android.os.SystemClock.elapsedRealtime();
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

    private long lastMoveTime = android.os.SystemClock.elapsedRealtime();
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

        // Взгляд живёт своей жизнью: раз в пару секунд зрачки смещаются на
        // пару пикселей и плавно возвращаются. Неподвижные глаза — главный
        // признак «нарисованной» игрушки.
        if (!sleeping) {
            eyeMoveTimer -= dt;
            if (eyeMoveTimer <= 0) {
                eyeMoveTimer = 1.4f + (float) Math.random() * 2.8f;
                eyeTargetX = ((float) Math.random() - 0.5f) * 0.034f;
                eyeTargetY = ((float) Math.random() - 0.5f) * 0.020f;
            }
            final float eyeK = Math.min(1f, dt * 11f);
            eyeX += (eyeTargetX - eyeX) * eyeK;
            eyeY += (eyeTargetY - eyeY) * eyeK;
        }

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
        beat = Math.max(0, beat - dt * 2.6f);

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
        if (danceLoop) {
            dance = 1f;   // музыка играет — не останавливаемся
        } else if (dance > 0) {
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
        } else if (sleeping) {
            // во сне крылья прижаты и чуть покачиваются в такт дыханию
            targetWing = 1.5f + (float) Math.sin(time * 0.8f) * 1.2f;
        } else {
            targetWing = 4f + (float) Math.sin(time * 1.7f) * 2.5f;
        }
        wingAngle += (targetWing - wingAngle) * Math.min(1f, dt * 14f);

        // моргание; во сне глаза просто закрыты
        if (sleeping) {
            blink = 1f;
            blinkTimer = 2.6f;
            return;
        }
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

        // Кадр всегда квадратный и центрированный. Вьюха может оказаться
        // вытянутой (шапка списка, обложка в плеере) — тогда TextureView
        // растянул бы картинку по своей форме, и пингвин выглядел бы
        // сплющенным. Квадратный вьюпорт с полями делает растяжение
        // невозможным при любой вёрстке.
        final int viewWidth = Math.max(1, surfaceWidth);
        final int viewHeight = Math.max(1, surfaceHeight);
        final int side = Math.min(viewWidth, viewHeight);
        final int viewportX = (viewWidth - side) / 2;
        final int viewportY = (viewHeight - side) / 2;
        GLES20.glViewport(viewportX, viewportY, side, side);
        GLES20.glClearColor(0, 0, 0, 0);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthFunc(GLES20.GL_LEQUAL);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glDisable(GLES20.GL_CULL_FACE);

        // сначала надеваем аксессуар: от его высоты зависит кадр
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

        // вьюпорт квадратный (см. выше), поэтому и проекция строго 1:1
        Matrix.perspectiveM(projection, 0, CAM_FOV_DEG, 1f, 1f, 14f);

        // Верх сцены — макушка или шляпа, низ — лапки. Раньше камера отъезжала
        // ещё на 0.40 сверху и на 10% запаса, и пингвин занимал меньше двух
        // третей кадра — в шапке он выглядел и вовсе игрушечным. Теперь кадр
        // считается от настоящего содержимого: сверху только высота прыжка и
        // пара сантиметров воздуха, снизу — лапки.
        final boolean showTuft = crownTuftMesh != null && skin != 1 && skin != 3 && skin != 8;
        final float contentTop = Math.max(
                Math.max(bodyMesh == null ? 1.18f : bodyMesh.maxY, showTuft ? crownTuftMesh.maxY : 0f),
                accessoryMesh == null ? 0f : accessoryMesh.maxY);
        final float fitTop = contentTop + CAM_JUMP_LIMIT + CAM_MARGIN;
        final float fitBottom = CONTENT_BOTTOM + CAM_MARGIN;
        final float fitHeight = Math.max(0.6f, fitTop - fitBottom);
        // половина кадра — это fitHeight / (2 * CAM_FILL); отсюда расстояние
        final float targetDistance = Math.max(3.2f, (fitHeight / CAM_FILL * 0.5f) / CAM_TAN_HALF_FOV);
        final float targetCenter = (fitTop + fitBottom) * 0.5f;
        if (camDistance <= 0.01f) {
            camDistance = targetDistance;
            camCenter = targetCenter;
        } else {
            final float k = Math.min(1f, dt * 6f);
            camDistance += (targetDistance - camDistance) * k;
            camCenter += (targetCenter - camCenter) * k;
        }
        Matrix.setLookAtM(view, 0, 0, camCenter, camDistance, 0, camCenter, 0, 0, 1, 0);

        GLES20.glUseProgram(program);

        final float dancing = Math.min(1f, dance);
        // чем громче играет, тем шире движения: тихий проигрыш — лёгкое покачивание,
        // припев — полноценная пляска
        final float drive = 0.55f + energy * 0.75f;
        final float idleBob = (float) Math.sin(time * 1.7f) * 0.022f
                + dancing * (float) Math.abs(Math.sin(time * 8.5f)) * 0.10f * drive;
        final float idleTilt = settled && !dragging ? (float) Math.sin(time * 0.85f) * 2.4f : 0f;
        final float danceTilt = dancing * (float) Math.sin(time * 8.5f) * 13f * drive;
        final float breathe = 1f + (float) Math.sin(time * 1.7f) * 0.012f;
        final float jumpY = Math.min(jump * 0.7f, CAM_JUMP_LIMIT);
        // удар сплющивает и тут же растягивает — та самая «резиновость»
        final float punch = beat * 0.16f;
        final float sx = (1f + squash * 0.9f + punch) * breathe;
        final float sy = (1f - squash + punch * 0.7f) * breathe;

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
        GLES20.glUniform1f(uTime, time);

        // тень рисуем до тела: она лежит позади, но именно она «ставит»
        // пингвина на землю и реагирует на прыжок
        drawContactShadow(idleBob);

        drawMesh(bodyMesh);
        if (showTuft) {
            drawMesh(crownTuftMesh);
        }
        if (accessoryMesh != null) {
            drawMesh(accessoryMesh);
        }

        // крылья живут отдельно: их можно поднимать и махать (отрицательный угол
        // поднимает левое крыло наружу, положительный — правое)
        drawWing(wingLeftMesh, -WING_PIVOT_X, -wingAngle);
        drawWing(wingRightMesh, WING_PIVOT_X, wave > 0 ? wingAngle * 0.25f : wingAngle);

        // глаза живут отдельной сеткой: их можно «прикрыть» при моргании и
        // сдвинуть микродвижением взгляда
        System.arraycopy(baseModel, 0, model, 0, 16);
        if (eyeX != 0 || eyeY != 0) {
            Matrix.translateM(model, 0, eyeX, eyeY, 0);
        }
        if (blink > 0.01f) {
            Matrix.translateM(model, 0, 0, 0.822f, 0);
            Matrix.scaleM(model, 0, 1f, Math.max(0.08f, 1f - blink), 1f);
            Matrix.translateM(model, 0, 0, -0.822f, 0);
        }
        Matrix.multiplyMM(tmp, 0, view, 0, model, 0);
        Matrix.multiplyMM(mvp, 0, projection, 0, tmp, 0);
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0);
        GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0);
        GLES20.glUniform1f(uAlpha, 1f);
        drawMesh(eyesMesh);
    }

    private static final float WING_PIVOT_X = 0.51f;
    private static final float WING_PIVOT_Y = 0.20f;
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

    /**
     * Мягкая контактная тень под лапками. В прыжке она расползается и
     * бледнеет — по ней видно высоту, и пингвин перестаёт «висеть в воздухе».
     */
    private void drawContactShadow(float idleBob) {
        if (shadowMesh == null || shadowProgram == 0) {
            return;
        }
        final float lift = Math.min(jump * 0.7f, CAM_JUMP_LIMIT);
        final float spread = 1f + lift * 1.6f;
        final float fade = 0.46f * (1f - Math.min(0.55f, lift * 1.4f));

        Matrix.setIdentityM(model, 0);
        Matrix.translateM(model, 0, 0, CONTENT_BOTTOM + 0.12f + idleBob * 0.35f, -0.55f);
        Matrix.scaleM(model, 0, 0.80f * spread, 0.095f * spread, 1f);
        Matrix.multiplyMM(tmp, 0, view, 0, model, 0);
        Matrix.multiplyMM(mvp, 0, projection, 0, tmp, 0);

        GLES20.glUseProgram(shadowProgram);
        GLES20.glUniformMatrix4fv(uShadowMvp, 1, false, mvp, 0);
        GLES20.glUniform1f(uShadowAlpha, fade);
        GLES20.glDepthMask(false);
        shadowMesh.buffer.position(0);
        GLES20.glVertexAttribPointer(aShadowPosition, 3, GLES20.GL_FLOAT, false, 9 * 4, shadowMesh.buffer);
        GLES20.glEnableVertexAttribArray(aShadowPosition);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, shadowMesh.count);
        GLES20.glDepthMask(true);
        GLES20.glUseProgram(program);
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

    /**
     * Свет «студии»: холодный верх, тёплый отскок от пола, мягкий ключевой
     * источник и френель по краю. Плоская заливка делала пингвина наклейкой —
     * теперь у чёрной спины и белого пуза разная матовость, а по силуэту
     * скользит голубой контровой свет.
     */
    private static final String FRAGMENT_SHADER =
            "#ifdef GL_FRAGMENT_PRECISION_HIGH\n" +
            "precision highp float;\n" +
            "#else\n" +
            "precision mediump float;\n" +
            "#endif\n" +
            "uniform float uAlpha;\n" +
            "uniform vec3 uLight;\n" +
            "uniform float uTime;\n" +
            "varying vec3 vNormal;\n" +
            "varying vec3 vColor;\n" +
            "varying vec3 vPos;\n" +
            "void main() {\n" +
            "  vec3 n = normalize(vNormal);\n" +
            "  vec3 keyDir = normalize(uLight);\n" +
            "  vec3 viewDir = normalize(vec3(0.0, 0.12, 1.0));\n" +
            // полусферический ambient: небо сверху, тёплый отскок снизу
            "  float hemi = n.y * 0.5 + 0.5;\n" +
            "  vec3 sky = vec3(0.62, 0.74, 0.90);\n" +
            "  vec3 ground = vec3(0.34, 0.30, 0.29);\n" +
            "  vec3 ambient = mix(ground, sky, hemi);\n" +
            // ключевой свет с широким переходом: перья рассеивают
            "  float diff = clamp((dot(n, keyDir) + 0.30) / 1.30, 0.0, 1.0);\n" +
            // тёплый отскок с противоположной стороны
            "  float fill = clamp(dot(n, normalize(vec3(-0.78, -0.10, 0.52))) * 0.5 + 0.5, 0.0, 1.0);\n" +
            // френель по краю силуэта
            "  float fres = pow(1.0 - clamp(dot(n, viewDir), 0.0, 1.0), 3.0);\n" +
            // блик: белое пузо матовое, чёрная спина и клюв глянцевые
            "  float lum = dot(vColor, vec3(0.299, 0.587, 0.114));\n" +
            "  vec3 halfV = normalize(keyDir + viewDir);\n" +
            "  float spec = pow(clamp(dot(n, halfV), 0.0, 1.0), mix(52.0, 14.0, lum));\n" +
            // блик еле дышит, иначе поверхность выглядит отполированной до пластика
            "  spec *= 0.85 + 0.15 * sin(uTime * 1.7);\n" +
            "  vec3 color = vColor * (ambient * 0.55 + diff * 0.74 * (1.0 - 0.25 * lum) + fill * 0.10);\n" +
            "  color += sky * fres * (0.58 - 0.30 * lum);\n" +
            "  color += vec3(1.0, 0.97, 0.90) * spec * (0.26 + 0.24 * (1.0 - lum));\n" +
            "  float backRim = pow(1.0 - clamp(dot(n, viewDir), 0.0, 1.0), 2.4) * clamp(-n.x * 0.45 + n.y * 0.55 + 0.35, 0.0, 1.0);\n" +
            "  color += vec3(0.46, 0.64, 0.95) * backRim * 0.20 * (1.0 - lum);\n" +
            // микроворс перьев: почти незаметная рябь убирает «пластик»
            "  float feather = sin(vPos.x * 46.0 + sin(vPos.z * 31.0)) * sin(vPos.y * 38.0 + 1.7);\n" +
            "  color *= 1.0 + feather * 0.014;\n" +
            "  color = pow(clamp(color, 0.0, 1.0), vec3(0.92));\n" +
            "  gl_FragColor = vec4(color, uAlpha);\n" +
            "}\n";

    /** тень: плоский диск в XY, альфа гаснет к краю круга */
    private static final String SHADOW_VERTEX_SHADER =
            "uniform mat4 uMvp;\n" +
            "attribute vec3 aPosition;\n" +
            "varying vec2 vLocal;\n" +
            "void main() {\n" +
            "  vLocal = aPosition.xy;\n" +
            "  gl_Position = uMvp * vec4(aPosition, 1.0);\n" +
            "}\n";

    private static final String SHADOW_FRAGMENT_SHADER =
            "#ifdef GL_FRAGMENT_PRECISION_HIGH\n" +
            "precision highp float;\n" +
            "#else\n" +
            "precision mediump float;\n" +
            "#endif\n" +
            "uniform float uAlpha;\n" +
            "varying vec2 vLocal;\n" +
            "void main() {\n" +
            "  float d = length(vLocal);\n" +
            "  float a = (1.0 - smoothstep(0.10, 1.0, d)) * uAlpha;\n" +
            "  gl_FragColor = vec4(0.02, 0.03, 0.05, a);\n" +
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
        uTime = GLES20.glGetUniformLocation(program, "uTime");

        final int shadowVs = compile(GLES20.GL_VERTEX_SHADER, SHADOW_VERTEX_SHADER);
        final int shadowFs = compile(GLES20.GL_FRAGMENT_SHADER, SHADOW_FRAGMENT_SHADER);
        if (shadowVs == 0 || shadowFs == 0) {
            // без тени пингвин всё равно рисуется — не роняем весь кадр
            shadowProgram = 0;
            return;
        }
        shadowProgram = GLES20.glCreateProgram();
        GLES20.glAttachShader(shadowProgram, shadowVs);
        GLES20.glAttachShader(shadowProgram, shadowFs);
        GLES20.glLinkProgram(shadowProgram);
        aShadowPosition = GLES20.glGetAttribLocation(shadowProgram, "aPosition");
        uShadowMvp = GLES20.glGetUniformLocation(shadowProgram, "uMvp");
        uShadowAlpha = GLES20.glGetUniformLocation(shadowProgram, "uAlpha");
    }

    // ---------------------------------------------------------------- geometry

    private static class Mesh {
        final FloatBuffer buffer;
        final int count;
        final float minY, maxY;

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
            float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
            for (int i = 1; i + 8 < array.length; i += 9) {
                final float y = array[i];
                if (y < lo) lo = y;
                if (y > hi) hi = y;
            }
            minY = count == 0 ? 0f : lo;
            maxY = count == 0 ? 0f : hi;
        }
    }

    /** цвет точки по её положению на единичной сфере */
    private interface Painter {
        float[] color(float ux, float uy, float uz);
    }

    /** параметрическая поверхность P(u, v), u ∈ [0..1], v ∈ [0..1] */
    private interface ParamSurface {
        void eval(float u, float v, float[] outPos);
    }

    /** раскраска параметрической поверхности с доступом к мировой позиции */
    private interface SurfacePainter {
        float[] color(float u, float v, float px, float py, float pz);
    }

    private static final float[] BLACK = {0.092f, 0.116f, 0.168f};
    private static final float[] CROWN_DARK = {0.062f, 0.080f, 0.122f};
    private static final float[] WHITE = {0.976f, 0.980f, 0.992f};
    private static final float[] CREAM = {0.952f, 0.934f, 0.896f};
    private static final float[] COLLAR = {0.995f, 0.830f, 0.440f};
    private static final float[] BLUSH = {0.988f, 0.640f, 0.665f};
    private static final float[] EYE_SOCKET = {0.850f, 0.880f, 0.940f};
    private static final float[] ORANGE = {0.995f, 0.635f, 0.125f};
    private static final float[] BEAK_TIP = {1.000f, 0.770f, 0.265f};
    private static final float[] BEAK_LOWER = {0.925f, 0.490f, 0.105f};
    private static final float[] BEAK_CREASE = {0.460f, 0.185f, 0.075f};
    private static final float[] FOOT_TOP = {0.988f, 0.618f, 0.135f};
    private static final float[] FOOT_BOT = {0.875f, 0.440f, 0.090f};
    private static final float[] DARK = {0.022f, 0.028f, 0.046f};
    private static final float[] IRIS_DEEP = {0.042f, 0.062f, 0.118f};
    private static final float[] IRIS_GLOW = {0.185f, 0.425f, 0.775f};
    private static final float[] SHINE = {1f, 1f, 1f};
    private static final float[] SHINE_SOFT = {0.88f, 0.95f, 1f};

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

    private void addParamSurface(ArrayList<Float> out, int slices, int stacks,
                                 ParamSurface surface, SurfacePainter painter) {
        final float eps = 0.0025f;
        final float[] pu1 = new float[3], pu2 = new float[3];
        final float[] pv1 = new float[3], pv2 = new float[3];
        final float[][] quad = new float[4][3];
        final float[][] quadN = new float[4][3];
        final float[][] quadC = new float[4][];

        for (int i = 0; i < stacks; ++i) {
            for (int j = 0; j < slices; ++j) {
                for (int k = 0; k < 4; ++k) {
                    final int si = i + (k == 2 || k == 3 ? 1 : 0);
                    final int sj = j + (k == 1 || k == 2 ? 1 : 0);
                    final float u = (float) si / stacks;
                    final float v = (float) sj / slices;
                    surface.eval(u, v, quad[k]);

                    final float uc = Math.min(1f - eps, Math.max(eps, u));
                    surface.eval(Math.max(0f, uc - eps), v, pu1);
                    surface.eval(Math.min(1f, uc + eps), v, pu2);
                    surface.eval(uc, v - eps, pv1);
                    surface.eval(uc, v + eps, pv2);

                    final float dux = pu2[0] - pu1[0], duy = pu2[1] - pu1[1], duz = pu2[2] - pu1[2];
                    final float dvx = pv2[0] - pv1[0], dvy = pv2[1] - pv1[1], dvz = pv2[2] - pv1[2];
                    quadN[k][0] = duy * dvz - duz * dvy;
                    quadN[k][1] = duz * dvx - dux * dvz;
                    quadN[k][2] = dux * dvy - duy * dvx;
                    normalize(quadN[k]);
                    quadC[k] = painter.color(u, v, quad[k][0], quad[k][1], quad[k][2]);
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

    private void buildMeshes() {
        final ArrayList<Float> body = new ArrayList<>();

        // 1. Туловище грушевидной формы: уютный животик, мягкая грудка и тёплый золотистый воротничок
        addParamSurface(body, 68, 46, (u, v, out) -> {
            final double phi = Math.PI * u;
            final double theta = 2.0 * Math.PI * v;
            final float ux = (float) (Math.sin(phi) * Math.sin(theta));
            final float uy = (float) Math.cos(phi);
            final float uz = (float) (Math.sin(phi) * Math.cos(theta));
            final float bellyCurve = (uy + 0.24f) / 0.44f;
            final float pear = 1f - 0.095f * smoothstep(-0.05f, 0.88f, uy)
                    + 0.062f * (float) Math.exp(-(bellyCurve * bellyCurve));
            final float chestCurve = (uy - 0.08f) / 0.50f;
            final float chestPuff = 0.042f * Math.max(0f, uz) * (float) Math.exp(-(chestCurve * chestCurve));
            out[0] = ux * 0.635f * pear;
            out[1] = -0.175f + uy * 0.800f;
            out[2] = uz * 0.595f * pear + chestPuff;
        }, (u, v, px, py, pz) -> {
            final double phi = Math.PI * u;
            final double theta = 2.0 * Math.PI * v;
            final float ux = (float) (Math.sin(phi) * Math.sin(theta));
            final float uy = (float) Math.cos(phi);
            final float uz = (float) (Math.sin(phi) * Math.cos(theta));
            final float arch = uz - uy * 0.20f - (ux * ux) * 0.20f;
            final float bellyT = smoothstep(0.32f, 0.40f, arch) * (1f - smoothstep(0.68f, 0.78f, uy));
            float[] bellyCol = mix(WHITE, CREAM, smoothstep(-0.22f, -0.85f, uy) * 0.40f);
            final float collarArg = (uy - 0.55f) / 0.13f;
            final float collarBand = (float) Math.exp(-(collarArg * collarArg)) * smoothstep(0.30f, 0.68f, uz);
            bellyCol = mix(bellyCol, COLLAR, collarBand * 0.34f);
            final float[] backCol = mix(BLACK, CROWN_DARK, smoothstep(0.30f, 0.88f, uy) * 0.40f);
            return mix(backCol, bellyCol, bellyT);
        });

        // 2. Голова с пухлыми щёчками, сердечком лицевой маски и лёгким румянцем
        addParamSurface(body, 68, 46, (u, v, out) -> {
            final double phi = Math.PI * u;
            final double theta = 2.0 * Math.PI * v;
            final float ux = (float) (Math.sin(phi) * Math.sin(theta));
            final float uy = (float) Math.cos(phi);
            final float uz = (float) (Math.sin(phi) * Math.cos(theta));
            final float cxArg = (Math.abs(ux) - 0.55f) / 0.32f;
            final float cyArg = (uy + 0.20f) / 0.30f;
            final float cheek = (float) Math.exp(-(cxArg * cxArg + cyArg * cyArg)) * Math.max(0f, uz + 0.15f);
            final float fhArg = (uy - 0.35f) / 0.35f;
            final float forehead = (float) Math.exp(-(fhArg * fhArg)) * Math.max(0f, uz) * 0.016f;
            out[0] = ux * 0.518f * (1f + 0.058f * cheek);
            out[1] = 0.692f + uy * 0.482f;
            out[2] = 0.016f + uz * 0.482f * (1f + 0.034f * cheek) + forehead;
        }, (u, v, px, py, pz) -> {
            final double phi = Math.PI * u;
            final double theta = 2.0 * Math.PI * v;
            final float ux = (float) (Math.sin(phi) * Math.sin(theta));
            final float uy = (float) Math.cos(phi);
            final float uz = (float) (Math.sin(phi) * Math.cos(theta));
            final float dl = (float) Math.hypot((ux + 0.355f) * 1.10f, (uy - 0.22f) * 1.24f);
            final float dr = (float) Math.hypot((ux - 0.355f) * 1.10f, (uy - 0.22f) * 1.24f);
            final float dChin = (float) Math.hypot(ux * 0.84f, (uy + 0.24f) * 1.14f);
            final float maskT = (1f - smoothstep(0.46f, 0.53f, Math.min(dl, Math.min(dr, dChin))))
                    * smoothstep(0.24f, 0.35f, uz);
            float[] faceCol = mix(WHITE, CREAM, smoothstep(-0.18f, -0.70f, uy) * 0.24f);
            final float bl = (float) Math.hypot((Math.abs(ux) - 0.50f) / 0.165f, (uy + 0.04f) / 0.115f);
            final float blushT = (1f - smoothstep(0.20f, 1.0f, bl)) * smoothstep(0.45f, 0.72f, uz) * maskT;
            faceCol = mix(faceCol, BLUSH, blushT * 0.38f);
            final float eyeRim = Math.min(
                    (float) Math.hypot((ux + 0.365f) / 0.20f, (uy - 0.26f) / 0.22f),
                    (float) Math.hypot((ux - 0.365f) / 0.20f, (uy - 0.26f) / 0.22f));
            final float socketT = (1f - smoothstep(0.75f, 1.05f, eyeRim)) * smoothstep(0.55f, 0.80f, uz);
            faceCol = mix(faceCol, EYE_SOCKET, socketT * 0.30f);
            final float[] darkCol = mix(BLACK, CROWN_DARK, smoothstep(0.10f, 0.75f, uy) * 0.55f);
            return mix(darkCol, faceCol, maskT);
        });

        // 3. Двухчастный изогнутый улыбающийся клюв (верхнее и нижнее надклювье без острых углов)
        addParamSurface(body, 34, 18, (u, v, out) -> {
            final double psi = u * (Math.PI * 0.50);
            final double theta = 2.0 * Math.PI * v;
            final float rad = Math.max(0f, (float) Math.sin(psi));
            final float fwd = Math.max(0f, (float) Math.cos(psi));
            final float ct = (float) Math.cos(theta);
            final float st = (float) Math.sin(theta);
            final float w = 0.146f * (float) Math.pow(rad, 0.88);
            final float hBase = st >= 0f ? (0.082f * (1f - 0.16f * ct * ct)) : 0.020f;
            final float h = hBase * (float) Math.pow(rad, 0.85);
            final float smile = 0.020f * (ct * ct) * (float) Math.pow(rad, 1.2);
            final float yCenter = 0.660f + 0.014f * (float) Math.sin(fwd * Math.PI)
                    - 0.024f * fwd * fwd + smile;
            out[0] = ct * w;
            out[1] = yCenter + st * h;
            out[2] = 0.390f + 0.238f * fwd;
        }, (u, v, px, py, pz) -> {
            final float[] top = mix(ORANGE, BEAK_TIP,
                    smoothstep(0.46f, 0.62f, pz) * 0.60f + smoothstep(0.66f, 0.73f, py) * 0.25f);
            return mix(top, BEAK_CREASE, smoothstep(0.660f, 0.640f, py) * 0.50f);
        });

        addParamSurface(body, 28, 14, (u, v, out) -> {
            final double psi = u * (Math.PI * 0.50);
            final double theta = 2.0 * Math.PI * v;
            final float rad = Math.max(0f, (float) Math.sin(psi));
            final float fwd = Math.max(0f, (float) Math.cos(psi));
            final float ct = (float) Math.cos(theta);
            final float st = (float) Math.sin(theta);
            final float w = 0.104f * (float) Math.pow(rad, 0.90);
            final float h = (st >= 0f ? 0.012f : 0.036f) * (float) Math.pow(rad, 0.88);
            final float smile = 0.014f * (ct * ct) * (float) Math.pow(rad, 1.2);
            out[0] = ct * w;
            out[1] = 0.642f - 0.010f * (float) Math.pow(fwd, 1.8) + smile + st * h;
            out[2] = 0.395f + 0.172f * fwd;
        }, (u, v, px, py, pz) -> mix(BEAK_LOWER, BEAK_CREASE, smoothstep(0.630f, 0.646f, py) * 0.58f));

        // 4. Перепончатые лапки с тремя пухлыми пальчиками и голеностопом
        for (int side = -1; side <= 1; side += 2) {
            final float sx = side;
            addEllipsoid(body, sx * 0.245f, -0.875f, 0.065f, 0.108f, 0.090f, 0.110f, 0f, 0f, 16, 10,
                    (ux, uy, uz) -> mix(FOOT_TOP, BLACK, smoothstep(-0.10f, 0.45f, uy)));
            addEllipsoid(body, sx * 0.252f, -0.922f, 0.145f, 0.155f, 0.064f, 0.160f, 0f, 0f, 20, 12,
                    (ux, uy, uz) -> mix(FOOT_BOT, FOOT_TOP, smoothstep(-0.4f, 0.5f, uy)));
            addEllipsoid(body, sx * 0.255f, -0.932f, 0.238f, 0.165f, 0.044f, 0.125f, 2f, 0f, 20, 10,
                    (ux, uy, uz) -> mix(FOOT_BOT, FOOT_TOP, smoothstep(-0.3f, 0.6f, uy) * 0.85f));
            addFootToe(body, sx * 0.170f, -0.926f, 0.285f, 0.058f, 0.056f, 0.130f, sx * -14f);
            addFootToe(body, sx * 0.255f, -0.924f, 0.312f, 0.064f, 0.060f, 0.146f, sx * 2f);
            addFootToe(body, sx * 0.340f, -0.928f, 0.278f, 0.056f, 0.054f, 0.124f, sx * 17f);
        }

        // 5. Двухцветный приподнятый хвостик с пёрышком на кончике
        addEllipsoid(body, 0f, -0.575f, -0.515f, 0.195f, 0.118f, 0.225f, -26f, 0f, 22, 14,
                (ux, uy, uz) -> mix(WHITE, BLACK, smoothstep(-0.45f, 0.10f, uy)));
        addEllipsoid(body, 0f, -0.515f, -0.605f, 0.118f, 0.072f, 0.132f, -34f, 0f, 16, 10,
                (ux, uy, uz) -> CROWN_DARK);

        bodyMesh = new Mesh(body);

        // Хохолок на макушке (прячется под закрытыми шапками)
        final ArrayList<Float> tuft = new ArrayList<>();
        addEllipsoid(tuft, 0.015f, 1.162f, 0.005f, 0.036f, 0.072f, 0.046f, -16f, -20f, 14, 10, (ux, uy, uz) -> BLACK);
        addEllipsoid(tuft, -0.032f, 1.152f, -0.010f, 0.030f, 0.058f, 0.040f, -20f, 22f, 12, 8, (ux, uy, uz) -> CROWN_DARK);
        crownTuftMesh = new Mesh(tuft);

        // 6. Глубокие глянцевые глаза с сапфировым полумесяцем радужки и двойным бликом
        final ArrayList<Float> eyes = new ArrayList<>();
        for (int side = -1; side <= 1; side += 2) {
            final float sx = side;
            final float ex = sx * 0.186f, ey = 0.822f, ez = 0.420f;
            addEllipsoid(eyes, ex, ey, ez, 0.088f, 0.096f, 0.068f, 0f, sx * -4f, 34, 26, (ux, uy, uz) -> {
                final float gx = ux + sx * 0.08f;
                final float r = (float) Math.hypot(gx, uy);
                final float lowerGlow = smoothstep(0.18f, -0.68f, uy)
                        * smoothstep(0.92f, 0.36f, r) * smoothstep(0.35f, 0.85f, uz);
                float[] c = mix(IRIS_DEEP, IRIS_GLOW, lowerGlow * 0.90f);
                final float pupilR = (float) Math.hypot(gx * 1.02f, (uy - 0.04f) * 0.98f);
                c = mix(c, DARK, (1f - smoothstep(0.38f, 0.56f, pupilR)) * 0.95f);
                return mix(c, DARK, smoothstep(0.76f, 0.94f, r) * 0.85f);
            });
            addEllipsoid(eyes, ex - 0.025f, ey + 0.030f, ez + 0.056f, 0.027f, 0.029f, 0.016f, -12f, 10f, 14, 10,
                    (ux, uy, uz) -> SHINE);
            addEllipsoid(eyes, ex + 0.025f, ey - 0.028f, ez + 0.055f, 0.013f, 0.013f, 0.011f, 10f, -10f, 10, 8,
                    (ux, uy, uz) -> SHINE_SOFT);
        }
        eyesMesh = new Mesh(eyes);

        // 7. Контурные двухцветные крылья-ласты (снаружи тёмные, изнутри белоснежные)
        final ArrayList<Float> left = new ArrayList<>();
        addFlipper(left, -1f);
        wingLeftMesh = new Mesh(left);

        final ArrayList<Float> right = new ArrayList<>();
        addFlipper(right, 1f);
        wingRightMesh = new Mesh(right);

        // контактная тень: диск радиуса 1 в плоскости XY, гаснет к краю в шейдере
        final ArrayList<Float> shadow = new ArrayList<>();
        final float[] shadowNormal = {0f, 0f, 1f};
        final int segments = 64;
        for (int i = 0; i < segments; ++i) {
            final double a0 = 2 * Math.PI * i / segments;
            final double a1 = 2 * Math.PI * (i + 1) / segments;
            addVertex(shadow, new float[]{0f, 0f, 0f}, shadowNormal, BLACK);
            addVertex(shadow, new float[]{(float) Math.cos(a0), (float) Math.sin(a0), 0f}, shadowNormal, BLACK);
            addVertex(shadow, new float[]{(float) Math.cos(a1), (float) Math.sin(a1), 0f}, shadowNormal, BLACK);
        }
        shadowMesh = new Mesh(shadow);
    }

    private void addFootToe(ArrayList<Float> out, float tx, float ty, float tz,
                            float rx, float ry, float rz, float yawDeg) {
        final float yawRad = (float) Math.toRadians(yawDeg);
        final float cy = (float) Math.cos(yawRad);
        final float sy = (float) Math.sin(yawRad);
        addParamSurface(out, 16, 10, (u, v, pos) -> {
            final double phi = Math.PI * u;
            final double theta = 2.0 * Math.PI * v;
            final float ux = (float) (Math.sin(phi) * Math.sin(theta));
            final float uy = (float) Math.cos(phi);
            final float uz = (float) (Math.sin(phi) * Math.cos(theta));
            final float lx = ux * rx;
            final float ly = uy * ry * (0.86f + 0.14f * uz);
            final float lz = uz * rz;
            pos[0] = tx + lx * cy + lz * sy;
            pos[1] = ty + ly;
            pos[2] = tz - lx * sy + lz * cy;
        }, (u, v, px, py, pz) -> mix(FOOT_BOT, BEAK_TIP, smoothstep(-0.96f, -0.88f, py) * 0.78f));
    }

    private void addFlipper(ArrayList<Float> out, float sx) {
        final float rxRad = (float) Math.toRadians(5f);
        final float rzRad = (float) Math.toRadians(sx * 13f);
        final float[] local = new float[3];
        addParamSurface(out, 30, 22, (u, v, pos) -> {
            final double phi = Math.PI * u;
            final double theta = 2.0 * Math.PI * v;
            final float ux = (float) (Math.sin(phi) * Math.sin(theta));
            final float uy = (float) Math.cos(phi);
            final float uz = (float) (Math.sin(phi) * Math.cos(theta));
            final float t = 0.5f - 0.5f * uy;
            final float taperZ = 0.82f + 0.26f * (float) Math.sin(t * Math.PI * 0.78)
                    - 0.22f * (float) Math.pow(t, 1.6);
            final float taperX = 1f - 0.30f * (float) Math.pow(t, 1.4);
            final float sweepBack = -0.055f * (float) Math.pow(t, 1.5);
            local[0] = ux * 0.098f * taperX;
            local[1] = uy * 0.405f;
            local[2] = uz * 0.245f * taperZ + sweepBack;
            rotate(local, rxRad, rzRad);
            pos[0] = sx * 0.575f + local[0];
            pos[1] = -0.145f + local[1];
            pos[2] = 0.010f + local[2];
        }, (u, v, px, py, pz) -> {
            final double phi = Math.PI * u;
            final double theta = 2.0 * Math.PI * v;
            final float ux = (float) (Math.sin(phi) * Math.sin(theta));
            final float uz = (float) (Math.sin(phi) * Math.cos(theta));
            final float inner = -sx * ux + uz * 0.22f;
            return mix(BLACK, WHITE, smoothstep(0.16f, 0.44f, inner) * 0.90f);
        });
    }

    // ---------------------------------------------------------------- скины

    private static final float[] RED = {0.855f, 0.176f, 0.200f};
    private static final float[] RED_DARK = {0.680f, 0.115f, 0.145f};
    private static final float[] GOLD = {0.972f, 0.804f, 0.265f};
    private static final float[] PURPLE = {0.439f, 0.282f, 0.780f};
    private static final float[] BLUE = {0.231f, 0.525f, 0.925f};
    private static final float[] BLUE_DARK = {0.155f, 0.385f, 0.765f};
    private static final float[] GRAY = {0.188f, 0.204f, 0.239f};
    private static final float[] LENS = {0.065f, 0.095f, 0.145f};
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
                addConeY(out, 0f, 1.03f, -0.02f, 0.415f, 0.62f, 0.10f, 0.30f, 28, RED);
                addTorus(out, 0f, 1.02f, -0.02f, 0.410f, 0.088f, 1, 30, 12, WHITE);
                addEllipsoid(out, 0.10f, 1.60f, 0.26f, 0.118f, 0.118f, 0.118f, 0f, 0f, 18, 14, (ux, uy, uz) -> WHITE);
                break;
            }
            case 2: { // вязаный двойной шарф с узлом и двумя концами
                addTorus(out, 0f, 0.355f, 0f, 0.448f, 0.086f, 1, 32, 14, BLUE);
                addTorus(out, 0f, 0.285f, 0f, 0.475f, 0.080f, 1, 32, 14, BLUE_DARK);
                addEllipsoid(out, 0.17f, 0.26f, 0.49f, 0.085f, 0.085f, 0.072f, 0f, 0f, 14, 10, (ux, uy, uz) -> BLUE);
                addEllipsoid(out, 0.19f, 0.03f, 0.515f, 0.088f, 0.235f, 0.052f, -12f, -8f, 16, 12, (ux, uy, uz) -> BLUE);
                addEllipsoid(out, 0.08f, 0.07f, 0.530f, 0.074f, 0.180f, 0.048f, -10f, 6f, 14, 10, (ux, uy, uz) -> BLUE_DARK);
                break;
            }
            case 3: { // кепка
                addEllipsoid(out, 0f, 0.90f, 0.01f, 0.535f, 0.335f, 0.510f, 0f, 0f, 32, 22, (ux, uy, uz) -> RED);
                addEllipsoid(out, 0f, 0.92f, 0.53f, 0.345f, 0.036f, 0.285f, -10f, 0f, 22, 10, (ux, uy, uz) -> GRAY);
                addEllipsoid(out, 0f, 1.225f, 0.01f, 0.065f, 0.065f, 0.065f, 0f, 0f, 12, 10, (ux, uy, uz) -> WHITE);
                break;
            }
            case 4: { // очки с оправой и бликующими тёмными линзами
                for (int side = -1; side <= 1; side += 2) {
                    final float sx = side;
                    addEllipsoid(out, sx * 0.195f, 0.826f, 0.448f, 0.152f, 0.110f, 0.042f, 0f, sx * -3f, 22, 14,
                            (ux, uy, uz) -> GRAY);
                    addEllipsoid(out, sx * 0.195f, 0.826f, 0.464f, 0.132f, 0.092f, 0.038f, 0f, sx * -3f, 20, 14,
                            (ux, uy, uz) -> mix(LENS, CYAN, smoothstep(0.25f, 0.85f, uy - ux * 0.35f) * 0.28f));
                    addEllipsoid(out, sx * 0.355f, 0.840f, 0.280f, 0.022f, 0.022f, 0.165f, 0f, 0f, 12, 8,
                            (ux, uy, uz) -> GRAY);
                }
                addEllipsoid(out, 0f, 0.845f, 0.468f, 0.078f, 0.022f, 0.025f, 0f, 0f, 12, 8, (ux, uy, uz) -> GRAY);
                break;
            }
            case 5: { // корона с самоцветами и жемчужинами
                addTorus(out, 0f, 1.045f, 0f, 0.345f, 0.062f, 1, 30, 12, GOLD);
                for (int a = 0; a < 5; ++a) {
                    final double angleRad = 2 * Math.PI * a / 5;
                    final float sx = (float) Math.sin(angleRad);
                    final float sz = (float) Math.cos(angleRad);
                    addConeY(out, sx * 0.33f, 1.055f, sz * 0.33f, 0.082f, 0.21f, 0f, 0f, 14, GOLD);
                    addEllipsoid(out, sx * 0.33f, 1.270f, sz * 0.33f, 0.032f, 0.032f, 0.032f, 0f, 0f, 10, 8,
                            (ux, uy, uz) -> WHITE);
                    final float[] gem = (a % 2 == 0) ? RED : CYAN;
                    addEllipsoid(out, sx * 0.395f, 1.045f, sz * 0.395f, 0.028f, 0.028f, 0.028f, 0f, 0f, 10, 8,
                            (ux, uy, uz) -> gem);
                }
                break;
            }
            case 6: { // наушники
                addTorus(out, 0f, 0.80f, 0f, 0.575f, 0.055f, 2, 28, 12, GRAY);
                addEllipsoid(out, -0.560f, 0.80f, 0f, 0.10f, 0.188f, 0.168f, 0f, 0f, 18, 14, (ux, uy, uz) -> GRAY);
                addEllipsoid(out, 0.560f, 0.80f, 0f, 0.10f, 0.188f, 0.168f, 0f, 0f, 18, 14, (ux, uy, uz) -> GRAY);
                addEllipsoid(out, -0.630f, 0.80f, 0f, 0.045f, 0.145f, 0.125f, 0f, 0f, 14, 10, (ux, uy, uz) -> CYAN);
                addEllipsoid(out, 0.630f, 0.80f, 0f, 0.045f, 0.145f, 0.125f, 0f, 0f, 14, 10, (ux, uy, uz) -> CYAN);
                break;
            }
            case 7: { // атласная бабочка на груди
                addEllipsoid(out, -0.125f, 0.295f, 0.485f, 0.122f, 0.088f, 0.046f, -12f, 18f, 16, 12,
                        (ux, uy, uz) -> mix(RED_DARK, RED, smoothstep(-0.3f, 0.5f, uz)));
                addEllipsoid(out, 0.125f, 0.295f, 0.485f, 0.122f, 0.088f, 0.046f, -12f, -18f, 16, 12,
                        (ux, uy, uz) -> mix(RED_DARK, RED, smoothstep(-0.3f, 0.5f, uz)));
                addEllipsoid(out, 0f, 0.295f, 0.505f, 0.048f, 0.056f, 0.040f, -12f, 0f, 12, 10, (ux, uy, uz) -> GOLD);
                break;
            }
            case 8: { // колпак волшебника
                addConeY(out, 0f, 1.02f, 0f, 0.38f, 0.95f, 0.06f, 0.16f, 26, PURPLE);
                addEllipsoid(out, 0f, 1.01f, 0f, 0.63f, 0.038f, 0.63f, 0f, 0f, 30, 10, (ux, uy, uz) -> PURPLE);
                addTorus(out, 0f, 1.10f, 0f, 0.355f, 0.055f, 1, 26, 10, GOLD);
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

        private final int token;
        /** поток, которому мы наследуем поверхность: дожидаемся его EGL-очистки */
        private final RenderThread previous;

        RenderThread(int token, RenderThread previous) {
            this.token = token;
            this.previous = previous;
        }

        private boolean alive() {
            return token == glToken.get();
        }

        @Override
        public void run() {
            if (previous != null) {
                try {
                    previous.join(500);
                } catch (InterruptedException ignore) {
                    return;
                }
            }
            if (!alive()) {
                return;
            }
            synchronized (glLock) {
                if (previous != null) {
                    // Предыдущий поток уже не владеет токеном и сам EGL не
                    // освободит — добираем его контекст, иначе на каждом
                    // пересоздании поверхности остаётся брошенный контекст.
                    releaseGLLocked();
                }
                if (!alive()) {
                    return;
                }
                try {
                    initGL();
                    initProgram();
                    buildMeshes();
                } catch (Throwable e) {
                    FileLog.e(e);
                    releaseGLLocked();
                    return;
                }
            }

            final int targetFps = Math.max(30, (int) AndroidUtilities.screenRefreshRate);
            final long frameTime = Math.max(1, 1000L / targetFps);
            long last = android.os.SystemClock.elapsedRealtime();

            while (alive()) {
                final long now = android.os.SystemClock.elapsedRealtime();
                final float dt = Math.max(0.001f, (now - last) / 1000f);
                last = now;
                boolean broken = false;
                // кадр тоже под замком: пока мы читаем общие EGL-поля, никто
                // не должен их переинициализировать
                synchronized (glLock) {
                    if (!alive()) {
                        break;
                    }
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
                        broken = true;
                    }
                }
                if (broken) {
                    break;
                }
                try {
                    final long spent = android.os.SystemClock.elapsedRealtime() - now;
                    if (paused) {
                        Thread.sleep(120);
                    } else if (spent < frameTime) {
                        Thread.sleep(frameTime - spent);
                    }
                } catch (InterruptedException ignore) {
                    break;
                }
            }
            synchronized (glLock) {
                if (alive()) {
                    releaseGLLocked();
                }
            }
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
                // без явного EGL_SAMPLES драйвер мог выдать буфер с одним сэмплом —
                // рёбра силуэта оставались ступенчатыми; просим 4x
                EGL10.EGL_SAMPLES, 4,
                EGL10.EGL_NONE
        };
        final int[] configsCount = new int[1];
        final EGLConfig[] configs = new EGLConfig[1];
        if (!egl.eglChooseConfig(eglDisplay, configSpec, configs, 1, configsCount) || configsCount[0] <= 0) {
            // 4x не выдали — пробуем 2x, совсем простые GPU уйдут в simpleSpec
            final int[] msaa2Spec = {
                    EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
                    EGL10.EGL_RED_SIZE, 8,
                    EGL10.EGL_GREEN_SIZE, 8,
                    EGL10.EGL_BLUE_SIZE, 8,
                    EGL10.EGL_ALPHA_SIZE, 8,
                    EGL10.EGL_DEPTH_SIZE, 16,
                    EGL10.EGL_STENCIL_SIZE, 0,
                    EGL10.EGL_SAMPLE_BUFFERS, 1,
                    EGL10.EGL_SAMPLES, 2,
                    EGL10.EGL_NONE
            };
            if (!egl.eglChooseConfig(eglDisplay, msaa2Spec, configs, 1, configsCount) || configsCount[0] <= 0) {
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

    /** вызывать только под glLock */
    private void releaseGLLocked() {
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
