package com.finora.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * What the model returns (docs/superpowers/specs/2026-10-22-ai-insights-foundation-design.md §5.1):
 * sentences as templates -- prose with {@code {subject}} and {@code {subject.metric}} placeholders, never
 * a figure or a name of its own -- each with a type, and, for the month call with ranking on, the row
 * ids in the order it ranks them.
 */
public record AiInsightTemplate(List<Sentence> sentences, List<String> order) {

    public record Sentence(String type, String template) {}

    public AiInsightTemplate {
        sentences = List.copyOf(sentences);
        order = order == null ? List.of() : List.copyOf(order);
    }

    /** A parsed reply, or the reason it could not be read: exactly one of the two is set. */
    public record Parsed(AiInsightTemplate template, AiInsightReason reason) {}

    private static final ObjectMapper JSON = new ObjectMapper();

    public static Parsed parse(String modelReply) {
        if (modelReply == null || modelReply.isBlank()) return new Parsed(null, AiInsightReason.EMPTY);
        String body = modelReply.strip();
        // A model sometimes fences JSON as ```json ... ```; the fence is not part of the reply.
        if (body.startsWith("```")) {
            int firstNewline = body.indexOf('\n');
            int lastFence = body.lastIndexOf("```");
            if (firstNewline < 0 || lastFence <= firstNewline) return new Parsed(null, AiInsightReason.INVALID_JSON);
            body = body.substring(firstNewline + 1, lastFence).strip();
        }
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (Exception e) {
            return new Parsed(null, AiInsightReason.INVALID_JSON);
        }
        if (root == null || !root.isObject() || !root.path("sentences").isArray()) {
            return new Parsed(null, root != null && root.isObject() ? AiInsightReason.SCHEMA_MISMATCH : AiInsightReason.INVALID_JSON);
        }
        List<Sentence> sentences = new ArrayList<>();
        for (JsonNode s : root.get("sentences")) {
            if (!s.isObject() || !s.path("type").isTextual() || !s.path("template").isTextual()) {
                return new Parsed(null, AiInsightReason.SCHEMA_MISMATCH);
            }
            sentences.add(new Sentence(s.get("type").asText(), s.get("template").asText()));
        }
        List<String> order = new ArrayList<>();
        JsonNode orderNode = root.path("order");
        if (!orderNode.isMissingNode() && !orderNode.isNull()) {
            if (!orderNode.isArray()) return new Parsed(null, AiInsightReason.SCHEMA_MISMATCH);
            for (JsonNode id : orderNode) {
                if (!id.isTextual()) return new Parsed(null, AiInsightReason.SCHEMA_MISMATCH);
                order.add(id.asText());
            }
        }
        return new Parsed(new AiInsightTemplate(sentences, order), null);
    }
}
