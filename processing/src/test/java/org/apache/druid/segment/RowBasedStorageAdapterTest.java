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

import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.math.LongMath;
import org.apache.druid.common.config.NullHandling;
import org.apache.druid.common.guava.GuavaUtils;
import org.apache.druid.java.util.common.DateTimes;
import org.apache.druid.java.util.common.Intervals;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.java.util.common.granularity.Granularities;
import org.apache.druid.java.util.common.granularity.Granularity;
import org.apache.druid.java.util.common.guava.Sequence;
import org.apache.druid.java.util.common.guava.Sequences;
import org.apache.druid.math.expr.ExprMacroTable;
import org.apache.druid.query.CursorGranularizer;
import org.apache.druid.query.dimension.DefaultDimensionSpec;
import org.apache.druid.query.filter.SelectorDimFilter;
import org.apache.druid.segment.column.ColumnCapabilities;
import org.apache.druid.segment.column.ColumnType;
import org.apache.druid.segment.column.RowSignature;
import org.apache.druid.segment.column.ValueType;
import org.apache.druid.segment.virtual.ExpressionVirtualColumn;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;

import static org.junit.Assert.assertThrows;

public class RowBasedStorageAdapterTest
{
  private static final String UNKNOWN_TYPE_NAME = "unknownType";

  private static final RowSignature ROW_SIGNATURE =
      RowSignature.builder()
                  .add(ValueType.FLOAT.name(), ColumnType.FLOAT)
                  .add(ValueType.DOUBLE.name(), ColumnType.DOUBLE)
                  .add(ValueType.LONG.name(), ColumnType.LONG)
                  .add(ValueType.STRING.name(), ColumnType.STRING)
                  .add(ValueType.COMPLEX.name(), ColumnType.UNKNOWN_COMPLEX)
                  .add(UNKNOWN_TYPE_NAME, null)
                  .build();

  private static final List<Function<Cursor, Supplier<Object>>> READ_STRING =
      ImmutableList.of(
          cursor -> {
            final BaseObjectColumnValueSelector selector =
                cursor.getColumnSelectorFactory().makeColumnValueSelector(ValueType.STRING.name());
            return selector::getObject;
          }
      );

  private static final List<BiFunction<Cursor, CursorGranularizer, Supplier<Object>>> READ_TIME_AND_STRING_GRAN =
      ImmutableList.of(
          (cursor, granularizer) -> granularizer::getBucketStart,
          (cursor, granularizer) -> {
            final BaseObjectColumnValueSelector selector =
                cursor.getColumnSelectorFactory().makeColumnValueSelector(ValueType.STRING.name());
            return selector::getObject;
          }
      );

  // VectorProcessors used by the "allProcessors" tasks.
  private static final LinkedHashMap<String, Function<Cursor, Supplier<Object>>> PROCESSORS = new LinkedHashMap<>();

  @BeforeClass
  public static void setUpClass()
  {
    NullHandling.initializeForTests();

    PROCESSORS.clear();

    // Read all the types as all the other types.

    for (final String valueTypeName : ROW_SIGNATURE.getColumnNames()) {
      PROCESSORS.put(
          StringUtils.format("%s-float", StringUtils.toLowerCase(valueTypeName)),
          cursor -> {
            final BaseFloatColumnValueSelector selector =
                cursor.getColumnSelectorFactory().makeColumnValueSelector(valueTypeName);
            return () -> {
              if (selector.isNull()) {
                return null;
              } else {
                return selector.getFloat();
              }
            };
          }
      );

      PROCESSORS.put(
          StringUtils.format("%s-double", StringUtils.toLowerCase(valueTypeName)),
          cursor -> {
            final BaseDoubleColumnValueSelector selector =
                cursor.getColumnSelectorFactory().makeColumnValueSelector(valueTypeName);
            return () -> {
              if (selector.isNull()) {
                return null;
              } else {
                return selector.getDouble();
              }
            };
          }
      );

      PROCESSORS.put(
          StringUtils.format("%s-long", StringUtils.toLowerCase(valueTypeName)),
          cursor -> {
            final BaseLongColumnValueSelector selector =
                cursor.getColumnSelectorFactory().makeColumnValueSelector(valueTypeName);
            return () -> {
              if (selector.isNull()) {
                return null;
              } else {
                return selector.getLong();
              }
            };
          }
      );

      PROCESSORS.put(
          StringUtils.format("%s-string", StringUtils.toLowerCase(valueTypeName)),
          cursor -> {
            final DimensionSelector selector =
                cursor.getColumnSelectorFactory().makeDimensionSelector(DefaultDimensionSpec.of(valueTypeName));
            return selector::defaultGetObject;
          }
      );

      PROCESSORS.put(
          StringUtils.format("%s-object", StringUtils.toLowerCase(valueTypeName)),
          cursor -> {
            final BaseObjectColumnValueSelector selector =
                cursor.getColumnSelectorFactory().makeColumnValueSelector(valueTypeName);
            return selector::getObject;
          }
      );
    }
  }

  /**
   * A RowAdapter for Integers where:
   * <p>
   * 1) timestampFunction returns a timestamp where the millis instant is equal to that integer as a number of hours
   * since the epoch (1970).
   * 2) columnFunction provides columns named after value types where each one equal to the cast to that type. All
   * other columns return null.
   */
  private static final RowAdapter<Integer> ROW_ADAPTER =
      new RowAdapter<Integer>()
      {
        @Override
        public ToLongFunction<Integer> timestampFunction()
        {
          return i -> i * Duration.standardHours(1).getMillis();
        }

        @Override
        public Function<Integer, Object> columnFunction(String columnName)
        {
          if (UNKNOWN_TYPE_NAME.equals(columnName)) {
            return i -> i;
          } else {
            final ValueType valueType = GuavaUtils.getEnumIfPresent(ValueType.class, columnName);

            if (valueType == null || valueType == ValueType.COMPLEX) {
              return i -> null;
            } else {
              return i -> DimensionHandlerUtils.convertObjectToType(
                  i,
                  ROW_SIGNATURE.getColumnType(columnName).orElse(null)
              );
            }
          }
        }
      };

  private static final RowAdapter<Integer> SAME_TIME_ROW_ADAPTER =
      new RowAdapter<Integer>()
      {
        private DateTime startTime = DateTimes.nowUtc();
        @Override
        public ToLongFunction<Integer> timestampFunction()
        {
          return i -> {
            long div = LongMath.divide(i, 3, RoundingMode.FLOOR);
            return startTime.plus(div).getMillis();
          };
        }

        @Override
        public Function<Integer, Object> columnFunction(String columnName)
        {
          if (UNKNOWN_TYPE_NAME.equals(columnName)) {
            return i -> i;
          } else {
            final ValueType valueType = GuavaUtils.getEnumIfPresent(ValueType.class, columnName);

            if (valueType == null || valueType == ValueType.COMPLEX) {
              return i -> null;
            } else {
              return i -> DimensionHandlerUtils.convertObjectToType(
                  i,
                  ROW_SIGNATURE.getColumnType(columnName).orElse(null)
              );
            }
          }
        }
      };

  public final AtomicLong numCloses = new AtomicLong();

  private RowBasedStorageAdapter<Integer> createIntAdapter(final int... ints)
  {
    return createIntAdapter(ROW_ADAPTER, ints);
  }

  private RowBasedStorageAdapter<Integer> createIntAdapter(RowAdapter<Integer> adapter, final int... ints)
  {
    return new RowBasedStorageAdapter<>(
        Sequences.simple(Arrays.stream(ints).boxed().collect(Collectors.toList()))
                 .withBaggage(numCloses::incrementAndGet),
        adapter,
        ROW_SIGNATURE
    );
  }

  @Test
  public void test_getInterval()
  {
    final RowBasedStorageAdapter<Integer> adapter = createIntAdapter();
    Assert.assertEquals(Intervals.ETERNITY, adapter.getInterval());
  }

  @Test
  public void test_getAvailableDimensions()
  {
    final RowBasedStorageAdapter<Integer> adapter = createIntAdapter();

    // Sort them for comparison purposes.
    Assert.assertEquals(
        ROW_SIGNATURE.getColumnNames().stream().sorted().collect(Collectors.toList()),
        Lists.newArrayList(adapter.getAvailableDimensions()).stream().sorted().collect(Collectors.toList())
    );
  }

  @Test
  public void test_getAvailableMetrics()
  {
    final RowBasedStorageAdapter<Integer> adapter = createIntAdapter();

    Assert.assertEquals(
        Collections.emptyList(),
        Lists.newArrayList(adapter.getAvailableMetrics())
    );
  }

  @Test
  public void test_getRowSignature()
  {
    final RowBasedStorageAdapter<Integer> adapter = createIntAdapter();
    Assert.assertEquals(ROW_SIGNATURE, adapter.getRowSignature());
  }

  @Test
  public void test_getDimensionCardinality_knownColumns()
  {
    final RowBasedStorageAdapter<Integer> adapter = createIntAdapter(0, 1, 2);

    // Row based adapters don't know cardinality (they don't walk their Iterables until makeCursor is called).
    for (String column : ROW_SIGNATURE.getColumnNames()) {
      Assert.assertEquals(DimensionDictionarySelector.CARDINALITY_UNKNOWN, adapter.getDimensionCardinality(column));
    }
  }

  @Test
  public void test_getDimensionCardinality_unknownColumn()
  {
    final RowBasedStorageAdapter<Integer> adapter = createIntAdapter(0, 1, 2);
    Assert.assertEquals(DimensionDictionarySelector.CARDINALITY_UNKNOWN, adapter.getDimensionCardinality("unknown"));
  }

  @Test
  public void test_getDimensionCardinality_timeColumn()
  {
    final RowBasedStorageAdapter<Integer> adapter = createIntAdapter(0, 1, 2);
    Assert.assertEquals(DimensionDictionarySelector.CARDINALITY_UNKNOWN, adapter.getDimensionCardinality("__time"));
  }

  @Test
  public void test_getMinTime()
  {
    final RowBasedStorageAdapter<Integer> adapter = createIntAdapter(0, 1, 2);
    Assert.assertEquals(Intervals.ETERNITY.getStart(), adapter.getMinTime());
  }

  @Test
  public void test_getMaxTime()
  {
    final RowBasedStorageAdapter<Integer> adapter = createIntAdapter(0, 1, 2);
    Assert.assertEquals(Intervals.ETERNITY.getEnd().minus(1), adapter.getMaxTime());
  }

  @Test
  public void test_getMinValue()
  {
    final RowBasedStorageAdapter<Integer> adapter = createIntAdapter(0, 1, 2);

    // Row based adapters don't know min/max values, so they always return null.
    // Test both known and unknown columns.
    final List<String> columns =
        ImmutableList.<String>builder().addAll(ROW_SIGNATURE.getColumnNames()).add("unknown", "__time").build();

    for (String column : columns) {
      Assert.assertNull(column, adapter.getMinValue(column));
    }
  }

  @Test
  public void test_getMaxValue()
  {
    final RowBasedStorageAdapter<Integer> adapter = createIntAdapter(0, 1, 2);

    // Row based adapters don't know min/max values, so they always return null.
    // Test both known and unknown columns.
    final List<String> columns =
        ImmutableList.<String>builder().addAll(ROW_SIGNATURE.getColumnNames()).add("unknown", "__time").build();

    for (String column : columns) {
      Assert.assertNull(column, adapter.getMaxValue(column));
    }
  }

  @Test
  public void test_getCapabilities()
  {
    final RowBasedStorageAdapter<Integer> adapter = createIntAdapter(0, 1, 2);

    // Row based adapters don't know cardinality (they don't walk their Iterables until makeCursor is called).
    for (String column : ROW_SIGNATURE.getColumnNames()) {
      Assert.assertEquals(DimensionDictionarySelector.CARDINALITY_UNKNOWN, adapter.getDimensionCardinality(column));
    }
  }

  @Test
  public void test_getColumnCapabilities_float()
  {
    final RowBasedStorageAdapter<Integer> adapter = createIntAdapter(0, 1, 2);

    final ColumnCapabilities capabilities = adapter.getColumnCapabilities(ValueType.FLOAT.name());
    Assert.assertEquals(ValueType.FLOAT, capabilities.getType());
    Assert.assertFalse(capabilities.hasMultipleValues().isMaybeTrue());
  }

  @Test
  public void test_getColumnCapabilities_double()
  {
    final RowBasedStorageAdapter<Integer> adapter = createIntAdapter(0, 1, 2);

    final ColumnCapabilities capabilities = adapter.getColumnCapabilities(ValueType.DOUBLE.name());
    Assert.assertEquals(ValueType.DOUBLE, capabilities.getType());
    Assert.assertFalse(capabilities.hasMultipleValues().isMaybeTrue());
  }

  @Test
  public void test_getColumnCapabilities_long()
  {
    final RowBasedStorageAdapter<Integer> adapter = createIntAdapter(0, 1, 2);

    final ColumnCapabilities capabilities = adapter.getColumnCapabilities(ValueType.LONG.name());
    Assert.assertEquals(ValueType.LONG, capabilities.getType());
    Assert.assertFalse(capabilities.hasMultipleValues().isMaybeTrue());
  }

  @Test
  public void test_getColumnCapabilities_string()
  {
    final RowBasedStorageAdapter<Integer> adapter = createIntAdapter(0, 1, 2);

    final ColumnCapabilities capabilities = adapter.getColumnCapabilities(ValueType.STRING.name());
    Assert.assertEquals(ValueType.STRING, capabilities.getType());

    // Note: unlike numeric types, STRING-typed columns might have multiple values, so they report as incomplete. It
    // would be good in the future to support some way of changing this, when it is known ahead of time that
    // multi-valuedness is definitely happening or is definitely impossible.
    Assert.assertTrue(capabilities.hasMultipleValues().isUnknown());
  }

  @Test
  public void test_getColumnCapabilities_complex()
  {
    final RowBasedStorageAdapter<Integer> adapter = createIntAdapter(0, 1, 2);

    final ColumnCapabilities capabilities = adapter.getColumnCapabilities(ValueType.COMPLEX.name());

    // Note: unlike numeric types, COMPLEX-typed columns report that they are incomplete for everything
    // except hasMultipleValues.
    Assert.assertEquals(ColumnType.UNKNOWN_COMPLEX, capabilities.toColumnType());
    Assert.assertFalse(capabilities.hasMultipleValues().isTrue());
    Assert.assertTrue(capabilities.isDictionaryEncoded().isUnknown());
  }

  @Test
  public void test_getColumnCapabilities_unknownType()
  {
    final RowBasedStorageAdapter<Integer> adapter = createIntAdapter(0, 1, 2);

    final ColumnCapabilities capabilities = adapter.getColumnCapabilities(UNKNOWN_TYPE_NAME);
    Assert.assertNull(capabilities);
  }

  @Test
  public void test_getColumnCapabilities_nonexistent()
  {
    final RowBasedStorageAdapter<Integer> adapter = createIntAdapter(0, 1, 2);
    Assert.assertNull(adapter.getColumnCapabilities("nonexistent"));
  }

  @Test
  public void test_getColumnTypeString()
  {
    final RowBasedStorageAdapter<Integer> adapter = createIntAdapter(0, 1, 2);

    for (String columnName : ROW_SIGNATURE.getColumnNames()) {
      if (UNKNOWN_TYPE_NAME.equals(columnName)) {
        Assert.assertNull(columnName, adapter.getColumnCapabilities(columnName));
      } else {
        Assert.assertEquals(
            columnName,
            ValueType.valueOf(columnName).name(),
            adapter.getColumnCapabilities(columnName).asTypeString()
        );
      }
    }
  }

  @Test
  public void test_getNumRows()
  {
    final RowBasedStorageAdapter<Integer> adapter = createIntAdapter(0, 1, 2);
    assertThrows(UnsupportedOperationException.class, () -> adapter.getMetadata());
  }

  @Test
  public void test_getMaxIngestedEventTime()
  {
    final RowBasedStorageAdapter<Integer> adapter = createIntAdapter(0, 1, 2);
    Assert.assertEquals(Intervals.ETERNITY.getEnd().minus(1), adapter.getMaxIngestedEventTime());
  }

  @Test
  public void test_getMetadata()
  {
    final RowBasedStorageAdapter<Integer> adapter = createIntAdapter(0, 1, 2);
    assertThrows(UnsupportedOperationException.class, () -> adapter.getMetadata());
  }

  @Test
  public void test_makeCursor()
  {
    final RowBasedStorageAdapter<Integer> adapter = createIntAdapter(0, 1, 2);

    final CursorBuildSpec buildSpec = CursorBuildSpec.builder()
                                                     .build();
    try (final CursorHolder cursorHolder = adapter.makeCursorHolder(buildSpec)) {
      final Cursor cursor = cursorHolder.asCursor();
      Assert.assertEquals(
          ImmutableList.of(
              ImmutableList.of("0"),
              ImmutableList.of("1"),
              ImmutableList.of("2")
          ),
          walkCursor(cursor, READ_STRING)
      );
    }

    Assert.assertEquals(2, numCloses.get());
  }

  @Test
  public void test_makeCursor_filterOnLong()
  {
    final RowBasedStorageAdapter<Integer> adapter = createIntAdapter(0, 1, 2);

    final CursorBuildSpec buildSpec = CursorBuildSpec.builder()
                                                     .setFilter(new SelectorDimFilter(ValueType.LONG.name(), "1.0", null).toFilter())
                                                     .build();
    try (final CursorHolder cursorHolder = adapter.makeCursorHolder(buildSpec)) {
      final Cursor cursor = cursorHolder.asCursor();

      Assert.assertEquals(
          ImmutableList.of(
              ImmutableList.of("1")
          ),
          walkCursor(cursor, READ_STRING)
      );
    }


    Assert.assertEquals(2, numCloses.get());
  }

  @Test
  public void test_makeCursor_filterOnNonexistentColumnEqualsNull()
  {
    final RowBasedStorageAdapter<Integer> adapter = createIntAdapter(0, 1);

    final CursorBuildSpec buildSpec = CursorBuildSpec.builder()
                                                     .setFilter(new SelectorDimFilter("nonexistent", null, null).toFilter())
                                                     .build();
    try (final CursorHolder cursorHolder = adapter.makeCursorHolder(buildSpec)) {
      final Cursor cursor = cursorHolder.asCursor();

      Assert.assertEquals(
          ImmutableList.of(
              ImmutableList.of("0"),
              ImmutableList.of("1")
          ),
          walkCursor(cursor, READ_STRING)
      );
    }

    Assert.assertEquals(2, numCloses.get());
  }

  @Test
  public void test_makeCursor_filterOnVirtualColumn()
  {
    final RowBasedStorageAdapter<Integer> adapter = createIntAdapter(0, 1);

    final CursorBuildSpec buildSpec = CursorBuildSpec.builder()
                                                     .setFilter(new SelectorDimFilter("vc", "2", null).toFilter())
                                                     .setVirtualColumns(
                                                         VirtualColumns.create(
                                                             ImmutableList.of(
                                                                 new ExpressionVirtualColumn(
                                                                     "vc",
                                                                     "\"LONG\" + 1",
                                                                     ColumnType.LONG,
                                                                     ExprMacroTable.nil()
                                                                 )
                                                             )
                                                         )
                                                     )
                                                     .build();

    try (final CursorHolder cursorHolder = adapter.makeCursorHolder(buildSpec)) {
      final Cursor cursor = cursorHolder.asCursor();
      Assert.assertEquals(
          ImmutableList.of(
              ImmutableList.of("1")
          ),
          walkCursor(cursor, READ_STRING)
      );
    }

    Assert.assertEquals(2, numCloses.get());
  }


  @Test
  public void test_makeCursor_descending()
  {
    final RowBasedStorageAdapter<Integer> adapter = createIntAdapter(0, 1, 2);

    final CursorBuildSpec buildSpec = CursorBuildSpec.builder()
                                                     .setPreferredOrdering(Cursors.descendingTimeOrder())
                                                     .build();
    try (final CursorHolder cursorHolder = adapter.makeCursorHolder(buildSpec)) {
      final Cursor cursor = cursorHolder.asCursor();
      Assert.assertEquals(
          ImmutableList.of(
              ImmutableList.of("2"),
              ImmutableList.of("1"),
              ImmutableList.of("0")
          ),
          walkCursor(cursor, READ_STRING)
      );
    }

    Assert.assertEquals(1, numCloses.get());
  }

  @Test
  public void test_makeCursor_intervalDoesNotMatch()
  {
    final RowBasedStorageAdapter<Integer> adapter = createIntAdapter(0, 1, 2);

    final CursorBuildSpec buildSpec = CursorBuildSpec.builder()
                                                     .setInterval(Intervals.of("2000/P1D"))
                                                     .build();
    try (final CursorHolder cursorHolder = adapter.makeCursorHolder(buildSpec)) {
      final Cursor cursor = cursorHolder.asCursor();
      Assert.assertEquals(
          ImmutableList.of(),
          walkCursor(cursor, READ_STRING)
      );
    }

    Assert.assertEquals(2, numCloses.get());
  }

  @Test
  public void test_makeCursor_intervalPartiallyMatches()
  {
    final RowBasedStorageAdapter<Integer> adapter = createIntAdapter(0, 1, 2);

    final CursorBuildSpec buildSpec = CursorBuildSpec.builder()
                                                     .setInterval(Intervals.of("1970-01-01T01/PT1H"))
                                                     .build();
    try (final CursorHolder cursorHolder = adapter.makeCursorHolder(buildSpec)) {
      final Cursor cursor = cursorHolder.asCursor();
      Assert.assertEquals(
          ImmutableList.of(
              ImmutableList.of("1")
          ),
          walkCursor(cursor, READ_STRING)
      );
    }

    Assert.assertEquals(2, numCloses.get());
  }

  @Test
  public void test_makeCursor_hourGranularity()
  {
    final RowBasedStorageAdapter<Integer> adapter = createIntAdapter(0, 1, 1, 2, 3);

    final CursorBuildSpec buildSpec = CursorBuildSpec.builder()
                                                     .setInterval(Intervals.of("1970/1971"))
                                                     .build();
    try (final CursorHolder cursorHolder = adapter.makeCursorHolder(buildSpec)) {
      final Cursor cursor = cursorHolder.asCursor();
      Assert.assertEquals(
          ImmutableList.of(
              ImmutableList.of(DateTimes.of("1970-01-01T00"), "0"),
              ImmutableList.of(DateTimes.of("1970-01-01T01"), "1"),
              ImmutableList.of(DateTimes.of("1970-01-01T01"), "1"),
              ImmutableList.of(DateTimes.of("1970-01-01T02"), "2"),
              ImmutableList.of(DateTimes.of("1970-01-01T03"), "3")
          ),
          walkCursorGranularized(adapter, cursor, buildSpec, Granularities.HOUR, READ_TIME_AND_STRING_GRAN)
      );
    }

    Assert.assertEquals(1, numCloses.get());
  }

  @Test
  public void test_makeCursor_hourGranularityWithInterval()
  {
    final RowBasedStorageAdapter<Integer> adapter = createIntAdapter(0, 1, 1, 2, 3);

    final CursorBuildSpec buildSpec = CursorBuildSpec.builder()
                                                     .setInterval(Intervals.of("1970-01-01T01/PT2H"))
                                                     .build();

    try (final CursorHolder cursorHolder = adapter.makeCursorHolder(buildSpec)) {
      final Cursor cursor = cursorHolder.asCursor();

      Assert.assertEquals(
          ImmutableList.of(
              ImmutableList.of(DateTimes.of("1970-01-01T01"), "1"),
              ImmutableList.of(DateTimes.of("1970-01-01T01"), "1"),
              ImmutableList.of(DateTimes.of("1970-01-01T02"), "2")
          ),
          walkCursorGranularized(adapter, cursor, buildSpec, Granularities.HOUR, READ_TIME_AND_STRING_GRAN)
      );
    }

    Assert.assertEquals(1, numCloses.get());
  }

  @Test
  public void test_makeCursor_hourGranularityWithIntervalDescending()
  {
    final RowBasedStorageAdapter<Integer> adapter = createIntAdapter(0, 1, 1, 2, 3);

    final CursorBuildSpec buildSpec = CursorBuildSpec.builder()
                                                     .setInterval(Intervals.of("1970-01-01T01/PT2H"))
                                                     .setPreferredOrdering(Cursors.descendingTimeOrder())
                                                     .build();

    try (final CursorHolder cursorHolder = adapter.makeCursorHolder(buildSpec)) {
      final Cursor cursor = cursorHolder.asCursor();
      Assert.assertEquals(
          ImmutableList.of(
              ImmutableList.of(DateTimes.of("1970-01-01T02"), "2"),
              ImmutableList.of(DateTimes.of("1970-01-01T01"), "1"),
              ImmutableList.of(DateTimes.of("1970-01-01T01"), "1")
          ),
          walkCursorGranularized(adapter, cursor, buildSpec, Granularities.HOUR, READ_TIME_AND_STRING_GRAN)
      );
    }

    Assert.assertEquals(1, numCloses.get());
  }

  @Test
  public void test_makeCursor_allProcessors()
  {
    final RowBasedStorageAdapter<Integer> adapter = createIntAdapter(0, 1);

    try (final CursorHolder cursorHolder = adapter.makeCursorHolder(CursorBuildSpec.FULL_SCAN)) {
      final Cursor cursor = cursorHolder.asCursor();
      Assert.assertEquals(
          ImmutableList.of(
              Lists.newArrayList(

                  // FLOAT
                  0f,
                  0d,
                  0L,
                  "0.0",
                  0f,

                  // DOUBLE
                  0f,
                  0d,
                  0L,
                  "0.0",
                  0d,

                  // LONG
                  0f,
                  0d,
                  0L,
                  "0",
                  0L,

                  // STRING
                  0f,
                  0d,
                  0L,
                  "0",
                  "0",

                  // COMPLEX
                  NullHandling.defaultFloatValue(),
                  NullHandling.defaultDoubleValue(),
                  NullHandling.defaultLongValue(),
                  null,
                  null,

                  // unknownType
                  0f,
                  0d,
                  0L,
                  "0",
                  0
              ),
              Lists.newArrayList(

                  // FLOAT
                  1f,
                  1d,
                  1L,
                  "1.0",
                  1f,

                  // DOUBLE
                  1f,
                  1d,
                  1L,
                  "1.0",
                  1d,

                  // LONG
                  1f,
                  1d,
                  1L,
                  "1",
                  1L,

                  // STRING
                  1f,
                  1d,
                  1L,
                  "1",
                  "1",

                  // COMPLEX
                  NullHandling.defaultFloatValue(),
                  NullHandling.defaultDoubleValue(),
                  NullHandling.defaultLongValue(),
                  null,
                  null,

                  // unknownType
                  1f,
                  1d,
                  1L,
                  "1",
                  1
              )
          ),
          walkCursor(cursor, new ArrayList<>(PROCESSORS.values()))
      );
    }

    Assert.assertEquals(2, numCloses.get());
  }

  @Test
  public void test_makeCursor_filterOnNonexistentColumnEqualsNonnull()
  {
    final RowBasedStorageAdapter<Integer> adapter = createIntAdapter(0, 1);

    final CursorBuildSpec buildSpec = CursorBuildSpec.builder()
                                                     .setFilter(new SelectorDimFilter("nonexistent", "abc", null).toFilter())
                                                     .build();

    try (final CursorHolder cursorHolder = adapter.makeCursorHolder(buildSpec)) {
      final Cursor cursor = cursorHolder.asCursor();
      Assert.assertEquals(
          ImmutableList.of(),
          walkCursor(cursor, new ArrayList<>(PROCESSORS.values()))
      );
    }

    Assert.assertEquals(2, numCloses.get());
  }

  private static List<List<Object>> walkCursor(
      final Cursor cursor,
      final List<Function<Cursor, Supplier<Object>>> processors
  )
  {
    final List<Supplier<Object>> suppliers = new ArrayList<>();
    for (Function<Cursor, Supplier<Object>> processor : processors) {
      suppliers.add(processor.apply(cursor));
    }

    final List<List<Object>> retVal = new ArrayList<>();

    // test cursor reset
    while (!cursor.isDone()) {
      cursor.advanceUninterruptibly();
    }

    cursor.reset();

    while (!cursor.isDone()) {
      final List<Object> row = new ArrayList<>();

      for (Supplier<Object> supplier : suppliers) {
        row.add(supplier.get());
      }

      retVal.add(row);
      cursor.advanceUninterruptibly();
    }

    return retVal;
  }

  private static List<List<Object>> walkCursorGranularized(
      final StorageAdapter adapter,
      final Cursor cursor,
      final CursorBuildSpec buildSpec,
      final Granularity granularity,
      final List<BiFunction<Cursor, CursorGranularizer, Supplier<Object>>> processors
  )
  {
    CursorGranularizer granularizer = CursorGranularizer.create(
        adapter,
        cursor,
        granularity,
        buildSpec.getInterval(),
        Cursors.preferDescendingTimeOrdering(buildSpec)
    );

    final List<Supplier<Object>> suppliers = new ArrayList<>();
    for (BiFunction<Cursor, CursorGranularizer, Supplier<Object>> processor : processors) {
      suppliers.add(processor.apply(cursor, granularizer));
    }

    final Sequence<List<Object>> theSequence =
        Sequences.simple(granularizer.getBucketIterable())
                 .flatMap(bucketInterval -> {
                   if (!granularizer.advanceToBucket(bucketInterval)) {
                     return Sequences.empty();
                   }
                   final List<List<Object>> retVal = new ArrayList<>();
                   while (!cursor.isDone()) {
                     final List<Object> row = new ArrayList<>();

                     for (Supplier<Object> supplier : suppliers) {
                       row.add(supplier.get());
                     }

                     retVal.add(row);
                     if (!granularizer.advanceCursorWithinBucketUninterruptedly()) {
                       break;
                     }
                   }
                   return Sequences.simple(retVal);
                 })
                 .filter(Predicates.notNull());
    return theSequence.toList();
  }
}
