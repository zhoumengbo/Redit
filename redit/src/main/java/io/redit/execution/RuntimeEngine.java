/*
 * MIT License
 *
 * Copyright (c) 2021 SATE-Lab
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 *
 */

package io.redit.execution;

import io.redit.ReditRunner;
import io.redit.dsl.entities.Deployment;
import io.redit.dsl.entities.ExposedPortDefinition;
import io.redit.dsl.entities.Node;
import io.redit.dsl.entities.Service;
import io.redit.rt.Redit;
import io.redit.workspace.NodeWorkspace;
import io.redit.Constants;
import io.redit.exceptions.RuntimeEngineException;
import io.redit.execution.single_node.SingleNodeRuntimeEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.*;
import java.util.concurrent.TimeoutException;

public abstract class RuntimeEngine implements LimitedRuntimeEngine {
    private final static Logger logger = LoggerFactory.getLogger(RuntimeEngine.class);
    private final EventServer eventServer;
    protected final Deployment deployment;
    protected Map<String, Node> nodeMap;
    protected Map<String, NodeWorkspace> nodeWorkspaceMap;
    protected boolean stopped;
    protected final NetworkPartitionManager networkPartitionManager;
    protected final NetworkOperationManager networkOperationManager;
    private ReditRunner reditRunner;
    private EventService eventService;
    private Redit reditClient;

    public RuntimeEngine(Deployment deployment, Map<String, NodeWorkspace> nodeWorkspaceMap) {
        this.stopped = true;
        this.deployment = deployment;
        nodeMap = new HashMap<>(deployment.getNodes());
        this.nodeWorkspaceMap = new HashMap<>(nodeWorkspaceMap);
        eventService = new EventService(deployment);
        eventServer = new EventServer(eventService);
        networkPartitionManager = new NetworkPartitionManager(this);
        networkOperationManager = new NetworkOperationManager(this);
    }

    // TODO this method should use an external configuration to detect the proper runtime engine and its corresponding configs
    // By default this method returns single node runtime engine
    public static RuntimeEngine getRuntimeEngine(Deployment deployment, Map<String, NodeWorkspace> nodeWorkspaceMap) {
        return new SingleNodeRuntimeEngine(deployment, nodeWorkspaceMap);
    }

    public Set<String> nodeNames() {
        return new HashSet<>(nodeMap.keySet());
    }

    public boolean isStopped() {
        return stopped;
    }

    public void start(ReditRunner reditRunner) throws RuntimeEngineException {
        this.reditRunner = reditRunner;

        // Exits if nodes' workspaces is not set
        if (nodeWorkspaceMap == null || nodeWorkspaceMap.isEmpty()) {
            throw new RuntimeEngineException("NodeWorkspaces is not set!");
        }

        if (!deployment.getSharedDirectories().isEmpty()) {
            logger.info("Starting file sharing service ...");
            startFileSharingService();
        }

        logger.info("Starting event server ...");
        startEventServer();

        // Configure local Redit runtime
        reditClient = new Redit("127.0.0.1", String.valueOf(eventServer.getPortNumber()));

        try {
            logger.info("Starting nodes ...");
            stopped = false;
            startNodes();
        } catch (RuntimeEngineException e) {
            stop(true, 0);
            throw e;
        }
    }

    protected void startEventServer() throws RuntimeEngineException {
        eventServer.start();
    }

    public void stop(boolean kill, Integer secondsUntilForcedStop) {
        logger.info("Stopping the runtime engine ...");
        logger.info("Stopping nodes ...");
        stopNodes(kill, secondsUntilForcedStop);
        logger.info("Stopping event server ...");
        stopEventServer();
        if (!deployment.getSharedDirectories().isEmpty()) {
            logger.info("Stopping file sharing service ...");
            stopFileSharingService();
        }
        stopped = true;
    }

    public void addNewNode(Node node, NodeWorkspace nodeWorkspace) throws RuntimeEngineException {
        nodeMap.put(node.getName(), node);
        nodeWorkspaceMap.put(node.getName(), nodeWorkspace);
        createNodeContainer(node);
        networkPartitionManager.addNewNode(node);
        startNode(node.getName());
    }

    protected void stopEventServer() {
        eventServer.stop();
    }

    protected Map<String, String> getNodeEnvironmentVariablesMap(String nodeName, Map<String, String> environment) {
        Node node = nodeMap.get(nodeName);
        Service nodeService = deployment.getService(node.getServiceName());

        for (Map.Entry<String, String> entry: nodeService.getEnvironmentVariables().entrySet()) {
            environment.put(entry.getKey(), entry.getValue());
        }

        for (Map.Entry<String, String> entry: node.getEnvironmentVariables().entrySet()) {
            environment.put(entry.getKey(), entry.getValue());
        }

        return environment;
    }

    protected Map<String, String> improveEnvironmentVariablesMap(String nodeName, Map<String, String> environment)
            throws RuntimeEngineException {
        // TODO: better to move the address achieving process to method getEventServerIpAddress
        // Use ipv4 checker to find if the address is ipv4
        // Use class NetworkInterface to get the docker0 address
        String ipAddress = "";
        String pattern =
                "^([01]?\\d\\d?|2[0-4]\\d|25[0-5])\\." +
                "([01]?\\d\\d?|2[0-4]\\d|25[0-5])\\." +
                "([01]?\\d\\d?|2[0-4]\\d|25[0-5])\\." +
                "([01]?\\d\\d?|2[0-4]\\d|25[0-5])$";

        try {
            Enumeration<InetAddress> InetAddressList= NetworkInterface.getByName("docker0").getInetAddresses();
            while(InetAddressList.hasMoreElements()) {
                ipAddress = InetAddressList.nextElement().getHostAddress();
                if(!ipAddress.matches(pattern)){
                    ipAddress = "";
                }
            }
            if(ipAddress.equals("")){
                ipAddress = getEventServerIpAddress();
                logger.warn("docker0 address may fail, perhaps causing connection refused.");
            }
        }
        catch (Exception ex){
            ex.printStackTrace();
            logger.error("Confirm that docker is set! ");
        }

        environment.put(Constants.REDIT_EVENT_SERVER_IP_ADDRESS_ENV_VAR, ipAddress);
        environment.put(Constants.REDIT_EVENT_SERVER_PORT_NUMBER_ENV_VAR, String.valueOf(eventServer.getPortNumber()));
        return environment;
    }

    protected final Map<String, String> getNodeEnvironmentVariablesMap(String nodeName) throws RuntimeEngineException {
        Map<String, String> retMap = new HashMap<>();
        retMap = getNodeEnvironmentVariablesMap(nodeName, retMap);
        retMap = improveEnvironmentVariablesMapForEngine(nodeName, retMap);
        retMap = improveEnvironmentVariablesMap(nodeName, retMap);
        return retMap;
    }

    protected Set<ExposedPortDefinition> getNodeExposedPorts(String nodeName) {
        Node node = nodeMap.get(nodeName);
        Service nodeService = deployment.getService(node.getServiceName());

        Set<ExposedPortDefinition> ports = new HashSet<>(nodeService.getExposedPorts());
        ports.addAll(node.getExposedPorts());
        return ports;
    }

    protected String getNodeInitCommand(String nodeName) {
        Node node = nodeMap.get(nodeName);
        Service nodeService = deployment.getService(node.getServiceName());

        if (node.getInitCommand() != null) {
            return node.getInitCommand();
        }
        return nodeService.getInitCommand();
    }

    protected String getNodeStartCommand(String nodeName) {
        Node node = nodeMap.get(nodeName);
        Service nodeService = deployment.getService(node.getServiceName());

        if (node.getStartCommand() != null) {
            return node.getStartCommand();
        }
        return nodeService.getStartCommand();
    }

    protected String getNodeStopCommand(String nodeName) {
        Node node = nodeMap.get(nodeName);
        Service nodeService = deployment.getService(node.getServiceName());

        if (node.getStopCommand() != null) {
            return node.getStopCommand();
        }
        return nodeService.getStopCommand();
    }

    protected boolean isClockDriftEnabledInNode(String nodeName) {
        Node node = nodeMap.get(nodeName);
        Service service = deployment.getService(node.getServiceName());
        return node.isClockDriftEnabled() && service.isClockDriftEnabled();
    }

    @Override
    public void waitFor(String eventName) throws RuntimeEngineException {
        try {
            waitFor(eventName, false, null);
        } catch (TimeoutException e) {
            // never happens here
        }
    }

    @Override
    public void waitFor(String eventName, Boolean includeEvent) throws RuntimeEngineException {
        try {
            waitFor(eventName, false, null);
        } catch (TimeoutException e) {
            // never happens here
        }
    }

    @Override
    public void waitFor(String eventName, Integer timeout) throws RuntimeEngineException, TimeoutException {
        waitFor(eventName, false, timeout);
    }

    @Override
    public void waitFor(String eventName, Boolean includeEvent, Integer timeout)
            throws RuntimeEngineException, TimeoutException {
        if (deployment.isInRunSequence(eventName)) {
            logger.info("Waiting for event {} ...", eventName);
            try {
                reditClient.blockAndPoll(eventName, includeEvent, timeout);
            } catch (TimeoutException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeEngineException("Error happened while waiting for event " + eventName, e);
            }
        } else {
            throw new RuntimeEngineException("Event " + eventName + " is not referred to in the run sequence. Thus," +
                    " its order cannot be enforced!");
        }
    }

    private void sendEvent(String eventName) throws RuntimeEngineException {
        if (deployment.isInRunSequence(eventName)) {
            logger.info("Sending test case event {} ...", eventName);
            reditClient.allowBlocking();
            reditClient.enforceOrder(eventName, null);
        } else {
            throw new RuntimeEngineException("Event " + eventName + " is not referred to" +
                    " in the run sequence. Thus, its order cannot be sent from the test case!");
        }
    }

    @Override
    public void enforceOrder(String eventName, ReditCheckedRunnable action) throws RuntimeEngineException {
        try {
            enforceOrder(eventName, null, action);
        } catch (TimeoutException e) {
            // never happens here
        }
    }

    @Override
    public void enforceOrder(String eventName, Integer timeout, ReditCheckedRunnable action)
            throws RuntimeEngineException, TimeoutException {

        if (!deployment.testCaseEventExists(eventName)) {
            throw new RuntimeEngineException("Event " + eventName + " is not a defined test case event and cannot be"
                    + " enforced using this method!");
        }

        waitFor(eventName, false, timeout);
        if (action != null) {
            action.run();
        }
        sendEvent(eventName);
    }

    public void waitForRunSequenceCompletion() throws TimeoutException {
        waitForRunSequenceCompletion(null,null);
    }

    public void waitForRunSequenceCompletion(Integer timeout) throws TimeoutException {
        waitForRunSequenceCompletion(timeout,null);
    }

    public void waitForRunSequenceCompletion(Integer timeout, Integer nextEventReceiptTimeout)
            throws TimeoutException {

        Integer originalTimeout = timeout;
        while (!isStopped() && (timeout == null || timeout > 0)) {

            if (eventService.isTheRunSequenceCompleted()) {
                logger.info("The run sequence is completed!");
                return;
            }

            if (deployment.getRunSequence() != null && !deployment.getRunSequence().isEmpty() &&
                    eventService.isLastEventReceivedTimeoutPassed(nextEventReceiptTimeout)) {
                throw new TimeoutException("The timeout for receiving the next event (" + nextEventReceiptTimeout + " seconds) is passed!");
            }

            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                // TODO is this the best thing to do ?
                logger.warn("The run sequence completion wait sleep thread is interrupted");
            }

            if (timeout != null) {
                timeout--;
            }
        }

        if (timeout != null && timeout <= 0) {
            throw new TimeoutException("The Wait timeout for run sequence completion (" + originalTimeout + " seconds) is passed!");
        }
    }

    @Override
    public void networkPartition(NetPart netPart) throws RuntimeEngineException {
        networkPartitionManager.networkPartition(netPart);
    }

    @Override
    public void removeNetworkPartition(NetPart netPart) throws RuntimeEngineException {
        networkPartitionManager.removeNetworkPartition(netPart);
    }

    @Override
    public void networkOperation(String nodeName, NetOp.BuilderBase... netOpBuilders) throws RuntimeEngineException {
        for (NetOp.BuilderBase netOpBuilder: netOpBuilders) {
            networkOperationManager.networkOperation(nodeName, netOpBuilder.build());
        }
    }

    /**
     * This method improves a node's env var map
     * @param nodeName the corresponding node to be improved
     * @param environment the current environment of the node
     * @return the improved environment for the node
     * @throws RuntimeEngineException if something goes wrong
     */
    protected abstract Map<String, String> improveEnvironmentVariablesMapForEngine(String nodeName, Map<String, String> environment)
            throws RuntimeEngineException;
    /**
     * This method should find the best IP address for the nodes to connect to the event server based on the current environment
     * @return the IP address to connect to the event server
     * @throws RuntimeEngineException if something goes wrong
     */
    protected abstract String getEventServerIpAddress() throws RuntimeEngineException;
    /**
     * This method should create a container based on the given node definition.
     * @param node the node definition to create a container upon
     * @throws RuntimeEngineException if something goes wrong
     */
    protected abstract void createNodeContainer(Node node) throws RuntimeEngineException;
    /**
     * This method should start all of the nodes. In case of a problem in startup of a node, all of the started nodes should be
     * stopped and a RuntimeEngine Exception should be thrown
     * @throws RuntimeEngineException
     */
    protected abstract void startNodes() throws RuntimeEngineException;

    /**
     * This method should stop all of the nodes and in case of a failure in stopping something it won't throw any exception, but
     * error logs the exception or a message. This method should only be called when stopping the runtime engine
     */
    protected abstract void stopNodes(Boolean kill, Integer secondsUntilForcedStop);

    /**
     * This method should start the file sharing service (if any), create the defined shared directory in the deployment definition
     * if they do not exist, and make them available through the sharing service. Mounting in the nodes (if necessary) should be
     * done later when starting the nodes.
     * @throws RuntimeEngineException if some error happens when creating the shared directory for the nodes
     */
    protected abstract void startFileSharingService() throws RuntimeEngineException;

    /**
     * This method should stop the potentially running file sharing server and unmount shared directories in the nodes (if necessary).
     * In case of a failure in stopping something it won't throw any exception, but error logs the exception or a message.
     * This method should only be called when stopping the runtime engine
     */
    protected abstract void stopFileSharingService();
}
