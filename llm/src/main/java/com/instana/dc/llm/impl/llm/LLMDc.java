/*
 * (c) Copyright IBM Corp. 2023
 * (c) Copyright Instana Inc.
 */
package com.instana.dc.llm.impl.llm;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import com.instana.dc.llm.AbstractLLMDc;
import com.instana.dc.llm.DataCollector.CustomDcConfig;
import static com.instana.dc.llm.LLMDcUtil.ANTHROPIC_PRICE_COMPLETE_TOKES_PER_KILO;
import static com.instana.dc.llm.LLMDcUtil.ANTHROPIC_PRICE_PROMPT_TOKES_PER_KILO;
import static com.instana.dc.llm.LLMDcUtil.LLM_COST_NAME;
import static com.instana.dc.llm.LLMDcUtil.LLM_DURATION_MAX_NAME;
import static com.instana.dc.llm.LLMDcUtil.LLM_DURATION_NAME;
import static com.instana.dc.llm.LLMDcUtil.LLM_REQ_COUNT_NAME;
import static com.instana.dc.llm.LLMDcUtil.LLM_STATUS_NAME;
import static com.instana.dc.llm.LLMDcUtil.LLM_TOKEN_NAME;
import static com.instana.dc.llm.LLMDcUtil.OPENAI_PRICE_COMPLETE_TOKES_PER_KILO;
import static com.instana.dc.llm.LLMDcUtil.OPENAI_PRICE_PROMPT_TOKES_PER_KILO;
import static com.instana.dc.llm.LLMDcUtil.SERVICE_LISTEN_PORT;
import static com.instana.dc.llm.LLMDcUtil.WATSONX_PRICE_COMPLETE_TOKES_PER_KILO;
import static com.instana.dc.llm.LLMDcUtil.WATSONX_PRICE_PROMPT_TOKES_PER_KILO;
import com.instana.dc.llm.impl.llm.MetricsCollectorService.OtelMetric;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.server.healthcheck.HealthCheckService;

@SuppressWarnings("null")
public class LLMDc extends AbstractLLMDc {
    private static final Logger logger = Logger.getLogger(LLMDc.class.getName());

    public static final String SENSOR_NAME = "com.instana.plugin.watsonx";
    private HashMap<String, ModelAggregation> modelAggrMap = new HashMap<>();
    private MetricsCollectorService metricsCollector = new MetricsCollectorService();
    private Double watsonxPricePromptTokens = 0.0;
    private Double watsonxPriceCompleteTokens = 0.0;
    private Double openaiPricePromptTokens = 0.0;
    private Double openaiPriceCompleteTokens = 0.0;
    private Double anthropicPricePromptTokens = 0.0;
    private Double anthropicPriceCompleteTokens = 0.0;
    private int listenPort = 0;

    /**
     * The poll rate in the configuration, in seconds. In other words, the number of
     * seconds between calls to Watsonx.
     */

    private class ModelAggregation { 
        private final String modelId;
        private final String aiSystem;
        private long deltaPromptTokens;
        private long deltaCompleteTokens;
        private long deltaDuration;
        private long deltaReqCount;
        private long maxDuration;
        private long lastTotalPromptTokens;
        private long lastTotalCompleteTokens;
        private long lastTotalDuration;
        private long lastTotalReqCount;
        
        public ModelAggregation(String modelId, String aiSystem) {
            this.modelId = modelId;
            this.aiSystem = aiSystem;
        }
        public String getModelId() {
            return modelId;
        }
        public String getAiSystem() {
            return aiSystem;
        }
        public void addDeltaPromptTokens(long currTokens, long reqCount) {
            if(currTokens == 0) {
                return;
            }
            long diffPromptTokens = 0;
            if(reqCount == 1) {
                diffPromptTokens = currTokens;
            } else if(currTokens > lastTotalPromptTokens && lastTotalPromptTokens != 0) {
                diffPromptTokens = currTokens - lastTotalPromptTokens;
            }
            lastTotalPromptTokens = currTokens;
            deltaPromptTokens += diffPromptTokens;
        }

        public long getDeltaPromptTokens() {
            return deltaPromptTokens;
        }
        public void addDeltaCompleteTokens(long currTokens, long reqCount) {
            if(currTokens == 0) {
                return;
            }
            long diffCompleteTokens = 0;
            if(reqCount == 1) {
                diffCompleteTokens = currTokens;
            } else if(currTokens > lastTotalCompleteTokens && lastTotalCompleteTokens != 0) {
                diffCompleteTokens = currTokens - lastTotalCompleteTokens;
            }
            lastTotalCompleteTokens = currTokens;
            deltaCompleteTokens += diffCompleteTokens;
        }
        public long getDeltaCompleteTokens() {
            return deltaCompleteTokens;
        }
        public void addDeltaDuration(long currDuration, long reqCount) {
            if(currDuration == 0) {
                return;
            }
            long diffDuration = 0;
            if(reqCount == 1) {
                diffDuration = currDuration;
            } else if(currDuration > lastTotalDuration && lastTotalDuration != 0) {
                diffDuration = currDuration - lastTotalDuration;
            }
            lastTotalDuration = currDuration;
            deltaDuration += diffDuration;
        }
        public long getDeltaDuration() {
            return deltaDuration;
        }
        public void setMaxDuration(long maxDuration) {
            this.maxDuration = maxDuration;
        }
        public long getMaxDuration() {
            return maxDuration;
        }
        public void addDeltaReqCount(long currCount) {
            if(currCount == 0) {
                return;
            }
            long diffReqCount = 0;
            if(currCount == 1) {
                diffReqCount = currCount;
            } else if(currCount > lastTotalReqCount && lastTotalReqCount != 0) {
                diffReqCount = currCount - lastTotalReqCount;
            }
            lastTotalReqCount = currCount;
            deltaReqCount += diffReqCount;
        }
        public long getDeltaReqCount() {
            return deltaReqCount;
        }
        public long getCurrentReqCount() {
            return lastTotalReqCount;
        }
        public void resetMetrics() {
            deltaPromptTokens = 0;
            deltaCompleteTokens = 0;
            deltaDuration = 0;
            deltaReqCount = 0;
        }
    }

    public LLMDc(Map<String, Object> properties, CustomDcConfig cdcConfig) throws Exception {
        super(properties, cdcConfig);
        watsonxPricePromptTokens = (Double) properties.getOrDefault(WATSONX_PRICE_PROMPT_TOKES_PER_KILO, 0.0);
        watsonxPriceCompleteTokens = (Double) properties.getOrDefault(WATSONX_PRICE_COMPLETE_TOKES_PER_KILO, 0.0);
        openaiPricePromptTokens = (Double) properties.getOrDefault(OPENAI_PRICE_PROMPT_TOKES_PER_KILO, 0.0);
        openaiPriceCompleteTokens = (Double) properties.getOrDefault(OPENAI_PRICE_COMPLETE_TOKES_PER_KILO, 0.0);
        anthropicPricePromptTokens = (Double) properties.getOrDefault(ANTHROPIC_PRICE_PROMPT_TOKES_PER_KILO, 0.0);
        anthropicPriceCompleteTokens = (Double) properties.getOrDefault(ANTHROPIC_PRICE_COMPLETE_TOKES_PER_KILO, 0.0);
        listenPort = (int) properties.getOrDefault(SERVICE_LISTEN_PORT, 8000);
    }

    @Override
    public void initOnce() throws ClassNotFoundException {
        var server = Server.builder()
                .http(listenPort)
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
                String aiSystem = metric.getAiSystem();
                long promptTokens = metric.getPromtTokens();
                long completeTokens = metric.getCompleteTokens();
                double duration = metric.getDuration();
                long requestCount = metric.getReqCount();

                ModelAggregation modelAggr = modelAggrMap.get(modelId);
                if (modelAggr == null) {
                    modelAggr = new ModelAggregation(modelId, aiSystem);
                    modelAggrMap.put(modelId, modelAggr);
                }
                // Always handle duration first!
                modelAggr.addDeltaDuration((long)(duration*1000), requestCount);
                modelAggr.addDeltaReqCount(requestCount);
                long currentReqCount = modelAggr.getCurrentReqCount();
                modelAggr.addDeltaPromptTokens(promptTokens, currentReqCount);
                modelAggr.addDeltaCompleteTokens(completeTokens, currentReqCount);

            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        logger.info("-----------------------------------------");
        for(Map.Entry<String,ModelAggregation> entry : modelAggrMap.entrySet()){
            ModelAggregation aggr = entry.getValue();
            String modelId = aggr.getModelId();
            String aiSystem = aggr.getAiSystem();
            long deltaRequestCount = aggr.getDeltaReqCount();
            long deltaDuration = aggr.getDeltaDuration();
            long deltaPromptTokens = aggr.getDeltaPromptTokens();
            long deltaCompleteTokens = aggr.getDeltaCompleteTokens();
            long maxDuration = aggr.getMaxDuration();

            long avgDuration = deltaRequestCount == 0 ? 0 : deltaDuration/deltaRequestCount;
            if(avgDuration > maxDuration) {
                maxDuration = avgDuration;
                aggr.setMaxDuration(maxDuration);
            }

            int intervalSeconds = LLM_POLL_INTERVAL;
            String agentLess = System.getenv("AGENTLESS_MODE_ENABLED");
            if (agentLess != null) {
                intervalSeconds = 1;
            }

            double pricePromptTokens = getPricePromptTokens(aiSystem);
            double priceCompleteTokens = getPriceCompleteTokens(aiSystem);

            double intervalReqCount = (double)deltaRequestCount/intervalSeconds;
            double intervalPromptTokens = (double)deltaPromptTokens/intervalSeconds;
            double intervalCompleteTokens = (double)deltaCompleteTokens/intervalSeconds;
            double intervalTotalTokens = intervalPromptTokens + intervalCompleteTokens;
            double intervalPromptCost = (intervalPromptTokens/1000) * pricePromptTokens;
            double intervalCompleteCost = (intervalCompleteTokens/1000) * priceCompleteTokens;
            double intervalTotalCost = intervalPromptCost + intervalCompleteCost;
            aggr.resetMetrics();

            System.out.printf("Metrics for model %s of %s:%n", modelId, aiSystem);
            System.out.println(" - Average Duration : " + avgDuration + " ms");
            System.out.println(" - Maximum Duration : " + maxDuration + " ms");
            System.out.println(" - Interval Tokens  : " + intervalTotalTokens);
            System.out.println(" - Interval Cost    : " + intervalTotalCost);
            System.out.println(" - Interval Request : " + intervalReqCount);

            Map<String, Object> attributes = new HashMap<>();
            attributes.put("model_id", modelId);
            attributes.put("ai_system", aiSystem);
            getRawMetric(LLM_STATUS_NAME).setValue(1);
            getRawMetric(LLM_DURATION_NAME).getDataPoint(modelId).setValue(avgDuration, attributes);
            getRawMetric(LLM_DURATION_MAX_NAME).getDataPoint(modelId).setValue(maxDuration, attributes);
            getRawMetric(LLM_COST_NAME).getDataPoint(modelId).setValue(intervalTotalCost, attributes);
            getRawMetric(LLM_TOKEN_NAME).getDataPoint(modelId).setValue(intervalTotalTokens, attributes);
            getRawMetric(LLM_REQ_COUNT_NAME).getDataPoint(modelId).setValue(intervalReqCount, attributes);
        }
        logger.info("-----------------------------------------");
    }

    private double getPricePromptTokens(String aiSystem) {
        switch (aiSystem) {
            case "watsonx": return watsonxPricePromptTokens;
            case "openai": return openaiPricePromptTokens;
            case "anthropic": return anthropicPricePromptTokens;
            default: return 0.0;
        }
    }

    private double getPriceCompleteTokens(String aiSystem) {
        switch (aiSystem) {
            case "watsonx": return watsonxPriceCompleteTokens;
            case "openai": return openaiPriceCompleteTokens;
            case "anthropic": return anthropicPriceCompleteTokens;
            default: return 0.0;
        }
    }
}
