/*
 * Copyright 2015-2018 the original author or authors.
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

import com.datastax.oss.driver.api.core.cql.AsyncResultSet;
import com.datastax.oss.driver.api.core.cql.BoundStatement;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.uuid.Uuids;
import com.google.common.collect.*;
import com.google.common.primitives.Ints;
import com.spotify.futures.CompletableFutures;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.glowroot.central.repo.Common.NeedsRollup;
import org.glowroot.central.repo.Common.NeedsRollupFromChildren;
import org.glowroot.central.util.ClusterManager;
import org.glowroot.central.util.Session;
import org.glowroot.common.util.CaptureTimes;
import org.glowroot.common.util.Clock;
import org.glowroot.common.util.OnlyUsedByTests;
import org.glowroot.common.util.Styles;
import org.glowroot.common2.repo.CassandraProfile;
import org.glowroot.common2.repo.ConfigRepository.RollupConfig;
import org.glowroot.common2.repo.util.Gauges;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.GaugeValueMessage.GaugeValue;
import org.immutables.serial.Serial;
import org.immutables.value.Value;

import java.io.Serializable;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAccumulator;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.util.concurrent.TimeUnit.*;

public class GaugeValueDaoImpl implements GaugeValueDao {

    private final Session session;
    private final ConfigRepositoryImpl configRepository;
    private final ExecutorService asyncExecutor;
    private final Clock clock;

    private final GaugeNameDao gaugeNameDao;

    // index is rollupLevel
    private final ImmutableList<PreparedStatement> insertValuePS;
    private final ImmutableList<PreparedStatement> readValuePS;
    private final ImmutableList<PreparedStatement> readOldestCaptureTimePS;
    private final ImmutableList<PreparedStatement> readValueForRollupPS;
    private final PreparedStatement readValueForRollupFromChildPS;

    private final List<PreparedStatement> insertNeedsRollup;
    private final List<PreparedStatement> readNeedsRollup;
    private final List<PreparedStatement> deleteNeedsRollup;

    private final PreparedStatement insertNeedsRollupFromChild;
    private final PreparedStatement readNeedsRollupFromChild;
    private final PreparedStatement deleteNeedsRollupFromChild;

    // needs rollup caches are only to reduce pressure on the needs rollup tables by reducing
    // duplicate entries
    private final ConcurrentMap<NeedsRollupKey, ImmutableSet<String>> needsRollupCache1;

    GaugeValueDaoImpl(Session session, ConfigRepositoryImpl configRepository,
                      ClusterManager clusterManager, ExecutorService asyncExecutor,
                      int cassandraGcGraceSeconds, Clock clock)
            throws Exception {
        this.session = session;
        this.configRepository = configRepository;
        this.asyncExecutor = asyncExecutor;
        this.clock = clock;

        gaugeNameDao = new GaugeNameDao(session, configRepository, clock);

        int count = configRepository.getRollupConfigs().size();
        List<Integer> rollupExpirationHours = Lists
                .newArrayList(configRepository.getCentralStorageConfig().toCompletableFuture().join().rollupExpirationHours());
        rollupExpirationHours.add(0, rollupExpirationHours.get(0));

        List<PreparedStatement> insertValuePS = new ArrayList<>();
        List<PreparedStatement> readValuePS = new ArrayList<>();
        List<PreparedStatement> readOldestCaptureTimePS = new ArrayList<>();
        List<PreparedStatement> readValueForRollupPS = new ArrayList<>();
        for (int i = 0; i <= count; i++) {
            // name already has "[counter]" suffix when it is a counter
            session.createTableWithTWCS("create table if not exists gauge_value_rollup_" + i
                    + " (agent_rollup varchar, gauge_name varchar, capture_time timestamp, value"
                    + " double, weight bigint, primary key ((agent_rollup, gauge_name),"
                    + " capture_time))", rollupExpirationHours.get(i));
            insertValuePS.add(session.prepare("insert into gauge_value_rollup_" + i
                    + " (agent_rollup, gauge_name, capture_time, value, weight) values (?, ?, ?, ?,"
                    + " ?) using ttl ?"));
            readValuePS.add(session.prepare("select capture_time, value, weight from"
                    + " gauge_value_rollup_" + i + " where agent_rollup = ? and gauge_name = ? and"
                    + " capture_time >= ? and capture_time <= ?"));
            readOldestCaptureTimePS.add(session.prepare("select capture_time from"
                    + " gauge_value_rollup_" + i + " where agent_rollup = ? and gauge_name = ?"
                    + " limit 1"));
            readValueForRollupPS.add(session.prepare("select value, weight from gauge_value_rollup_"
                    + i + " where agent_rollup = ? and gauge_name = ? and capture_time > ? and"
                    + " capture_time <= ?"));
        }
        this.insertValuePS = ImmutableList.copyOf(insertValuePS);
        this.readValuePS = ImmutableList.copyOf(readValuePS);
        this.readOldestCaptureTimePS = ImmutableList.copyOf(readOldestCaptureTimePS);
        this.readValueForRollupPS = ImmutableList.copyOf(readValueForRollupPS);
        this.readValueForRollupFromChildPS = session.prepare("select value, weight from"
                + " gauge_value_rollup_1 where agent_rollup = ? and gauge_name = ? and"
                + " capture_time = ?");

        List<PreparedStatement> insertNeedsRollup = new ArrayList<>();
        List<PreparedStatement> readNeedsRollup = new ArrayList<>();
        List<PreparedStatement> deleteNeedsRollup = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            session.createTableWithLCS("create table if not exists gauge_needs_rollup_" + i
                    + " (agent_rollup varchar, capture_time timestamp, uniqueness timeuuid,"
                    + " gauge_names set<varchar>, primary key (agent_rollup, capture_time,"
                    + " uniqueness)) with gc_grace_seconds = " + cassandraGcGraceSeconds, true);
            insertNeedsRollup.add(session.prepare("insert into gauge_needs_rollup_" + i
                    + " (agent_rollup, capture_time, uniqueness, gauge_names) values (?, ?, ?, ?)"
                    + " using TTL ?"));
            readNeedsRollup.add(session.prepare("select capture_time, uniqueness, gauge_names from"
                    + " gauge_needs_rollup_" + i + " where agent_rollup = ?"));
            deleteNeedsRollup.add(session.prepare("delete from gauge_needs_rollup_" + i + " where"
                    + " agent_rollup = ? and capture_time = ? and uniqueness = ?"));
        }
        this.insertNeedsRollup = insertNeedsRollup;
        this.readNeedsRollup = readNeedsRollup;
        this.deleteNeedsRollup = deleteNeedsRollup;

        session.createTableWithLCS("create table if not exists gauge_needs_rollup_from_child"
                + " (agent_rollup varchar, capture_time timestamp, uniqueness timeuuid,"
                + " child_agent_rollup varchar, gauge_names set<varchar>, primary key"
                + " (agent_rollup, capture_time, uniqueness)) with gc_grace_seconds = "
                + cassandraGcGraceSeconds, true);
        insertNeedsRollupFromChild = session.prepare("insert into gauge_needs_rollup_from_child"
                + " (agent_rollup, capture_time, uniqueness, child_agent_rollup, gauge_names)"
                + " values (?, ?, ?, ?, ?) using TTL ?");
        readNeedsRollupFromChild = session.prepare("select capture_time, uniqueness,"
                + " child_agent_rollup, gauge_names from gauge_needs_rollup_from_child where"
                + " agent_rollup = ?");
        deleteNeedsRollupFromChild = session.prepare("delete from gauge_needs_rollup_from_child"
                + " where agent_rollup = ? and capture_time = ? and uniqueness = ?");

        needsRollupCache1 =
                clusterManager.createReplicatedMap("gaugeNeedsRollupCache1", 5, MINUTES);
    }

    @Override
    public CompletionStage<?> store(String agentId, List<GaugeValue> gaugeValues) {
        return store(agentId, AgentRollupIds.getAgentRollupIds(agentId), gaugeValues);
    }

    public CompletionStage<?> store(String agentId, List<String> agentRollupIdsForMeta,
                                    List<GaugeValue> gaugeValues) {
        if (gaugeValues.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return getTTLs().thenCompose(ttls -> {
            int ttl = ttls.get(0);

            long maxCaptureTime = 0;
            List<CompletionStage<?>> futures = new ArrayList<>();
            for (GaugeValue gaugeValue : gaugeValues) {
                BoundStatement boundStatement = insertValuePS.get(0).bind();
                String gaugeName = gaugeValue.getGaugeName();
                long captureTime = gaugeValue.getCaptureTime();
                maxCaptureTime = Math.max(captureTime, maxCaptureTime);
                int adjustedTTL = Common.getAdjustedTTL(ttl, captureTime, clock);
                int i = 0;
                boundStatement = boundStatement.setString(i++, agentId)
                        .setString(i++, gaugeName)
                        .setInstant(i++, Instant.ofEpochMilli(captureTime))
                        .setDouble(i++, gaugeValue.getValue())
                        .setLong(i++, gaugeValue.getWeight())
                        .setInt(i++, adjustedTTL);
                futures.add(session.writeAsync(boundStatement, CassandraProfile.collector).toCompletableFuture());
                for (String agentRollupIdForMeta : agentRollupIdsForMeta) {
                    futures.add(gaugeNameDao.insert(agentRollupIdForMeta, captureTime, gaugeName));
                }
            }

            // wait for success before inserting "needs rollup" records
            return CompletableFutures.allAsList(futures).thenCompose(ignored -> {
                // insert into gauge_needs_rollup_1
                Map<NeedsRollupKey, ImmutableSet<String>> updatesForNeedsRollupCache1 = new HashMap<>();
                SetMultimap<Long, String> rollupCaptureTimes = getRollupCaptureTimes(gaugeValues);
                for (Map.Entry<Long, Set<String>> entry : Multimaps.asMap(rollupCaptureTimes).entrySet()) {
                    Long captureTime = entry.getKey();
                    Set<String> gaugeNames = entry.getValue();
                    NeedsRollupKey needsRollupKey = ImmutableNeedsRollupKey.of(agentId, captureTime);
                    ImmutableSet<String> needsRollupGaugeNames = needsRollupCache1.get(needsRollupKey);
                    if (needsRollupGaugeNames == null) {
                        // first insert for this key
                        updatesForNeedsRollupCache1.put(needsRollupKey,
                                ImmutableSet.copyOf(gaugeNames));
                    } else if (needsRollupGaugeNames.containsAll(gaugeNames)) {
                        // capture current time after getting data from cache to prevent race condition with
                        // reading the data in Common.getNeedsRollupList()
                        if (!Common.isOldEnoughToRollup(captureTime, clock.currentTimeMillis(),
                                configRepository.getRollupConfigs().get(0).intervalMillis())) {
                            // completely covered by prior inserts that haven't been rolled up yet so no
                            // need to re-insert same data
                            continue;
                        }
                    } else {
                        // merge will maybe help prevent a few subsequent inserts
                        Set<String> combined = new HashSet<>(needsRollupGaugeNames);
                        combined.addAll(gaugeNames);
                        updatesForNeedsRollupCache1.put(needsRollupKey,
                                ImmutableSet.copyOf(gaugeNames));
                    }
                    BoundStatement boundStatement = insertNeedsRollup.get(0).bind();
                    int adjustedTTL = Common.getAdjustedTTL(ttl, captureTime, clock);
                    int needsRollupAdjustedTTL = Common.getNeedsRollupAdjustedTTL(adjustedTTL,
                            configRepository.getRollupConfigs());
                    int i = 0;
                    boundStatement = boundStatement.setString(i++, agentId)
                            .setInstant(i++, Instant.ofEpochMilli(captureTime))
                            .setUuid(i++, Uuids.timeBased())
                            .setSet(i++, gaugeNames, String.class)
                            .setInt(i++, needsRollupAdjustedTTL);
                    futures.add(session.writeAsync(boundStatement, CassandraProfile.collector).toCompletableFuture());
                }
                return CompletableFutures.allAsList(futures).thenAccept(ignore ->
                        // update the cache now that the above inserts were successful
                        needsRollupCache1.putAll(updatesForNeedsRollupCache1)
                );
            });
        });
    }

    @Override
    public CompletionStage<List<Gauge>> getRecentlyActiveGauges(String agentRollupId) {
        long now = clock.currentTimeMillis();
        long from = now - DAYS.toMillis(7);
        return getGauges(agentRollupId, from, now + DAYS.toMillis(365), CassandraProfile.web);
    }

    @Override
    public CompletionStage<List<Gauge>> getGauges(String agentRollupId, long from, long to, CassandraProfile profile) {

        return gaugeNameDao.getGaugeNames(agentRollupId, from, to, profile).thenApply(gaugeNames -> {
            List<Gauge> gauges = new ArrayList<>();
            for (String gaugeName : gaugeNames) {
                gauges.add(Gauges.getGauge(gaugeName));
            }
            return gauges;
        });
    }

    // from is INCLUSIVE
    @Override
    public CompletionStage<List<GaugeValue>> readGaugeValues(String agentRollupId, String gaugeName, long from,
                                                             long to, int rollupLevel, CassandraProfile profile) {
        int i = 0;
        BoundStatement boundStatement = readValuePS.get(rollupLevel).bind()
                .setString(i++, agentRollupId)
                .setString(i++, gaugeName)
                .setInstant(i++, Instant.ofEpochMilli(from))
                .setInstant(i++, Instant.ofEpochMilli(to));
        List<GaugeValue> gaugeValues = new ArrayList<>();
        Function<AsyncResultSet, CompletableFuture<List<GaugeValue>>> compute = new Function<AsyncResultSet, CompletableFuture<List<GaugeValue>>>() {
            @Override
            public CompletableFuture<List<GaugeValue>> apply(AsyncResultSet results) {
                for (Row row : results.currentPage()) {
                    int i = 0;
                    gaugeValues.add(GaugeValue.newBuilder()
                            .setCaptureTime(checkNotNull(row.getInstant(i++)).toEpochMilli())
                            .setValue(row.getDouble(i++))
                            .setWeight(row.getLong(i++))
                            .build());
                }
                if (results.hasMorePages()) {
                    return results.fetchNextPage().thenCompose(this::apply).toCompletableFuture();
                }
                return CompletableFuture.completedFuture(gaugeValues);
            }
        };
        return session.readAsync(boundStatement, profile).thenCompose(compute);
    }

    @Override
    public CompletionStage<Long> getOldestCaptureTime(String agentRollupId, String gaugeName, int rollupLevel, CassandraProfile profile) {
        int i = 0;
        BoundStatement boundStatement = readOldestCaptureTimePS.get(rollupLevel).bind()
                .setString(i++, agentRollupId)
                .setString(i++, gaugeName);
        return session.readAsync(boundStatement, profile).thenApply(results -> {
            Row row = results.one();
            return row == null ? Long.MAX_VALUE : checkNotNull(row.getInstant(0)).toEpochMilli();
        });
    }

    @Override
    public CompletionStage<?> rollup(String agentRollupId) {
        return rollup(agentRollupId, AgentRollupIds.getParent(agentRollupId),
                !agentRollupId.endsWith("::"), CassandraProfile.rollup);
    }

    // there is no rollup from children on 5-second gauge values
    //
    // child agent rollups should be processed before their parent agent rollup, since initial
    // parent rollup depends on the 1-minute child rollup
    public CompletionStage<?> rollup(String agentRollupId, @Nullable String parentAgentRollupId, boolean leaf, CassandraProfile profile) {

        return getTTLs().thenCompose(ttls -> {
            int rollupLevel;
            CompletionStage<?> starting = CompletableFuture.completedFuture(null);
            if (leaf) {
                rollupLevel = 1;
            } else {
                starting = rollupFromChildren(agentRollupId, parentAgentRollupId, ttls.get(1), profile);
                rollupLevel = 2;
            }

            Function<Integer, CompletionStage<Integer>> lambda = new Function<>() {
                @Override
                public CompletionStage<Integer> apply(Integer rollupLevelInner) {
                    if (rollupLevelInner <= configRepository.getRollupConfigs().size()) {
                        return rollup(agentRollupId, parentAgentRollupId, rollupLevelInner, ttls.get(rollupLevelInner), profile)
                                .thenApply(ignored -> rollupLevelInner + 1)
                                .thenCompose(this);
                    } else {
                        return CompletableFuture.completedFuture(null);
                    }
                }
            };
            return starting.thenApply(ignored -> rollupLevel).thenCompose(lambda);
        });
    }

    private SetMultimap<Long, String> getRollupCaptureTimes(List<GaugeValue> gaugeValues) {
        SetMultimap<Long, String> rollupCaptureTimes = HashMultimap.create();
        List<RollupConfig> rollupConfigs = configRepository.getRollupConfigs();
        for (GaugeValue gaugeValue : gaugeValues) {
            String gaugeName = gaugeValue.getGaugeName();
            long captureTime = gaugeValue.getCaptureTime();
            long intervalMillis = rollupConfigs.get(0).intervalMillis();
            long rollupCaptureTime = CaptureTimes.getRollup(captureTime, intervalMillis);
            rollupCaptureTimes.put(rollupCaptureTime, gaugeName);
        }
        return rollupCaptureTimes;
    }

    private CompletionStage<?> rollupFromChildren(String agentRollupId, @Nullable String parentAgentRollupId,
                                                  int ttl, CassandraProfile profile) {
        final int rollupLevel = 1;
        return Common.getNeedsRollupFromChildrenList(agentRollupId, readNeedsRollupFromChild, session, profile).thenCompose(needsRollupFromChildrenList -> {

            List<RollupConfig> rollupConfigs = configRepository.getRollupConfigs();
            long nextRollupIntervalMillis = rollupConfigs.get(rollupLevel).intervalMillis();

            int maxIndexNeedsRollupFromChildren = needsRollupFromChildrenList.size();
            Function<Integer, CompletionStage<?>> lambda = new Function<Integer, CompletionStage<?>>() {
                @Override
                public CompletionStage<?> apply(Integer indexNeedsRollupFromChildren) {
                    if (indexNeedsRollupFromChildren >= maxIndexNeedsRollupFromChildren) {
                        return CompletableFuture.completedFuture(null);
                    }
                    NeedsRollupFromChildren needsRollupFromChildren = needsRollupFromChildrenList.get(indexNeedsRollupFromChildren);
                    long captureTime = needsRollupFromChildren.getCaptureTime();
                    int adjustedTTL = Common.getAdjustedTTL(ttl, captureTime, clock);

                    List<CompletableFuture<?>> futures = new ArrayList<>();
                    for (Map.Entry<String, Collection<String>> entry : needsRollupFromChildren.getKeys()
                            .asMap()
                            .entrySet()) {
                        String gaugeName = entry.getKey();
                        Collection<String> childAgentRollupIds = entry.getValue();
                        futures.add(rollupOneFromChildren(rollupLevel, agentRollupId, gaugeName,
                                childAgentRollupIds, captureTime, adjustedTTL, profile));
                    }
                    return CompletableFutures.allAsList(futures).thenCompose(ignored -> {
                        int needsRollupAdjustedTTL =
                                Common.getNeedsRollupAdjustedTTL(adjustedTTL, rollupConfigs);

                        CompletionStage<?> starting = CompletableFuture.completedFuture(null);
                        if (parentAgentRollupId != null) {
                            // insert needs to happen first before call to postRollup(), see method-level
                            // comment on postRollup
                            starting = Common.insertNeedsRollupFromChild(agentRollupId, parentAgentRollupId,
                                    insertNeedsRollupFromChild, needsRollupFromChildren, captureTime,
                                    needsRollupAdjustedTTL, session, profile);
                        }
                        return starting.thenCompose(ignore -> Common.postRollup(agentRollupId, needsRollupFromChildren.getCaptureTime(),
                                needsRollupFromChildren.getKeys().keySet(),
                                needsRollupFromChildren.getUniquenessKeysForDeletion(),
                                nextRollupIntervalMillis, insertNeedsRollup.get(rollupLevel),
                                deleteNeedsRollupFromChild, needsRollupAdjustedTTL, session, profile));

                    }).thenCompose(ignored -> apply(indexNeedsRollupFromChildren + 1));

                }
            };

            return lambda.apply(0);
        });
    }

    private CompletionStage<?> rollup(String agentRollupId, @Nullable String parentAgentRollupId, int rollupLevel,
                                      int ttl, CassandraProfile profile) {
        List<RollupConfig> rollupConfigs = configRepository.getRollupConfigs();
        long rollupIntervalMillis = rollupConfigs.get(rollupLevel - 1).intervalMillis();
        return Common.getNeedsRollupList(agentRollupId,
                rollupLevel, rollupIntervalMillis, readNeedsRollup, session, clock, profile).thenCompose(needsRollupCollection -> {
            List<NeedsRollup> needsRollupList = ImmutableList.copyOf(needsRollupCollection);
            Long nextRollupIntervalMillis = null;
            if (rollupLevel < rollupConfigs.size()) {
                nextRollupIntervalMillis = rollupConfigs.get(rollupLevel).intervalMillis();
            }
            final Long finalNextRollupIntervalMillis = nextRollupIntervalMillis;
            int maxIndexNeedsRollup = needsRollupList.size();

            Function<Integer, CompletionStage<?>> lambda = new Function<Integer, CompletionStage<?>>() {
                @Override
                public CompletionStage<?> apply(Integer indexNeedsRollup) {
                    if (indexNeedsRollup >= maxIndexNeedsRollup) {
                        return CompletableFuture.completedFuture(null);
                    }
                    final NeedsRollup needsRollup = needsRollupList.get(indexNeedsRollup);
                    long captureTime = needsRollup.getCaptureTime();
                    long from = captureTime - rollupIntervalMillis;
                    int adjustedTTL = Common.getAdjustedTTL(ttl, captureTime, clock);
                    Set<String> gaugeNames = needsRollup.getKeys();
                    List<CompletableFuture<?>> futures = new ArrayList<>();
                    for (String gaugeName : gaugeNames) {
                        futures.add(rollupOne(rollupLevel, agentRollupId, gaugeName, from, captureTime,
                                adjustedTTL, profile));
                    }
                    if (futures.isEmpty()) {
                        // no rollups occurred, warning already logged inside rollupOne() above
                        // this can happen there is an old "needs rollup" record that was created prior to
                        // TTL was introduced in 0.9.6, and when the "last needs rollup" record wasn't
                        // processed (also prior to 0.9.6), and when the corresponding old data has expired
                        return Common.postRollup(agentRollupId, needsRollup.getCaptureTime(), gaugeNames,
                                needsRollup.getUniquenessKeysForDeletion(), null, null,
                                deleteNeedsRollup.get(rollupLevel - 1), -1, session, profile).thenApply(ignored -> indexNeedsRollup + 1);
                    }
                    return CompletableFutures.allAsList(futures).thenCompose(ignored -> {
                        int needsRollupAdjustedTTL =
                                Common.getNeedsRollupAdjustedTTL(adjustedTTL, rollupConfigs);
                        CompletionStage<?> starting = CompletableFuture.completedFuture(null);
                        if (rollupLevel == 1 && parentAgentRollupId != null) {
                            // insert needs to happen first before call to postRollup(), see method-level
                            // comment on postRollup
                            int i = 0;
                            BoundStatement boundStatement = insertNeedsRollupFromChild.bind()
                                    .setString(i++, parentAgentRollupId)
                                    .setInstant(i++, Instant.ofEpochMilli(captureTime))
                                    .setUuid(i++, Uuids.timeBased())
                                    .setString(i++, agentRollupId)
                                    .setSet(i++, gaugeNames, String.class)
                                    .setInt(i++, needsRollupAdjustedTTL);
                            starting = session.writeAsync(boundStatement, CassandraProfile.collector);
                        }
                        PreparedStatement insertNeedsRollup = finalNextRollupIntervalMillis == null ? null
                                : GaugeValueDaoImpl.this.insertNeedsRollup.get(rollupLevel);
                        PreparedStatement deleteNeedsRollup = GaugeValueDaoImpl.this.deleteNeedsRollup.get(rollupLevel - 1);
                        return starting.thenCompose(ignored2 -> Common.postRollup(agentRollupId, needsRollup.getCaptureTime(), gaugeNames,
                                needsRollup.getUniquenessKeysForDeletion(), finalNextRollupIntervalMillis,
                                insertNeedsRollup, deleteNeedsRollup, needsRollupAdjustedTTL, session, profile)).thenCompose(ignored3 -> apply(indexNeedsRollup + 1));
                    });
                }
            };
            return lambda.apply(0);
        });
    }

    private CompletableFuture<?> rollupOneFromChildren(int rollupLevel, String agentRollupId,
                                                       String gaugeName, Collection<String> childAgentRollupIds, long captureTime,
                                                       int adjustedTTL, CassandraProfile profile) {
        List<CompletableFuture<AsyncResultSet>> futures = new ArrayList<>();
        for (String childAgentRollupId : childAgentRollupIds) {
            int i = 0;
            BoundStatement boundStatement = readValueForRollupFromChildPS.bind()
                    .setString(i++, childAgentRollupId)
                    .setString(i++, gaugeName)
                    .setInstant(i++, Instant.ofEpochMilli(captureTime));
            futures.add(session.readAsyncWarnIfNoRows(boundStatement, profile, "no gauge value table"
                            + " records found for agentRollupId={}, gaugeName={}, captureTime={}, level={}",
                    childAgentRollupId, gaugeName, captureTime, rollupLevel).toCompletableFuture());
        }
        return CompletableFutures.allAsList(futures).thenCompose(rows -> {
            return rollupOneFromRows(rollupLevel, agentRollupId, gaugeName, captureTime,
                    adjustedTTL, rows, profile);
        });
    }

    // from is non-inclusive
    private CompletableFuture<?> rollupOne(int rollupLevel, String agentRollupId,
                                           String gaugeName, long from, long to, int adjustedTTL, CassandraProfile profile) {
        int i = 0;
        BoundStatement boundStatement = readValueForRollupPS.get(rollupLevel - 1).bind()
                .setString(i++, agentRollupId)
                .setString(i++, gaugeName)
                .setInstant(i++, Instant.ofEpochMilli(from))
                .setInstant(i++, Instant.ofEpochMilli(to));
        CompletableFuture<AsyncResultSet> future = session.readAsyncWarnIfNoRows(boundStatement, profile,
                "no gauge value table records found for agentRollupId={}, gaugeName={}, from={},"
                        + " to={}, level={}",
                agentRollupId, gaugeName, from, to, rollupLevel).toCompletableFuture();
        return future.thenCompose(rows -> {
            return rollupOneFromRows(rollupLevel, agentRollupId, gaugeName, to, adjustedTTL,
                    Lists.newArrayList(rows), profile);
        });
    }

    private CompletionStage<?> rollupOneFromRows(int rollupLevel, String agentRollupId,
                                                 String gaugeName, long to, int adjustedTTL, List<AsyncResultSet> results, CassandraProfile profile) {
        DoubleAccumulator totalWeightedValue = new DoubleAccumulator(Double::sum, 0.0);
        AtomicLong totalWeight = new AtomicLong(0);

        Function<AsyncResultSet, CompletableFuture<?>> compute = new Function<AsyncResultSet, CompletableFuture<?>>() {
            @Override
            public CompletableFuture<?> apply(AsyncResultSet asyncResultSet) {
                for (Row row : asyncResultSet.currentPage()) {
                    double value = row.getDouble(0);
                    long weight = row.getLong(1);
                    totalWeightedValue.accumulate(value * weight);
                    totalWeight.addAndGet(weight);
                }
                if (asyncResultSet.hasMorePages()) {
                    return asyncResultSet.fetchNextPage().thenCompose(this::apply).toCompletableFuture();
                }
                return CompletableFuture.completedFuture(null);
            }
        };
        return CompletableFutures.allAsList(results.stream().map(compute::apply).collect(Collectors.toList()))
                .thenCompose(ignored -> {
                    int i = 0;
                    BoundStatement boundStatement = insertValuePS.get(rollupLevel).bind()
                            .setString(i++, agentRollupId)
                            .setString(i++, gaugeName)
                            .setInstant(i++, Instant.ofEpochMilli(to));
                    // individual gauge value weights cannot be zero, and rows is non-empty
                    // (see callers of this method), so totalWeight is guaranteed non-zero
                    checkState(totalWeight.get() != 0);
                    boundStatement = boundStatement.setDouble(i++, totalWeightedValue.get() / totalWeight.get())
                            .setLong(i++, totalWeight.get())
                            .setInt(i++, adjustedTTL);
                    return session.writeAsync(boundStatement, profile);
                });
    }

    private CompletionStage<List<Integer>> getTTLs() {
        return configRepository.getCentralStorageConfig().thenApply(centralStorageConfig -> {
            List<Integer> rollupExpirationHours = Lists
                    .newArrayList(centralStorageConfig.rollupExpirationHours());
            rollupExpirationHours.add(0, rollupExpirationHours.get(0));
            List<Integer> ttls = new ArrayList<>();
            for (long expirationHours : rollupExpirationHours) {
                ttls.add(Ints.saturatedCast(HOURS.toSeconds(expirationHours)));
            }
            return ttls;
        });
    }

    @Override
    @OnlyUsedByTests
    public void truncateAll() throws Exception {
        for (int i = 0; i <= configRepository.getRollupConfigs().size(); i++) {
            session.updateSchemaWithRetry("truncate gauge_value_rollup_" + i);
        }
        for (int i = 1; i <= configRepository.getRollupConfigs().size(); i++) {
            session.updateSchemaWithRetry("truncate gauge_needs_rollup_" + i);
        }
        session.updateSchemaWithRetry("truncate gauge_name");
        session.updateSchemaWithRetry("truncate gauge_needs_rollup_from_child");
    }

    @Value.Immutable
    @Serial.Structural
    @Styles.AllParameters
    interface NeedsRollupKey extends Serializable {
        String agentRollupId();

        long captureTime();
    }
}
