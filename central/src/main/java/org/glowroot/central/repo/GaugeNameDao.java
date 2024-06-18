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

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

import com.datastax.oss.driver.api.core.cql.*;
import com.google.common.collect.ImmutableList;
import org.glowroot.common2.repo.CassandraProfile;
import org.immutables.value.Value;

import org.glowroot.central.util.Session;
import org.glowroot.common.util.CaptureTimes;
import org.glowroot.common.util.Clock;
import org.glowroot.common.util.Styles;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.DAYS;

class GaugeNameDao {

    private final Session session;
    private final ConfigRepositoryImpl configRepository;
    private final Clock clock;

    private final PreparedStatement insertPS;
    private final PreparedStatement readPS;

    GaugeNameDao(Session session, ConfigRepositoryImpl configRepository, Clock clock)
            throws Exception {
        this.session = session;
        this.configRepository = configRepository;
        this.clock = clock;

        int maxRollupHours = configRepository.getCentralStorageConfig().toCompletableFuture().join().getMaxRollupHours();
        session.createTableWithTWCS("create table if not exists gauge_name (agent_rollup_id"
                + " varchar, capture_time timestamp, gauge_name varchar, primary key"
                + " (agent_rollup_id, capture_time, gauge_name))", maxRollupHours);

        insertPS = session.prepare("insert into gauge_name (agent_rollup_id, capture_time,"
                + " gauge_name) values (?, ?, ?) using ttl ?");
        readPS = session.prepare("select gauge_name from gauge_name where agent_rollup_id = ? and"
                + " capture_time >= ? and capture_time <= ?");
    }

    CompletionStage<Set<String>> getGaugeNames(String agentRollupId, long from, long to, CassandraProfile profile) {
        long rolledUpFrom = CaptureTimes.getRollup(from, DAYS.toMillis(1));
        long rolledUpTo = CaptureTimes.getRollup(to, DAYS.toMillis(1));
        BoundStatement boundStatement = readPS.bind()
            .setString(0, agentRollupId)
            .setInstant(1, Instant.ofEpochMilli(rolledUpFrom))
            .setInstant(2, Instant.ofEpochMilli(rolledUpTo));
        Set<String> gaugeNames = new HashSet<>();
        Function<AsyncResultSet, CompletableFuture<Set<String>>> compute = new Function<>() {
            @Override
            public CompletableFuture<Set<String>> apply(AsyncResultSet results) {
                for (Row row : results.currentPage()) {
                    gaugeNames.add(checkNotNull(row.getString(0)));
                }
                if (results.hasMorePages()) {
                    return results.fetchNextPage().thenCompose(this::apply).toCompletableFuture();
                }
                return CompletableFuture.completedFuture(gaugeNames);
            }
        };

        return session.readAsync(boundStatement, profile).thenCompose(compute);
    }

    CompletionStage<?> insert(String agentRollupId, long captureTime, String gaugeName) {
        long rollupCaptureTime = CaptureTimes.getRollup(captureTime, DAYS.toMillis(1));
        return configRepository.getCentralStorageConfig().thenCompose(centralStorageConfig -> {
            int maxRollupTTL = centralStorageConfig.getMaxRollupTTL();
            int i = 0;
            BoundStatement boundStatement = insertPS.bind()
                    .setString(i++, agentRollupId)
                    .setInstant(i++, Instant.ofEpochMilli(rollupCaptureTime))
                    .setString(i++, gaugeName)
                    .setInt(i++, Common.getAdjustedTTL(maxRollupTTL, rollupCaptureTime, clock));
            return session.writeAsync(boundStatement, CassandraProfile.collector);
        });
    }

    @Value.Immutable
    @Styles.AllParameters
    interface GaugeKey {
        String agentRollupId();
        long captureTime();
        String gaugeName();
    }
}
