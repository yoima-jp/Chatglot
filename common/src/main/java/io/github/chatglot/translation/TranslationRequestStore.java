package io.github.chatglot.translation;

import io.github.chatglot.ChatglotConstants;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;

public final class TranslationRequestStore {
    private final AtomicInteger idSequence = new AtomicInteger(0);
    private final ConcurrentHashMap<Integer, StoredRequest> requests = new ConcurrentHashMap<>();
    private final Deque<Integer> insertionOrder = new ArrayDeque<>();

    public synchronized int register(String originalText) {
        return register(originalText, null, null);
    }

    public synchronized int register(String originalText, MessageSignature signature) {
        return register(originalText, null, signature);
    }

    public synchronized int register(String originalText, Component originalMessage, MessageSignature signature) {
        int id = idSequence.updateAndGet(prev -> prev >= 999_999 ? 1 : prev + 1);
        requests.put(
            id,
            new StoredRequest(
                id,
                originalText,
                originalMessage == null ? null : originalMessage.copy(),
                signature,
                Instant.now().toEpochMilli()
            )
        );
        insertionOrder.addLast(id);

        while (insertionOrder.size() > ChatglotConstants.MAX_STORED_MESSAGE_ACTIONS) {
            Integer oldest = insertionOrder.pollFirst();
            if (oldest != null) {
                requests.remove(oldest);
            }
        }

        return id;
    }

    public Optional<StoredRequest> find(int id) {
        return Optional.ofNullable(requests.get(id));
    }

    public record StoredRequest(
        int id,
        String originalText,
        Component originalMessage,
        MessageSignature signature,
        long createdAtMillis
    ) {
    }
}
