package io.diagrid.dapr;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Representer;

public class DaprContainer extends GenericContainer<DaprContainer> {

    public enum DaprLogLevel {
        error, warn, info, debug
    }

    public static class Subscription {
        String name;
        String pubsubName;
        String topic;
        String route;

        public Subscription(String name, String pubsubName, String topic, String route) {
            this.name = name;
            this.pubsubName = pubsubName;
            this.topic = topic;
            this.route = route;
        }

    }

    public static class MetadataEntry {
        String name;
        Object value;

        public MetadataEntry(String name, Object value) {
            this.name = name;
            this.value = value;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Object getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }


    }

    public static class Component {
        String name;

        String type;

        String version;

        List<MetadataEntry> metadata;

        public Component(String name, String type, String version, Map<String, Object> metadata) {
            this.name = name;
            this.type = type;
            this.version = version;
            this.metadata = new ArrayList<MetadataEntry>();
            if (!metadata.isEmpty()) {
                for (Map.Entry<String, Object> entry : metadata.entrySet()) {
                    this.metadata.add(new MetadataEntry(entry.getKey(), entry.getValue()));
                }
            }
        }

        public Component(String name, String type, String version, List<MetadataEntry> metadataEntries) {
            this.name = name;
            this.type = type;
            this.version = version;
            metadata = Objects.requireNonNull(metadataEntries);
        }

        public String getName() {
            return name;
        }

        public String getType() {
            return type;
        }

        public List<MetadataEntry> getMetadata() {
            return metadata;
        }

        public String getVersion() {
            return version;
        }

    }

    private static final int DAPRD_HTTP_PORT = 3500;
    private static final int DAPRD_GRPC_PORT = 50001;
    private final Set<Component> components = new HashSet<>();
    private final Set<Subscription> subscriptions = new HashSet<>();
    private String appName;
    private Integer appPort = 8080;
    private DaprLogLevel daprLogLevel = DaprLogLevel.info;
    private String appChannelAddress = "localhost";
    private String placementService = "placement";
    private static final DockerImageName DEFAULT_IMAGE_NAME = DockerImageName.parse("daprio/daprd");
    private Yaml yaml;
    private DaprPlacementContainer placementContainer;
    private String placementDockerImageName = "daprio/placement";
    private boolean shouldReusePlacement = false;

    public DaprContainer(DockerImageName dockerImageName) {
        super(dockerImageName);
        dockerImageName.assertCompatibleWith(DEFAULT_IMAGE_NAME);
        // For susbcriptions the container needs to access the app channel
        withAccessToHost(true);
        // Here we don't want to wait for the Dapr sidecar to be ready, as the sidecar
        // needs to
        // connect with the application for susbcriptions

        withExposedPorts(DAPRD_HTTP_PORT, DAPRD_GRPC_PORT);

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);

        Representer representer = new YamlRepresenter(options);
        representer.addClassTag(MetadataEntry.class, Tag.MAP);
        this.yaml = new Yaml(representer);
    }

    public DaprContainer(String image) {
        this(DockerImageName.parse(image));
    }

    public Set<Component> getComponents() {
        return components;
    }

    public Set<Subscription> getSubscriptions() {
        return subscriptions;
    }

    public DaprContainer withComponent(Component component) {
        components.add(component);
        return this;
    }

    public DaprContainer withAppPort(Integer port) {
        this.appPort = port;
        return this;
    }

    public DaprContainer withPlacementService(String placementService) {
        this.placementService = placementService;
        return this;
    }

    public DaprContainer withAppName(String appName) {
        this.appName = appName;
        return this;
    }

    public DaprContainer withDaprLogLevel(DaprLogLevel daprLogLevel) {
        this.daprLogLevel = daprLogLevel;
        return this;
    }

    public DaprContainer withSubscription(String name, String pubSubName, String pubSubTopic, String route) {
        subscriptions.add(new Subscription(name, pubSubName, pubSubTopic, route));
        return this;
    }

    public DaprContainer withComponent(String name, String type, String version, List<MetadataEntry> metadataEntries) {
        components.add(new Component(name, type, version, metadataEntries));
        return this;
    }

    public DaprContainer withComponent(Path path) {
        try {
            Map<String, Object> component = this.yaml.loadAs(
                    Files.newInputStream(path),
                    Map.class
            );

            String type = (String) component.get("type");
            Map<String, Object> metadata = (Map<String, Object>) component.get("metadata");
            String name = (String) metadata.get("name");
            

            Map<String, Object> spec = (Map<String, Object>) component.get("spec");
            String version = (String) spec.get("version");
            List<Map<String, Object>> specMetadata = (List<Map<String, Object>>) spec.getOrDefault("metadata", Collections.emptyMap());

            ArrayList<MetadataEntry> metadataEntries = new ArrayList<>();

            for (Map<String, Object> specMetadataItem : specMetadata) {
                for (Map.Entry<String, Object> metadataItem : specMetadataItem.entrySet()) {
                    metadataEntries.add(new MetadataEntry(metadataItem.getKey(), metadataItem.getValue()));
                }
            }

            return withComponent(name, type, version, metadataEntries);
        } catch (IOException e) {
            logger().warn("Error while reading component from {}", path.toAbsolutePath());
        }
        return this;
    }

    public int getHTTPPort() {
        return getMappedPort(DAPRD_HTTP_PORT);
    }

    public String getHttpEndpoint() {
        return "http://" + getHost() + ":" + getMappedPort(DAPRD_HTTP_PORT);
    }

    public int getGRPCPort() {
        return getMappedPort(DAPRD_GRPC_PORT);
    }

    public DaprContainer withAppChannelAddress(String appChannelAddress) {
        this.appChannelAddress = appChannelAddress;
        return this;
    }

    public Map<String, Object> componentToMap(Component component) {
        Map<String, Object> componentProps = new HashMap<>();
        componentProps.put("apiVersion", "dapr.io/v1alpha1");
        componentProps.put("kind", "Component");

        Map<String, String> componentMetadata = new LinkedHashMap<>();
        componentMetadata.put("name", component.name);
        componentProps.put("metadata", componentMetadata);

        Map<String, Object> componentSpec = new HashMap<>();
        componentSpec.put("type", component.type);
        componentSpec.put("version", component.version);

        if (!component.metadata.isEmpty()) {
            componentSpec.put("metadata", component.metadata);
        }
        componentProps.put("spec", componentSpec);
        return componentProps;
    }

    public Map<String, Object> subscriptionToMap(Subscription subscription) {
        Map<String, Object> subscriptionProps = new HashMap<>();
        subscriptionProps.put("apiVersion", "dapr.io/v1alpha1");
        subscriptionProps.put("kind", "Subscription");

        Map<String, String> subscriptionMetadata = new LinkedHashMap<>();
        subscriptionMetadata.put("name", subscription.name);
        subscriptionProps.put("metadata", subscriptionMetadata);

        Map<String, Object> subscriptionSpec = new HashMap<>();
        subscriptionSpec.put("pubsubname", subscription.pubsubName);
        subscriptionSpec.put("topic", subscription.topic);
        subscriptionSpec.put("route", subscription.route);

        subscriptionProps.put("spec", subscriptionSpec);
        return subscriptionProps;
    }

    @Override
    protected void configure() {
        super.configure();

        if(getNetwork() == null){
            withNetwork(Network.newNetwork());
        }
        if (this.placementContainer == null) {
            this.placementContainer = new DaprPlacementContainer(this.placementDockerImageName)
                    .withNetwork(getNetwork())
                    .withNetworkAliases(placementService)
                    .withReuse(this.shouldReusePlacement);
            this.placementContainer.start();        
        }

        withCommand(
                "./daprd",
                "-app-id", appName,
                "--dapr-listen-addresses=0.0.0.0",
                "--app-protocol", "http",
                "-placement-host-address", placementService + ":50006",
                "--app-channel-address", appChannelAddress,
                "--app-port", Integer.toString(appPort),
                "--log-level", daprLogLevel.toString(),
                "-components-path", "/components");

        if (components.isEmpty()) {
            components.add(new Component("kvstore", "state.in-memory", "v1", Collections.emptyMap()));
            components.add(new Component("pubsub", "pubsub.in-memory", "v1", Collections.emptyMap()));
        }

        if (subscriptions.isEmpty() && !components.isEmpty()) {
            subscriptions.add(new Subscription("local", "pubsub", "topic", "/events"));
        }


        for (Component component : components) {
            String componentYaml = componentToYAML(component);
            withCopyToContainer(
                    Transferable.of(componentYaml), "/components/" + component.name + ".yaml");
        }

        for (Subscription subscription : subscriptions) {
            String subscriptionYaml = subscriptionToYAML(subscription);
            withCopyToContainer(
                    Transferable.of(subscriptionYaml), "/components/" + subscription.name + ".yaml");
        }

    }

    public String subscriptionToYAML(Subscription subscription) {
        Map<String, Object> subscriptionMap = subscriptionToMap(subscription);
        return yaml.dumpAsMap(subscriptionMap);
    }

    public String componentToYAML(Component component) {
        Map<String, Object> componentMap = componentToMap(component);
        return yaml.dumpAsMap(componentMap);
    }

    public String getAppName() {
        return appName;
    }

    public Integer getAppPort() {
        return appPort;
    }

    public String getAppChannelAddress() {
        return appChannelAddress;
    }

    public String getPlacementService() {
        return placementService;
    }

    public static DockerImageName getDefaultImageName() {
        return DEFAULT_IMAGE_NAME;
    }

    public DaprContainer withPlacementImage(String placementDockerImageName) {
        this.placementDockerImageName = placementDockerImageName;
        return this;
    }

    public DaprContainer withReusablePlacement(boolean reuse) {
        this.shouldReusePlacement = shouldReusePlacement;
        return this;
    }

    public DaprContainer withPlacementContainer(DaprPlacementContainer placementContainer) {
        this.placementContainer = placementContainer;
        return this;
    }

}