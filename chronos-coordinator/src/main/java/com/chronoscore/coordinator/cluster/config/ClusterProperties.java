package com.chronoscore.coordinator.cluster.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuration properties for the Coordinator's worker cluster management and gRPC server.
 */
@ConfigurationProperties(prefix = "chronos.coordinator")
public class ClusterProperties {

    private final Cluster cluster = new Cluster();
    private final Grpc grpc = new Grpc();

    public Cluster getCluster() {
        return cluster;
    }

    public Grpc getGrpc() {
        return grpc;
    }

    public static class Cluster {
        private boolean enabled = true;
        private Duration heartbeatTimeout = Duration.ofSeconds(3);
        private Duration evictionTimeout = Duration.ofSeconds(6);
        private Duration healthCheckInterval = Duration.ofSeconds(1);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getHeartbeatTimeout() {
            return heartbeatTimeout;
        }

        public void setHeartbeatTimeout(Duration heartbeatTimeout) {
            this.heartbeatTimeout = heartbeatTimeout;
        }

        public Duration getEvictionTimeout() {
            return evictionTimeout;
        }

        public void setEvictionTimeout(Duration evictionTimeout) {
            this.evictionTimeout = evictionTimeout;
        }

        public Duration getHealthCheckInterval() {
            return healthCheckInterval;
        }

        public void setHealthCheckInterval(Duration healthCheckInterval) {
            this.healthCheckInterval = healthCheckInterval;
        }
    }

    public static class Grpc {
        private boolean enabled = true;
        private int port = 9090;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }
    }
}
