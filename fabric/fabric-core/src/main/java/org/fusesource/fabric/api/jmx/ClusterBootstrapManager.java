package org.fusesource.fabric.api.jmx;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;

import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Reference;
import org.codehaus.jackson.map.DeserializationConfig;
import org.codehaus.jackson.map.ObjectMapper;
import org.fusesource.fabric.api.CreateEnsembleOptions;
import org.fusesource.fabric.api.FabricException;
import org.fusesource.fabric.api.ZooKeeperClusterBootstrap;
import org.fusesource.fabric.api.jcip.ThreadSafe;
import org.fusesource.fabric.api.scr.AbstractComponent;
import org.fusesource.fabric.api.scr.ValidatingReference;
import org.fusesource.fabric.zookeeper.ZkDefs;
import org.osgi.service.component.ComponentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Stan Lewis
 */
@ThreadSafe
@Component(description = "Fabric ZooKeeper Cluster Bootstrap Manager JMX MBean") // Done
public final class ClusterBootstrapManager extends AbstractComponent implements ClusterBootstrapManagerMBean {

    private static final transient Logger LOG = LoggerFactory.getLogger(ClusterBootstrapManager.class);

    private static ObjectName OBJECT_NAME;
    static {
        try {
            OBJECT_NAME = new ObjectName("org.fusesource.fabric:type=ClusterBootstrapManager");
        } catch (MalformedObjectNameException e) {
            // ignore
        }
    }

    @Reference(referenceInterface = ZooKeeperClusterBootstrap.class)
    private final ValidatingReference<ZooKeeperClusterBootstrap> bootstrap = new ValidatingReference<ZooKeeperClusterBootstrap>();
    @Reference(referenceInterface = MBeanServer.class, bind = "bindMBeanServer", unbind = "unbindMBeanServer")
    private final ValidatingReference<MBeanServer> mbeanServer = new ValidatingReference<MBeanServer>();

    @Activate
    void activate(ComponentContext context) throws Exception {
        JMXUtils.registerMBean(this, mbeanServer.get(), OBJECT_NAME);
        activateComponent();
    }

    @Deactivate
    void deactivate() throws Exception {
        deactivateComponent();
        JMXUtils.unregisterMBean(mbeanServer.get(), OBJECT_NAME);
    }

    static CreateEnsembleOptions getCreateEnsembleOptions(Map<String, Object> options) {
        String username = (String) options.remove("username");
        String password = (String) options.remove("password");
        String role = (String) options.remove("role");

        if (username == null || password == null || role == null) {
            throw new FabricException("Must specify an administrator username, password and administrative role when creating a fabric");
        }

        Object profileObject = options.remove("profiles");

        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationConfig.Feature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.configure(DeserializationConfig.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true);

        CreateEnsembleOptions.Builder builder = mapper.convertValue(options, CreateEnsembleOptions.Builder.class);

        if (profileObject != null) {
            List profiles = mapper.convertValue(profileObject, List.class);
            builder.profiles(profiles);
        }

        org.apache.felix.utils.properties.Properties userProps = null;
        try {
             userProps = new org.apache.felix.utils.properties.Properties(new File(System.getProperty("karaf.home") + "/etc/users.properties"));
        } catch (IOException e) {
            userProps = new org.apache.felix.utils.properties.Properties();
        }

        if (userProps.get(username) == null) {
            userProps.put(username, password + "," + role);
        }

        CreateEnsembleOptions answer = builder.users(userProps).withUser(username, password, role).build();
        LOG.debug("Creating ensemble with options: {}", answer);

        maybeSetProperty(ZkDefs.GLOBAL_RESOLVER_PROPERTY, answer.getGlobalResolver());
        maybeSetProperty(ZkDefs.LOCAL_RESOLVER_PROPERTY, answer.getResolver());
        maybeSetProperty(ZkDefs.MANUAL_IP, answer.getManualIp());
        maybeSetProperty(ZkDefs.BIND_ADDRESS, answer.getBindAddress());
        maybeSetProperty(ZkDefs.MINIMUM_PORT, answer.getMinimumPort());
        maybeSetProperty(ZkDefs.MAXIMUM_PORT, answer.getMaximumPort());

        return answer;
    }

    private static void maybeSetProperty(String prop, Object value) {
        if (value != null) {
            System.setProperty(prop, value.toString());
        }
    }

    @Override
    public void createCluster() {
        assertValid();
        bootstrap.get().create(CreateEnsembleOptions.builder().fromSystemProperties().build());
    }

    @Override
    public void createCluster(Map<String, Object> options) {
        assertValid();
        CreateEnsembleOptions createEnsembleOptions = ClusterBootstrapManager.getCreateEnsembleOptions(options);
        bootstrap.get().create(createEnsembleOptions);
    }

    void bindMBeanServer(MBeanServer mbeanServer) {
        this.mbeanServer.set(mbeanServer);
    }

    void unbindMBeanServer(MBeanServer mbeanServer) {
        this.mbeanServer.set(null);
    }

    void bindBootstrap(ZooKeeperClusterBootstrap bootstrap) {
        this.bootstrap.set(bootstrap);
    }

    void unbindBootstrap(ZooKeeperClusterBootstrap bootstrap) {
        this.bootstrap.set(null);
    }
}
