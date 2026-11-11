package dev.vikram.edge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class EdgeControlPlaneApplication {

  public static void main(String[] args) {
    // The probe sets a custom Host header to pick a virtual host on Envoy.
    // Java's HttpClient treats Host as restricted unless this is set before the client loads.
    System.setProperty("jdk.httpclient.allowRestrictedHeaders", "host");
    SpringApplication.run(EdgeControlPlaneApplication.class, args);
  }
}
