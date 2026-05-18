package com.risksentinel.core.gateway;

import com.risksentinel.core.domain.Side;
import com.risksentinel.core.domain.TradeProposal;
import org.junit.jupiter.api.Test;

import static com.risksentinel.core.gateway.CheckTestFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

class KillSwitchCheckTest {

    private final RiskCheck check = new KillSwitchCheck();

    @Test
    void shouldReject_whenKillSwitchEngaged() {
        GatewayState state = new GatewayState();
        state.engageKillSwitch();
        TradeProposal p = proposal("AAPL", Side.BUY, 10, 150.0);

        var reasons = check.check(p, ctx(null, AAPL, defaultLimits(), state));

        assertThat(reasons).hasSize(1);
        assertThat(reasons.get(0).code()).isEqualTo(RejectCode.KILL_SWITCH_ENGAGED);
    }

    @Test
    void shouldPass_whenKillSwitchDisengaged() {
        TradeProposal p = proposal("AAPL", Side.BUY, 10, 150.0);
        assertThat(check.check(p, ctx(null, AAPL, defaultLimits(), new GatewayState()))).isEmpty();
    }
}
