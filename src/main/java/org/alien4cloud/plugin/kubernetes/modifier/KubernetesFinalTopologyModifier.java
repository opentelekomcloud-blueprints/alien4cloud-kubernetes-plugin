package org.alien4cloud.plugin.kubernetes.modifier;

import java.util.List;
import java.util.Map;
import java.util.Set;

import alien4cloud.paas.plan.ToscaNodeLifecycleConstants;
import alien4cloud.paas.wf.util.WorkflowUtils;
import alien4cloud.rest.utils.JsonUtil;
import alien4cloud.tosca.context.ToscaContext;
import alien4cloud.tosca.context.ToscaContextual;
import alien4cloud.utils.AlienUtils;
import alien4cloud.utils.PropertyUtil;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import lombok.SneakyThrows;
import lombok.extern.java.Log;
import org.alien4cloud.alm.deployment.configuration.flow.FlowExecutionContext;
import org.alien4cloud.tosca.exceptions.InvalidPropertyValueException;
import org.alien4cloud.tosca.model.Csar;
import org.alien4cloud.tosca.model.definitions.AbstractPropertyValue;
import org.alien4cloud.tosca.model.definitions.ComplexPropertyValue;
import org.alien4cloud.tosca.model.definitions.Interface;
import org.alien4cloud.tosca.model.definitions.Operation;
import org.alien4cloud.tosca.model.definitions.PropertyDefinition;
import org.alien4cloud.tosca.model.definitions.PropertyValue;
import org.alien4cloud.tosca.model.definitions.ScalarPropertyValue;
import org.alien4cloud.tosca.model.templates.NodeTemplate;
import org.alien4cloud.tosca.model.templates.RelationshipTemplate;
import org.alien4cloud.tosca.model.templates.Topology;
import org.alien4cloud.tosca.model.types.DataType;
import org.alien4cloud.tosca.model.types.NodeType;
import org.alien4cloud.tosca.normative.constants.NormativeRelationshipConstants;
import org.alien4cloud.tosca.normative.primitives.Size;
import org.alien4cloud.tosca.normative.primitives.SizeUnit;
import org.alien4cloud.tosca.normative.types.SizeType;
import org.alien4cloud.tosca.normative.types.ToscaTypes;
import org.alien4cloud.tosca.utils.FunctionEvaluator;
import org.alien4cloud.tosca.utils.FunctionEvaluatorContext;
import org.alien4cloud.tosca.utils.TopologyNavigationUtil;
import org.springframework.stereotype.Component;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import alien4cloud.paas.plan.ToscaNodeLifecycleConstants;
import alien4cloud.tosca.context.ToscaContext;
import alien4cloud.tosca.context.ToscaContextual;
import alien4cloud.utils.AlienUtils;
import alien4cloud.utils.PropertyUtil;
import lombok.extern.java.Log;

/**
 * Transform a matched K8S topology containing <code>Container</code>s, <code>Deployment</code>s, <code>Service</code>s and replace them with <code>DeploymentResource</code>s and <code>ServiceResource</code>s.
 */
@Log
@Component(value = "kubernetes-final-modifier")
public class KubernetesFinalTopologyModifier extends AbstractKubernetesTopologyModifier {

    public static final String A4C_KUBERNETES_MODIFIER_TAG = "a4c_kubernetes-final-modifier";

    private static Map<String, Parser> k8sParsers = Maps.newHashMap();

    static {
        k8sParsers.put(ToscaTypes.SIZE, new SizeParser(ToscaTypes.SIZE));
    }

    @Override
    @ToscaContextual
    public void process(Topology topology, FlowExecutionContext context) {
        log.info("Processing topology " + topology.getId());

        Csar csar = new Csar(topology.getArchiveName(), topology.getArchiveVersion());

        // just a map that store the node name as key and the replacement node as value
        Map<String, NodeTemplate> nodeReplacementMap = Maps.newHashMap();

        // for each Service create a node of type ServiceResource
        Map<String, Map<String, AbstractPropertyValue>> resourceNodeYamlStructures = Maps.newHashMap();
        Set<NodeTemplate> serviceNodes = TopologyNavigationUtil.getNodesOfType(topology, K8S_TYPES_SERVICE, false);
        for (NodeTemplate serviceNode : serviceNodes) {
            NodeTemplate serviceResourceNode = addNodeTemplate(csar, topology, serviceNode.getName() + "_Resource", K8S_TYPES_SERVICE_RESOURCE, K8S_CSAR_VERSION);
            nodeReplacementMap.put(serviceNode.getName(), serviceResourceNode);
            setNodeTagValue(serviceResourceNode, A4C_KUBERNETES_MODIFIER_TAG + "_created_from", serviceNode.getName());
            Map<String, AbstractPropertyValue> serviceResourceNodeProperties = Maps.newHashMap();
            resourceNodeYamlStructures.put(serviceResourceNode.getName(), serviceResourceNodeProperties);

            copyProperty(csar, topology, serviceNode, "apiVersion", serviceResourceNodeProperties, "resource_def.apiVersion");
            copyProperty(csar, topology, serviceNode, "kind", serviceResourceNodeProperties, "resource_def.kind");
            copyProperty(csar, topology, serviceNode, "metadata", serviceResourceNodeProperties, "resource_def.metadata");

            AbstractPropertyValue namePropertyValue = PropertyUtil.getPropertyValueFromPath(AlienUtils.safe(serviceNode.getProperties()), "metadata.name");
            setNodePropertyPathValue(csar, topology, serviceResourceNode, "service_name", namePropertyValue);

            AbstractPropertyValue propertyValue = PropertyUtil.getPropertyValueFromPath(AlienUtils.safe(serviceNode.getProperties()), "spec");
            // TODO: propertyValue should be transformed before injected in the serviceResourceNodeProperties
            NodeType nodeType = ToscaContext.get(NodeType.class, serviceNode.getType());
            PropertyDefinition propertyDefinition = nodeType.getProperties().get("spec");
            Object transformedValue = getTransformedValue(propertyValue, propertyDefinition, "");
            // rename entry service_type to type
            renameProperty(transformedValue, "service_type", "type");
            feedPropertyValue(serviceResourceNodeProperties, "resource_def.spec", transformedValue, false);

        }

        // for each Deployment create a node of type DeploymentResource
        Set<NodeTemplate> deploymentNodes = TopologyNavigationUtil.getNodesOfType(topology, K8S_TYPES_DEPLOYMENT, false);
        for (NodeTemplate deploymentNode : deploymentNodes) {
            NodeTemplate deploymentResourceNode = addNodeTemplate(csar, topology, deploymentNode.getName() + "_Resource", K8S_TYPES_DEPLOYMENT_RESOURCE, K8S_CSAR_VERSION);
            nodeReplacementMap.put(deploymentNode.getName(), deploymentResourceNode);
            setNodeTagValue(deploymentResourceNode, A4C_KUBERNETES_MODIFIER_TAG + "_created_from", deploymentNode.getName());
            Map<String, AbstractPropertyValue> deploymentResourceNodeProperties = Maps.newHashMap();
            resourceNodeYamlStructures.put(deploymentResourceNode.getName(), deploymentResourceNodeProperties);

            copyProperty(csar, topology, deploymentNode, "apiVersion", deploymentResourceNodeProperties, "resource_def.apiVersion");
            copyProperty(csar, topology, deploymentNode, "kind", deploymentResourceNodeProperties, "resource_def.kind");
            copyProperty(csar, topology, deploymentNode, "metadata", deploymentResourceNodeProperties, "resource_def.metadata");

            AbstractPropertyValue propertyValue = PropertyUtil.getPropertyValueFromPath(AlienUtils.safe(deploymentNode.getProperties()), "spec");
            // TODO: propertyValue should be transformed before injected in the deploymentResourceNodeProperties
            NodeType nodeType = ToscaContext.get(NodeType.class, deploymentNode.getType());
            PropertyDefinition propertyDefinition = nodeType.getProperties().get("spec");
            Object transformedValue = getTransformedValue(propertyValue, propertyDefinition, "");
            feedPropertyValue(deploymentResourceNodeProperties, "resource_def.spec", transformedValue, false);
//            copyAndTransformProperty(csar, topology, deploymentNode, "spec", deploymentResourceNodeProperties, "resource_def.spec");

            // find each node of type Service that targets this deployment
            Set<NodeTemplate> sourceCandidates = TopologyNavigationUtil.getSourceNodes(topology, deploymentNode, "feature");
            for (NodeTemplate nodeTemplate : sourceCandidates) {
                // TODO: manage inheritance ?
                if (nodeTemplate.getType().equals(K8S_TYPES_SERVICE)) {
                    // find the replacer
                    NodeTemplate serviceResource = nodeReplacementMap.get(nodeTemplate.getName());
                    if (!TopologyNavigationUtil.hasRelationship(serviceResource, deploymentResourceNode.getName(), "dependency", "feature")) {
                        RelationshipTemplate relationshipTemplate = addRelationshipTemplate(csar, topology, serviceResource, deploymentResourceNode.getName(), NormativeRelationshipConstants.DEPENDS_ON, "dependency", "feature");
                        setNodeTagValue(relationshipTemplate, A4C_KUBERNETES_MODIFIER_TAG + "_created_from", nodeTemplate.getName() + " -> " + deploymentNode.getName());
                    }
                }
            }
            // find each node of type service this deployment depends on
            Set<NodeTemplate> targetCandidates = TopologyNavigationUtil.getTargetNodes(topology, deploymentNode, "dependency");
            for (NodeTemplate nodeTemplate : targetCandidates) {
                // TODO: manage inheritance ?
                if (nodeTemplate.getType().equals(K8S_TYPES_SERVICE)) {
                    // find the replacer
                    NodeTemplate serviceResource = nodeReplacementMap.get(nodeTemplate.getName());
                    if (!TopologyNavigationUtil.hasRelationship(deploymentResourceNode, serviceResource.getName(), "dependency", "feature")) {
                        RelationshipTemplate relationshipTemplate = addRelationshipTemplate(csar, topology, deploymentResourceNode, serviceResource.getName(), NormativeRelationshipConstants.DEPENDS_ON, "dependency", "feature");
                        setNodeTagValue(relationshipTemplate, A4C_KUBERNETES_MODIFIER_TAG + "_created_from", deploymentNode.getName() + " -> " + nodeTemplate.getName());
                    }
                }
            }
        }

        Map<String, PropertyValue> inputValues = Maps.newHashMap();
        FunctionEvaluatorContext functionEvaluatorContext = new FunctionEvaluatorContext(topology, inputValues);

        Map<String, List<String>> serviceIpAddressesPerDeploymentResource = Maps.newHashMap();

        // for each container,
        Set<NodeTemplate> containerNodes = TopologyNavigationUtil.getNodesOfType(topology, K8S_TYPES_CONTAINER, false);
        for (NodeTemplate containerNode : containerNodes) {
            // get the hosting node
            NodeTemplate deploymentNode = TopologyNavigationUtil.getImmediateHostTemplate(topology, containerNode);
            // find the replacer
            NodeTemplate deploymentResource = nodeReplacementMap.get(deploymentNode.getName());
            Map<String, AbstractPropertyValue> deploymentResourceNodeProperties = resourceNodeYamlStructures.get(deploymentResource.getName());

            // resolve env variables
            // TODO: in the interface, create operation, search for ENV_, resolve all get_property ...
            Set<NodeTemplate> hostedContainers = TopologyNavigationUtil.getSourceNodes(topology, containerNode, "host");
            for (NodeTemplate nodeTemplate : hostedContainers) {
                // we should have a single hosted docker container
                NodeType nodeType = ToscaContext.get(NodeType.class, nodeTemplate.getType());
                if (nodeType.getInterfaces() != null && nodeType.getInterfaces().containsKey(ToscaNodeLifecycleConstants.STANDARD)) {
                    Interface standardInterface = nodeType.getInterfaces().get(ToscaNodeLifecycleConstants.STANDARD);
                    if (standardInterface.getOperations() != null && standardInterface.getOperations().containsKey(ToscaNodeLifecycleConstants.CREATE)) {
                        Operation createOp = standardInterface.getOperations().get(ToscaNodeLifecycleConstants.CREATE);
                        AlienUtils.safe(createOp.getInputParameters()).forEach((k, iValue) -> {
                            if (iValue instanceof AbstractPropertyValue && k.startsWith("ENV_")) {
                                String envKey = k.substring(4);

                                if (KubeAttributeDetector.isServiceIpAddress(topology, nodeTemplate, iValue)) {
                                    NodeTemplate serviceTemplate = KubeAttributeDetector.getServiceDependency(topology, nodeTemplate, iValue);
                                    AbstractPropertyValue serviceNameValue = PropertyUtil.getPropertyValueFromPath(serviceTemplate.getProperties(), "metadata.name");
                                    String serviceName = PropertyUtil.getScalarValue(serviceNameValue);

                                    List<String> serviceIpAddresses = serviceIpAddressesPerDeploymentResource.get(deploymentResource.getName());
                                    if (serviceIpAddresses == null) {
                                        serviceIpAddresses = Lists.newArrayList();
                                        serviceIpAddressesPerDeploymentResource.put(deploymentResource.getName(), serviceIpAddresses);
                                    }
                                    serviceIpAddresses.add(serviceName);

                                    ComplexPropertyValue envEntry = new ComplexPropertyValue();
                                    envEntry.setValue(Maps.newHashMap());
                                    envEntry.getValue().put("name", envKey);
                                    envEntry.getValue().put("value", "$SERVICE_IP_LOOKUP" + (serviceIpAddresses.size() - 1));
                                    appendNodePropertyPathValue(csar, topology, containerNode, "container.env", envEntry);
                                } else if (KubeAttributeDetector.isTargetedEndpointProperty(topology, nodeTemplate, iValue)) {
                                    AbstractPropertyValue apv = KubeAttributeDetector.getTargetedEndpointProperty(topology, nodeTemplate, iValue);
                                    ComplexPropertyValue envEntry = new ComplexPropertyValue();
                                    envEntry.setValue(Maps.newHashMap());
                                    envEntry.getValue().put("name", envKey);
                                    envEntry.getValue().put("value", PropertyUtil.getScalarValue(apv));
                                    appendNodePropertyPathValue(csar, topology, containerNode, "container.env", envEntry);
                                } else {
                                    try {
                                        PropertyValue propertyValue = FunctionEvaluator.resolveValue(functionEvaluatorContext, nodeTemplate, nodeTemplate.getProperties(), (AbstractPropertyValue)iValue);
                                        if (propertyValue != null) {
                                            ComplexPropertyValue envEntry = new ComplexPropertyValue();
                                            envEntry.setValue(Maps.newHashMap());
                                            envEntry.getValue().put("name", envKey);
                                            envEntry.getValue().put("value", propertyValue);
                                            appendNodePropertyPathValue(csar, topology, containerNode, "container.env", envEntry);
                                        }
                                    } catch(IllegalArgumentException iae) {
                                        log.severe(iae.getMessage());
                                    }
                                }
                            }
                        });
                    }
                }
            }

            // populate the service_dependency_lookups property of the deployment resource nodes
            serviceIpAddressesPerDeploymentResource.forEach((deploymentResourceNodeName, ipAddressLookups) -> {
                NodeTemplate deploymentResourceNode = topology.getNodeTemplates().get(deploymentResourceNodeName);
                StringBuilder serviceDependencyDefinitionsValue = new StringBuilder();
                for (int i=0; i<ipAddressLookups.size(); i++) {
                    if (i > 0) {
                        serviceDependencyDefinitionsValue.append(",");
                    }
                    serviceDependencyDefinitionsValue.append("SERVICE_IP_LOOKUP").append(i);
                    serviceDependencyDefinitionsValue.append(":").append(ipAddressLookups.get(i));
                }
                setNodePropertyPathValue(csar, topology, deploymentResourceNode, "service_dependency_lookups", new ScalarPropertyValue(serviceDependencyDefinitionsValue.toString()));
            });

            // add an entry in the deployment resource
            AbstractPropertyValue propertyValue = PropertyUtil.getPropertyValueFromPath(AlienUtils.safe(containerNode.getProperties()), "container");
            // transform data
            NodeType nodeType = ToscaContext.get(NodeType.class, containerNode.getType());
            PropertyDefinition propertyDefinition = nodeType.getProperties().get("container");
            Object transformedValue = getTransformedValue(propertyValue, propertyDefinition, "");
            feedPropertyValue(deploymentResourceNodeProperties, "resource_def.spec.template.spec.containers", transformedValue, true);

        }



        serviceNodes.forEach(nodeTemplate -> removeNode(topology, nodeTemplate));
        deploymentNodes.forEach(nodeTemplate -> removeNode(topology, nodeTemplate));

        Set<NodeTemplate> resourceNodes = TopologyNavigationUtil.getNodesOfType(topology, K8S_TYPES_RESOURCE, true);
        for (NodeTemplate resourceNode : resourceNodes) {
            Map<String, AbstractPropertyValue> resourceNodeProperties = resourceNodeYamlStructures.get(resourceNode.getName());
            if (resourceNodeProperties != null && resourceNodeProperties.containsKey("resource_def")) {
                Object propertyValue = getValue(resourceNodeProperties.get("resource_def"));
                String serializedPropertyValue = PropertyUtil.serializePropertyValue(propertyValue);
                setNodePropertyPathValue(csar, topology, resourceNode, "resource_yaml", new ScalarPropertyValue(serializedPropertyValue));
            }
        }

    }

    private void renameProperty(Object propertyValue, String propertyPath, String newName) {
        PropertyUtil.NestedPropertyWrapper nestedPropertyWrapper = PropertyUtil.getNestedProperty(propertyValue, propertyPath);
        if (nestedPropertyWrapper != null) {
            Object value = nestedPropertyWrapper.parent.remove(nestedPropertyWrapper.key);
            // value can't be null if nestedPropertyWrapper isn't null
            nestedPropertyWrapper.parent.put(newName, value);
        }
    }

    private void copyProperty(Csar csar, Topology topology, NodeTemplate sourceTemplate, String sourcePath, Map<String, AbstractPropertyValue> propertyValues, String targetPath) {
        AbstractPropertyValue propertyValue = PropertyUtil.getPropertyValueFromPath(AlienUtils.safe(sourceTemplate.getProperties()), sourcePath);
        feedPropertyValue(propertyValues, targetPath, propertyValue, false);
    }

    /**
     * Transform the object by replacing eventual PropertyValue found by it's value.
     */
    private Object getTransformedValue(Object value, PropertyDefinition propertyDefinition, String path) {
        if (value == null) {
            return null;
        } else if (value instanceof PropertyValue) {
            return getTransformedValue(((PropertyValue)value).getValue(), propertyDefinition, path);
        } else if (value instanceof Map<?, ?>) {
            Map<String, Object> newMap = Maps.newHashMap();
            if (!ToscaTypes.isPrimitive(propertyDefinition.getType())) {
                DataType dataType = ToscaContext.get(DataType.class, propertyDefinition.getType());
                for (Map.Entry<String, Object> entry : ((Map<String, Object>)value).entrySet()) {
                    PropertyDefinition pd = dataType.getProperties().get(entry.getKey());
                    String innerPath = (path.equals("")) ? entry.getKey() : path + "." + entry.getKey();
                    Object entryValue = getTransformedValue(entry.getValue(), pd, innerPath);
                    newMap.put(entry.getKey(), entryValue);
                }
            } else if (ToscaTypes.MAP.equals(propertyDefinition.getType())) {
                PropertyDefinition pd = propertyDefinition.getEntrySchema();
                for (Map.Entry<String, Object> entry : ((Map<String, Object>)value).entrySet()) {
                    String innerPath = (path.equals("")) ? entry.getKey() : path + "." + entry.getKey();
                    Object entryValue = getTransformedValue(entry.getValue(), pd, innerPath);
                    newMap.put(entry.getKey(), entryValue);
                }
            }
            return newMap;
        } else if (value instanceof List<?>) {
            PropertyDefinition pd = propertyDefinition.getEntrySchema();
            List<Object> newList = Lists.newArrayList();
            for (Object entry : (List<Object>)value) {
                Object entryValue = getTransformedValue(entry, pd, path);
                newList.add(entryValue);
            }
            return newList;
        } else {
            if (ToscaTypes.isSimple(propertyDefinition.getType())) {
                String valueAsString = value.toString();
                if (k8sParsers.containsKey(propertyDefinition.getType())) {
                    return k8sParsers.get(propertyDefinition.getType()).parseValue(valueAsString);
                } else {
                    switch (propertyDefinition.getType()) {
                        case ToscaTypes.INTEGER: return Integer.parseInt(valueAsString);
                        case ToscaTypes.FLOAT: return Float.parseFloat(valueAsString);
                        case ToscaTypes.BOOLEAN: return Boolean.parseBoolean(valueAsString);
                        default: return valueAsString;
                    }
                }
            } else {
                return value;
            }
        }
    }

    private static abstract class Parser {
        private String type;
        public Parser(String type) {
            this.type = type;
        }
        public abstract Object parseValue(String value);
    }

    private static class SizeParser extends Parser {
        public SizeParser(String type) {
            super(type);
        }

        @Override
        public Object parseValue(String value) {
            SizeType sizeType = new SizeType();
            try {
                Size size = sizeType.parse(value);
                Double d = size.convert(SizeUnit.B.toString());
                return d.longValue();
            } catch (InvalidPropertyValueException e) {
                return value;
            }
        }
    }
}
