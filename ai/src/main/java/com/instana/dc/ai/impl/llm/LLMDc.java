/*
 * (c) Copyright IBM Corp. 2023
 * (c) Copyright Instana Inc.
 */
package com.instana.dc.ai.impl.llm;

import com.instana.dc.ai.AbstractLLMDc;
import com.instana.dc.ai.DataCollector.CustomDcConfig;
import com.instana.dc.ai.impl.llm.MetricsCollectorService.OtelMetric;

import java.util.logging.Logger;
import java.util.*;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.server.healthcheck.HealthCheckService;

//import static com.instana.agent.sensorsdk.semconv.SemanticAttributes.*;
import static com.instana.dc.ai.LLMDcUtil.*;

@SuppressWarnings("null")
public class LLMDc extends AbstractLLMDc {
    private static final Logger logger = Logger.getLogger(LLMDc.class.getName());

    public static final String SENSOR_NAME = "com.instana.plugin.watsonx";
    private HashMap<String, ModelAggregation> modelAggrMap = new HashMap<>();
    private MetricsCollectorService metricsCollector = new MetricsCollectorService();

    /**
     * The poll rate in the configuration, in seconds. In other words, the number of
     * seconds between calls to Watsonx.
    */

    private class ModelAggregation { 
        private final String modelId;
        private final String userId;
        private int totalPromptTokens;
        private int totalCompleteTokens;
        private int totalDuration;
        private int maxDuration;
        private int totalReqCount;
    
        public ModelAggregation(String modelId, String userId) {
            this.modelId = modelId;
            this.userId = userId;
        }
        public String getModelId() {
            return modelId;
        }
        public String getUserId() {
            return userId;
        }
        public void addTotalPromptTokens(int tokens) {
            totalPromptTokens += tokens;
        }
        public int getTotalPromptTokens() {
            return totalPromptTokens;
        }
        public void addTotalCompleteTokens(int tokens) {
            totalCompleteTokens += tokens;
        }
        public int getTotalCompleteTokens() {
            return totalCompleteTokens;
        }
        public void addTotalDuration(int duration) {
            totalDuration += duration;
            if(duration > maxDuration)
               maxDuration = duration;
        }
        public int getTotalDuration() {
            return totalDuration;
        }
        public int getMaxDuration() {
            return maxDuration;
        }
        public void addReqCount(int count) {
            totalReqCount += count;
        }
        public int getReqCount() {
            return totalReqCount;
        }
        public void resetMetrics() {
            totalPromptTokens = 0;
            totalCompleteTokens = 0;
            totalDuration = 0;
            maxDuration = 0;
            totalReqCount = 0;
        }
    }

    public LLMDc(Map<String, Object> properties, CustomDcConfig cdcConfig) throws Exception {
        super(properties, cdcConfig);
    }

    @Override
    public void initOnce() throws ClassNotFoundException {
        var server = Server.builder()
                .http(8000)
                .service(
                        GrpcService.builder()
                                .addService(metricsCollector)
                                .build())
                .service(
                        "/",
                        (ctx, req) -> {
                            var requests = metricsCollector.getMetrics();
                            if (requests != null) {
                                return HttpResponse.of(
                                        HttpStatus.OK, MediaType.JSON, HttpData.wrap("OK".getBytes()));
                            } else {
                                return HttpResponse.of(
                                        HttpStatus.BAD_REQUEST, MediaType.JSON,
                                        HttpData.wrap("Bad Request".getBytes()));
                            }
                        })
                .service("/health", HealthCheckService.of())
                .build();

        server.start().join();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop().join()));
    }

    @Override
    public void registerMetrics() {
        super.registerMetrics();
    }

    @Override
    public void collectData() {
        logger.info("Start to collect metrics");
  
        for(Map.Entry<String,ModelAggregation> entry : modelAggrMap.entrySet()){
            ModelAggregation aggr = entry.getValue();
            aggr.resetMetrics();
        }

        List<OtelMetric> otelMetrics = metricsCollector.getMetrics();
        metricsCollector.clearMetrics();
        for (OtelMetric metric : otelMetrics) {
            try {
                String modelId = metric.getModelId();
                long promptTokens = metric.getPromtTokens();
                long completeTokens = metric.getCompleteTokens();
                double duration = metric.getDuration();

                ModelAggregation modelAggr = modelAggrMap.get(modelId);
                if (modelAggr == null) {
                    modelAggr = new ModelAggregation(modelId, "llmUser");
                    modelAggrMap.put(modelId, modelAggr);
                }

                modelAggr.addTotalPromptTokens((int)(promptTokens));
                modelAggr.addTotalCompleteTokens((int)(completeTokens));
                modelAggr.addReqCount(1);
                modelAggr.addTotalDuration((int)(duration*1000));

            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        getRawMetric(LLM_STATUS_NAME).setValue(1);
        for(Map.Entry<String,ModelAggregation> entry : modelAggrMap.entrySet()){
            ModelAggregation aggr = entry.getValue();
            int requestCount = aggr.getReqCount();
            int totalDuration = aggr.getTotalDuration();
            int maxDuration = aggr.getMaxDuration();
            int totalPromptTokens = aggr.getTotalPromptTokens();
            int totalCompleteTokens = aggr.getTotalCompleteTokens();

            int avgDuration = totalDuration/(requestCount==0?1:requestCount);
            double intervalPromptTokens = (double)totalPromptTokens/LLM_POLL_INTERVAL;
            double intervalCompleteTokens = (double)totalCompleteTokens/LLM_POLL_INTERVAL;
            double intervalTotalTokens = intervalPromptTokens + intervalCompleteTokens;
            double intervalPromptCost = (intervalPromptTokens/1000) * 0.03;
            double intervalCompleteCost = (intervalCompleteTokens/1000) * 0.03;
            double intervalTotalCost = intervalPromptCost + intervalCompleteCost;
            double intervalRequests = (double)requestCount/LLM_POLL_INTERVAL;
            aggr.resetMetrics();

            Map<String, Object> attributes = new HashMap<>();
            attributes.put("model_id", aggr.getModelId());
            attributes.put("user_id", aggr.getUserId());
            getRawMetric(LLM_DURATION_NAME).setValue(avgDuration, attributes);
            getRawMetric(LLM_DURATION_MAX_NAME).setValue(maxDuration, attributes);
            getRawMetric(LLM_COST_NAME).setValue(intervalTotalCost, attributes);
            getRawMetric(LLM_TOKEN_NAME).setValue(intervalTotalTokens, attributes);
            getRawMetric(LLM_REQ_COUNT_NAME).setValue(intervalRequests, attributes);

            logger.info("LLM_DURATION_NAME: " + avgDuration);
            logger.info("LLM_DURATION_MAX_NAME: " + maxDuration);
            logger.info("LLM_COST_NAME: " + intervalTotalCost);
            logger.info("LLM_TOKEN_NAME: " + intervalTotalTokens);
            logger.info("LLM_REQ_COUNT_NAME: " + intervalRequests);
        }
    }
}
