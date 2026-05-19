package com.risksentinel.core.ops;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.risksentinel.core.broker.FillEvent;
import com.risksentinel.core.broker.FillSink;
import com.risksentinel.core.broker.InstantFillModel;
import com.risksentinel.core.broker.PaperBroker;
import com.risksentinel.core.domain.Instrument;
import com.risksentinel.core.domain.Side;
import com.risksentinel.core.domain.TradeProposal;
import com.risksentinel.core.gateway.GatewayLimits;
import com.risksentinel.core.gateway.GatewayState;
import com.risksentinel.core.gateway.PreTradeGateway;
import com.risksentinel.core.risk.ConcurrentRiskSnapshotCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class LoggingPropagationTest {

    private static final Instrument AAPL = new Instrument("AAPL", "Technology", "US", 150.0);
    private static final Map<String, Instrument> REGISTRY = Map.of("AAPL", AAPL);

    private ListAppender<ILoggingEvent> appender;
    private Logger gatewayLogger;
    private Logger brokerLogger;
    private Level prevGateway;
    private Level prevBroker;

    @BeforeEach
    void setUp() {
        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        gatewayLogger = ctx.getLogger("com.risksentinel.core.gateway.PreTradeGateway");
        brokerLogger = ctx.getLogger("com.risksentinel.core.broker.PaperBroker");
        prevGateway = gatewayLogger.getLevel();
        prevBroker = brokerLogger.getLevel();
        gatewayLogger.setLevel(Level.DEBUG);
        brokerLogger.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        appender.start();
        gatewayLogger.addAppender(appender);
        brokerLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        gatewayLogger.detachAppender(appender);
        brokerLogger.detachAppender(appender);
        gatewayLogger.setLevel(prevGateway);
        brokerLogger.setLevel(prevBroker);
        MDC.clear();
    }

    private TradeProposal proposal(String id) {
        return new TradeProposal(
                id, "port-XYZ", "AAPL", Side.BUY, 1L, 150.0, 150.0,
                "t", 0.9, "snap-42", Instant.now());
    }

    @Test
    void shouldPopulateMdc_onGatewayDecide() {
        GatewayState state = new GatewayState();
        GatewayLimits limits = new GatewayLimits(
                Long.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE,
                1.0, 1.0, 1.0, Long.MAX_VALUE, Duration.ofDays(1));
        PreTradeGateway gw = new PreTradeGateway(
                new ConcurrentRiskSnapshotCache(), REGISTRY, limits, state);
        try {
            gw.decide(proposal("prop-42"));

            ILoggingEvent event = appender.list.stream()
                    .filter(e -> e.getLoggerName().endsWith("PreTradeGateway"))
                    .findFirst()
                    .orElseThrow();
            Map<String, String> mdc = event.getMDCPropertyMap();

            assertThat(mdc).containsEntry("portfolioId", "port-XYZ");
            assertThat(mdc).containsEntry("proposalId", "prop-42");
            assertThat(mdc).containsEntry("snapshotId", "snap-42");
            assertThat(mdc).containsEntry("decisionCode", "ACCEPT");
        } finally {
            state.shutdown();
        }
    }

    @Test
    void shouldClearMdc_afterGatewayDecide() {
        GatewayState state = new GatewayState();
        GatewayLimits limits = new GatewayLimits(
                Long.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE,
                1.0, 1.0, 1.0, Long.MAX_VALUE, Duration.ofDays(1));
        PreTradeGateway gw = new PreTradeGateway(
                new ConcurrentRiskSnapshotCache(), REGISTRY, limits, state);
        try {
            gw.decide(proposal("prop-1"));

            assertThat(MDC.get("portfolioId")).isNull();
            assertThat(MDC.get("proposalId")).isNull();
        } finally {
            state.shutdown();
        }
    }

    @Test
    void shouldPropagateMdc_acrossBrokerExecutor() throws InterruptedException {
        ThreadPoolExecutor exec = new ThreadPoolExecutor(
                1, 1, 30L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64));
        List<FillEvent> fills = new ArrayList<>();
        FillSink sink = fills::add;

        PaperBroker broker = new PaperBroker(
                REGISTRY, new InstantFillModel(), sink, exec, Clock.systemUTC());
        try {
            broker.submit(proposal("prop-async-1"));

            exec.shutdown();
            assertThat(exec.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

            ILoggingEvent fillEvent = appender.list.stream()
                    .filter(e -> e.getLoggerName().endsWith("PaperBroker"))
                    .filter(e -> e.getFormattedMessage().startsWith("Order filled"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no fill log event"));
            Map<String, String> mdc = fillEvent.getMDCPropertyMap();

            assertThat(mdc).containsEntry("portfolioId", "port-XYZ");
            assertThat(mdc).containsEntry("proposalId", "prop-async-1");
        } finally {
            broker.shutdown();
            exec.shutdownNow();
        }
    }
}
