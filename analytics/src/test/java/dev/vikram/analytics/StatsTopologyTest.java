package dev.vikram.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Properties;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.TestOutputTopic;
import org.apache.kafka.streams.TopologyTestDriver;
import org.apache.kafka.streams.state.WindowStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StatsTopologyTest {

  private static final Instant T0 = Instant.parse("2026-11-11T12:00:00Z");
  private TopologyTestDriver driver;
  private TestInputTopic<String, String> logs;
  private TestOutputTopic<String, String> out;

  @BeforeEach
  void setUp() {
    Properties p = new Properties();
    p.put(StreamsConfig.APPLICATION_ID_CONFIG, "test");
    p.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "unused:9092");
    driver = new TopologyTestDriver(StatsTopology.build(), p);
    logs = driver.createInputTopic(StatsTopology.INPUT, new StringSerializer(), new StringSerializer());
    out = driver.createOutputTopic(StatsTopology.OUTPUT, new StringDeserializer(), new StringDeserializer());
  }

  @AfterEach
  void tearDown() {
    driver.close();
  }

  private static String log(String node, String authority, String backend, int status, int ms) {
    return """
        {"node":"%s","authority":"%s","backend":"%s","status":%d,"duration_ms":%d,"flags":"-"}"""
        .formatted(node, authority, backend, status, ms);
  }

  @Test
  void countsErrorsAndLatencyPerGroupHostAndBackend() {
    for (int i = 0; i < 98; i++) {
      logs.pipeInput(null, log("envoy-canary-abc", "crm.edge.local", "billing", 200, 8), T0.plusMillis(i));
    }
    logs.pipeInput(null, log("envoy-canary-abc", "crm.edge.local:10443", "billing", 503, 900), T0.plusMillis(100));
    logs.pipeInput(null, log("envoy-canary-abc", "crm.edge.local", "billing", 503, 1500), T0.plusMillis(101));
    logs.pipeInput(null, log("envoy-stable-xyz", "crm.edge.local", "sales", 200, 1), T0.plusMillis(102));
    logs.pipeInput(null, "[warning] not an access log line", T0.plusMillis(103));

    WindowStore<String, WindowStats> store = driver.getWindowStore(StatsTopology.STORE);
    WindowStats canary = store.fetch("canary|crm.edge.local|billing", T0.toEpochMilli());
    assertThat(canary.count).isEqualTo(100);        // port stripped from the :10443 request
    assertThat(canary.errors).isEqualTo(2);
    assertThat(canary.percentileMs(0.5)).isEqualTo(10);
    assertThat(canary.percentileMs(0.99)).isEqualTo(1000);
    assertThat(store.fetch("stable|crm.edge.local|sales", T0.toEpochMilli()).count).isEqualTo(1);
    assertThat(out.readKeyValuesToList()).isNotEmpty();
  }

  @Test
  void windowsAreFiveSecondsAndMergeable() {
    logs.pipeInput(null, log("envoy-stable-1", "a.local", "sales", 200, 3), T0);
    logs.pipeInput(null, log("envoy-stable-1", "a.local", "sales", 500, 3), T0.plusSeconds(6));
    WindowStore<String, WindowStats> store = driver.getWindowStore(StatsTopology.STORE);
    WindowStats first = store.fetch("stable|a.local|sales", T0.toEpochMilli());
    WindowStats second = store.fetch("stable|a.local|sales", T0.plusSeconds(5).toEpochMilli());
    assertThat(first.errors).isZero();
    assertThat(second.errors).isEqualTo(1);
    assertThat(new WindowStats().merge(first).merge(second).errorRate()).isEqualTo(0.5);
  }

  @Test
  void requestsWithNoRouteHaveBackendDash() {
    assertThat(AccessLog.parse(log("envoy-stable-1", "nope.local", "-", 404, 0)))
        .get().extracting(AccessLog::backend).isEqualTo("-");
    assertThat(AccessLog.parse("{\"status\":200}")).isEmpty();   // no node: not ours
  }

  @Test
  void countsPerHostnameMetricsOnlyForRoutedRequests() {
    var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    var runner = new StreamsRunner("unused:9092", registry);
    driver.close();
    Properties p = new Properties();
    p.put(StreamsConfig.APPLICATION_ID_CONFIG, "test2");
    p.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "unused:9092");
    driver = new TopologyTestDriver(StatsTopology.build(runner::count), p);
    logs = driver.createInputTopic(StatsTopology.INPUT, new StringSerializer(), new StringSerializer());

    logs.pipeInput(null, log("envoy-stable-1", "crm.edge.local", "billing", 503, 3), T0);
    logs.pipeInput(null, log("envoy-stable-1", "crm.edge.local", "billing", 503, 3), T0);
    logs.pipeInput(null, log("envoy-stable-1", "crm.edge.local", "sales", 200, 3), T0);
    logs.pipeInput(null, log("envoy-stable-1", "random-attacker-host-123", "-", 404, 0), T0);

    assertThat(registry.counter("edge.host.requests", "group", "stable", "host", "crm.edge.local",
        "status_class", "5xx").count()).isEqualTo(2);
    assertThat(registry.find("edge.host.requests").tag("host", "random-attacker-host-123").counter())
        .isNull();
  }
}
