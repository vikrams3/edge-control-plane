package dev.vikram.streaming;

import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class StreamingApplicationTest {

  @Test
  void streamsTheRequestedTokensThenDone() {
    StepVerifier.create(new StreamingApplication().complete(3, 1).map(e -> e.data()))
        .expectNext("token-0", "token-1", "token-2", "[DONE]")
        .verifyComplete();
  }
}
