/*
 * (c) Copyright IBM Corp. 2024
 * (c) Copyright Instana Inc.
 */

package com.instana.dc.rdb.impl.informix.metric.collection.strategy;

import com.instana.dc.rdb.impl.informix.OnstatCommandExecutor;
import com.instana.dc.rdb.impl.informix.metric.collection.MetricDataConfig;

import java.util.Optional;

public class CommandExecutorStrategy extends MetricsExecutionStrategy {
    private final OnstatCommandExecutor onstatCommandExecutor;

    protected CommandExecutorStrategy(OnstatCommandExecutor onstatCommandExecutor) {
        this.onstatCommandExecutor = onstatCommandExecutor;
    }

    protected <T> T collectMetrics(MetricDataConfig metricDataConfig) {
        return (T) collectMetricsUsingCMD(metricDataConfig, onstatCommandExecutor);
    }

    private Number collectMetricsUsingCMD(MetricDataConfig metricDataConfig, OnstatCommandExecutor onstatCommandExecutor) {
        if (TypeChecker.isNumber(metricDataConfig.getReturnType())) {
            Optional<String[]> result = onstatCommandExecutor.executeCommand(metricDataConfig.getScriptName());
            if (result.isPresent()) {
                return Integer.parseInt(result.get()[0]); //TODO: Need to verify this
            }
        }
        return null;
    }
}
