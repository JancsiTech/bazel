// Copyright 2022 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.worker;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.flogger.GoogleLogger;
import com.google.devtools.build.lib.util.OS;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Collects and populates system metrics about persistent workers. */
public class WorkerMetricsCollector {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  /** The metrics collector (a static singleton instance). Inactive by default. */
  private static final WorkerMetricsCollector instance = new WorkerMetricsCollector();

  /** Mapping of worker ids to their metrics. */
  private final Map<Integer, WorkerMetric.WorkerProperties> workerIdToWorkerProperties =
      new ConcurrentHashMap<>();

  private WorkerMetricsCollector() {}

  public static WorkerMetricsCollector instance() {
    return instance;
  }

  // Collects process stats for each worker
  @VisibleForTesting
  public Map<Long, WorkerMetric.WorkerStat> collectStats(OS os, List<Long> processIds) {
    // TODO(b/181317827): Support Windows.
    if (os != OS.LINUX && os != OS.DARWIN) {
      return new HashMap<>();
    }

    Map<Long, Long> pidsToWorkerPid = getSubprocesses(processIds);
    Instant now = Instant.now();
    Map<Long, Integer> psMemory = collectDataFromPs(pidsToWorkerPid.keySet());

    Map<Long, Integer> sumMemory = new HashMap<>();
    psMemory.forEach(
        (pid, memory) -> {
          long parent = pidsToWorkerPid.get(pid);
          int parentMemory = 0;
          if (sumMemory.containsKey(parent)) {
            parentMemory = sumMemory.get(parent);
          }
          sumMemory.put(parent, parentMemory + memory);
        });

    Map<Long, WorkerMetric.WorkerStat> pidResults = new HashMap<>();
    sumMemory.forEach(
        (parent, memory) -> pidResults.put(parent, WorkerMetric.WorkerStat.create(memory, now)));

    return pidResults;
  }

  /**
   * For each parent process collects pids of all descendants. Stores them into the map, where key
   * is the descendant pid and the value is parent pid.
   */
  @VisibleForTesting
  public Map<Long, Long> getSubprocesses(List<Long> parents) {
    Map<Long, Long> subprocessesToProcess = new HashMap<>();
    for (Long pid : parents) {
      Optional<ProcessHandle> processHandle = ProcessHandle.of(pid);

      if (processHandle.isPresent()) {
        processHandle
            .get()
            .descendants()
            .map(p -> p.pid())
            .forEach(p -> subprocessesToProcess.put(p, pid));
        subprocessesToProcess.put(pid, pid);
      }
    }

    return subprocessesToProcess;
  }

  // Collects memory usage for every process
  private Map<Long, Integer> collectDataFromPs(Collection<Long> pids) {
    BufferedReader psOutput;
    try {
      psOutput =
          new BufferedReader(
              new InputStreamReader(this.buildPsProcess(pids).getInputStream(), UTF_8));
    } catch (IOException e) {
      logger.atWarning().withCause(e).log("Error while executing command for pids: %s", pids);
      return new HashMap<>();
    }

    HashMap<Long, Integer> processMemory = new HashMap<>();

    try {
      // The output of the above ps command looks similar to this:
      // PID RSS
      // 211706 222972
      // 2612333 6180
      // We skip over the first line (the header) and then parse the PID and the resident memory
      // size in kilobytes.
      String output = null;
      boolean isFirst = true;
      while ((output = psOutput.readLine()) != null) {
        if (isFirst) {
          isFirst = false;
          continue;
        }
        List<String> line = Splitter.on(" ").trimResults().omitEmptyStrings().splitToList(output);
        if (line.size() != 2) {
          logger.atWarning().log("Unexpected length of split line %s %d", output, line.size());
          continue;
        }

        long pid = Long.parseLong(line.get(0));
        int memoryInKb = Integer.parseInt(line.get(1));

        processMemory.put(pid, memoryInKb);
      }
    } catch (IllegalArgumentException | IOException e) {
      logger.atWarning().withCause(e).log("Error while parsing psOutput: %s", psOutput);
    }

    return processMemory;
  }

  @VisibleForTesting
  public Process buildPsProcess(Collection<Long> processIds) throws IOException {
    ImmutableList<Long> filteredProcessIds =
        processIds.stream().filter(p -> p > 0).collect(toImmutableList());
    String pids = Joiner.on(",").join(filteredProcessIds);
    return new ProcessBuilder("ps", "-o", "pid,rss", "-p", pids).start();
  }

  public ImmutableList<WorkerMetric> collectMetrics() {
    Map<Long, WorkerMetric.WorkerStat> workerStats =
        collectStats(
            OS.getCurrent(),
            this.workerIdToWorkerProperties.values().stream()
                .map(WorkerMetric.WorkerProperties::getProcessId)
                .collect(toImmutableList()));

    ImmutableList.Builder<WorkerMetric> workerMetrics = new ImmutableList.Builder<>();
    List<Integer> nonMeasurableWorkerIds = new ArrayList<>();
    for (WorkerMetric.WorkerProperties workerProperties :
        this.workerIdToWorkerProperties.values()) {
      Long pid = workerProperties.getProcessId();
      Integer workerId = workerProperties.getWorkerId();
      if (workerStats.containsKey(pid)) {
        workerMetrics.add(
            WorkerMetric.create(workerProperties, workerStats.get(pid), /* isMeasurable= */ true));
      } else {
        workerMetrics.add(
            WorkerMetric.create(
                workerProperties, /* workerStat= */ null, /* isMeasurable= */ false));
        nonMeasurableWorkerIds.add(workerId);
      }
    }

    workerIdToWorkerProperties.keySet().removeAll(nonMeasurableWorkerIds);

    return workerMetrics.build();
  }

  public void clear() {
    this.workerIdToWorkerProperties.clear();
  }

  @VisibleForTesting
  public Map<Integer, WorkerMetric.WorkerProperties> getWorkerIdToWorkerProperties() {
    return workerIdToWorkerProperties;
  }

  /**
   * Initializes workerIdToWorkerProperties for workers. If worker metrics already exists for this
   * worker, does nothing.
   */
  public void registerWorker(WorkerMetric.WorkerProperties properties) {
    if (workerIdToWorkerProperties.containsKey(properties.getWorkerId())) {
      return;
    }

    workerIdToWorkerProperties.put(properties.getWorkerId(), properties);
  }

  // TODO(b/238416583) Add deregister function
}
