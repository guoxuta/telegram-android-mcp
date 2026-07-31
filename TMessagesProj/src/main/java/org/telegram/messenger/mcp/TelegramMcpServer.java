package org.telegram.messenger.mcp;

import android.content.Context;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import fi.iki.elonen.NanoHTTPD;

/**
 * Debug-only, loopback-only MCP endpoint for the Telegram Android process.
 *
 * <p>The bearer token is generated inside the application's private files
 * directory. The host retrieves it with {@code adb shell run-as}; it is never
 * logged or returned by an unauthenticated endpoint.</p>
 */
public final class TelegramMcpServer extends NanoHTTPD {
    public static final int PORT = 19876;
    public static final String PROTOCOL_VERSION = "2025-03-26";
    public static final String SERVER_VERSION = "0.1.0";

    private static final int MAX_BODY_BYTES = 1024 * 1024;
    private static final Gson GSON = new Gson();
    private static volatile TelegramMcpServer instance;

    private final TelegramMcpService service;
    private final String bearerToken;
    private final JsonObject catalog;
    private final Map<String, JsonObject> catalogTools = new HashMap<>();

    private TelegramMcpServer(Context context) throws Exception {
        super("127.0.0.1", PORT);
        bearerToken = loadOrCreateToken(context);
        catalog = loadCatalog(context);
        for (JsonElement element : catalog.getAsJsonArray("tools")) {
            JsonObject tool = element.getAsJsonObject();
            catalogTools.put(getString(tool, "name", ""), tool);
        }
        service = new TelegramMcpService();
    }

    /** Starts the local server once. Release/standalone builds never call this. */
    public static synchronized void startIfEnabled() {
        if (!BuildVars.DEBUG_VERSION || instance != null) {
            return;
        }
        try {
            TelegramMcpServer server = new TelegramMcpServer(ApplicationLoader.applicationContext);
            server.start(SOCKET_READ_TIMEOUT, false);
            instance = server;
            FileLog.d("Telegram MCP listening on device loopback port " + PORT);
        } catch (Throwable error) {
            FileLog.e(error);
        }
    }

    @Override
    public Response serve(IHTTPSession session) {
        try {
            return serveChecked(session);
        } catch (Throwable error) {
            FileLog.e(error);
            return jsonHttp(Response.Status.INTERNAL_ERROR, rpcError(null, -32603,
                    "Internal MCP server error", null));
        }
    }

    private Response serveChecked(IHTTPSession session) throws Exception {
        if (!isAuthorized(session.getHeaders().get("authorization"))) {
            return jsonHttp(Response.Status.UNAUTHORIZED, errorEnvelope(
                    "UNAUTHORIZED", "Missing or invalid bearer token", false, null));
        }

        if (Method.GET.equals(session.getMethod()) && "/health".equals(session.getUri())) {
            return jsonHttp(Response.Status.OK, service.health());
        }
        if (!Method.POST.equals(session.getMethod()) || !"/mcp".equals(session.getUri())) {
            return jsonHttp(Response.Status.NOT_FOUND, errorEnvelope(
                    "NOT_FOUND", "Use POST /mcp or GET /health", false, null));
        }

        String lengthHeader = session.getHeaders().get("content-length");
        if (lengthHeader != null) {
            try {
                if (Long.parseLong(lengthHeader) > MAX_BODY_BYTES) {
                    return jsonHttp(Response.Status.PAYLOAD_TOO_LARGE, errorEnvelope(
                            "REQUEST_TOO_LARGE", "MCP request exceeds 1 MiB", false, null));
                }
            } catch (NumberFormatException error) {
                return jsonHttp(Response.Status.BAD_REQUEST, rpcError(null, -32600,
                        "Invalid Content-Length", null));
            }
        }

        Map<String, String> files = new HashMap<>();
        session.parseBody(files);
        String body = files.get("postData");
        if (body == null || body.isEmpty()) {
            return jsonHttp(Response.Status.BAD_REQUEST, rpcError(null, -32600,
                    "Empty JSON-RPC request", null));
        }
        if (body.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
            return jsonHttp(Response.Status.PAYLOAD_TOO_LARGE, errorEnvelope(
                    "REQUEST_TOO_LARGE", "MCP request exceeds 1 MiB", false, null));
        }

        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(body);
        } catch (Throwable error) {
            return jsonHttp(Response.Status.BAD_REQUEST, rpcError(null, -32700,
                    "Invalid JSON", null));
        }
        if (!parsed.isJsonObject()) {
            return jsonHttp(Response.Status.BAD_REQUEST, rpcError(null, -32600,
                    "JSON-RPC request must be an object", null));
        }

        JsonObject request = parsed.getAsJsonObject();
        JsonElement id = request.get("id");
        String method = getString(request, "method", null);
        if (method == null) {
            return jsonHttp(Response.Status.BAD_REQUEST, rpcError(id, -32600,
                    "Missing JSON-RPC method", null));
        }

        JsonObject params = request.has("params") && request.get("params").isJsonObject()
                ? request.getAsJsonObject("params") : new JsonObject();
        JsonElement result;
        switch (method) {
            case "initialize":
                result = initializeResult();
                break;
            case "notifications/initialized":
                return newFixedLengthResponse(Response.Status.NO_CONTENT, "application/json", "");
            case "ping":
                result = new JsonObject();
                break;
            case "tools/list":
                result = toolsList();
                break;
            case "tools/call":
                result = callTool(params);
                break;
            case "resources/list":
                result = resourcesList();
                break;
            case "resources/read":
                result = readResource(params);
                break;
            default:
                return jsonHttp(Response.Status.OK, rpcError(id, -32601,
                        "Unsupported MCP method: " + method, null));
        }
        return jsonHttp(Response.Status.OK, rpcResult(id, result));
    }

    private JsonObject initializeResult() {
        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", PROTOCOL_VERSION);
        JsonObject capabilities = new JsonObject();
        capabilities.add("tools", new JsonObject());
        capabilities.add("resources", new JsonObject());
        result.add("capabilities", capabilities);
        JsonObject serverInfo = new JsonObject();
        serverInfo.addProperty("name", "telegram-android-mcp");
        serverInfo.addProperty("version", SERVER_VERSION);
        result.add("serverInfo", serverInfo);
        result.addProperty("instructions",
                "Resolve peers before writes. Reuse idempotency_key on retries. " +
                "Operations carrying _confirm require the literal boolean true.");
        return result;
    }

    private JsonObject toolsList() {
        JsonArray tools = new JsonArray();
        for (JsonElement element : catalog.getAsJsonArray("tools")) {
            JsonObject source = element.getAsJsonObject();
            JsonObject tool = new JsonObject();
            String toolName = getString(source, "name", "");
            tool.addProperty("name", toolName);
            tool.addProperty("title", getString(source, "title", ""));
            tool.addProperty("description", getString(source, "description", ""));
            tool.add("inputSchema", source.getAsJsonObject("input_schema").deepCopy());
            JsonObject annotations = new JsonObject();
            annotations.addProperty("readOnlyHint", getBoolean(source, "read_only", false));
            annotations.addProperty("destructiveHint", getBoolean(source, "destructive", false));
            annotations.addProperty("idempotentHint", getBoolean(source, "idempotent", false));
            annotations.addProperty("openWorldHint", getBoolean(source, "open_world", false));
            tool.add("annotations", annotations);
            JsonObject metadata = new JsonObject();
            String[] nameParts = toolName.split("\\.");
            metadata.addProperty("io.telegram.mcp/domain", nameParts.length > 1 ? nameParts[1] : "unknown");
            metadata.addProperty("io.telegram.mcp/tier", getString(source, "tier", "standard"));
            metadata.addProperty("io.telegram.mcp/confirmationArgument",
                    getString(source, "confirmation_argument", ""));
            metadata.addProperty("io.telegram.mcp/readbackStrategy",
                    getString(source, "readback_strategy", ""));
            if (source.has("preferred_alternatives") && source.get("preferred_alternatives").isJsonArray()) {
                metadata.add("io.telegram.mcp/preferredAlternatives",
                        source.getAsJsonArray("preferred_alternatives").deepCopy());
            }
            tool.add("_meta", metadata);
            tools.add(tool);
        }
        JsonObject result = new JsonObject();
        result.add("tools", tools);
        return result;
    }

    private JsonObject callTool(JsonObject params) {
        String name = getString(params, "name", null);
        JsonElement rawArguments = params.get("arguments");
        JsonObject arguments = rawArguments == null || rawArguments.isJsonNull()
                ? new JsonObject()
                : rawArguments.isJsonObject() ? rawArguments.getAsJsonObject() : null;
        JsonObject structured;
        boolean failed = false;
        try {
            if (name == null) {
                throw new TelegramMcpService.McpException(
                        "INVALID_ARGUMENT", "Missing tool name", false, null);
            }
            JsonObject tool = catalogTools.get(name);
            if (tool == null) {
                throw new TelegramMcpService.McpException(
                        "TOOL_NOT_FOUND", "Unknown Telegram MCP tool: " + name, false, null);
            }
            if (arguments == null) {
                throw new TelegramMcpService.McpException(
                        "INVALID_ARGUMENT", "arguments must be a JSON object", false, null);
            }
            validateSchema(arguments, tool.getAsJsonObject("input_schema"), "arguments");
            structured = service.call(name, arguments);
        } catch (TelegramMcpService.McpException error) {
            failed = true;
            structured = errorEnvelope(error.code, error.getMessage(), error.retryable, error.details);
        } catch (Throwable error) {
            FileLog.e(error);
            failed = true;
            structured = errorEnvelope("INTERNAL_ERROR", "Tool execution failed", true, null);
        }

        JsonObject result = new JsonObject();
        JsonArray content = new JsonArray();
        JsonObject text = new JsonObject();
        text.addProperty("type", "text");
        text.addProperty("text", GSON.toJson(structured));
        content.add(text);
        result.add("content", content);
        result.add("structuredContent", structured);
        result.addProperty("isError", failed);
        return result;
    }

    /** Validates the JSON-Schema subset emitted by the deterministic catalog generator. */
    private static void validateSchema(
            JsonElement value,
            JsonObject schema,
            String path) throws TelegramMcpService.McpException {
        String type = getString(schema, "type", "");
        switch (type) {
            case "object":
                if (!value.isJsonObject()) invalidSchema(path, "must be an object");
                validateObject(value.getAsJsonObject(), schema, path);
                break;
            case "array":
                if (!value.isJsonArray()) invalidSchema(path, "must be an array");
                validateArray(value.getAsJsonArray(), schema, path);
                break;
            case "string":
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                    invalidSchema(path, "must be a string");
                }
                validateString(value.getAsString(), schema, path);
                break;
            case "integer":
                validateInteger(value, schema, path);
                break;
            case "boolean":
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
                    invalidSchema(path, "must be a boolean");
                }
                break;
            case "":
                break;
            default:
                throw new IllegalStateException("Unsupported catalog schema type: " + type);
        }

        if (schema.has("const") && !schema.get("const").equals(value)) {
            invalidSchema(path, "must equal " + GSON.toJson(schema.get("const")));
        }
        if (schema.has("enum") && schema.get("enum").isJsonArray()) {
            boolean matched = false;
            for (JsonElement allowed : schema.getAsJsonArray("enum")) {
                if (allowed.equals(value)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) invalidSchema(path, "must be one of " + GSON.toJson(schema.get("enum")));
        }
    }

    private static void validateObject(
            JsonObject value,
            JsonObject schema,
            String path) throws TelegramMcpService.McpException {
        int minProperties = getInt(schema, "minProperties", 0);
        if (value.size() < minProperties) {
            invalidSchema(path, "must contain at least " + minProperties + " properties");
        }
        JsonObject properties = schema.has("properties") && schema.get("properties").isJsonObject()
                ? schema.getAsJsonObject("properties") : new JsonObject();
        if (schema.has("required") && schema.get("required").isJsonArray()) {
            for (JsonElement required : schema.getAsJsonArray("required")) {
                String key = required.getAsString();
                if (!value.has(key)) invalidSchema(path + "." + key, "is required");
            }
        }
        JsonElement additional = schema.get("additionalProperties");
        for (Map.Entry<String, JsonElement> entry : value.entrySet()) {
            JsonElement propertySchema = properties.get(entry.getKey());
            if (propertySchema != null && propertySchema.isJsonObject()) {
                validateSchema(entry.getValue(), propertySchema.getAsJsonObject(),
                        path + "." + entry.getKey());
            } else if (additional != null && additional.isJsonPrimitive()
                    && additional.getAsJsonPrimitive().isBoolean()
                    && !additional.getAsBoolean()) {
                invalidSchema(path + "." + entry.getKey(), "is not allowed");
            } else if (additional != null && additional.isJsonObject()) {
                validateSchema(entry.getValue(), additional.getAsJsonObject(),
                        path + "." + entry.getKey());
            }
        }
    }

    private static void validateArray(
            JsonArray value,
            JsonObject schema,
            String path) throws TelegramMcpService.McpException {
        int minItems = getInt(schema, "minItems", 0);
        int maxItems = getInt(schema, "maxItems", Integer.MAX_VALUE);
        if (value.size() < minItems || value.size() > maxItems) {
            invalidSchema(path, "must contain between " + minItems + " and " + maxItems + " items");
        }
        if (getBoolean(schema, "uniqueItems", false)) {
            Set<String> seen = new HashSet<>();
            for (JsonElement item : value) {
                if (!seen.add(GSON.toJson(item))) invalidSchema(path, "must contain unique items");
            }
        }
        JsonElement items = schema.get("items");
        if (items != null && items.isJsonObject()) {
            for (int index = 0; index < value.size(); index++) {
                validateSchema(value.get(index), items.getAsJsonObject(), path + "[" + index + "]");
            }
        }
    }

    private static void validateString(
            String value,
            JsonObject schema,
            String path) throws TelegramMcpService.McpException {
        int minLength = getInt(schema, "minLength", 0);
        int maxLength = getInt(schema, "maxLength", Integer.MAX_VALUE);
        if (value.length() < minLength || value.length() > maxLength) {
            invalidSchema(path, "length must be between " + minLength + " and " + maxLength);
        }
        if ("date-time".equals(getString(schema, "format", ""))) {
            try {
                Instant.parse(value);
            } catch (Throwable error) {
                invalidSchema(path, "must be an ISO-8601 UTC date-time");
            }
        }
    }

    private static void validateInteger(
            JsonElement value,
            JsonObject schema,
            String path) throws TelegramMcpService.McpException {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            invalidSchema(path, "must be an integer");
        }
        BigDecimal number;
        try {
            number = value.getAsBigDecimal();
        } catch (Throwable error) {
            invalidSchema(path, "must be an integer");
            return;
        }
        if (number.stripTrailingZeros().scale() > 0) invalidSchema(path, "must be an integer");
        if (schema.has("minimum")
                && number.compareTo(schema.get("minimum").getAsBigDecimal()) < 0) {
            invalidSchema(path, "must be >= " + schema.get("minimum"));
        }
        if (schema.has("maximum")
                && number.compareTo(schema.get("maximum").getAsBigDecimal()) > 0) {
            invalidSchema(path, "must be <= " + schema.get("maximum"));
        }
    }

    private static void invalidSchema(String path, String reason)
            throws TelegramMcpService.McpException {
        throw new TelegramMcpService.McpException(
                "INVALID_ARGUMENT", path + " " + reason, false, null);
    }

    private JsonObject resourcesList() {
        JsonArray resources = new JsonArray();
        JsonObject catalogResource = new JsonObject();
        catalogResource.addProperty("uri", "telegram://mcp/tool-catalog");
        catalogResource.addProperty("name", "Telegram MCP tool catalog");
        catalogResource.addProperty("mimeType", "application/json");
        resources.add(catalogResource);
        JsonObject result = new JsonObject();
        result.add("resources", resources);
        return result;
    }

    private JsonObject readResource(JsonObject params) throws TelegramMcpService.McpException {
        String uri = getString(params, "uri", null);
        if (!"telegram://mcp/tool-catalog".equals(uri)) {
            throw new TelegramMcpService.McpException(
                    "RESOURCE_NOT_FOUND", "Unknown MCP resource", false, null);
        }
        JsonArray contents = new JsonArray();
        JsonObject content = new JsonObject();
        content.addProperty("uri", uri);
        content.addProperty("mimeType", "application/json");
        content.addProperty("text", GSON.toJson(catalog));
        contents.add(content);
        JsonObject result = new JsonObject();
        result.add("contents", contents);
        return result;
    }

    private boolean isAuthorized(String header) {
        if (header == null || !header.startsWith("Bearer ")) {
            return false;
        }
        byte[] expected = bearerToken.getBytes(StandardCharsets.UTF_8);
        byte[] supplied = header.substring("Bearer ".length()).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, supplied);
    }

    private static JsonObject rpcResult(JsonElement id, JsonElement result) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id == null ? null : id.deepCopy());
        response.add("result", result);
        return response;
    }

    private static JsonObject rpcError(JsonElement id, int code, String message, JsonElement data) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id == null ? null : id.deepCopy());
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        if (data != null) {
            error.add("data", data);
        }
        response.add("error", error);
        return response;
    }

    static JsonObject successEnvelope(JsonElement data) {
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.add("data", data == null ? new JsonObject() : data);
        return result;
    }

    static JsonObject errorEnvelope(String code, String message, boolean retryable, JsonElement details) {
        JsonObject result = new JsonObject();
        result.addProperty("ok", false);
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        error.addProperty("retryable", retryable);
        if (details != null) {
            error.add("details", details);
        }
        result.add("error", error);
        return result;
    }

    private static Response jsonHttp(Response.Status status, JsonElement value) {
        Response response = newFixedLengthResponse(status, "application/json; charset=utf-8", GSON.toJson(value));
        response.addHeader("Cache-Control", "no-store");
        response.addHeader("X-Content-Type-Options", "nosniff");
        return response;
    }

    private static JsonObject loadCatalog(Context context) throws Exception {
        try (InputStream input = context.getAssets().open("mcp/telegram_mcp_tools.json");
             InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static String loadOrCreateToken(Context context) throws Exception {
        File directory = new File(context.getFilesDir(), "mcp");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("Cannot create MCP private directory");
        }
        File tokenFile = new File(directory, "token");
        if (tokenFile.isFile()) {
            byte[] existing = new byte[(int) tokenFile.length()];
            try (FileInputStream input = new FileInputStream(tokenFile)) {
                int offset = 0;
                while (offset < existing.length) {
                    int read = input.read(existing, offset, existing.length - offset);
                    if (read < 0) {
                        break;
                    }
                    offset += read;
                }
                String token = new String(existing, 0, offset, StandardCharsets.US_ASCII).trim();
                if (token.matches("[0-9a-f]{64}")) {
                    return token;
                }
            }
        }

        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        StringBuilder builder = new StringBuilder(64);
        for (byte value : random) {
            builder.append(String.format(java.util.Locale.US, "%02x", value & 0xff));
        }
        String token = builder.toString();
        try (FileOutputStream output = new FileOutputStream(tokenFile, false)) {
            output.write(token.getBytes(StandardCharsets.US_ASCII));
            output.getFD().sync();
        }
        return token;
    }

    private static String getString(JsonObject object, String key, String fallback) {
        try {
            return object.has(key) && !object.get(key).isJsonNull()
                    ? object.get(key).getAsString() : fallback;
        } catch (Throwable ignore) {
            return fallback;
        }
    }

    private static boolean getBoolean(JsonObject object, String key, boolean fallback) {
        try {
            return object.has(key) && object.get(key).getAsBoolean();
        } catch (Throwable ignore) {
            return fallback;
        }
    }

    private static int getInt(JsonObject object, String key, int fallback) {
        try {
            return object.has(key) && object.get(key).isJsonPrimitive()
                    ? object.get(key).getAsInt() : fallback;
        } catch (Throwable ignore) {
            return fallback;
        }
    }
}
