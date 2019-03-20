/**
 * Copyright (C) 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.management.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.gravitee.management.model.*;
import io.gravitee.management.service.EventService;
import io.gravitee.management.service.InstanceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
@Component
public class InstanceServiceImpl implements InstanceService {

    private static final Logger LOGGER = LoggerFactory.getLogger(InstanceServiceImpl.class);

    @Autowired
    private EventService eventService;

    @Autowired
    private ObjectMapper objectMapper;

    private static final List<EventType> instancesAllState = new ArrayList<>();

    {
        instancesAllState.add(EventType.GATEWAY_STARTED);
        instancesAllState.add(EventType.GATEWAY_STOPPED);
    }

    private static final List<EventType> instancesRunningOnly = new ArrayList<>();

    {
        instancesRunningOnly.add(EventType.GATEWAY_STARTED);
    }

    @Override
    public Collection<InstanceListItem> findInstances(boolean includeStopped, final String gatewayId) {
        final EventQuery query = new EventQuery();
        if (includeStopped) {
            query.setTypes(instancesAllState);
        } else {
            query.setTypes(instancesRunningOnly);
        }
        if (gatewayId != null) {
            query.setId(gatewayId);
        }
        final Collection<EventEntity> events = eventService.search(query);

        Instant nowMinusXMinutes = Instant.now().minus(5, ChronoUnit.MINUTES);
        return events.stream().map(
                event -> {
                    Map<String, String> props = event.getProperties();
                    InstanceListItem instance = new InstanceListItem(props.get("id"));
                    instance.setEvent(event.getId());
                    instance.setLastHeartbeatAt(new Date(Long.parseLong(props.get("last_heartbeat_at"))));
                    instance.setStartedAt(new Date(Long.parseLong(props.get("started_at"))));

                    if (event.getPayload() != null) {
                        try {
                            InstanceInfo info = objectMapper.readValue(event.getPayload(), InstanceInfo.class);
                            instance.setHostname(info.getHostname());
                            instance.setIp(info.getIp());
                            instance.setPort(info.getPort());
                            instance.setVersion(info.getVersion());
                            instance.setTags(info.getTags());
                            instance.setTenant(info.getTenant());
                            instance.setOperatingSystemName(info.getSystemProperties().get("os.name"));
                        } catch (IOException ioe) {
                            LOGGER.error("Unexpected error while getting instance informations from event payload", ioe);
                        }
                    }

                    if (event.getType() == EventType.GATEWAY_STARTED) {
                        instance.setState(InstanceState.STARTED);
                        // If last heartbeat timestamp is < now - 5m, set as unknown state
                        Instant lastHeartbeat = Instant.ofEpochMilli(instance.getLastHeartbeatAt().getTime());
                        if (lastHeartbeat.isBefore(nowMinusXMinutes)) {
                            instance.setState(InstanceState.UNKNOWN);
                        }
                    } else {
                        instance.setState(InstanceState.STOPPED);
                        instance.setStoppedAt(new Date(Long.parseLong(props.get("stopped_at"))));
                    }

                    return instance;
                }
        ).collect(Collectors.toList());
    }

    @Override
    public Collection<InstanceListItem> findInstances(boolean includeStopped) {
        return findInstances(includeStopped, null);
    }

    @Override
    public InstanceEntity findById(String eventId) {
        EventEntity event = eventService.findById(eventId);
        Instant nowMinusXMinutes = Instant.now().minus(5, ChronoUnit.MINUTES);

        Map<String, String> props = event.getProperties();
        InstanceEntity instance = new InstanceEntity(props.get("id"));
        instance.setLastHeartbeatAt(new Date(Long.parseLong(props.get("last_heartbeat_at"))));
        instance.setStartedAt(new Date(Long.parseLong(props.get("started_at"))));

        if (event.getPayload() != null) {
            try {
                InstanceInfo info = objectMapper.readValue(event.getPayload(), InstanceInfo.class);
                instance.setHostname(info.getHostname());
                instance.setIp(info.getIp());
                instance.setPort(info.getPort());
                instance.setTenant(info.getTenant());
                instance.setVersion(info.getVersion());
                instance.setTags(info.getTags());
                instance.setSystemProperties(info.getSystemProperties());
                instance.setPlugins(info.getPlugins());
            } catch (IOException ioe) {
                LOGGER.error("Unexpected error while getting instance informations from event payload", ioe);
            }
        }

        if (event.getType() == EventType.GATEWAY_STARTED) {
            instance.setState(InstanceState.STARTED);
            // If last heartbeat timestamp is < now - 5m, set as unknown state
            Instant lastHeartbeat = Instant.ofEpochMilli(instance.getLastHeartbeatAt().getTime());
            if (lastHeartbeat.isBefore(nowMinusXMinutes)) {
                instance.setState(InstanceState.UNKNOWN);
            }
        } else {
            instance.setState(InstanceState.STOPPED);
            instance.setStoppedAt(new Date(Long.parseLong(props.get("stopped_at"))));
        }

        return instance;
    }


    private static class InstanceInfo {
        private String id;
        private String version;
        private List<String> tags;
        private Set<PluginEntity> plugins;
        private String hostname;
        private String ip;
        private String port;
        private String tenant;
        private Map<String, String> systemProperties;

        public String getHostname() {
            return hostname;
        }

        public void setHostname(String hostname) {
            this.hostname = hostname;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getIp() {
            return ip;
        }

        public void setIp(String ip) {
            this.ip = ip;
        }

        public String getPort() {
            return port;
        }

        public void setPort(String port) {
            this.port = port;
        }

        public Map<String, String> getSystemProperties() {
            return systemProperties;
        }

        public void setSystemProperties(Map<String, String> systemProperties) {
            this.systemProperties = systemProperties;
        }

        public List<String> getTags() {
            return tags;
        }

        public void setTags(List<String> tags) {
            this.tags = tags;
        }

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }

        public Set<PluginEntity> getPlugins() {
            return plugins;
        }

        public void setPlugins(Set<PluginEntity> plugins) {
            this.plugins = plugins;
        }

        public String getTenant() {
            return tenant;
        }

        public void setTenant(String tenant) {
            this.tenant = tenant;
        }
    }
}
