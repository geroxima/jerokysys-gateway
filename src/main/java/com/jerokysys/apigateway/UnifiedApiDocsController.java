package com.jerokysys.apigateway;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.swagger.v3.core.util.Json;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Serves one OpenAPI document that merges the specifications of all
 * downstream services. The document is available at /docs/all.
 */
@RestController
@EnableConfigurationProperties(UnifiedApiDocsController.DocsProperties.class)
public class UnifiedApiDocsController {

    private static final Logger log = LoggerFactory.getLogger(UnifiedApiDocsController.class);

    public record DocService(String serviceId, String pathPrefix) {}

    @ConfigurationProperties(prefix = "gateway.docs")
    public record DocsProperties(List<DocService> services) {}

    private final WebClient webClient;

    private final DocsProperties properties;

    public UnifiedApiDocsController(@LoadBalanced WebClient.Builder builder, DocsProperties properties) {
        this.webClient = builder.build();
        this.properties = properties;
    }

    @GetMapping(value = "/docs/all", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<String> unifiedSpec() {
        ObjectMapper mapper = Json.mapper();
        return Flux.fromIterable(properties.services())
                .flatMap(service -> fetch(mapper, service)
                        .map(doc -> Map.entry(service, doc))
                        .onErrorResume(e -> {
                            log.warn("Service {} is not in the unified document: {}",
                                    service.serviceId(), e.getMessage());
                            return Mono.empty();
                        }))
                .collectList()
                .map(entries -> merge(entries))
                .map(root -> write(mapper, root));
    }

    private Mono<JsonNode> fetch(ObjectMapper mapper, DocService service) {
        return webClient.get()
                .uri("http://{serviceId}/v3/api-docs", service.serviceId())
                .retrieve()
                .bodyToMono(String.class)
                .map(body -> read(mapper, body));
    }

    private ObjectNode merge(List<Map.Entry<DocService, JsonNode>> entries) {
        ObjectNode root = Json.mapper().createObjectNode();
        root.put("openapi", "3.1.0");
        ObjectNode info = root.putObject("info");
        info.put("title", "Distri API");
        info.put("version", "v1");
        ObjectNode paths = root.putObject("paths");
        ObjectNode components = root.putObject("components");
        ArrayNode tags = root.putArray("tags");
        Set<String> tagNames = new HashSet<>();

        for (Map.Entry<DocService, JsonNode> entry : entries) {
            DocService service = entry.getKey();
            JsonNode doc = entry.getValue();
            String id = service.serviceId();
            String prefix = service.pathPrefix() == null ? "" : service.pathPrefix();

            for (Map.Entry<String, JsonNode> path : doc.path("paths").properties()) {
                ObjectNode item = (ObjectNode) path.getValue().deepCopy();
                prefixRefs(item, id);
                paths.set(prefix + path.getKey(), item);
            }

            JsonNode docComponents = doc.get("components");
            if (docComponents != null) {
                mergeComponents(components, (ObjectNode) docComponents.deepCopy(), id);
            }

            for (JsonNode tag : doc.path("tags")) {
                if (tagNames.add(tag.path("name").asText())) {
                    tags.add(tag.deepCopy());
                }
            }
        }
        return root;
    }

    private void mergeComponents(ObjectNode target, ObjectNode source, String serviceId) {
        for (Map.Entry<String, JsonNode> section : source.properties()) {
            ObjectNode targetSection = (ObjectNode) target.with(section.getKey());
            List<Map.Entry<String, JsonNode>> definitions =
                    new ArrayList<>(section.getValue().properties());
            for (Map.Entry<String, JsonNode> definition : definitions) {
                ObjectNode value = (ObjectNode) definition.getValue();
                prefixRefs(value, serviceId);
                targetSection.set(serviceId + "." + definition.getKey(), value);
            }
        }
    }

    /**
     * Rewrites every "#/components/<section>/<name>" reference and every
     * security-scheme name so they point at the prefixed component of the
     * service.
     */
    private void prefixRefs(JsonNode node, String serviceId) {
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            JsonNode ref = object.get("$ref");
            if (ref != null && ref.isTextual() && ref.asText().startsWith("#/components/")) {
                String value = ref.asText();
                int lastSlash = value.lastIndexOf('/');
                object.put("$ref", value.substring(0, lastSlash + 1) + serviceId + "."
                        + value.substring(lastSlash + 1));
            }
            JsonNode security = object.get("security");
            if (security != null && security.isArray()) {
                for (JsonNode requirement : security) {
                    if (requirement.isObject()) {
                        ObjectNode requirementObject = (ObjectNode) requirement;
                        List<Map.Entry<String, JsonNode>> schemes =
                                new ArrayList<>(requirementObject.properties());
                        for (Map.Entry<String, JsonNode> scheme : schemes) {
                            requirementObject.set(serviceId + "." + scheme.getKey(),
                                    requirementObject.remove(scheme.getKey()));
                        }
                    }
                }
            }
            for (JsonNode child : object) {
                prefixRefs(child, serviceId);
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                prefixRefs(child, serviceId);
            }
        }
    }

    private JsonNode read(ObjectMapper mapper, String body) {
        try {
            return mapper.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot parse the OpenAPI document", e);
        }
    }

    private String write(ObjectMapper mapper, JsonNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot build the unified OpenAPI document", e);
        }
    }
}
