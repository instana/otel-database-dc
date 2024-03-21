/*
 * (c) Copyright IBM Corp. 2024
 * (c) Copyright Instana Inc.
 */

package com.instana.dc.rdb.impl.informix.metric.collection;


/**
 * This class which holds the meta data of a given metrics
 */
public class MetricDataConfig {
    private String query;
    private final Class<?> returnType;
    private String metricKey;
    private String scriptName;
    private final MetricCollectionMode selectedMode;
    private String[] attr;

    public MetricDataConfig(String query,
                            MetricCollectionMode mode,
                            Class<?> returnType,
                            String... attr) {
        this.query = query;
        this.returnType = returnType;
        this.selectedMode = mode;
        this.attr = attr;
    }

    public MetricDataConfig(String metricKey,
                            String scriptName,
                            MetricCollectionMode mode,
                            Class<?> returnType,
                            String... attr) {
        this.metricKey = metricKey;
        this.returnType = returnType;
        this.selectedMode = mode;
        this.attr = attr;
        this.scriptName = scriptName;
    }

    public MetricCollectionMode getSelectedMode() {
        return selectedMode;
    }

    public String getScriptName() {
        return scriptName;
    }

    public String getMetricKey() {
        return metricKey;
    }

    public String[] getAttr() {
        return attr;
    }

    public String getQuery() {
        return query;
    }

    public Class<?> getReturnType() {
        return returnType;
    }
}
