package io.github.chatglot.client;

public final class ChatMessagePipelineGuard {
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    private ChatMessagePipelineGuard() {
    }

    public static boolean isSuppressed() {
        return DEPTH.get() > 0;
    }

    public static void runSuppressed(Runnable runnable) {
        DEPTH.set(DEPTH.get() + 1);
        try {
            runnable.run();
        } finally {
            int next = DEPTH.get() - 1;
            if (next <= 0) {
                DEPTH.remove();
            } else {
                DEPTH.set(next);
            }
        }
    }
}
