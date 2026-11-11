package dev.vikram.streaming;

import java.time.Duration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * A mock LLM: GET /?tokens=200&ms=100 streams one server-sent event per token, ms apart
 * (20 s by default), then "[DONE]". Non-blocking, so thousands of open streams are cheap.
 */
@SpringBootApplication
@RestController
public class StreamingApplication {

  public static void main(String[] args) {
    SpringApplication.run(StreamingApplication.class, args);
  }

  @GetMapping(path = "/", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public Flux<ServerSentEvent<String>> complete(
      @RequestParam(defaultValue = "200") int tokens,
      @RequestParam(defaultValue = "100") long ms) {
    return Flux.interval(Duration.ofMillis(ms))
        .take(tokens)
        .map(i -> ServerSentEvent.builder("token-" + i).build())
        .concatWith(Flux.just(ServerSentEvent.builder("[DONE]").build()));
  }
}
