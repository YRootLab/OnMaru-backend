package com.yrootlab.onmaru.journey.thread;

import com.yrootlab.onmaru.journey.exploration.ExplorationRunOutcome;
import com.yrootlab.onmaru.journey.exploration.ExplorationRunStatus;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

public final class JourneyThreadService {

    private static final long CURSOR_TTL_MILLIS = 10 * 60 * 1000L; // 10 minutes

    private final JourneyThreadStore store;
    private final Clock clock;

    public JourneyThreadService(JourneyThreadStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    public JourneyThread recordOrSync(
            UUID memberId,
            UUID explorationId,
            String title,
            String rawQuery,
            ExplorationRunOutcome outcome,
            ExplorationRunStatus runStatus,
            UUID savedJourneyId,
            int pinnedCount,
            int candidateCount,
            UUID runId) {
        if (memberId == null || explorationId == null) {
            throw new JourneyThreadInputInvalidException("memberId or explorationId is null");
        }

        var now = clock.instant();
        var existing = store.findByExplorationId(explorationId);

        var redaction = QueryRedactor.redact(rawQuery);
        var preview = redaction.redactedText().isBlank() ? null : redaction.redactedText();

        JourneyThread thread;
        if (existing.isPresent()) {
            thread = existing.get().withUpdatedState(
                    title,
                    preview,
                    outcome,
                    runStatus,
                    savedJourneyId,
                    pinnedCount,
                    candidateCount,
                    now);
            store.saveOrUpdate(thread);
        } else {
            thread = new JourneyThread(
                    UUID.randomUUID(),
                    memberId,
                    explorationId,
                    title != null && !title.isBlank() ? title : "여정 탐색",
                    preview,
                    outcome,
                    runStatus,
                    savedJourneyId,
                    pinnedCount,
                    candidateCount,
                    now,
                    now,
                    null);
            store.saveOrUpdate(thread);
        }

        if (rawQuery != null && !rawQuery.isBlank()) {
            var turnMemory = new JourneyTurnMemory(
                    UUID.randomUUID(),
                    thread.threadId(),
                    explorationId,
                    "MEMBER",
                    redaction.redactedText(),
                    redaction.flags(),
                    runId,
                    outcome,
                    now);
            store.recordTurn(turnMemory);
        }

        return thread;
    }

    public JourneyThreadPage list(UUID memberId, int limit, String cursor) {
        if (memberId == null) {
            throw new JourneyThreadInputInvalidException("memberId");
        }
        if (limit < 1 || limit > 50) {
            throw new JourneyThreadInputInvalidException("limit");
        }

        var offset = decodeCursor(cursor, memberId);
        var all = store.list(memberId);

        if (offset > all.size()) {
            throw new JourneyThreadInputInvalidException("cursor");
        }

        var toIndex = Math.min(all.size(), offset + limit);
        var subList = all.subList(offset, toIndex);
        var summaries = subList.stream().map(JourneyThreadSummary::from).toList();

        var hasMore = toIndex < all.size();
        var nextCursor = hasMore ? encodeCursor(memberId, toIndex) : null;

        return new JourneyThreadPage(summaries, nextCursor, hasMore);
    }

    public JourneyThreadDetail get(UUID memberId, UUID threadId) {
        if (memberId == null || threadId == null) {
            throw new JourneyThreadInputInvalidException("memberId or threadId");
        }
        var thread = store.find(memberId, threadId).orElseThrow(JourneyThreadNotFoundException::new);
        var turns = store.listTurns(threadId);
        var snapshotUrl = "/api/v1/explorations/" + thread.explorationId();
        return new JourneyThreadDetail(thread, turns, snapshotUrl);
    }

    public void delete(UUID memberId, UUID threadId) {
        if (memberId == null || threadId == null) {
            throw new JourneyThreadInputInvalidException("memberId or threadId");
        }
        var thread = store.find(memberId, threadId).orElseThrow(JourneyThreadNotFoundException::new);
        store.delete(memberId, thread.threadId(), clock.instant());
    }

    private int decodeCursor(String cursor, UUID currentMemberId) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        try {
            var decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            var parts = decoded.split(":");
            if (parts.length != 4 || !"thread_cursor".equals(parts[0])) {
                // Also accept simple offset format for backward compatibility
                if (decoded.startsWith("offset:")) {
                    var off = Integer.parseInt(decoded.substring("offset:".length()));
                    if (off < 0) {
                        throw new IllegalArgumentException("negative offset");
                    }
                    return off;
                }
                throw new IllegalArgumentException("invalid cursor structure");
            }

            var cursorMemberId = UUID.fromString(parts[1]);
            if (!cursorMemberId.equals(currentMemberId)) {
                throw new JourneyThreadInputInvalidException("cursor");
            }

            var createdAtEpoch = Long.parseLong(parts[2]);
            var nowEpoch = clock.instant().toEpochMilli();
            if (nowEpoch - createdAtEpoch > CURSOR_TTL_MILLIS || createdAtEpoch > nowEpoch + 60_000) {
                throw new JourneyThreadInputInvalidException("cursor");
            }

            var offset = Integer.parseInt(parts[3]);
            if (offset < 0) {
                throw new IllegalArgumentException("negative offset");
            }
            return offset;
        } catch (JourneyThreadInputInvalidException e) {
            throw e;
        } catch (Exception e) {
            throw new JourneyThreadInputInvalidException("cursor");
        }
    }

    private String encodeCursor(UUID memberId, int offset) {
        var nowEpoch = clock.instant().toEpochMilli();
        var raw = "thread_cursor:" + memberId + ":" + nowEpoch + ":" + offset;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}
