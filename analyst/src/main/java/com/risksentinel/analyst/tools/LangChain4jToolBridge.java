package com.risksentinel.analyst.tools;

import com.risksentinel.core.audit.Caller;
import com.risksentinel.mcp.Tool;
import com.risksentinel.mcp.ToolRegistry;
import com.risksentinel.mcp.ToolResult;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Adapts a Phase 7 {@link ToolRegistry} into the LangChain4j tool surface so
 * the in-process analyst agent and the MCP transport share one tool catalogue.
 *
 * <p>Two responsibilities:
 * <ol>
 *   <li>{@link #specifications()} — translate each {@link Tool}'s
 *   home-grown JSON Schema map into a LangChain4j
 *   {@link ToolSpecification}.</li>
 *   <li>{@link #execute(ToolExecutionRequest)} — accept a model's tool call,
 *   dispatch through the registry, and surface the result (success or error)
 *   as a {@link ToolExecutionResultMessage} the model can read in its next
 *   turn. Errors never throw — the LLM sees the failure text and may adjust.</li>
 * </ol>
 *
 * <p>This class is the only place in {@code analyst/} (besides
 * {@code LangChain4jAnalyst}) that imports {@code dev.langchain4j.*}. If the
 * SDK churns, this file is the blast radius.
 */
public final class LangChain4jToolBridge {

    private static final Logger log = LoggerFactory.getLogger(LangChain4jToolBridge.class);

    private final ToolRegistry registry;
    private final Caller caller;
    private final ObjectMapper jsonMapper;

    public LangChain4jToolBridge(ToolRegistry registry, Caller caller, ObjectMapper jsonMapper) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.caller = Objects.requireNonNull(caller, "caller");
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper");
    }

    /**
     * Build a {@link ToolSpecification} for every tool in the registry, in
     * registration order. The returned list is safe to hand to
     * {@code ChatRequest.builder().toolSpecifications(...)}.
     */
    public List<ToolSpecification> specifications() {
        List<Tool> tools = registry.list();
        List<ToolSpecification> specs = new ArrayList<>(tools.size());
        for (Tool tool : tools) {
            specs.add(ToolSpecification.builder()
                    .name(tool.name())
                    .description(tool.description())
                    .parameters(toJsonObjectSchema(tool.inputSchema()))
                    .build());
        }
        return specs;
    }

    /**
     * Dispatch a model's tool call through the registry and wrap the result
     * for the next turn. The returned message includes the request id so
     * LangChain4j can correlate it with the originating {@code AiMessage}.
     */
    public ToolExecutionResultMessage execute(ToolExecutionRequest request) {
        Objects.requireNonNull(request, "request");

        JsonNode input;
        String args = request.arguments();
        if (args == null || args.isBlank()) {
            input = jsonMapper.createObjectNode();
        } else {
            try {
                input = jsonMapper.readTree(args);
            } catch (JacksonException e) {
                log.warn("Tool {} received malformed arguments JSON: {}", request.name(), e.toString());
                ToolResult err = ToolResult.error("Malformed arguments JSON: " + e.getMessage());
                return ToolExecutionResultMessage.from(request, err.content());
            }
        }

        ToolResult result = registry.invoke(request.name(), input, caller);
        return ToolExecutionResultMessage.from(request, result.content());
    }

    @SuppressWarnings("unchecked")
    static JsonObjectSchema toJsonObjectSchema(Map<String, Object> schema) {
        JsonObjectSchema.Builder builder = JsonObjectSchema.builder();

        Object props = schema.get("properties");
        if (props instanceof Map<?, ?> propMap) {
            for (Map.Entry<?, ?> entry : propMap.entrySet()) {
                if (!(entry.getKey() instanceof String name)) continue;
                if (!(entry.getValue() instanceof Map<?, ?> propDef)) continue;
                builder.addProperty(name, toJsonSchemaElement((Map<String, Object>) propDef));
            }
        }

        Object required = schema.get("required");
        if (required instanceof List<?> reqList) {
            List<String> names = new ArrayList<>(reqList.size());
            for (Object o : reqList) {
                if (o instanceof String s) names.add(s);
            }
            if (!names.isEmpty()) builder.required(names);
        }

        Object desc = schema.get("description");
        if (desc instanceof String s) builder.description(s);

        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private static JsonSchemaElement toJsonSchemaElement(Map<String, Object> def) {
        String type = (def.get("type") instanceof String s) ? s : "string";
        String description = (def.get("description") instanceof String s) ? s : null;

        return switch (type) {
            case "string" -> JsonStringSchema.builder().description(description).build();
            case "integer" -> JsonIntegerSchema.builder().description(description).build();
            case "number" -> JsonNumberSchema.builder().description(description).build();
            case "boolean" -> JsonBooleanSchema.builder().description(description).build();
            case "array" -> {
                Object items = def.get("items");
                JsonSchemaElement itemSchema = (items instanceof Map<?, ?> itemMap)
                        ? toJsonSchemaElement((Map<String, Object>) itemMap)
                        : new JsonStringSchema();
                yield JsonArraySchema.builder()
                        .description(description)
                        .items(itemSchema)
                        .build();
            }
            case "object" -> toJsonObjectSchema(def);
            default -> JsonStringSchema.builder().description(description).build();
        };
    }
}
