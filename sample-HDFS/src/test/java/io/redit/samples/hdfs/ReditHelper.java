package io.redit.samples.hdfs;

import io.redit.ReditRunner;
import io.redit.dsl.entities.Deployment;
import io.redit.dsl.entities.PathAttr;
import io.redit.dsl.entities.ServiceType;
import io.redit.exceptions.RuntimeEngineException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ReditHelper {
    public static final Logger logger = LoggerFactory.getLogger(ReditHelper.class);

    public static Deployment getDeployment(int numOfDNs) {

        // TODO find way to change bash-running working directory, check is the code really running
        String version = "3.1.2"; // this can be dynamically generated from maven metadata
        String dir = "hadoop-" + version;
        String tmpdir="/hadoop/hadoop-3.1.2/";
//        return Deployment.builder("example-hdfs")
//                .withService("zk").dockerImageName("redit/zk:3.4.14").dockerFileAddress("docker/zk", true)
//                .disableClockDrift().and()
//                .withService("hadoop-base")
//                .applicationPath("./hadoop-3.1.2-build/hadoop-dist/target/" + dir + ".tar.gz", "/hadoop", PathAttr.COMPRESSED)
//                .applicationPath("etc", "/hadoop/" + dir + "/etc")
//                .environmentVariable("HADOOP_HOME", "/hadoop/" + dir).environmentVariable("HADOOP_HEAPSIZE_MAX", "1g")
//                .dockerImageName("redit/hadoop:1.0").dockerFileAddress("docker/Dockerfile", true)
//                .logDirectory("/hadoop/" + dir + "/logs").serviceType(ServiceType.JAVA).and()
//
//                .withService("nn", "hadoop-base")
//                .initCommand("bin/hdfs namenode -bootstrapStandby")
//                .startCommand("bin/hdfs --daemon start zkfc && bin/hdfs --daemon start namenode").tcpPort(50070)
//                .stopCommand("bin/hdfs --daemon stop namenode").and()
//                .nodeInstances(3, "nn", "nn", true)
//
//                .withService("dn", "hadoop-base")
//                .startCommand("bin/hdfs --daemon start datanode")
//                .stopCommand("bin/hdfs --daemon stop datanode")
//                .and()
//                .nodeInstances(numOfDNs, "dn", "dn", true)
//
//                .withService("jn", "hadoop-base")
//                .startCommand("bin/hdfs --daemon start journalnode")
//                .stopCommand("bin/hdfs --dae stop journalnode").and()
//                .nodeInstances(3, "jn", "jn", false)
//                .withNode("zk1", "zk").and()
//
//                .node("nn1").initCommand("bin/hdfs namenode -format && bin/hdfs zkfc -formatZK").and()
//                .build();
        return Deployment.builder("example-hdfs")
                .withService("zk").dockerImageName("redit/zk:3.4.14").dockerFileAddress("docker/zk", true).disableClockDrift().and()
                .withService("hadoop-base")
                .applicationPath("../hadoop-3.1.2-build/hadoop-dist/target/" + dir + ".tar.gz", "/hadoop", PathAttr.COMPRESSED)
                .applicationPath("etc", "/hadoop/" + dir + "/etc").workDir("/hadoop/" + dir)
                .environmentVariable("HADOOP_HOME", "/hadoop/" + dir).environmentVariable("HADOOP_HEAPSIZE_MAX", "1g")
                .dockerImageName("redit/hadoop:1.0").dockerFileAddress("docker/Dockerfile", true)
                .logDirectory("/hadoop/" + dir + "/logs").serviceType(ServiceType.JAVA).and()
                .withService("nn", "hadoop-base").initCommand(tmpdir+"bin/hdfs namenode -bootstrapStandby")
                .startCommand(tmpdir+"bin/hdfs --daemon start zkfc && "+tmpdir+"bin/hdfs --daemon start namenode").tcpPort(50070)
                .stopCommand(tmpdir+"bin/hdfs --daemon stop namenode").and().nodeInstances(3, "nn", "nn", true)
                .withService("dn", "hadoop-base")
                .startCommand(tmpdir+"bin/hdfs --daemon start datanode").stopCommand(tmpdir+"bin/hdfs --daemon stop datanode")
                .and().nodeInstances(numOfDNs, "dn", "dn", true)
                .withService("jn", "hadoop-base")
                .startCommand(tmpdir+"bin/hdfs --daemon start journalnode").stopCommand(tmpdir+"bin/hdfs --dae stop journalnode").and()
                .nodeInstances(3, "jn", "jn", false).withNode("zk1", "zk").and()
                .node("nn1").initCommand(tmpdir+"bin/hdfs namenode -format && "+tmpdir+"bin/hdfs zkfc -formatZK").and().build();
    }

    public static void startNodesInOrder(ReditRunner runner) throws InterruptedException, RuntimeEngineException {
        Thread.sleep(10000);
        runner.runtime().startNode("nn1");
        Thread.sleep(10000);
        runner.runtime().startNode("nn2");
        runner.runtime().startNode("nn3");
        for (String node: runner.runtime().nodeNames()) if (node.startsWith("dn")) runner.runtime().startNode(node);
        Thread.sleep(10000);
    }
}
