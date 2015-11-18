/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hbase.replication.regionserver;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.google.common.annotations.VisibleForTesting;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hbase.classification.InterfaceAudience;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.TableNotFoundException;
import org.apache.hadoop.hbase.client.HConnection;
import org.apache.hadoop.hbase.client.HConnectionManager;
import org.apache.hadoop.hbase.protobuf.ReplicationProtbufUtil;
import org.apache.hadoop.hbase.protobuf.generated.AdminProtos.AdminService.BlockingInterface;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.wal.WAL.Entry;
import org.apache.hadoop.hbase.replication.HBaseReplicationEndpoint;
import org.apache.hadoop.hbase.replication.ReplicationPeer.PeerState;
import org.apache.hadoop.hbase.replication.regionserver.ReplicationSinkManager.SinkPeer;
import org.apache.hadoop.ipc.RemoteException;

/**
 * A {@link org.apache.hadoop.hbase.replication.ReplicationEndpoint} 
 * implementation for replicating to another HBase cluster.
 * For the slave cluster it selects a random number of peers
 * using a replication ratio. For example, if replication ration = 0.1
 * and slave cluster has 100 region servers, 10 will be selected.
 * <p>
 * A stream is considered down when we cannot contact a region server on the
 * peer cluster for more than 55 seconds by default.
 * </p>
 */
@InterfaceAudience.Private
public class HBaseInterClusterReplicationEndpoint extends HBaseReplicationEndpoint {

  private static final Log LOG = LogFactory.getLog(HBaseInterClusterReplicationEndpoint.class);
  private HConnection conn;

  private Configuration conf;

  // How long should we sleep for each retry
  private long sleepForRetries;

  // Maximum number of retries before taking bold actions
  private int maxRetriesMultiplier;
  // Socket timeouts require even bolder actions since we don't want to DDOS
  private int socketTimeoutMultiplier;
  //Metrics for this source
  private MetricsSource metrics;
  // Handles connecting to peer region servers
  private ReplicationSinkManager replicationSinkMgr;
  private boolean peersSelected = false;
  private ThreadPoolExecutor exec;
  private int maxThreads;

  @Override
  public void init(Context context) throws IOException {
    super.init(context);
    this.conf = HBaseConfiguration.create(ctx.getConfiguration());
    decorateConf();
    this.maxRetriesMultiplier = this.conf.getInt("replication.source.maxretriesmultiplier", 300);
    this.socketTimeoutMultiplier = this.conf.getInt("replication.source.socketTimeoutMultiplier",
        maxRetriesMultiplier);
    // TODO: This connection is replication specific or we should make it particular to
    // replication and make replication specific settings such as compression or codec to use
    // passing Cells.
    this.conn = HConnectionManager.createConnection(this.conf);
    this.sleepForRetries =
        this.conf.getLong("replication.source.sleepforretries", 1000);
    this.metrics = context.getMetrics();
    // ReplicationQueueInfo parses the peerId out of the znode for us
    this.replicationSinkMgr = new ReplicationSinkManager(conn, ctx.getPeerId(), this, this.conf);
    // per sink thread pool
    this.maxThreads = this.conf.getInt(HConstants.REPLICATION_SOURCE_MAXTHREADS_KEY,
      HConstants.REPLICATION_SOURCE_MAXTHREADS_DEFAULT);
    this.exec = new ThreadPoolExecutor(1, maxThreads, 60, TimeUnit.SECONDS,
      new SynchronousQueue<Runnable>());
  }

  private void decorateConf() {
    String replicationCodec = this.conf.get(HConstants.REPLICATION_CODEC_CONF_KEY);
    if (StringUtils.isNotEmpty(replicationCodec)) {
      this.conf.set(HConstants.RPC_CODEC_CONF_KEY, replicationCodec);
    }
  }

  private void connectToPeers() {
    getRegionServers();

    int sleepMultiplier = 1;

    // Connect to peer cluster first, unless we have to stop
    while (this.isRunning() && replicationSinkMgr.getSinks().size() == 0) {
      replicationSinkMgr.chooseSinks();
      if (this.isRunning() && replicationSinkMgr.getSinks().size() == 0) {
        if (sleepForRetries("Waiting for peers", sleepMultiplier)) {
          sleepMultiplier++;
        }
      }
    }
  }

  /**
   * Do the sleeping logic
   * @param msg Why we sleep
   * @param sleepMultiplier by how many times the default sleeping time is augmented
   * @return True if <code>sleepMultiplier</code> is &lt; <code>maxRetriesMultiplier</code>
   */
  protected boolean sleepForRetries(String msg, int sleepMultiplier) {
    try {
      if (LOG.isTraceEnabled()) {
        LOG.trace(msg + ", sleeping " + sleepForRetries + " times " + sleepMultiplier);
      }
      Thread.sleep(this.sleepForRetries * sleepMultiplier);
    } catch (InterruptedException e) {
      LOG.debug("Interrupted while sleeping between retries");
    }
    return sleepMultiplier < maxRetriesMultiplier;
  }

  /**
   * Do the shipping logic
   */
  @Override
  public boolean replicate(ReplicateContext replicateContext) {
    List<Entry> entries = replicateContext.getEntries();
    String walGroupId = replicateContext.getWalGroupId();
    int sleepMultiplier = 1;

    if (!peersSelected && this.isRunning()) {
      connectToPeers();
      peersSelected = true;
    }

    if (replicationSinkMgr.getSinks().size() == 0) {
      return false;
    }
    // minimum of: configured threads, number of 100-waledit batches,
    //  and number of current sinks
    int n = Math.min(Math.min(this.maxThreads, entries.size()/100+1),
      replicationSinkMgr.getSinks().size());
    List<List<Entry>> entryLists = new ArrayList<List<Entry>>(n);
    if (n == 1) {
      entryLists.add(entries);
    } else {
      for (int i=0; i<n; i++) {
        entryLists.add(new ArrayList<Entry>(entries.size()/n+1));
      }
      // now group by region
      for (Entry e : entries) {
        entryLists.get(Math.abs(Bytes.hashCode(e.getKey().getEncodedRegionName())%n)).add(e);
      }
    }
    while (this.isRunning()) {
      if (!isPeerEnabled()) {
        if (sleepForRetries("Replication is disabled", sleepMultiplier)) {
          sleepMultiplier++;
        }
        continue;
      }
      try {
        if (LOG.isTraceEnabled()) {
          LOG.trace("Replicating " + entries.size() +
              " entries of total size " + replicateContext.getSize());
        }

        List<Future<Integer>> futures = new ArrayList<Future<Integer>>(entryLists.size());
        for (int i=0; i<entryLists.size(); i++) {
          if (!entryLists.get(i).isEmpty()) {
            if (LOG.isTraceEnabled()) {
              LOG.trace("Submitting " + entryLists.get(i).size() +
                  " entries of total size " + replicateContext.getSize());
            }
            // RuntimeExceptions encountered here bubble up and are handled in ReplicationSource
            futures.add(exec.submit(createReplicator(entryLists.get(i), i)));
          }
        }
        IOException iox = null;
        for (int index = futures.size() - 1; index >= 0; index--) {
          try {
            // wait for all futures, remove successful parts
            // (only the remaining parts will be retried)
            Future<Integer> f = futures.get(index);
            entryLists.remove(f.get().intValue());
          } catch (InterruptedException ie) {
            iox =  new IOException(ie);
          } catch (ExecutionException ee) {
            // cause must be an IOException
            iox = (IOException)ee.getCause();
          }
        }
        if (iox != null) {
          // if we had any exceptions, try again
          throw iox;
        }
        // update metrics
        this.metrics.setAgeOfLastShippedOp(entries.get(entries.size() - 1).getKey().getWriteTime(),
          walGroupId);
        return true;

      } catch (IOException ioe) {
        // Didn't ship anything, but must still age the last time we did
        this.metrics.refreshAgeOfLastShippedOp(walGroupId);
        if (ioe instanceof RemoteException) {
          ioe = ((RemoteException) ioe).unwrapRemoteException();
          LOG.warn("Can't replicate because of an error on the remote cluster: ", ioe);
          if (ioe instanceof TableNotFoundException) {
            if (sleepForRetries("A table is missing in the peer cluster. "
                + "Replication cannot proceed without losing data.", sleepMultiplier)) {
              sleepMultiplier++;
            }
          }
        } else {
          if (ioe instanceof SocketTimeoutException) {
            // This exception means we waited for more than 60s and nothing
            // happened, the cluster is alive and calling it right away
            // even for a test just makes things worse.
            sleepForRetries("Encountered a SocketTimeoutException. Since the " +
              "call to the remote cluster timed out, which is usually " +
              "caused by a machine failure or a massive slowdown",
              this.socketTimeoutMultiplier);
          } else if (ioe instanceof ConnectException) {
            LOG.warn("Peer is unavailable, rechecking all sinks: ", ioe);
            replicationSinkMgr.chooseSinks();
          } else {
            LOG.warn("Can't replicate because of a local or network error: ", ioe);
          }
        }
        if (sleepForRetries("Since we are unable to replicate", sleepMultiplier)) {
          sleepMultiplier++;
        }
      }
    }
    return false; // in case we exited before replicating
  }

  protected boolean isPeerEnabled() {
    return ctx.getReplicationPeer().getPeerState() == PeerState.ENABLED;
  }

  @Override
  protected void doStop() {
    disconnect(); //don't call super.doStop()
    if (this.conn != null) {
      try {
        this.conn.close();
        this.conn = null;
      } catch (IOException e) {
        LOG.warn("Failed to close the connection");
      }
    }
    exec.shutdownNow();
    notifyStopped();
  }

  // is this needed? Nobody else will call doStop() otherwise
  @Override
  public State stopAndWait() {
    doStop();
    return super.stopAndWait();
  }

  @VisibleForTesting
  protected Replicator createReplicator(List<Entry> entries, int ordinal) {
    return new Replicator(entries, ordinal);
  }

  @VisibleForTesting
  protected class Replicator implements Callable<Integer> {
    private List<Entry> entries;
    private int ordinal;
    public Replicator(List<Entry> entries, int ordinal) {
      this.entries = entries;
      this.ordinal = ordinal;
    }

    @Override
    public Integer call() throws IOException {
      SinkPeer sinkPeer = null;
      try {
        sinkPeer = replicationSinkMgr.getReplicationSink();
        BlockingInterface rrs = sinkPeer.getRegionServer();
        ReplicationProtbufUtil.replicateWALEntry(rrs,
            entries.toArray(new Entry[entries.size()]));
        replicationSinkMgr.reportSinkSuccess(sinkPeer);
        return ordinal;

      } catch (IOException ioe) {
        if (sinkPeer != null) {
          replicationSinkMgr.reportBadSink(sinkPeer);
        }
        throw ioe;
      }
    }

  }
}
