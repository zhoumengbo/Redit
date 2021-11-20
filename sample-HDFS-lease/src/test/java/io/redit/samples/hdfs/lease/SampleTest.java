package io.redit.samples.hdfs.lease;

import com.google.common.base.Supplier;
import io.redit.ReditRunner;
import io.redit.exceptions.RuntimeEngineException;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.*;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.test.GenericTestUtils;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.security.PrivilegedExceptionAction;
import java.util.concurrent.TimeoutException;

import static org.junit.Assert.assertFalse;

public class SampleTest {

    protected static final int NUM_OF_NNS = 3;
    protected static final int NUM_OF_DNS = 3;
    protected static final int NUM_OF_JNS = 3;

    private static final int BLOCK_SIZE = 4096;
    private static final int BLOCK_AND_A_HALF = BLOCK_SIZE * 3 / 2;

    private static final Path TEST_PATH =
            new Path("/test-file");

    protected static final Logger LOG = LoggerFactory.getLogger(SampleTest.class);


    /**
     * Test the scenario where the NN fails over after issuing a block
     * synchronization request, but before it is committed. The
     * DN running the recovery should then fail to commit the synchronization
     * and a later retry will succeed.
     */
    @Test
    public void testFailoverRightBeforeCommitSynchronization()
            throws IOException, RuntimeEngineException, InterruptedException, TimeoutException {
        final Configuration conf = new Configuration();
        // Disable permissions so that another user can recover the lease.
        conf.setBoolean(DFSConfigKeys.DFS_PERMISSIONS_ENABLED_KEY, false);
        conf.setInt(DFSConfigKeys.DFS_BLOCK_SIZE_KEY, BLOCK_SIZE);

        FSDataOutputStream stm = null;

        ReditHelper reditHelper = new ReditHelper(NUM_OF_NNS, NUM_OF_DNS, NUM_OF_JNS);
        reditHelper.addInstrumentablePath("/share/hadoop/hdfs/hadoop-hdfs-3.1.2.jar");
        reditHelper.getDeploymentBuiler().node("nn1")
                .stackTrace("e1", "org.apache.hadoop.hdfs.server.namenode.NameNodeRpcServer.commitBlockSynchronization")
                .stackTrace("e2", "org.apache.hadoop.hdfs.server.namenode.FSNamesystem.commitBlockSynchronization")
                .stackTrace("e3",
                        "org.apache.hadoop.hdfs.server.namenode.FSNamesystem.commitBlockSynchronization,"
                                + "org.apache.hadoop.hdfs.server.namenode.FSNamesystem.checkOperation,"
                                + "org.apache.hadoop.hdfs.server.namenode.ha.StandbyState.checkOperation")
                .and().testCaseEvents("t1").runSequence("e1 * t1 * e2 * e3");
        ReditRunner runner = reditHelper.start();
        reditHelper.waitActive();

        try {
            reditHelper.transitionToActive(1);
            Thread.sleep(500);

            FileSystem fs = reditHelper.getFileSystem();

            stm = fs.create(TEST_PATH);

            // write a half block
            AppendTestUtil.write(stm, 0, BLOCK_SIZE / 2);
            stm.hflush();

            DistributedFileSystem fsOtherUser = createFsAsOtherUser(reditHelper, conf);
            assertFalse(fsOtherUser.recoverLease(TEST_PATH));

            reditHelper.runner().runtime().enforceOrder("t1", () -> {
                LOG.info("Failing over to NN 1");

                reditHelper.transitionToStandby(1);
                reditHelper.transitionToActive(2);
            });

            reditHelper.runner().runtime().waitForRunSequenceCompletion(30);

            loopRecoverLease(fsOtherUser, TEST_PATH);

            AppendTestUtil.check(fs, TEST_PATH, BLOCK_SIZE/2);
        } finally {
            IOUtils.closeStream(stm);
            runner.stop();
        }
    }

    private DistributedFileSystem createFsAsOtherUser(
            final ReditHelper reditHelper, final Configuration conf)
            throws IOException, InterruptedException {
        return (DistributedFileSystem) UserGroupInformation.createUserForTesting(
                "otheruser", new String[] { "othergroup"})
                .doAs(new PrivilegedExceptionAction<FileSystem>() {
                    @Override
                    public FileSystem run() throws Exception {
                        return reditHelper.getFileSystem();
                    }
                });
    }

    /**
     * Try to recover the lease on the given file for up to 60 seconds.
     * @param fsOtherUser the filesystem to use for the recoverLease call
     * @param testPath the path on which to run lease recovery
     * @throws TimeoutException if lease recover does not succeed within 60
     * seconds
     * @throws InterruptedException if the thread is interrupted
     */
    private static void loopRecoverLease(
            final FileSystem fsOtherUser, final Path testPath)
            throws TimeoutException, InterruptedException {
        try {
            GenericTestUtils.waitFor(new Supplier<Boolean>() {
                @Override
                public Boolean get() {
                    boolean success;
                    try {
                        success = ((DistributedFileSystem)fsOtherUser)
                                .recoverLease(testPath);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    if (!success) {
                        LOG.info("Waiting to recover lease successfully");
                    }
                    return success;
                }
            }, 1000, 60000);
        } catch (TimeoutException e) {
            throw new TimeoutException("Timed out recovering lease for " +
                    testPath);
        }
    }
}