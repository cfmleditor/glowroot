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
package org.glowroot.common2.repo;

import java.util.List;
import java.util.concurrent.CompletionStage;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.immutables.value.Value;

import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertCondition;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertNotification;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertSeverity;

public interface IncidentRepository {

    CompletionStage<?> insertOpenIncident(String agentRollupId, AlertCondition condition, AlertSeverity severity,
                                          AlertNotification notification, long openTime, CassandraProfile profile);


    CompletionStage<OpenIncident> readOpenIncident(String agentRollupId, AlertCondition condition,
            AlertSeverity severity, CassandraProfile profile);

    CompletionStage<List<OpenIncident>> readOpenIncidents(String agentRollupId, CassandraProfile profile);

    // this is used by UI
    CompletionStage<List<OpenIncident>> readAllOpenIncidents(CassandraProfile profile);

    CompletionStage<?> resolveIncident(OpenIncident openIncident, long resolveTime, CassandraProfile profile);

    CompletionStage<List<ResolvedIncident>> readResolvedIncidents(long from);

    @Value.Immutable
    interface OpenIncident {
        String agentRollupId();
        long openTime();
        AlertCondition condition();
        AlertSeverity severity();
        AlertNotification notification();
    }

    @Value.Immutable
    interface ResolvedIncident {
        String agentRollupId();
        long openTime();
        long resolveTime();
        AlertCondition condition();
        AlertSeverity severity();
        AlertNotification notification();
    }
}
