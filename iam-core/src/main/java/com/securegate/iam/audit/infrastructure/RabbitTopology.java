package com.securegate.iam.audit.infrastructure;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.util.Map;

/** Packages and declares the same non-secret topology used by the local Compose bootstrap. */
public final class RabbitTopology {
  private final ObjectMapper json;

  public RabbitTopology(ObjectMapper json) {
    this.json = json;
  }

  public void declare(Channel channel) throws IOException {
    try (var input = getClass().getResourceAsStream("/rabbitmq/definitions.json")) {
      if (input == null) {
        throw new IOException("Missing broker topology.");
      }
      JsonNode topology = json.readTree(input);
      for (var exchange : topology.path("exchanges")) {
        channel.exchangeDeclare(
            exchange.path("name").asText(),
            exchange.path("type").asText(),
            exchange.path("durable").asBoolean(),
            exchange.path("auto_delete").asBoolean(),
            exchange.path("internal").asBoolean(),
            arguments(exchange));
      }
      for (var queue : topology.path("queues")) {
        channel.queueDeclare(
            queue.path("name").asText(),
            queue.path("durable").asBoolean(),
            false,
            queue.path("auto_delete").asBoolean(),
            arguments(queue));
      }
      for (var binding : topology.path("bindings")) {
        if (!"queue".equals(binding.path("destination_type").asText())) {
          throw new IOException("Unsupported broker topology binding.");
        }
        channel.queueBind(
            binding.path("destination").asText(),
            binding.path("source").asText(),
            binding.path("routing_key").asText(),
            arguments(binding));
      }
    }
  }

  private Map<String, Object> arguments(JsonNode definition) {
    return json.convertValue(
        definition.path("arguments"), new TypeReference<Map<String, Object>>() {});
  }
}
