/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.msq.indexing.destination;


import com.google.common.collect.ImmutableMap;
import nl.jqno.equalsverifier.EqualsVerifier;
import org.apache.druid.data.input.impl.DimensionSchema;
import org.apache.druid.data.input.impl.StringDimensionSchema;
import org.junit.Test;

import java.util.Map;

public class DataSourceMSQDestinationTest
{

  @Test
  public void testEquals()
  {
    EqualsVerifier.forClass(DataSourceMSQDestination.class)
                  .withNonnullFields("dataSource", "segmentGranularity", "segmentSortOrder", "dimensionSchemas")
                  .withPrefabValues(
                      Map.class,
                      ImmutableMap.of(
                          "language",
                          new StringDimensionSchema(
                              "language",
                              DimensionSchema.MultiValueHandling.SORTED_ARRAY,
                              false
                          )
                      ),
                      ImmutableMap.of(
                          "region",
                          new StringDimensionSchema(
                              "region",
                              DimensionSchema.MultiValueHandling.SORTED_ARRAY,
                              false
                          )
                      )
                  )
                  .usingGetClass()
                  .verify();
  }
}
