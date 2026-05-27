package com.risksentinel.mcp;

import com.risksentinel.core.audit.AsyncAuditLog;
import com.risksentinel.core.audit.Caller;
import com.risksentinel.core.audit.SqliteAuditLog;
import com.risksentinel.core.broker.InstantFillModel;
import com.risksentinel.core.broker.PaperBroker;
import com.risksentinel.core.domain.Instrument;
import com.risksentinel.core.gateway.GatewayLimits;
import com.risksentinel.core.gateway.GatewayState;
import com.risksentinel.core.gateway.PreTradeGateway;
import com.risksentinel.core.ops.LatencyRecorder;
import com.risksentinel.core.ops.MetricsRegistry;
import com.risksentinel.core.ops.MicrometerMetricsRegistry;
import com.risksentinel.core.ops.Tags;
import com.risksentinel.core.risk.ConcurrentRiskSnapshotCache;
import com.risksentinel.core.risk.RiskSnapshotCache;
import com.risksentinel.core.positions.ConcurrentPositionBook;
import com.risksentinel.core.positions.PositionBook;
import com.risksentinel.mcp.tools.DisengageKillSwitchTool;
import com.risksentinel.mcp.tools.EngageKillSwitchTool;
import com.risksentinel.mcp.tools.GetInstrumentTool;
import com.risksentinel.mcp.tools.GetSnapshotTool;
import com.risksentinel.mcp.tools.ListPositionsTool;
import com.risksentinel.mcp.tools.ListRecentDecisionsTool;
import com.risksentinel.mcp.tools.SubmitProposalTool;
import io.modelcontextprotocol.server.McpSyncServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Entry point for the MCP bridge. Wires a deterministic in-memory pipeline,
 * opens a SQLite audit log on disk, registers the six tools, and either
 * starts the MCP server on stdio or — with {@code --check} — prints the
 * registered tool names and exits zero.
 */
public final class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    private static final Map<String, Instrument> DEFAULT_REGISTRY = Map.of(
            "AAPL", new Instrument("AAPL", "Technology", "US", 150.0),
            "GOOGL", new Instrument("GOOGL", "Technology", "US", 100.0),
            "JPM", new Instrument("JPM", "Finance", "US", 200.0));

    private Main() {}

    public static void main(String[] args) throws Exception {
        boolean checkOnly = false;
        Caller caller = Caller.agent("mcp-client");
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--check".equals(arg)) {
                checkOnly = true;
            } else if ("--operator".equals(arg)) {
                if (i + 1 >= args.length || args[i + 1].isBlank()) {
                    System.err.println("Usage: --operator <id>");
                    System.exit(2);
                }
                caller = Caller.operator(args[++i]);
            } else if ("--help".equals(arg) || "-h".equals(arg)) {
                System.out.println("Usage: risksentinel [--check] [--operator <id>]");
                System.out.println("  --check         List registered tools and exit.");
                System.out.println("  --operator <id> Run with OPERATOR identity (grants ADMIN tools).");
                System.out.println("                  Default: AGENT identity (READ_ONLY + WRITE only).");
                return;
            }
        }

        ToolRegistry registry = buildRegistry(checkOnly);

        if (checkOnly) {
            System.out.println("RiskSentinel MCP bridge — registered tools:");
            for (Tool t : registry.list()) {
                System.out.println("  - " + t.name() + " — " + t.description());
            }
            return;
        }

        log.info("Starting MCP bridge as caller kind={} id={}", caller.kind(), caller.id());
        McpSyncServer server = new McpServerAdapter(registry, caller).build();
        log.info("RiskSentinel MCP bridge started on stdio");
        // stdio transport runs on its own reader thread; park main until the JVM is interrupted.
        Thread.currentThread().join();
    }

    /**
     * Build a fully-wired registry with all six tools. Visible for the
     * {@code --check} path and the future end-to-end test in this module.
     */
    static ToolRegistry buildRegistry(boolean ephemeral) {
        PositionBook positionBook = new ConcurrentPositionBook();
        RiskSnapshotCache snapshotCache = new ConcurrentRiskSnapshotCache();
        GatewayState state = new GatewayState();
        MetricsRegistry metrics = new MicrometerMetricsRegistry();

        GatewayLimits limits = new GatewayLimits(
                10_000L, 1_000_000.0, 1_000_000.0,
                1.0, 1.0, 0.10, 100_000L, Duration.ofMinutes(5));

        Path dbPath = ephemeral
                ? Paths.get(System.getProperty("java.io.tmpdir"), "risksentinel-check.db")
                : Paths.get("risksentinel-audit.db");
        SqliteAuditLog sqlite = new SqliteAuditLog(dbPath);
        AsyncAuditLog audit = new AsyncAuditLog(
                sqlite, 1024, metrics.counter("audit_dropped_total", Tags.empty()));

        PreTradeGateway gateway = new PreTradeGateway(
                snapshotCache, DEFAULT_REGISTRY, limits, state,
                Clock.systemUTC(),
                LatencyRecorder.active("gateway-decide-nanos", 60_000_000_000L, 3),
                metrics, audit);

        // Broker is wired so the future `submit_fill` tool / sim has a sink.
        new PaperBroker(
                DEFAULT_REGISTRY, new InstantFillModel(),
                event -> {}, PaperBroker.defaultExecutor(), Clock.systemUTC());

        return new ToolRegistry(List.of(
                new GetSnapshotTool(snapshotCache),
                new ListPositionsTool(positionBook),
                new GetInstrumentTool(DEFAULT_REGISTRY),
                new ListRecentDecisionsTool(audit),
                new SubmitProposalTool(gateway, Clock.systemUTC()),
                new EngageKillSwitchTool(state),
                new DisengageKillSwitchTool(state)));
    }
}
