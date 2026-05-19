package com.risksentinel.core.broker;

/**
 * Where {@link PaperBroker} emits fills after simulation. The pipeline supplies
 * an implementation that translates the {@link FillEvent} into a
 * {@link com.risksentinel.core.domain.Trade} and offers it to the ingestion queue.
 *
 * <p>Implementations must be safe to call from the broker's executor threads.
 */
public interface FillSink {
    void onFill(FillEvent event);
}
