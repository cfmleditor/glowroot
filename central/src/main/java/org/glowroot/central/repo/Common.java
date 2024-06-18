/*
 * Copyright 2017-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glowroot.central.repo;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

import com.datastax.oss.driver.api.core.cql.*;
import com.datastax.oss.driver.api.core.uuid.Uuids;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multimap;
import com.google.common.primitives.Ints;
import org.checkerframework.checker.nullness.qual.Nullable;

import org.glowroot.central.util.MoreFutures;
import org.glowroot.central.util.Session;
import org.glowroot.common.util.CaptureTimes;
import org.glowroot.common.util.Clock;
import org.glowroot.common2.repo.CassandraProfile;
import org.glowroot.common2.repo.ConfigRepository.RollupConfig;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

class Common {

    private Common() {}

    static int getAdjustedTTL(int ttl, long captureTime, Clock clock) {
        if (ttl == 0) {
            return 0;
        }
        long captureTimeAgoSeconds =
                MILLISECONDS.toSeconds(clock.currentTimeMillis() - captureTime);
        // need saturated cast because captureTimeAgoSeconds may be negative
        int adjustedTTL = Ints.saturatedCast(ttl - captureTimeAgoSeconds);
        // max is a safety guard
        return Math.max(adjustedTTL, 60);
    }

    static int getNeedsRollupAdjustedTTL(int adjustedTTL, List<RollupConfig> rollupConfigs) {
        if (adjustedTTL == 0) {
            return 0;
        }
        long maxRollupInterval = Iterables.getLast(rollupConfigs).intervalMillis();
        // reduced by an extra 1 hour to make sure that once needs rollup record is retrieved,
        // there is plenty of time to read the all of the data records in the interval before they
        // expire (reading partially expired interval can lead to non-idempotent rollups)
        int needsRollupAdjustedTTL =
                adjustedTTL - Ints.saturatedCast(MILLISECONDS.toSeconds(maxRollupInterval)) - 3600;
        // max is a safety guard
        return Math.max(needsRollupAdjustedTTL, 60);
    }

    static CompletionStage<Collection<NeedsRollup>> getNeedsRollupList(String agentRollupId, int rollupLevel,
                                                                       long rollupIntervalMillis, List<PreparedStatement> readNeedsRollup, Session session,
                                                                       Clock clock, CassandraProfile profile) {
        // capture current time before reading data to prevent race condition with optimization
        // that prevents duplicate needs rollup data which is also based on current time
        long currentTimeMillis = clock.currentTimeMillis();
        BoundStatement boundStatement = readNeedsRollup.get(rollupLevel - 1).bind()
            .setString(0, agentRollupId);
        Map<Long, NeedsRollup> needsRollupMap = new LinkedHashMap<>();
        Function<AsyncResultSet, CompletableFuture<Map<Long, NeedsRollup>>> compute = new Function<AsyncResultSet, CompletableFuture<Map<Long, NeedsRollup>>>() {
            @Override
            public CompletableFuture<Map<Long, NeedsRollup>> apply(AsyncResultSet results) {
                for (Row row : results.currentPage()) {
                    int i = 0;
                    long captureTime = checkNotNull(row.getInstant(i++)).toEpochMilli();
                    if (!isOldEnoughToRollup(captureTime, currentTimeMillis, rollupIntervalMillis)) {
                        // normally, the last "needs rollup" capture time is in the near future, so don't
                        // roll it up since it is likely still being added to
                        //
                        // this is mostly to avoid rolling up this data twice, but also currently the UI
                        // assumes when it finds rolled up data, it doesn't check for non-rolled up data for
                        // same interval
                        //
                        // and now another reason: optimization for gauge_needs_rollup_1 relies on it being
                        // safe to not re-insert the same data up until rollupIntervalMillis after the
                        // rollup capture time
                        //
                        // safe to "break" instead of just "continue" since results are ordered by
                        // capture_time
                        break;
                    }
                    UUID uniqueness = row.getUuid(i++);
                    Set<String> keys = checkNotNull(row.getSet(i++, String.class));
                    NeedsRollup needsRollup = needsRollupMap.get(captureTime);
                    if (needsRollup == null) {
                        needsRollup = new NeedsRollup(captureTime);
                        needsRollupMap.put(captureTime, needsRollup);
                    }
                    needsRollup.keys.addAll(keys);
                    needsRollup.uniquenessKeysForDeletion.add(uniqueness);
                }
                if (results.hasMorePages()) {
                    return results.fetchNextPage().thenCompose(this::apply).toCompletableFuture();
                }
                return CompletableFuture.completedFuture(needsRollupMap);
            }
        };
        return session.readAsync(boundStatement, profile)
                .thenCompose(compute).thenApply(Map::values);
    }

    static CompletionStage<List<NeedsRollupFromChildren>> getNeedsRollupFromChildrenList(
            String agentRollupId,
            PreparedStatement readNeedsRollupFromChild, Session session, CassandraProfile profile) {
        BoundStatement boundStatement = readNeedsRollupFromChild.bind()
            .setString(0, agentRollupId);
        Map<Long, NeedsRollupFromChildren> needsRollupFromChildrenMap = new LinkedHashMap<>();
        Function<AsyncResultSet, CompletableFuture<Map<Long, NeedsRollupFromChildren>>> compute = new Function<AsyncResultSet, CompletableFuture<Map<Long, NeedsRollupFromChildren>>>() {
            @Override
            public CompletableFuture<Map<Long, NeedsRollupFromChildren>> apply(AsyncResultSet results) {
                for (Row row : results.currentPage()) {
                    int i = 0;
                    long captureTime = checkNotNull(row.getInstant(i++)).toEpochMilli();
                    UUID uniqueness = row.getUuid(i++);
                    String childAgentRollupId = checkNotNull(row.getString(i++));
                    Set<String> keys = checkNotNull(row.getSet(i++, String.class));
                    NeedsRollupFromChildren needsRollup = needsRollupFromChildrenMap.get(captureTime);
                    if (needsRollup == null) {
                        needsRollup = new NeedsRollupFromChildren(captureTime);
                        needsRollupFromChildrenMap.put(captureTime, needsRollup);
                    }
                    for (String key : keys) {
                        needsRollup.keys.put(key, childAgentRollupId);
                    }
                    needsRollup.uniquenessKeysForDeletion.add(uniqueness);
                }
                if (results.hasMorePages()) {
                    return results.fetchNextPage().thenCompose(this::apply).toCompletableFuture();
                }
                return CompletableFuture.completedFuture(needsRollupFromChildrenMap);
            }
        };
        return session.readAsync(boundStatement, profile).thenCompose(compute).thenApply(map -> ImmutableList.copyOf(map.values()));
    }

    static CompletionStage<?> insertNeedsRollupFromChild(String agentRollupId, String parentAgentRollupId,
            PreparedStatement insertNeedsRollupFromChild,
            NeedsRollupFromChildren needsRollupFromChildren, long captureTime,
            int needsRollupAdjustedTTL, Session session, CassandraProfile profile) {
        int i = 0;
        BoundStatement boundStatement = insertNeedsRollupFromChild.bind()
            .setString(i++, parentAgentRollupId)
            .setInstant(i++, Instant.ofEpochMilli(captureTime))
            .setUuid(i++, Uuids.timeBased())
            .setString(i++, agentRollupId)
            .setSet(i++, needsRollupFromChildren.getKeys().keySet(), String.class)
            .setInt(i++, needsRollupAdjustedTTL);
        return session.writeAsync(boundStatement, profile);
    }

    // it is important that the insert into next needs_rollup happens after present
    // rollup and before deleting present rollup
    // if insert before present rollup then possible for the next rollup to occur before
    // present rollup has completed
    // if insert after deleting present rollup then possible for error to occur in between
    // and insert would never happen
    static CompletionStage<?> postRollup(String agentRollupId, long captureTime, Set<String> keys,
            Set<UUID> uniquenessKeysForDeletion, @Nullable Long nextRollupIntervalMillis,
            @Nullable PreparedStatement insertNeedsRollup, PreparedStatement deleteNeedsRollup,
            int needsRollupAdjustedTTL, Session session, CassandraProfile profile) {
        CompletionStage<?> starting = CompletableFuture.completedFuture(null);
        if (nextRollupIntervalMillis != null) {
            checkNotNull(insertNeedsRollup);
            long rollupCaptureTime = CaptureTimes.getRollup(captureTime,
                    nextRollupIntervalMillis);
            int i = 0;
            BoundStatement boundStatement = insertNeedsRollup.bind()
                .setString(i++, agentRollupId)
                .setInstant(i++, Instant.ofEpochMilli(rollupCaptureTime))
                .setUuid(i++, Uuids.timeBased())
                .setSet(i++, keys, String.class)
                .setInt(i++, needsRollupAdjustedTTL);
            // intentionally not async, see method-level comment
            starting = session.writeAsync(boundStatement, profile);
        }
        return starting.thenCompose(ignored -> {
            List<CompletableFuture<?>> futures = new ArrayList<>();
            for (UUID uniqueness : uniquenessKeysForDeletion) {
                int i = 0;
                BoundStatement boundStatement = deleteNeedsRollup.bind()
                    .setString(i++, agentRollupId)
                    .setInstant(i++, Instant.ofEpochMilli(captureTime))
                    .setUuid(i++, uniqueness);
                futures.add(session.writeAsync(boundStatement, profile).toCompletableFuture());
            }
            return CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0]));
        });
    }

    static boolean isOldEnoughToRollup(long captureTime, long currentTimeMillis,
            long intervalMillis) {
        return captureTime < currentTimeMillis - intervalMillis;
    }

    static class NeedsRollup {

        private final long captureTime;
        private final Set<String> keys = new HashSet<>(); // transaction types or gauge names
        private final Set<UUID> uniquenessKeysForDeletion = new HashSet<>();

        private NeedsRollup(long captureTime) {
            this.captureTime = captureTime;
        }

        long getCaptureTime() {
            return captureTime;
        }

        Set<String> getKeys() {
            return keys;
        }

        Set<UUID> getUniquenessKeysForDeletion() {
            return uniquenessKeysForDeletion;
        }
    }

    static class NeedsRollupFromChildren {

        private final long captureTime;
        // map keys are transaction types or gauge names
        // map values are childAgentRollupIds
        private final Multimap<String, String> keys = HashMultimap.create();
        private final Set<UUID> uniquenessKeysForDeletion = new HashSet<>();

        private NeedsRollupFromChildren(long captureTime) {
            this.captureTime = captureTime;
        }

        long getCaptureTime() {
            return captureTime;
        }

        Multimap<String, String> getKeys() {
            return keys;
        }

        Set<UUID> getUniquenessKeysForDeletion() {
            return uniquenessKeysForDeletion;
        }
    }
}
