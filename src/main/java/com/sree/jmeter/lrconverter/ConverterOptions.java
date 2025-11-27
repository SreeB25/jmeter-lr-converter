package com.sree.jmeter.lrconverter;

/**
 * Options to control which features are applied during conversion.
 */
public class ConverterOptions {

    private boolean enableHeaders = true;
    private boolean enableCorrelation = true;
    private boolean enableThinkTime = true;

    public ConverterOptions() {
    }

    public boolean isEnableHeaders() {
        return enableHeaders;
    }

    public void setEnableHeaders(boolean enableHeaders) {
        this.enableHeaders = enableHeaders;
    }

    public boolean isEnableCorrelation() {
        return enableCorrelation;
    }

    public void setEnableCorrelation(boolean enableCorrelation) {
        this.enableCorrelation = enableCorrelation;
    }

    public boolean isEnableThinkTime() {
        return enableThinkTime;
    }

    public void setEnableThinkTime(boolean enableThinkTime) {
        this.enableThinkTime = enableThinkTime;
    }
}
