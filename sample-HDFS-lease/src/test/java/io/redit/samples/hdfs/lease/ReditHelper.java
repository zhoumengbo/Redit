package io.redit.samples.hdfs.lease;

import io.redit.ReditRunner;
import io.redit.dsl.entities.Deployment;
import io.redit.dsl.entities.PathAttr;
import io.redit.dsl.entities.ServiceType;
import io.redit.dsl.entities.PortType;
import io.redit.exceptions.RuntimeEngineException;
import io.redit.execution.CommandResults;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.*;


public class ReditHelper {

    public static final Logger logger = LoggerFactory.getLogger(ReditHelper.class);

    private static final String CLUSTER_NAME = "mycluster";
    private static final int NN_HTTP_PORT = 50070;
//    private static final int NN_HTTP_PORT = 9870;
    private static final int NN_RPC_PORT = 8020;

    private int numOfDNs;
    private int numOfNNs;
    private int numOfJNs;
    private ReditRunner runner;
    private Deployment deployment;
    private Deployment.Builder deploymentBuiler;

    public ReditHelper(int numOfNNs, int numOfDNs, int numOfJNs) {
        this.numOfDNs = numOfDNs;
        this.numOfNNs = numOfNNs;
        this.numOfJNs = numOfJNs;
        createDeployment();
    }

    public Deployment.Builder getDeploymentBuiler() {
        return deploymentBuiler;
    }

    private String getHadoopHomeDir() {
        String version = "3.1.2"; // this can be dynamically generated from maven metadata
        String dir = "hadoop-" + version;
        return "/hadoop/" + dir;
    }

    public void addInstrumentablePath(String path) {
        String[] services = {"hadoop-base", "nn", "dn", "jn"};
        for (String service: services) {
            deploymentBuiler.service(service).instrumentablePath(getHadoopHomeDir() + path).and();
        }
    }

    private void createDeployment() {
        String version = "3.1.2"; // this can be dynamically generated from maven metadata
        String dir = "hadoop-" + version;
        String fsAddress = numOfNNs > 1 ? CLUSTER_NAME : "nn1:" + NN_RPC_PORT;
        String hdfsSiteFileName = numOfNNs > 1 ? "hdfs-site-ha.xml" : "hdfs-site.xml";
        Deployment.Builder builder = Deployment.builder("example-hdfs-lease")
                .withService("zk").dockerImageName("redit/zk:3.4.14").dockerFileAddress("docker/zk", true).disableClockDrift().and()
                .withService("hadoop-base")
                .applicationPath("../hadoop-3.1.2-build/hadoop-dist/target/" + dir + ".tar.gz", "/hadoop", PathAttr.COMPRESSED)
                .applicationPath("etc", getHadoopHomeDir() + "/etc").workDir(getHadoopHomeDir())
                .applicationPath("etc/" + hdfsSiteFileName, getHadoopHomeDir() + "/etc/hadoop/hdfs-site.xml",
                        new HashMap<String, String>() {{
                            put("NN_STRING", getNNString());
                            put("NN_ADDRESSES", getNNAddresses());
                        }})
                .applicationPath("etc/hadoop/core-site.xml", getHadoopHomeDir() + "/etc/hadoop/core-site.xml",
                        new HashMap<String, String>() {{ put("CLUSTER_ADDRESS", fsAddress); }})
                .environmentVariable("HADOOP_HOME", getHadoopHomeDir()).environmentVariable("HADOOP_HEAPSIZE_MAX", "1g")
                .dockerImageName("redit/hadoop:1.0").dockerFileAddress("docker/Dockerfile", true)
                .libraryPath(getHadoopHomeDir() + "/share/hadoop/**/*.jar")
                .logDirectory(getHadoopHomeDir() + "/logs").serviceType(ServiceType.JAVA).and();

        addRuntimeLibsToDeployment(builder, getHadoopHomeDir());

        builder.withService("nn", "hadoop-base").tcpPort(NN_HTTP_PORT, NN_RPC_PORT)
                .initCommand(getHadoopHomeDir() + "bin/hdfs namenode -bootstrapStandby")
                .startCommand(getHadoopHomeDir() + "bin/hdfs --daemon start zkfc && " + getHadoopHomeDir() + "bin/hdfs --daemon start namenode")
                .stopCommand(getHadoopHomeDir() + "bin/hdfs --daemon stop namenode").and()
                .nodeInstances(numOfNNs, "nn", "nn", true)
                .withService("dn", "hadoop-base")
                .startCommand(getHadoopHomeDir() + "bin/hdfs --daemon start datanode")
                .stopCommand(getHadoopHomeDir() + "bin/hdfs --daemon stop datanode").and()
                .nodeInstances(numOfDNs, "dn", "dn", true)
                .node("nn1").stackTrace("e1", "test.armin.balalaie.io.facebook").and().runSequence("e1");

        if (numOfNNs > 1) {
            builder.withService("jn", "hadoop-base")
                    .startCommand(getHadoopHomeDir() + "bin/hdfs --daemon start journalnode")
                    .stopCommand(getHadoopHomeDir() + "bin/hdfs --daemon stop journalnode").and()
                    .nodeInstances(numOfJNs, "jn", "jn", false);
        }

        builder.node("nn1").initCommand(getHadoopHomeDir() + "bin/hdfs namenode -format && " + getHadoopHomeDir() + "bin/hdfs zkfc -formatZK").and();

        deploymentBuiler = builder;
    }

    //Add the runtime library to the deployment
    private void addRuntimeLibsToDeployment(Deployment.Builder builder, String hadoopHome) {
        for (String cpItem: System.getProperty("java.class.path").split(":")) {
            if (cpItem.contains("aspectjrt") || cpItem.contains("reditrt")) {
                String fileName = new File(cpItem).getName();
                builder.service("hadoop-base")
                        .applicationPath(cpItem, hadoopHome + "/share/hadoop/common/" + fileName, PathAttr.LIBRARY).and();
            }
        }
    }

    public ReditRunner start() throws RuntimeEngineException {
        deployment = deploymentBuiler.build();
        runner = ReditRunner.run(deployment);
        startNodesInOrder();
        return runner;
    }

    public void stop() {
        if (runner != null) {
            runner.stop();
        }
    }

    public void startNodesInOrder() throws RuntimeEngineException {
        try {

            if (numOfNNs > 1) {
                // wait for journal nodes to come up
                Thread.sleep(10000);
            }

            runner.runtime().startNode("nn1");
            for (int retry=6; retry>0; retry--) {
                Thread.sleep(5000);
                if (isNNUp(1)) break;
                if (retry == 1) {
                    throw new RuntimeException("NN nn1 is not UP after 30 seconds");
                }
            }

            if (numOfNNs > 1) {
                for (int nnIndex=2; nnIndex<=numOfNNs; nnIndex++) {
                    runner.runtime().startNode("nn" + nnIndex);
                }
            }

            for (String node : runner.runtime().nodeNames())
                if (node.startsWith("dn")) runner.runtime().startNode(node);
        } catch (InterruptedException e) {
            logger.warn("startNodesInOrder sleep got interrupted");
        }
    }

    private String getNNString() {
        StringJoiner stringJoiner = new StringJoiner(",");
        for (int i=1; i<=numOfNNs; i++) {
            stringJoiner.add("nn" + i);
        }
        return stringJoiner.toString();
    }

    private String getNNAddresses() {
        String addrTemplate =
                "    <property>\n" +
                        "        <name>dfs.namenode.rpc-address.mycluster.{{NAME}}</name>\n" +
                        "        <value>{{NAME}}:" + NN_RPC_PORT + "</value>\n" +
                        "    </property>\n" +
                        "    <property>\n" +
                        "        <name>dfs.namenode.http-address.mycluster.{{NAME}}</name>\n" +
                        "        <value>{{NAME}}:" + NN_HTTP_PORT + "</value>\n" +
                        "    </property>";

        String retStr = "";
        for (int i=1; i<=numOfNNs; i++) {
            retStr += addrTemplate.replace("{{NAME}}", "nn" + i);
        }
        return retStr;
    }

    public ReditRunner runner() {
        return runner;
    }

    public Deployment deployment() {
        return deployment;
    }

    public FileSystem getFileSystem() throws IOException {
        return FileSystem.get(getConfiguration());
    }

    public Configuration getConfiguration() {
        Configuration conf = new Configuration();
        conf.set("fs.defaultFS", "hdfs://" + CLUSTER_NAME);
        conf.set("dfs.client.failover.proxy.provider."+ CLUSTER_NAME,
                "org.apache.hadoop.hdfs.server.namenode.ha.ConfiguredFailoverProxyProvider");
        conf.set("dfs.nameservices", CLUSTER_NAME);
        conf.set("dfs.ha.namenodes."+ CLUSTER_NAME, getNNString());

        for (int i=1; i<=numOfNNs; i++) {
            String nnIp = runner.runtime().ip("nn" + i);
            conf.set("dfs.namenode.rpc-address."+ CLUSTER_NAME +".nn" + i, nnIp + ":" +
                    runner.runtime().portMapping("nn" + i, NN_RPC_PORT, PortType.TCP));
            conf.set("dfs.namenode.http-address."+ CLUSTER_NAME +".nn" + i, nnIp + ":" +
                    runner.runtime().portMapping("nn" + i, NN_HTTP_PORT, PortType.TCP));
        }

        return conf;
    }

    private InetSocketAddress getNNRpcAddress(int index) {
        return new InetSocketAddress(runner.runtime().ip("nn" + index),
                runner.runtime().portMapping("nn" + index, NN_RPC_PORT, PortType.TCP));
    }

    private InetSocketAddress getNNHttpAddress(int index) {
        return new InetSocketAddress(runner.runtime().ip("nn" + index),
                runner.runtime().portMapping("nn" + index, NN_HTTP_PORT, PortType.TCP));
    }

    public void waitActive() throws RuntimeEngineException {

        for (int index=1; index<= numOfNNs; index++) {
            boolean isUp = false;
            for (int retry=3; retry>0; retry--){
                logger.info("Checking if NN nn{} is UP (retries left {})", index, retry-1);
                if (this.assertNNisUpAndReceivingReport(index, numOfDNs))
                    break;

                if (retry > 1) {
                    try {
                        Thread.sleep(10000);
                    } catch (InterruptedException e) {
                        logger.warn("waitActive sleep got interrupted");
                    }
                } else {
                    throw new RuntimeException("NN nn" + index + " is not active or not receiving reports from DNs");
                }
            }
        }
        logger.info("The cluster is ACTIVE");
    }

    public boolean assertNNisUpAndReceivingReport(int index, int numOfDNs) throws RuntimeEngineException {
        if (!isNNUp(index))
            return false;

        String res = getNNJmxHaInfo(index);
        if (res == null) {
            logger.warn("Error while trying to get the status of name node");
            return false;
        }

        logger.info("NN {} is up. Checking datanode connections", "nn" + index);
        return res.contains("\"NumLiveDataNodes\" : " + numOfDNs);
    }

    public boolean isNNUp(int index) throws RuntimeEngineException {
        String res = getNNJmxHaInfo(index);
        if (res == null) {
            logger.warn("Error while trying to get the status of name node");
            return false;
        }

        return res.contains("\"tag.HAState\" : \"active\"") || res.contains("\"tag.HAState\" : \"standby\"");
    }

    private String getNNJmxHaInfo(int index) {
        OkHttpClient client = new OkHttpClient();
        try {
            return client.newCall(new Request.Builder()
                    .url("http://" + runner.runtime().ip("nn" + index) + ":" + NN_HTTP_PORT +
                            "/jmx?qry=Hadoop:service=NameNode,name=FSNamesystem")
                    .build()).execute().body().string();
        } catch (IOException e) {
            logger.warn("Error while trying to get the status of name node");
            return null;
        }
    }

    public void transitionToActive(int nnNum) throws RuntimeEngineException {
        logger.info("Transitioning nn{} to ACTIVE", nnNum);
        CommandResults res = runner.runtime().runCommandInNode("nn" + nnNum, "bin/hdfs haadmin -transitionToActive nn" + nnNum);
        if (res.exitCode() != 0) {
            throw new RuntimeException("Error while transitioning nn" + nnNum + " to ACTIVE.\n" + res.stdErr());
        }
    }

    public void transitionToStandby(int nnNum) throws RuntimeEngineException {
        logger.info("Transitioning nn{} to STANDBY", nnNum);
        CommandResults res = runner.runtime().runCommandInNode("nn" + nnNum, "bin/hdfs haadmin -transitionToStandby nn" + nnNum);
        if (res.exitCode() != 0) {
            throw new RuntimeException("Error while transitioning nn" + nnNum + " to STANDBY.\n" + res.stdErr());
        }
    }
}
