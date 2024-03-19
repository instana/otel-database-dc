/*
 * (c) Copyright IBM Corp. 2024
 * (c) Copyright Instana Inc.
 */
package com.instana.dc.rdb.impl.informix;

import com.instana.dc.CalculationMode;
import com.instana.dc.SimpleQueryResult;
import com.instana.dc.rdb.AbstractDbDc;
import com.instana.dc.rdb.DbDcUtil;
import com.instana.dc.rdb.impl.Constants;
import com.instana.dc.rdb.impl.informix.metric.collection.MetricDataExtractor;
import com.instana.dc.rdb.impl.informix.metric.collection.MetricCollectionMode;
import com.instana.dc.rdb.impl.informix.metric.collection.MetricsDataExtractorMapping;
import com.instana.dc.rdb.impl.informix.metric.collection.strategy.MetricsCollectorStrategy;
import com.instana.dc.rdb.impl.informix.metric.collection.strategy.SqlExecutorStrategy;
import org.apache.commons.dbcp2.BasicDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.instana.dc.rdb.DbDcUtil.*;
import static com.instana.dc.rdb.impl.informix.InformixUtil.DB_HOST_AND_VERSION_SQL;


public class InformixDc extends AbstractDbDc {
    private static final Logger LOGGER = Logger.getLogger(InformixDc.class.getName());
    private String tableSpaceSizeQuery;
    private String tableSpaceUsedQuery;
    private String tableSpaceUtilizationQuery;
    private String tableSpaceMaxQuery;
    private boolean customPollRateEnabled = true;
    private ScheduledExecutorService executorService;
    private final BasicDataSource ds;
    private final OnstatCommandExecutor onstatCommandExecutor;

    private final MetricsCollectorStrategy metricsCollectorStrategy;

    public InformixDc(Map<String, Object> properties, String dbSystem, String dbDriver) throws SQLException {
        super(properties, dbSystem, dbDriver);
        parseCustomAttributes(properties);
        onstatCommandExecutor = new OnstatCommandExecutor(getDbPath(), getServerName());
        setDbPassword(InformixUtil.decodePassword(getDbPassword()));
        setDbConnUrl();

        ds = new BasicDataSource();
        ds.setDriverClassName(getDbDriver());
        ds.setUsername(getDbUserName());
        ds.setPassword(getDbPassword());
        ds.setUrl(getDbConnUrl());
        ds.setInitialSize(3);
        ds.setMaxIdle(1);
        if (getServiceInstanceId() == null) {
            setServiceInstanceId(getDbAddress() + ":" + getDbPort() + "@" + getDbName());
        }
        getDbNameAndVersion();
        parseCustomPollRate(properties);
        registerMetricCommands();
        metricsCollectorStrategy = new SqlExecutorStrategy();
    }

    private void registerMetricCommands() {
        MetricsDataExtractorMapping.subscribeDataExtractor(DB_TABLESPACE_SIZE_NAME, new SqlExecutorStrategy(tableSpaceSizeQuery, , List.class, DB_TABLESPACE_SIZE_KEY));
        MetricsDataExtractorMapping.subscribeDataExtractor(DB_TABLESPACE_USED_NAME, new MetricDataExtractor(tableSpaceUsedQuery, null, MetricCollectionMode.SQL, List.class, DB_TABLESPACE_USED_KEY));
        MetricsDataExtractorMapping.subscribeDataExtractor(DB_TABLESPACE_UTILIZATION_NAME, new MetricDataExtractor(tableSpaceUtilizationQuery, null, MetricCollectionMode.SQL, List.class, DB_TABLESPACE_UTILIZATION_KEY));
        MetricsDataExtractorMapping.subscribeDataExtractor(DB_TABLESPACE_MAX_NAME, new MetricDataExtractor(tableSpaceMaxQuery, null, MetricCollectionMode.SQL, List.class, DB_TABLESPACE_MAX_KEY));

    }

    /**
     * Util method to parse the user input
     *
     * @param properties : user inputs
     */
    private void parseCustomPollRate(Map<String, Object> properties) {
        Map<String, Object> customInput = (Map<String, Object>) properties.get("custom.poll.interval");
        if (null == customInput || customInput.isEmpty()) {
            customPollRateEnabled = false;
            LOGGER.info("No custom polling interval fallback to default");
            return;
        }

        executorService = Executors.newScheduledThreadPool(3);

        for (Map.Entry<String, Object> entry : customInput.entrySet()) {
            IntervalType type = getPollingInterval(entry.getKey());
            int pollInterval = (int) entry.getValue();
            assert type != null;
            scheduleCustomPollRate(pollInterval, type);
        }
    }


    /**
     * Util method to schedule custom Poll Rate based on the user Input
     *
     * @param pollInterval : Polling value
     * @param intervalType : Type of the Interval
     */
    private void scheduleCustomPollRate(int pollInterval, IntervalType intervalType) {
        switch (intervalType) {
            case HIGH:
                LOGGER.info("Starting Long Polling Scheduler");
                executorService.scheduleWithFixedDelay(this::longPollingInterval, 1, pollInterval, TimeUnit.SECONDS);
                break;
            case MEDIUM:
                LOGGER.info("Starting Medium Polling Scheduler");
                executorService.scheduleWithFixedDelay(this::mediumPollingInterval, 1, pollInterval, TimeUnit.SECONDS);
                break;
            case LOW:
                LOGGER.info("Starting Low Polling Scheduler");
                executorService.scheduleWithFixedDelay(this::shortPollingInterval, 1, pollInterval, TimeUnit.SECONDS);
                break;
        }
    }


    /**
     * Util method to parse the config and get the custom Attributes from the Config
     *
     * @param properties : Config data
     */
    private void parseCustomAttributes(Map<String, Object> properties) {
        Map<String, Object> customInput = (Map<String, Object>) properties.get("custom.input");
        String[] dbNames = ((String) customInput.get("db.names")).split(",");
        StringBuilder sb = new StringBuilder(Constants.SINGLE_QUOTES + dbNames[0] + Constants.SINGLE_QUOTES);
        for (int i = 1; i < dbNames.length; i++) {
            sb.append(Constants.COMMA).append(Constants.SINGLE_QUOTES).append(dbNames[i].trim()).append(Constants.SINGLE_QUOTES);
        }
        tableSpaceSizeQuery = String.format(InformixUtil.TABLESPACE_SIZE_SQL, sb);
        tableSpaceUsedQuery = String.format(InformixUtil.TABLESPACE_USED_SQL, sb);
        tableSpaceUtilizationQuery = String.format(InformixUtil.TABLESPACE_UTILIZATION_SQL, sb);
        tableSpaceMaxQuery = String.format(InformixUtil.TABLESPACE_MAX_SQL, sb);
    }

    private void setDbConnUrl() {
        String url = String.format("jdbc:informix-sqli://%s:%s/sysmaster:informixserver=%s;user=%s;Password=%s",
                getDbAddress(),
                getDbPort(),
                getServerName(),
                getDbUserName(),
                getDbPassword()
        );
        setDbConnUrl(url);
    }

    private void getDbNameAndVersion() throws SQLException {
        try (Connection connection = ds.getConnection()) {
            ResultSet rs = DbDcUtil.executeQuery(connection, DB_HOST_AND_VERSION_SQL);
            rs.next();
            setDbVersion(rs.getString("Version"));
        }
    }

    @Override
    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(getDbConnUrl());
    }

    @Override
    public void registerMetrics() {
        super.registerMetrics();
        getRawMetric(DbDcUtil.DB_TRANSACTION_RATE_NAME).setCalculationMode(CalculationMode.RATE);
        getRawMetric(DbDcUtil.DB_SQL_RATE_NAME).setCalculationMode(CalculationMode.RATE);
        getRawMetric(DbDcUtil.DB_IO_READ_RATE_NAME).setCalculationMode(CalculationMode.RATE);
        getRawMetric(DbDcUtil.DB_IO_WRITE_RATE_NAME).setCalculationMode(CalculationMode.RATE);
    }

    @Override
    public void collectData() {
        LOGGER.info("Start to collect metrics for Informix DB");
        getallMetrics();
    }

    private void getallMetrics() {
        longPollingInterval();
        mediumPollingInterval();
        shortPollingInterval();
    }

    private void mediumPollingInterval() {
        try (Connection connection = ds.getConnection()) {
            getRawMetric(DB_SQL_COUNT_NAME).setValue((Number) metricsCollectorStrategy.collectMetrics(DB_SQL_COUNT_NAME, connection, onstatCommandExecutor));
            getRawMetric(DB_SQL_RATE_NAME).setValue((Number) metricsCollectorStrategy.collectMetrics(DB_SQL_RATE_NAME, connection, onstatCommandExecutor));
            getRawMetric(DB_TRANSACTION_COUNT_NAME).setValue((Number) metricsCollectorStrategy.collectMetrics(DB_TRANSACTION_COUNT_NAME, connection, onstatCommandExecutor));
            getRawMetric(DB_TRANSACTION_RATE_NAME).setValue((Number) metricsCollectorStrategy.collectMetrics(DB_TRANSACTION_COUNT_NAME, connection, onstatCommandExecutor));
            getRawMetric(DB_SQL_ELAPSED_TIME_NAME).setValue((List<SimpleQueryResult>) metricsCollectorStrategy.collectMetrics(DB_SQL_ELAPSED_TIME_NAME, connection, onstatCommandExecutor));
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Error while retrieving the Informix data for Host: {} ", getServerName());
        }
    }

    private void shortPollingInterval() {
        try (Connection connection = ds.getConnection()) {
            getRawMetric(DbDcUtil.DB_STATUS_NAME).setValue(1);
            getRawMetric(DbDcUtil.DB_INSTANCE_COUNT_NAME).setValue((Number) metricsCollectorStrategy.collectMetrics(DB_INSTANCE_COUNT_NAME, connection, onstatCommandExecutor));
            getRawMetric(DbDcUtil.DB_INSTANCE_ACTIVE_COUNT_NAME).setValue((Number) metricsCollectorStrategy.collectMetrics(DB_INSTANCE_ACTIVE_COUNT_NAME, connection, onstatCommandExecutor));
            getRawMetric(DbDcUtil.DB_SESSION_COUNT_NAME).setValue((Number) metricsCollectorStrategy.collectMetrics(DB_SESSION_COUNT_NAME, connection, onstatCommandExecutor));
            getRawMetric(DbDcUtil.DB_SESSION_ACTIVE_COUNT_NAME).setValue((Number) metricsCollectorStrategy.collectMetrics(DB_SESSION_ACTIVE_COUNT_NAME, connection, onstatCommandExecutor));
            getRawMetric(DbDcUtil.DB_IO_READ_RATE_NAME).setValue((Number) metricsCollectorStrategy.collectMetrics(DB_IO_READ_RATE_NAME, connection, onstatCommandExecutor));
            getRawMetric(DbDcUtil.DB_IO_WRITE_RATE_NAME).setValue((Number) metricsCollectorStrategy.collectMetrics(DB_IO_WRITE_RATE_NAME, connection, onstatCommandExecutor));
            getRawMetric(DB_MEM_UTILIZATION_NAME).setValue((Number) metricsCollectorStrategy.collectMetrics(DB_MEM_UTILIZATION_NAME, connection, onstatCommandExecutor));

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error while retrieving the data : ", e);
        }
    }

    private void longPollingInterval() {
        try (Connection connection = ds.getConnection()) {
            getRawMetric(DB_TABLESPACE_SIZE_NAME).setValue((List<SimpleQueryResult>) metricsCollectorStrategy.collectMetrics(DB_TABLESPACE_SIZE_NAME, connection, onstatCommandExecutor));
            getRawMetric(DB_TABLESPACE_USED_NAME).setValue((List<SimpleQueryResult>) metricsCollectorStrategy.collectMetrics(DB_TABLESPACE_USED_NAME, connection, onstatCommandExecutor));
            getRawMetric(DB_TABLESPACE_UTILIZATION_NAME).setValue((List<SimpleQueryResult>) metricsCollectorStrategy.collectMetrics(DB_TABLESPACE_UTILIZATION_NAME, connection, onstatCommandExecutor));
            getRawMetric(DB_TABLESPACE_MAX_NAME).setValue((List<SimpleQueryResult>) metricsCollectorStrategy.collectMetrics(DB_TABLESPACE_MAX_NAME, connection, onstatCommandExecutor));
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Error while retrieving the data : ", e);
        }
    }

    @Override
    public void start() {
        if (customPollRateEnabled) {
            LOGGER.info("Custom Poll Rate is Enabled for InformixDC, not starting default executors");
            return;
        }
        super.start();
    }

    private enum IntervalType {
        HIGH,
        MEDIUM,
        LOW;
    }

    /**
     * Util method to get the Polling Interval
     *
     * @param pollingInterval : User input of the Interval
     * @return : Mapped Type of the Interval
     */
    private IntervalType getPollingInterval(String pollingInterval) {
        for (IntervalType interval : IntervalType.values()) {
            if (pollingInterval.equalsIgnoreCase(interval.name())) {
                return interval;
            }
        }
        LOGGER.log(Level.SEVERE, "Invalid Polling Interval : {}", pollingInterval);
        return null;
    }
}
