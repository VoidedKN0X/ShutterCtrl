package android.util;

/**
 * Test-only stand-in for Android's Pair (the SDK's unit-test jar only has stubs). androidx.exifinterface uses it
 * when writing tags, so the EXIF tests can run on the JVM. Never part of the app.
 */
public class Pair<F, S> {
    public final F first;
    public final S second;

    public Pair(F first, S second) {
        this.first = first;
        this.second = second;
    }

    public static <A, B> Pair<A, B> create(A a, B b) {
        return new Pair<>(a, b);
    }
}
