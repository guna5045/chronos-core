package com.chronoscore.worker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Configuration properties for the Chronos Worker runtime, execution limits, and gRPC communication.
 */
@ConfigurationProperties(prefix = "chronos.worker")
public class WorkerProperties {

    private boolean enabled = true;
    private String id;
    private String hostname;
    private String ipAddress = "127.0.0.1";
    private int port = 9091;
    private int maxConcurrency = 8;
    private String version = "0.1.0-SNAPSHOT";
    private String coordinatorHost = "localhost";
    private int coordinatorPort = 9090;
    private Duration callTimeout = Duration.ofSeconds(5);
    private Set<String> supportedTaskTypes = new HashSet<>(Set.of("DEFAULT", "COMPUTE", "DATA_PIPELINE", "REPORT_GEN"));

    private final Heartbeat heartbeat = new Heartbeat();

    public WorkerProperties() {
        // Resolve safe fallback worker id and hostname
        try {
            this.hostname = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            this.hostname = "localhost";
        }
        this.id = "worker-" + UUID.randomUUID().toString().substring(0, 8);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        if (id != null && !id.isBlank()) {
            this.id = id;
        }
    }

    public String getHostname() {
        return hostname;
    }

    public void setHostname(String hostname) {
        if (hostname != null && !hostname.isBlank()) {
            this.hostname = hostname;
        }
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public int getMaxConcurrency() {
        return maxConcurrency;
    }

    public void setMaxConcurrency(int maxConcurrency) {
        this.maxConcurrency = maxConcurrency;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getCoordinatorHost() {
        return coordinatorHost;
    }

    public void setCoordinatorHost(String coordinatorHost) {
        this.coordinatorHost = coordinatorHost;
    }

    public int getCoordinatorPort() {
        return coordinatorPort;
    }

    public void setCoordinatorPort(int coordinatorPort) {
        this.coordinatorPort = coordinatorPort;
    }

    public Duration getCallTimeout() {
        return callTimeout;
    }

    public void setCallTimeout(Duration callTimeout) {
        this.callTimeout = callTimeout;
    }

    public Set<String> getSupportedTaskTypes() {
        return supportedTaskTypes;
    }

    public void setSupportedTaskTypes(Set<String> supportedTaskTypes) {
        this.supportedTaskTypes = supportedTaskTypes;
    }

    public Heartbeat getHeartbeat() {
        return heartbeat;
    }

    public static class Heartbeat {
        private Duration interval = Duration.ofMillis(1000);
        private Duration reconnectBackoff = Duration.ofSeconds(2);

        public Duration getInterval() {
            return interval;
        }

        public void setInterval(Duration interval) {
            this.interval = interval;
        }

        public Duration getReconnectBackoff() {
            return reconnectBackoff;
        }

        public void setReconnectBackoff(Duration reconnectBackoff) {
            this.reconnectBackoff = reconnectBackoff;
        }
    }
}
