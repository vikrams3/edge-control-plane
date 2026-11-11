package dev.vikram.edge;

import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * mTLS for the xDS gRPC channel, from application.yml "edge.xds-tls". With all three files
 * present, the control plane serves xDS over TLS and requires a client certificate signed by
 * the CA; otherwise plaintext (Docker Compose). The channel carries TLS private keys (SDS), so
 * in any shared network it must be encrypted and authenticated.
 *
 * @param certFile the control plane's certificate chain (PEM)
 * @param keyFile its private key (PEM, PKCS#8)
 * @param caFile CA that client (Envoy) certificates must be signed by
 */
@ConfigurationProperties(prefix = "edge.xds-tls")
public record XdsTlsProperties(String certFile, String keyFile, String caFile) {

  public boolean enabled() {
    return readable(certFile) && readable(keyFile) && readable(caFile);
  }

  private static boolean readable(String f) {
    return f != null && Files.isReadable(Path.of(f));
  }
}
