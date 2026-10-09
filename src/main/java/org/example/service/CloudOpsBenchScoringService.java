package org.example.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.common.aiops.AiOpsRunResult;
import org.example.common.aiops.AiOpsRunTrace;
import org.example.common.aiops.CloudOpsBenchCaseRepository;
import org.example.dto.CloudOpsBenchScoreResponse;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Computes the single-case metrics used by Cloud-OpsBench evaluation. */
@Service
public class CloudOpsBenchScoringService {

    private static final Map<String, String> RESOURCE_ALIASES = Map.ofEntries(
            Map.entry("pod", "pods"), Map.entry("pods", "pods"), Map.entry("po", "pods"),
            Map.entry("svc", "services"), Map.entry("service", "services"), Map.entry("services", "services"),
            Map.entry("ep", "endpoints"), Map.entry("endpoint", "endpoints"), Map.entry("endpoints", "endpoints"),
            Map.entry("deploy", "deployments"), Map.entry("deployment", "deployments"),
            Map.entry("deployments", "deployments"), Map.entry("rs", "replicasets"),
            Map.entry("replicaset", "replicasets"), Map.entry("replicasets", "replicasets"),
            Map.entry("node", "nodes"), Map.entry("nodes", "nodes"), Map.entry("event", "events"),
            Map.entry("events", "events"), Map.entry("pv", "persistentvolumes"),
            Map.entry("persistentvolume", "persistentvolumes"), Map.entry("persistentvolumes", "persistentvolumes"),
            Map.entry("pvc", "persistentvolumeclaims"),
            Map.entry("persistentvolumeclaim", "persistentvolumeclaims"),
            Map.entry("persistentvolumeclaims", "persistentvolumeclaims"));

    private final CloudOpsBenchCaseRepository caseRepository;
    private final ObjectMapper objectMapper;

    public CloudOpsBenchScoringService(CloudOpsBenchCaseRepository caseRepository, ObjectMapper objectMapper) {
        this.caseRepository = caseRepository;
        this.objectMapper = objectMapper;
    }

    public CloudOpsBenchScoreResponse score(AiOpsRunResult run) {
        String caseId = run.getContext().getCaseId();
        JsonNode annotation = caseRepository.loadProcessLabel(caseId);
        JsonNode groundTruth = annotation.path("result");

        double componentAccuracy = equalNormalized(
                run.getDiagnosis().getFaultObject(), text(groundTruth, "fault_object")) ? 1.0 : 0.0;
        double faultAccuracy = equalNormalized(
                run.getDiagnosis().getRootCause(), text(groundTruth, "root_cause")) ? 1.0 : 0.0;
        double jointRcaAccuracy = componentAccuracy == 1.0 && faultAccuracy == 1.0 ? 1.0 : 0.0;

        List<TrajectoryStep> trajectory = buildTrajectory(run.getTrace());
        boolean disableGetAlertsCredit = caseId.split("/").length > 1
                && "performance".equals(caseId.split("/")[1]) && componentAccuracy < 1.0;
        ProcessScores processScores = scoreProcess(annotation, trajectory, disableGetAlertsCredit);

        int toolCallCount = trajectory.size();
        int finalAnswerCount = isNotBlank(run.getDiagnosis().getRawReport()) ? 1 : 0;
        double redundantActionRate = redundantActionRate(trajectory);
        CloudOpsBenchScoreResponse.Scores scores = new CloudOpsBenchScoreResponse.Scores(
                componentAccuracy,
                faultAccuracy,
                jointRcaAccuracy,
                round2(processScores.milestoneCoverage()),
                round2(processScores.evidenceOrderConsistency()),
                round2(toolCallCount == 0 ? 0.0 : (double) processScores.evidenceStepIndexes().size() / toolCallCount),
                toolCallCount + finalAnswerCount,
                round2(redundantActionRate));
        return new CloudOpsBenchScoreResponse(caseId, scores);
    }

    private ProcessScores scoreProcess(JsonNode annotation, List<TrajectoryStep> steps,
            boolean disableGetAlertsCredit) {
        JsonNode milestones = annotation.path("milestones");
        Map<String, List<Match>> matchesByMilestone = new LinkedHashMap<>();
        Set<Integer> evidenceStepIndexes = new LinkedHashSet<>();
        List<String> milestoneIds = new ArrayList<>();

        if (milestones.isArray()) {
            for (JsonNode milestone : milestones) {
                String id = text(milestone, "id");
                if (id.isBlank()) {
                    continue;
                }
                milestoneIds.add(id);
                List<Match> matches = matchMilestone(milestone, steps, disableGetAlertsCredit);
                if (!matches.isEmpty()) {
                    matches.sort(Comparator.comparingInt(Match::stepIndex)
                            .thenComparingInt(Match::admissibleIndex));
                    matchesByMilestone.put(id, matches);
                    matches.forEach(match -> evidenceStepIndexes.add(match.stepIndex()));
                }
            }
        }

        Set<String> established = matchesByMilestone.keySet();
        Set<String> ordered = findOrderedMilestones(annotation.path("dependency_edges"), matchesByMilestone,
                milestoneIds, steps.size());
        JsonNode formula = annotation.get("completion_formula");
        if (formula == null || formula.isNull()) {
            formula = objectMapper.valueToTree(milestoneIds);
        }
        return new ProcessScores(formulaScore(formula, established), formulaScore(formula, ordered),
                evidenceStepIndexes);
    }

    private List<Match> matchMilestone(JsonNode milestone, List<TrajectoryStep> steps,
            boolean disableGetAlertsCredit) {
        List<Match> matches = new ArrayList<>();
        JsonNode admissibleUses = milestone.path("admissible_tool_uses");
        if (admissibleUses.isArray()) {
            for (int admissibleIndex = 0; admissibleIndex < admissibleUses.size(); admissibleIndex++) {
                JsonNode admissible = admissibleUses.get(admissibleIndex);
                if (disableGetAlertsCredit && "GetAlerts".equals(text(admissible, "tool_name"))) {
                    continue;
                }
                for (int stepIndex = 0; stepIndex < steps.size(); stepIndex++) {
                    if (matchesUse(admissible, steps.get(stepIndex))) {
                        matches.add(new Match(stepIndex, admissibleIndex));
                    }
                }
            }
        }

        JsonNode groups = milestone.path("admissible_evidence_groups");
        if (groups.isArray()) {
            for (JsonNode group : groups) {
                JsonNode uses = group.path("tool_uses");
                if (!uses.isArray() || uses.isEmpty() || (disableGetAlertsCredit && containsGetAlerts(uses))) {
                    continue;
                }
                int completionStep = -1;
                boolean complete = true;
                for (JsonNode use : uses) {
                    int firstMatch = firstMatch(use, steps);
                    if (firstMatch < 0) {
                        complete = false;
                        break;
                    }
                    completionStep = Math.max(completionStep, firstMatch);
                }
                if (complete) {
                    matches.add(new Match(completionStep, -1));
                }
            }
        }
        return matches;
    }

    private boolean containsGetAlerts(JsonNode uses) {
        for (JsonNode use : uses) {
            if ("GetAlerts".equals(text(use, "tool_name"))) {
                return true;
            }
        }
        return false;
    }

    private int firstMatch(JsonNode admissible, List<TrajectoryStep> steps) {
        for (int index = 0; index < steps.size(); index++) {
            if (matchesUse(admissible, steps.get(index))) {
                return index;
            }
        }
        return -1;
    }

    private boolean matchesUse(JsonNode admissible, TrajectoryStep step) {
        if (!text(admissible, "tool_name").equals(step.toolName())) {
            return false;
        }
        JsonNode expectedArguments = admissible.path("arguments");
        if (expectedArguments.isObject()) {
            var fields = expectedArguments.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                JsonNode actual = step.arguments().get(entry.getKey());
                if (actual == null || !argumentMatches(actual, entry.getValue())) {
                    return false;
                }
            }
        }
        JsonNode patterns = admissible.path("evidence_patterns");
        if (patterns.isArray()) {
            for (JsonNode evidencePattern : patterns) {
                if (!matchesEvidencePattern(evidencePattern, step.observation())) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean argumentMatches(JsonNode actual, JsonNode expected) {
        if (expected.isArray()) {
            for (JsonNode option : expected) {
                if (argumentMatches(actual, option)) {
                    return true;
                }
            }
            return false;
        }
        if (actual.equals(expected)) {
            return true;
        }
        if (actual.isTextual() && expected.isTextual()) {
            String actualValue = actual.asText();
            String expectedValue = expected.asText();
            return RESOURCE_ALIASES.getOrDefault(actualValue, actualValue)
                    .equals(RESOURCE_ALIASES.getOrDefault(expectedValue, expectedValue));
        }
        return false;
    }

    private boolean matchesEvidencePattern(JsonNode evidencePattern, JsonNode observation) {
        String kind = text(evidencePattern, "kind");
        String patternValue = evidencePattern.path("value").isTextual()
                ? evidencePattern.path("value").asText() : evidencePattern.path("value").toString();
        String observedText = observationText(observation);
        JsonNode flags = evidencePattern.path("flags");
        boolean caseSensitive = containsText(flags, "case_sensitive");
        switch (kind) {
            case "literal":
                return caseSensitive ? observedText.contains(patternValue)
                        : lower(observedText).contains(lower(patternValue));
            case "regex":
                int regexFlags = Pattern.DOTALL;
                if (!caseSensitive) {
                    regexFlags |= Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
                }
                if (containsText(flags, "multiline")) {
                    regexFlags |= Pattern.MULTILINE;
                }
                try {
                    return Pattern.compile(patternValue, regexFlags).matcher(observedText).find();
                } catch (PatternSyntaxException exception) {
                    throw new IllegalArgumentException("评分标注中的正则表达式无效: " + patternValue, exception);
                }
            case "json_path":
                return matchesJsonPath(evidencePattern, observation, patternValue);
            case "yaml_path":
                return matchesYamlPath(evidencePattern, observedText, patternValue);
            case "code_snippet":
                return squashWhitespace(observedText).contains(squashWhitespace(patternValue));
            default:
                throw new IllegalArgumentException("Cloud-OpsBench 不支持的 evidence pattern 类型: " + kind);
        }
    }

    private boolean matchesJsonPath(JsonNode evidencePattern, JsonNode observation, String patternValue) {
        JsonNode root = observation;
        if (root.isTextual()) {
            try {
                root = objectMapper.readTree(root.asText());
            } catch (Exception ignored) {
                return false;
            }
        }
        if (root == null || root.isTextual()) {
            return false;
        }
        String path = evidencePattern.path("path").isTextual()
                ? evidencePattern.path("path").asText() : patternValue;
        JsonNode value = readJsonPath(root, path);
        if (value == null) {
            return false;
        }
        if (evidencePattern.has("equals") && !evidencePattern.get("equals").isNull()) {
            return value.equals(evidencePattern.get("equals"));
        }
        if (evidencePattern.has("contains") && !evidencePattern.get("contains").isNull()) {
            JsonNode contains = evidencePattern.get("contains");
            if (value.isArray()) {
                for (JsonNode item : value) {
                    if (item.equals(contains)) {
                        return true;
                    }
                }
                return false;
            }
            return lower(value.isTextual() ? value.asText() : value.toString())
                    .contains(lower(contains.isTextual() ? contains.asText() : contains.toString()));
        }
        return isTruthy(value);
    }

    private JsonNode readJsonPath(JsonNode root, String path) {
        JsonNode current = root;
        for (String part : path.split("\\.")) {
            if (current.isObject()) {
                current = current.get(part);
            } else if (current.isArray()) {
                if ("*".equals(part)) {
                    return current;
                }
                try {
                    current = current.get(Integer.parseInt(part));
                } catch (NumberFormatException exception) {
                    return null;
                }
            } else {
                return null;
            }
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    private boolean matchesYamlPath(JsonNode evidencePattern, String observedText, String patternValue) {
        String path = evidencePattern.path("path").isTextual()
                ? evidencePattern.path("path").asText() : patternValue;
        String leaf = Pattern.quote(path.substring(path.lastIndexOf('.') + 1));
        JsonNode expected = evidencePattern.has("equals") && !evidencePattern.get("equals").isNull()
                ? evidencePattern.get("equals") : evidencePattern.get("contains");
        String expression = expected == null || expected.isNull()
                ? "^\\s*" + leaf + "\\s*:"
                : "^\\s*" + leaf + "\\s*:\\s*['\\\"]?" + Pattern.quote(jsonScalar(expected)) + "['\\\"]?\\s*$";
        return Pattern.compile(expression, Pattern.CASE_INSENSITIVE | Pattern.MULTILINE)
                .matcher(observedText).find();
    }

    private Set<String> findOrderedMilestones(JsonNode edges, Map<String, List<Match>> matchesByMilestone,
            List<String> milestoneIds, int stepCount) {
        Map<String, Set<String>> prerequisites = new HashMap<>();
        milestoneIds.forEach(id -> prerequisites.put(id, new HashSet<>()));
        if (edges.isArray()) {
            for (JsonNode edge : edges) {
                String source;
                String target;
                if (edge.isObject()) {
                    source = text(edge, "from");
                    target = text(edge, "to");
                } else if (edge.isArray() && edge.size() >= 2) {
                    source = edge.get(0).asText();
                    target = edge.get(1).asText();
                } else {
                    continue;
                }
                prerequisites.computeIfAbsent(target, ignored -> new HashSet<>()).add(source);
            }
        }

        Map<Integer, List<String>> hitsByStep = new HashMap<>();
        matchesByMilestone.forEach((id, matches) -> matches.forEach(match ->
                hitsByStep.computeIfAbsent(match.stepIndex(), ignored -> new ArrayList<>()).add(id)));
        Set<String> ordered = new LinkedHashSet<>();
        for (int stepIndex = 0; stepIndex < stepCount; stepIndex++) {
            List<String> candidates = new ArrayList<>(new LinkedHashSet<>(hitsByStep.getOrDefault(stepIndex, List.of())));
            boolean changed;
            do {
                changed = false;
                List<String> remaining = new ArrayList<>();
                for (String candidate : candidates) {
                    if (ordered.contains(candidate)) {
                        continue;
                    }
                    if (ordered.containsAll(prerequisites.getOrDefault(candidate, Set.of()))) {
                        ordered.add(candidate);
                        changed = true;
                    } else {
                        remaining.add(candidate);
                    }
                }
                candidates = remaining;
            } while (changed);
        }
        return ordered;
    }

    private double formulaScore(JsonNode formula, Set<String> milestoneIds) {
        if (formula == null || formula.isNull()) {
            return 0.0;
        }
        if (formula.isTextual()) {
            return milestoneIds.contains(formula.asText()) ? 1.0 : 0.0;
        }
        if (formula.isArray()) {
            return averageChildren(formula, milestoneIds);
        }
        if (!formula.isObject()) {
            return 0.0;
        }
        if (formula.has("all")) {
            return averageChildren(formula.get("all"), milestoneIds);
        }
        if (formula.has("any")) {
            JsonNode children = formula.get("any");
            double max = 0.0;
            for (JsonNode child : children) {
                max = Math.max(max, formulaScore(child, milestoneIds));
            }
            return max;
        }
        if (formula.has("milestone")) {
            return milestoneIds.contains(formula.path("milestone").asText()) ? 1.0 : 0.0;
        }
        return 0.0;
    }

    private double averageChildren(JsonNode children, Set<String> milestoneIds) {
        if (children == null || !children.isArray() || children.isEmpty()) {
            return 0.0;
        }
        double total = 0.0;
        for (JsonNode child : children) {
            total += formulaScore(child, milestoneIds);
        }
        return total / children.size();
    }

    private List<TrajectoryStep> buildTrajectory(AiOpsRunTrace trace) {
        List<TrajectoryStep> steps = new ArrayList<>();
        if (trace == null || trace.getToolCalls() == null) {
            return steps;
        }
        synchronized (trace.getToolCalls()) {
            for (AiOpsRunTrace.ToolCall call : trace.getToolCalls()) {
                ObjectNode arguments = objectMapper.createObjectNode();
                try {
                    JsonNode parsed = objectMapper.readTree(call.getInput());
                    if (parsed != null && parsed.isObject()) {
                        arguments = (ObjectNode) parsed;
                    }
                } catch (Exception ignored) {
                    // Match the benchmark evaluator's treatment of invalid arguments as an empty object.
                }
                steps.add(new TrajectoryStep(call.getToolName(), arguments,
                        parseObservation(call.getOutput())));
            }
        }
        return steps;
    }

    private JsonNode parseObservation(String output) {
        if (output == null) {
            return objectMapper.getNodeFactory().nullNode();
        }
        try {
            return objectMapper.readTree(output);
        } catch (Exception ignored) {
            return objectMapper.getNodeFactory().textNode(output);
        }
    }

    private double redundantActionRate(List<TrajectoryStep> steps) {
        if (steps.isEmpty()) {
            return 0.0;
        }
        Set<String> signatures = new HashSet<>();
        int redundant = 0;
        for (TrajectoryStep step : steps) {
            String signature = step.toolName() + ":" + canonicalJson(step.arguments());
            if (!signatures.add(signature)) {
                redundant++;
            }
        }
        return (double) redundant / steps.size();
    }

    private JsonNode canonicalize(JsonNode value) {
        if (value.isObject()) {
            ObjectNode sorted = objectMapper.createObjectNode();
            TreeMap<String, JsonNode> fields = new TreeMap<>();
            value.fields().forEachRemaining(entry -> fields.put(entry.getKey(), entry.getValue()));
            fields.forEach((key, child) -> sorted.set(key, canonicalize(child)));
            return sorted;
        }
        if (value.isArray()) {
            ArrayNode array = objectMapper.createArrayNode();
            value.forEach(child -> array.add(canonicalize(child)));
            return array;
        }
        return value;
    }

    private String canonicalJson(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(canonicalize(value));
        } catch (Exception exception) {
            return "{}";
        }
    }

    private boolean isTruthy(JsonNode value) {
        if (value.isNull() || value.isMissingNode()) {
            return false;
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        if (value.isNumber()) {
            return value.asDouble() != 0.0;
        }
        if (value.isTextual()) {
            return !value.asText().isEmpty();
        }
        return true;
    }

    private boolean containsText(JsonNode array, String text) {
        if (array != null && array.isArray()) {
            for (JsonNode item : array) {
                if (text.equals(item.asText())) {
                    return true;
                }
            }
        }
        return false;
    }

    private String observationText(JsonNode observation) {
        return observation == null || observation.isNull()
                ? "null" : observation.isTextual() ? observation.asText() : observation.toString();
    }

    private String jsonScalar(JsonNode value) {
        return value.isTextual() ? value.asText() : value.asText(value.toString());
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? "" : value.asText();
    }

    private boolean equalNormalized(String left, String right) {
        return normalize(left).equals(normalize(right));
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    private String squashWhitespace(String value) {
        return value.replaceAll("\\s+", " ").trim();
    }

    private boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private record TrajectoryStep(String toolName, ObjectNode arguments, JsonNode observation) {
    }

    private record Match(int stepIndex, int admissibleIndex) {
    }

    private record ProcessScores(double milestoneCoverage, double evidenceOrderConsistency,
            Set<Integer> evidenceStepIndexes) {
    }
}
