package ben.smith53.utils.async;

import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Async {

    //todo exception handling

    private static final ExecutorService THREAD_POOL = Executors.newCachedThreadPool();

    private static final android.os.Handler THREAD_MAIN = new android.os.Handler(Looper.getMainLooper());

    public static void execute(Runnable async, long timeoutMs) {
        THREAD_POOL.execute(async);
    }

    public static void execute(Runnable async) {
        execute(async, 0);
    }

    public static void executeOnMainThread(Runnable async, long timeoutMs) {
        THREAD_MAIN.post(async);
    }

    public static void executeOnMainThread(Runnable async) {
        executeOnMainThread(async, 0);
    }

}
