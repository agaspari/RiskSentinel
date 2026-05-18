package com.risksentinel.core.gateway;

import com.risksentinel.core.domain.TradeProposal;

/**
 * Terminal destination for proposals that have passed the {@link PreTradeGateway}.
 *
 * <p>Phase 3 ships only a stub implementation; Phase 4 will introduce the
 * paper-broker adapter that translates accepted proposals into simulated
 * fills and feeds them back into the ingestion queue.
 */
public interface BrokerSink {
    void submit(TradeProposal acceptedProposal);
}
