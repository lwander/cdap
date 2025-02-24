/*
 * Copyright © 2015-2019 Cask Data, Inc.
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

package co.cask.cdap.explore.executor;

import co.cask.cdap.proto.QueryInfo;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.Lists;
import com.google.common.primitives.Longs;
import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import io.cdap.http.AbstractHttpHandler;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.handler.codec.http.FullHttpRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * An abstract class that provides common functionality for namespaced and non-namespaced ExploreQuery handlers.
 */
public class AbstractExploreQueryExecutorHttpHandler extends AbstractHttpHandler {
  private static final Logger LOG = LoggerFactory.getLogger(AbstractExploreQueryExecutorHttpHandler.class);
  private static final Gson GSON = new Gson();
  private static final Type STRING_MAP_TYPE = new TypeToken<Map<String, String>>() { }.getType();

  protected static final int DOWNLOAD_FETCH_CHUNK_SIZE = 1000;


  protected List<QueryInfo> filterQueries(List<QueryInfo> queries, final long offset,
                                          final boolean isForward, final int limit) {
    // Reverse the list if the pagination is in the reverse from the offset until the max limit
    if (!isForward) {
      queries = Lists.reverse(queries);
    }

    return FluentIterable.from(queries)
      .filter(new Predicate<QueryInfo>() {
        @Override
        public boolean apply(QueryInfo queryInfo) {
          if (isForward) {
            return queryInfo.getTimestamp() < offset;
          } else {
            return queryInfo.getTimestamp() > offset;
          }
        }
      })
      .limit(limit)
      .toSortedImmutableList(new Comparator<QueryInfo>() {
        @Override
        public int compare(QueryInfo first, QueryInfo second) {
          //sort descending.
          return Longs.compare(second.getTimestamp(), first.getTimestamp());
        }
      });
  }

  // get arguments contained in the request body
  protected Map<String, String> decodeArguments(FullHttpRequest request) throws IOException {
    ByteBuf content = request.content();
    if (!content.isReadable()) {
      return Collections.emptyMap();
    }
    try (Reader reader = new InputStreamReader(new ByteBufInputStream(content), StandardCharsets.UTF_8)) {
      Map<String, String> args = GSON.fromJson(reader, STRING_MAP_TYPE);
      return args == null ? Collections.<String, String>emptyMap() : args;
    } catch (JsonSyntaxException e) {
      LOG.info("Failed to parse runtime arguments on {}", request.uri(), e);
      throw e;
    }
  }

}
