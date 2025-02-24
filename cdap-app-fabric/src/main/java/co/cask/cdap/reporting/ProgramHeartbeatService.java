/*
 * Copyright © 2018-2019 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.cdap.reporting;

import co.cask.cdap.internal.app.store.RunRecordMeta;
import co.cask.cdap.spi.data.transaction.TransactionRunner;
import co.cask.cdap.spi.data.transaction.TransactionRunners;
import com.google.inject.Inject;

import java.util.Collection;
import java.util.Set;

/**
 * Service to access {@link ProgramHeartbeatTable} in transaction
 */
public class ProgramHeartbeatService {
  private final TransactionRunner transactionRunner;


  @Inject
  public ProgramHeartbeatService(TransactionRunner transactionRunner) {
    this.transactionRunner = transactionRunner;
  }

  /**
   * Performs the {@link ProgramHeartbeatTable#scan(long, long, Set)}
   * @param startTimestampInSeconds starting timestamp inclusive
   * @param endTimestampInSeconds ending timestamp exclusive
   * @param namespaces set of namespaces to scan for the timerange
   * @return collection of run record meta
   */
  public Collection<RunRecordMeta> scan(long startTimestampInSeconds,
                                        long endTimestampInSeconds, Set<String> namespaces) {
    return TransactionRunners.run(transactionRunner, context -> {
      return new ProgramHeartbeatTable(context).scan(startTimestampInSeconds, endTimestampInSeconds, namespaces);
    });
  }
}
