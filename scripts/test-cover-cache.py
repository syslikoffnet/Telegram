#!/usr/bin/env python3
"""Exercise the real artwork queue with host-JVM Android stubs (bounded backlog / cancellation)."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "TMessagesProj/src/main/java/org/telegram/messenger/PengramCovers.java"


def put(root, name, text):
    file = root / name
    file.parent.mkdir(parents=True, exist_ok=True)
    file.write_text(text)


with tempfile.TemporaryDirectory(prefix="pengram-cover-test-") as directory:
    root = Path(directory)
    put(root, "android/graphics/Bitmap.java", """package android.graphics;
public class Bitmap {
    public boolean isRecycled() { return false; }
    public int getByteCount() { return 512; }
}""")
    put(root, "android/text/TextUtils.java", """package android.text;
public class TextUtils { public static boolean isEmpty(CharSequence s) { return s == null || s.length() == 0; } }
""")
    put(root, "android/util/LruCache.java", """package android.util;
public class LruCache<K, V> {
    private java.util.Map<K, V> entries = new java.util.HashMap<>();
    public LruCache(int maxSize) {}
    protected int sizeOf(K key, V value) { return 1; }
    public synchronized V get(K key) { return entries.get(key); }
    public synchronized V put(K key, V value) { return entries.put(key, value); }
    public synchronized void evictAll() { entries.clear(); }
}""")
    put(root, "org/telegram/messenger/MessageObject.java", """package org.telegram.messenger;
public class MessageObject {
    public int currentAccount = 0;
    public Object messageOwner = new Object();
    public int id;
    public MessageObject(int id) { this.id = id; }
    public String getFileName() { return "track.mp3"; }
    public long getDialogId() { return 42; }
    public int getId() { return id; }
}""")
    put(root, "org/telegram/messenger/FileLoader.java", """package org.telegram.messenger;
import java.io.File;
public class FileLoader {
    public static File file;
    public static FileLoader getInstance(int account) { return new FileLoader(); }
    public File getPathToMessage(Object message) { return file; }
}""")
    put(root, "org/telegram/messenger/AndroidUtilities.java", """package org.telegram.messenger;
public class AndroidUtilities { public static void runOnUIThread(Runnable r) { r.run(); } }
""")
    put(root, "org/telegram/messenger/FileLog.java", """package org.telegram.messenger;
public class FileLog { public static void e(Throwable e) { throw new AssertionError(e); } }
""")
    put(root, "org/telegram/messenger/audioinfo/AudioInfo.java", """package org.telegram.messenger.audioinfo;
import java.io.File;
import java.util.concurrent.CountDownLatch;
import android.graphics.Bitmap;
public class AudioInfo {
    public static final CountDownLatch gate = new CountDownLatch(1);
    public static final CountDownLatch started = new CountDownLatch(1);
    public static AudioInfo getAudioInfo(File file) throws Exception {
        started.countDown();
        if (!gate.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("decoder blocked");
        return new AudioInfo();
    }
    public Bitmap getCover() { return new Bitmap(); }
    public Bitmap getSmallCover() { return null; }
}""")
    put(root, "TestCoverCache.java", """import java.io.File;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.*;
import org.telegram.messenger.*;
import org.telegram.messenger.audioinfo.AudioInfo;
public class TestCoverCache {
    static void check(boolean good, String reason) { if (!good) throw new AssertionError(reason); }
    public static void main(String[] args) throws Exception {
        FileLoader.file = new File(args[0]);
        for (int i = 0; i < 1000; ++i) PengramCovers.prefetch(new MessageObject(i));
        check(AudioInfo.started.await(5, TimeUnit.SECONDS), "decoder never started");
        Field queue = PengramCovers.class.getDeclaredField("pool");
        queue.setAccessible(true);
        ThreadPoolExecutor pool = (ThreadPoolExecutor) queue.get(null);
        check(pool.getQueue().size() <= 2, "prefetch backlog unbounded");
        Field loading = PengramCovers.class.getDeclaredField("loading");
        loading.setAccessible(true);
        check(((Map<?, ?>)loading.get(null)).size() <= 3, "loading map unbounded");
        PengramCovers.clear();
        check(pool.getQueue().isEmpty(), "queued prefetch not cancelled on trim");
        CountDownLatch received = new CountDownLatch(1);
        MessageObject visible = new MessageObject(0); // same key as cancelled in-flight decode
        for (int i = 0; i < 50; i++) PengramCovers.request(visible, (key, bitmap) -> received.countDown());
        check(((Map<?, ?>)loading.get(null)).size() == 1, "duplicate decoding");
        check(((java.util.List<?>)((Map<?, ?>)loading.get(null)).values().iterator().next()).size() <= 4,
                "stale views retained while cover loads");
        AudioInfo.gate.countDown();
        check(received.await(5, TimeUnit.SECONDS), "new view lost its callback after trim");
        check(PengramCovers.getCached(visible) != null, "visible artwork not cached");
        System.out.println("cover queue: bounded prefetch, cancellation, latest callbacks OK");
    }
}""")
    file = root / "track.mp3"
    file.write_bytes(b"test")
    sources = [str(SOURCE), *map(str, root.rglob("*.java"))]
    subprocess.run(["javac", "-d", str(root), *sources], check=True)
    subprocess.run(["java", "-cp", str(root), "TestCoverCache", str(file)], check=True)
