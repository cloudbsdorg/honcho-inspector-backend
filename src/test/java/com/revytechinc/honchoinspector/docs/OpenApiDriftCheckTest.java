package com.revytechinc.honchoinspector.docs;

import static org.assertj.core.api.Assertions.fail;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Drift check between the hand-written OpenAPI contract ({@code docs/openapi.yaml})
 * and the springdoc-generated snapshot ({@code docs/openapi.generated.json}).
 *
 * <p>Compares only structural shape — paths, HTTP methods per path, and
 * operationId per operation. Descriptions, examples, parameters, requestBody,
 * responses, info, and servers are intentionally ignored. Tolerates the
 * {@code openapi} version difference (3.0.3 hand-written vs 3.0.1 generated).
 *
 * <p>Operations marked {@code x-phase: "2"} in the hand-written file are
 * excluded from comparison — those are documented Phase 2 placeholders that
 * have no implementation yet, so the generated snapshot cannot reference them.
 *
 * <p>Tagged {@code @Tag("drift")} so it can be excluded with {@code -Dgroups=!drift}.
 */
@Tag("drift")
class OpenApiDriftCheckTest {

    private static final Set<String> HTTP_METHODS = new LinkedHashSet<>(Arrays.asList(
            "get", "post", "put", "delete", "patch", "head", "options", "trace"));
    private static final String PHASE_2_MARKER = "2";

    private static final File HAND_WRITTEN =
            Paths.get(System.getProperty("user.dir"), "docs", "openapi.yaml").toFile();
    private static final File GENERATED =
            Paths.get(System.getProperty("user.dir"), "docs", "openapi.generated.json").toFile();

    private static Map<String, Map<String, Map<String, Object>>> handWrittenSpec;
    private static Map<String, Map<String, Map<String, Object>>> generatedSpec;

    @BeforeAll
    static void loadSpecs() throws Exception {
        handWrittenSpec = parseSpec(HAND_WRITTEN);
        generatedSpec = parseSpec(GENERATED);
    }

    @Test
    void noDriftBetweenHandWrittenAndGenerated() {
        StringBuilder diffs = new StringBuilder();

        Set<String> hwPaths = handWrittenSpec.keySet();
        Set<String> genPaths = generatedSpec.keySet();
        for (String p : new TreeSet<>(hwPaths)) {
            if (!genPaths.contains(p)) {
                diffs.append("  Drift: hand-written has path ").append(p)
                        .append(" but generated doesn't\n");
            }
        }
        for (String p : new TreeSet<>(genPaths)) {
            if (!hwPaths.contains(p)) {
                diffs.append("  Drift: generated has path ").append(p)
                        .append(" but hand-written doesn't\n");
            }
        }

        for (String path : new TreeSet<>(hwPaths)) {
            Map<String, Map<String, Object>> genOps = generatedSpec.get(path);
            if (genOps == null) continue;
            Map<String, Map<String, Object>> hwOps = handWrittenSpec.get(path);
            for (String method : new TreeSet<>(hwOps.keySet())) {
                if (!genOps.containsKey(method)) {
                    diffs.append("  Drift: hand-written ").append(path).append(" has method ")
                            .append(method.toUpperCase()).append(" but generated doesn't\n");
                }
            }
            for (String method : new TreeSet<>(genOps.keySet())) {
                if (!hwOps.containsKey(method)) {
                    diffs.append("  Drift: generated ").append(path).append(" has method ")
                            .append(method.toUpperCase()).append(" but hand-written doesn't\n");
                }
            }
        }

        for (String path : new TreeSet<>(hwPaths)) {
            Map<String, Map<String, Object>> genOps = generatedSpec.get(path);
            if (genOps == null) continue;
            Map<String, Map<String, Object>> hwOps = handWrittenSpec.get(path);
            for (String method : hwOps.keySet()) {
                Map<String, Object> genOp = genOps.get(method);
                if (genOp == null) continue;
                String hwId = stringOrNull(hwOps.get(method).get("operationId"));
                String genId = stringOrNull(genOp.get("operationId"));
                if (hwId == null && genId == null) continue;
                if (hwId == null || genId == null || !hwId.equals(genId)) {
                    diffs.append("  Drift: ").append(path).append(" ").append(method.toUpperCase())
                            .append(" operationId mismatch: hand-written=").append(formatId(hwId))
                            .append(", generated=").append(formatId(genId)).append('\n');
                }
            }
        }

        if (diffs.length() > 0) {
            fail("Drift detected between docs/openapi.yaml (hand-written) and "
                    + "docs/openapi.generated.json (springdoc snapshot):\n" + diffs);
        }
    }

    private static Map<String, Map<String, Map<String, Object>>> parseSpec(File file) throws Exception {
        if (!Files.isReadable(file.toPath())) {
            fail("Drift check cannot read " + file.getAbsolutePath()
                    + " — file missing or unreadable. Working dir: " + System.getProperty("user.dir"));
        }
        Map<String, Map<String, Object>> pathsRaw;
        if (file.getName().endsWith(".yaml") || file.getName().endsWith(".yml")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> root = new Yaml().load(new FileInputStream(file));
            @SuppressWarnings("unchecked")
            Map<String, Map<String, Object>> raw = root == null ? null
                    : (Map<String, Map<String, Object>>) root.get("paths");
            pathsRaw = raw;
        } else {
            JsonNode root = new ObjectMapper().readTree(file);
            JsonNode pathsNode = root.get("paths");
            pathsRaw = new TreeMap<>();
            if (pathsNode != null) {
                for (Map.Entry<String, JsonNode> e : pathsNode.properties()) {
                    pathsRaw.put(e.getKey(), jsonObjectToMap(e.getValue()));
                }
            }
        }
        Map<String, Map<String, Map<String, Object>>> out = new TreeMap<>();
        if (pathsRaw == null) return out;
        for (Map.Entry<String, Map<String, Object>> pathEntry : pathsRaw.entrySet()) {
            String path = pathEntry.getKey();
            Map<String, Object> methodsRaw = pathEntry.getValue();
            if (methodsRaw == null) continue;
            Map<String, Map<String, Object>> keptOps = new TreeMap<>();
            for (Map.Entry<String, Object> methodEntry : methodsRaw.entrySet()) {
                String method = methodEntry.getKey();
                if (!HTTP_METHODS.contains(method)) continue;
                if (!(methodEntry.getValue() instanceof Map<?, ?> opMap)) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> op = (Map<String, Object>) opMap;
                if (isPhase2(op)) continue;
                keptOps.put(method, op);
            }
            if (!keptOps.isEmpty()) out.put(path, keptOps);
        }
        return out;
    }

    private static Map<String, Object> jsonObjectToMap(JsonNode obj) {
        Map<String, Object> map = new TreeMap<>();
        for (Map.Entry<String, JsonNode> e : obj.properties()) {
            JsonNode v = e.getValue();
            if (v.isObject()) {
                map.put(e.getKey(), jsonObjectToMap(v));
            } else if (v.isTextual()) {
                map.put(e.getKey(), v.asText());
            } else if (v.isNumber()) {
                map.put(e.getKey(), v.numberValue());
            } else if (v.isBoolean()) {
                map.put(e.getKey(), v.booleanValue());
            } else if (v.isNull()) {
                map.put(e.getKey(), null);
            } else {
                map.put(e.getKey(), v);
            }
        }
        return map;
    }

    private static boolean isPhase2(Map<String, Object> op) {
        Object xPhase = op.get("x-phase");
        return PHASE_2_MARKER.equals(xPhase) || Integer.valueOf(2).equals(xPhase);
    }

    private static String stringOrNull(Object o) {
        return (o instanceof String s && !s.isEmpty()) ? s : null;
    }

    private static String formatId(String id) {
        return id == null ? "<absent>" : "'" + id + "'";
    }
}
