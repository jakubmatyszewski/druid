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

package org.apache.druid.segment;

import org.apache.druid.java.util.common.DateTimes;
import org.apache.druid.segment.column.BaseColumn;
import org.apache.druid.segment.column.ColumnCapabilities;
import org.apache.druid.segment.column.ColumnHolder;
import org.apache.druid.segment.column.ColumnIndexSupplier;
import org.apache.druid.segment.column.DictionaryEncodedColumn;
import org.apache.druid.segment.column.NumericColumn;
import org.apache.druid.segment.data.Indexed;
import org.apache.druid.segment.index.semantic.DictionaryEncodedStringValueIndex;
import org.joda.time.DateTime;
import org.joda.time.Interval;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 *
 */
public class QueryableIndexStorageAdapter implements StorageAdapter
{
  public static final int DEFAULT_VECTOR_SIZE = 512;

  private final QueryableIndex index;

  @Nullable
  private volatile DateTime minTime;

  @Nullable
  private volatile DateTime maxTime;

  public QueryableIndexStorageAdapter(QueryableIndex index)
  {
    this.index = index;
  }

  @Override
  public Interval getInterval()
  {
    return index.getDataInterval();
  }

  @Override
  public Indexed<String> getAvailableDimensions()
  {
    return index.getAvailableDimensions();
  }

  @Override
  public Iterable<String> getAvailableMetrics()
  {
    // Use LinkedHashSet to preserve the original order.
    final Set<String> columnNames = new LinkedHashSet<>(index.getColumnNames());

    for (final String dimension : index.getAvailableDimensions()) {
      columnNames.remove(dimension);
    }

    return columnNames;
  }

  @Override
  public int getDimensionCardinality(String dimension)
  {
    ColumnHolder columnHolder = index.getColumnHolder(dimension);
    if (columnHolder == null) {
      // NullDimensionSelector has cardinality = 1 (one null, nothing else).
      return 1;
    }
    try (BaseColumn col = columnHolder.getColumn()) {
      if (!(col instanceof DictionaryEncodedColumn)) {
        return DimensionDictionarySelector.CARDINALITY_UNKNOWN;
      }
      return ((DictionaryEncodedColumn) col).getCardinality();
    }
    catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public int getNumRows()
  {
    return index.getNumRows();
  }

  @Override
  public DateTime getMinTime()
  {
    if (minTime == null) {
      // May be called a few times in parallel when first populating minTime, but this is benign, so allow it.
      populateMinMaxTime();
    }

    return minTime;
  }

  @Override
  public DateTime getMaxTime()
  {
    if (maxTime == null) {
      // May be called a few times in parallel when first populating maxTime, but this is benign, so allow it.
      populateMinMaxTime();
    }

    return maxTime;
  }

  @Override
  @Nullable
  public Comparable getMinValue(String dimension)
  {
    ColumnHolder columnHolder = index.getColumnHolder(dimension);
    if (columnHolder != null && columnHolder.getCapabilities().hasBitmapIndexes()) {
      ColumnIndexSupplier indexSupplier = columnHolder.getIndexSupplier();
      DictionaryEncodedStringValueIndex index = indexSupplier.as(DictionaryEncodedStringValueIndex.class);
      return index.getCardinality() > 0 ? index.getValue(0) : null;
    }
    return null;
  }

  @Override
  @Nullable
  public Comparable getMaxValue(String dimension)
  {
    ColumnHolder columnHolder = index.getColumnHolder(dimension);
    if (columnHolder != null && columnHolder.getCapabilities().hasBitmapIndexes()) {
      ColumnIndexSupplier indexSupplier = columnHolder.getIndexSupplier();
      DictionaryEncodedStringValueIndex index = indexSupplier.as(DictionaryEncodedStringValueIndex.class);
      return index.getCardinality() > 0 ? index.getValue(index.getCardinality() - 1) : null;
    }
    return null;
  }

  @Override
  @Nullable
  public ColumnCapabilities getColumnCapabilities(String column)
  {
    return index.getColumnCapabilities(column);
  }

  @Override
  public DateTime getMaxIngestedEventTime()
  {
    // For immutable indexes, maxIngestedEventTime is maxTime.
    return getMaxTime();
  }

  @Override
  public CursorHolder makeCursorHolder(CursorBuildSpec spec)
  {
    return new QueryableIndexCursorHolder(
        index,
        CursorBuildSpec.builder(spec).build()
    );
  }

  @Override
  public Metadata getMetadata()
  {
    return index.getMetadata();
  }

  private void populateMinMaxTime()
  {
    // Compute and cache minTime, maxTime.
    final ColumnHolder columnHolder = index.getColumnHolder(ColumnHolder.TIME_COLUMN_NAME);
    try (NumericColumn column = (NumericColumn) columnHolder.getColumn()) {
      this.minTime = DateTimes.utc(column.getLongSingleValueRow(0));
      this.maxTime = DateTimes.utc(column.getLongSingleValueRow(column.length() - 1));
    }
  }
}
