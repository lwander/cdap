/*
 * Copyright © 2019 Cask Data, Inc.
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

package co.cask.cdap.spi.data.common;

import co.cask.cdap.common.conf.CConfiguration;
import co.cask.cdap.common.conf.Constants;
import co.cask.cdap.data2.dataset2.DatasetFrameworkTestUtil;
import co.cask.cdap.spi.data.nosql.NoSqlStructuredTableRegistry;
import co.cask.cdap.spi.data.table.StructuredTableRegistry;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.tephra.TransactionManager;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;

/**
 * NoSQL backend for {@link CachedStructuredTableRegistryTest}
 */
public class NoSqlCachedStructuredTableRegistryTest extends CachedStructuredTableRegistryTest {
  @ClassRule
  public static DatasetFrameworkTestUtil dsFrameworkUtil = new DatasetFrameworkTestUtil();

  private static TransactionManager txManager;
  private static StructuredTableRegistry registry;

  @BeforeClass
  public static void beforeClass() {
    Configuration txConf = HBaseConfiguration.create();
    txManager = new TransactionManager(txConf);
    txManager.startAndWait();


    CConfiguration cConf = dsFrameworkUtil.getConfiguration();
    cConf.set(Constants.Dataset.DATA_STORAGE_IMPLEMENTATION, Constants.Dataset.DATA_STORAGE_NOSQL);
    registry =
      dsFrameworkUtil.getInjector().getInstance(StructuredTableRegistry.class);
    Assert.assertTrue(registry instanceof CachedStructuredTableRegistry);
  }

  @Override
  protected StructuredTableRegistry getStructuredTableRegistry() {
    return registry;
  }

  @Override
  protected StructuredTableRegistry getNonCachedStructuredTableRegistry() {
    return dsFrameworkUtil.getInjector().getInstance(NoSqlStructuredTableRegistry.class);
  }

  @AfterClass
  public static void afterClass() {
    if (txManager != null) {
      txManager.stopAndWait();
    }
  }
}
