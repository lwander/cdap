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

package co.cask.cdap.master.environment.k8s;

import co.cask.cdap.api.dataset.DatasetProperties;
import co.cask.cdap.api.dataset.lib.KeyValueTable;
import co.cask.cdap.api.metadata.MetadataScope;
import co.cask.cdap.client.MetadataClient;
import co.cask.cdap.client.config.ClientConfig;
import co.cask.cdap.client.config.ConnectionConfig;
import co.cask.cdap.common.conf.Constants;
import co.cask.cdap.common.discovery.RandomEndpointStrategy;
import co.cask.cdap.data2.dataset2.DatasetFramework;
import co.cask.cdap.proto.id.DatasetId;
import co.cask.cdap.proto.id.NamespaceId;
import co.cask.cdap.proto.metadata.MetadataSearchResponse;
import co.cask.cdap.proto.metadata.MetadataSearchResultRecord;
import co.cask.cdap.spi.metadata.MetadataConstants;
import com.google.inject.Injector;
import org.apache.twill.discovery.Discoverable;
import org.apache.twill.discovery.DiscoveryServiceClient;
import org.junit.Assert;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Unit test for the {@link MetadataServiceMain}.
 */
public class MetadataServiceMainTest extends MasterServiceMainTestBase {

  @Test
  public void testMetadataService() throws Exception {
    Injector injector = getServiceMainInstance(MetadataServiceMain.class).getInjector();

    DatasetId datasetId = NamespaceId.DEFAULT.dataset("testds");

    // Create a dataset, a metadata should get published.
    DatasetFramework datasetFramework = injector.getInstance(DatasetFramework.class);

    long beforeCreation = System.currentTimeMillis();
    datasetFramework.addInstance(KeyValueTable.class.getName(), datasetId, DatasetProperties.EMPTY);

    // Query the metadata
    DiscoveryServiceClient discoveryServiceClient = injector.getInstance(DiscoveryServiceClient.class);
    Discoverable metadataEndpoint = new RandomEndpointStrategy(
      () -> discoveryServiceClient.discover(Constants.Service.METADATA_SERVICE)).pick(5, TimeUnit.SECONDS);

    Assert.assertNotNull(metadataEndpoint);

    // Try to query the metadata
    InetSocketAddress metadataAddr = metadataEndpoint.getSocketAddress();
    ConnectionConfig connConfig = ConnectionConfig.builder()
      .setHostname(metadataAddr.getHostName())
      .setPort(metadataAddr.getPort())
      .build();

    MetadataClient metadataClient = new MetadataClient(ClientConfig.builder().setConnectionConfig(connConfig).build());
    MetadataSearchResponse response = metadataClient.searchMetadata(datasetId.getNamespaceId(), "*", (String) null);

    Set<MetadataSearchResultRecord> results = response.getResults();
    Assert.assertFalse(results.isEmpty());

    long creationTime = results.stream()
      .filter(r -> datasetId.equals(r.getEntityId()))
      .map(MetadataSearchResultRecord::getMetadata)
      .map(metadata -> metadata.get(MetadataScope.SYSTEM).getProperties().get(MetadataConstants.CREATION_TIME_KEY))
      .map(Long::parseLong)
      .findFirst()
      .orElse(-1L);

    // The creation time should be between the beforeCreation time and the current time
    Assert.assertTrue(creationTime >= beforeCreation);
    Assert.assertTrue(creationTime <= System.currentTimeMillis());
  }
}
